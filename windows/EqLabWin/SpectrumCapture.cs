using System;
using System.Threading.Tasks;
using NAudio.Wave;

namespace EqLabWin
{
    /// <summary>
    /// 기본 출력 장치에서 재생 중인 소리를 WASAPI loopback 으로 받아 32개 막대(31Hz~16kHz, 로그 간격)의
    /// 레벨(dBFS)을 계산한다. 화면은 Db 를 읽어 간다.
    /// </summary>
    sealed class SpectrumCapture : IDisposable
    {
        public const int Bars = 32;
        const int FftN = 4096;
        const int Hop = 2048;

        public static readonly double Ratio = Math.Pow(16000.0 / 31.0, 1.0 / (Bars - 1));
        public static readonly double[] BarFreq = MakeBarFreq();

        public volatile float[] Db = Silence();
        public long LastTick;
        public volatile string State = "꺼짐";

        readonly object sync = new object();
        readonly float[] buf = new float[FftN];
        readonly float[] win = new float[FftN];
        readonly float[] re = new float[FftN];
        readonly float[] im = new float[FftN];
        readonly float[] cosT = new float[FftN / 2];
        readonly float[] sinT = new float[FftN / 2];
        readonly float winSum;

        WasapiLoopbackCapture cap;
        bool disposed;
        bool wanted;
        int count;
        int channels = 2;
        int bits = 32;
        int rate = 48000;

        public SpectrumCapture()
        {
            double s = 0;
            for (int i = 0; i < FftN; i++)
            {
                double w = 0.5 - 0.5 * Math.Cos(2.0 * Math.PI * i / FftN);
                win[i] = (float)w;
                s += w;
            }
            winSum = (float)s;
            for (int k = 0; k < FftN / 2; k++)
            {
                cosT[k] = (float)Math.Cos(2.0 * Math.PI * k / FftN);
                sinT[k] = (float)Math.Sin(2.0 * Math.PI * k / FftN);
            }
        }

        static double[] MakeBarFreq()
        {
            var a = new double[Bars];
            for (int b = 0; b < Bars; b++) a[b] = 31.0 * Math.Pow(Ratio, b);
            return a;
        }

        static float[] Silence()
        {
            var a = new float[Bars];
            for (int i = 0; i < Bars; i++) a[i] = -120f;
            return a;
        }

        public void Start()
        {
            lock (sync)
            {
                wanted = true;
                if (disposed || cap != null) return;
                try
                {
                    var c = new WasapiLoopbackCapture();
                    c.DataAvailable += OnData;
                    c.RecordingStopped += OnStopped;
                    channels = c.WaveFormat.Channels;
                    bits = c.WaveFormat.BitsPerSample;
                    rate = c.WaveFormat.SampleRate;
                    count = 0;
                    c.StartRecording();
                    cap = c;
                    State = "캡처 중 (" + rate + "Hz, " + channels + "채널, " + bits + "비트)";
                }
                catch (Exception e)
                {
                    State = "캡처 실패: " + e.Message;
                    EqEngine.Log(State);
                    ScheduleRetry();
                }
            }
        }

        public void Stop()
        {
            WasapiLoopbackCapture c;
            lock (sync)
            {
                wanted = false;
                c = cap;
                cap = null;
                State = "꺼짐";
            }
            DisposeCapture(c);
        }

        void ScheduleRetry()
        {
            Task.Delay(1500).ContinueWith(t =>
            {
                bool again;
                lock (sync) { again = wanted && !disposed && cap == null; }
                if (again) Start();
            });
        }

        static void DisposeCapture(WasapiLoopbackCapture c)
        {
            if (c == null) return;
            Task.Run(() =>
            {
                try { c.Dispose(); }
                catch (Exception e) { EqEngine.Log("캡처 정리 오류: " + e.Message); }
            });
        }

        void OnStopped(object sender, StoppedEventArgs e)
        {
            // 캡처 스레드 안에서 직접 Dispose 하면 교착할 수 있어 다른 스레드로 넘긴다.
            Task.Run(() =>
            {
                WasapiLoopbackCapture c;
                bool retry;
                lock (sync)
                {
                    c = cap;
                    cap = null;
                    retry = wanted && !disposed;
                    if (!disposed && wanted) State = "캡처가 멈춰 다시 시도 중";
                }
                if (e.Exception != null) EqEngine.Log("캡처 중지: " + e.Exception.Message);
                DisposeCapture(c);
                if (retry) ScheduleRetry();
            });
        }

        void OnData(object sender, WaveInEventArgs e)
        {
            int ch = channels;
            int bytes = bits / 8;
            if (ch < 1 || (bits != 32 && bits != 16)) return;
            int frame = ch * bytes;
            int frames = e.BytesRecorded / frame;
            byte[] data = e.Buffer;
            for (int f = 0; f < frames; f++)
            {
                int off = f * frame;
                float sum = 0f;
                for (int c = 0; c < ch; c++)
                {
                    if (bits == 32) sum += BitConverter.ToSingle(data, off + c * 4);
                    else sum += BitConverter.ToInt16(data, off + c * 2) / 32768f;
                }
                Push(sum / ch);
            }
        }

        void Push(float s)
        {
            buf[count++] = s;
            if (count < FftN) return;
            Analyze();
            Array.Copy(buf, Hop, buf, 0, FftN - Hop);
            count = FftN - Hop;
        }

        void Analyze()
        {
            for (int i = 0; i < FftN; i++)
            {
                re[i] = buf[i] * win[i];
                im[i] = 0f;
            }
            Fft();

            double sqrtR = Math.Sqrt(Ratio);
            int half = FftN / 2;
            var db = new float[Bars];
            for (int b = 0; b < Bars; b++)
            {
                double lo = BarFreq[b] / sqrtR;
                double hi = BarFreq[b] * sqrtR;
                int k0 = (int)Math.Round(lo * FftN / rate);
                int k1 = (int)Math.Round(hi * FftN / rate);
                if (k0 < 1) k0 = 1;
                if (k1 < k0) k1 = k0;
                if (k1 > half - 1) k1 = half - 1;
                if (k0 > k1) k0 = k1;
                float peak = 0f;
                for (int k = k0; k <= k1; k++)
                {
                    float m = (float)Math.Sqrt(re[k] * re[k] + im[k] * im[k]) * 2f / winSum;
                    if (m > peak) peak = m;
                }
                double d = 20.0 * Math.Log10(Math.Max(peak, 1e-6f));
                db[b] = (float)Math.Max(d, -100.0);
            }
            Db = db;
            LastTick = Environment.TickCount64;
        }

        /// <summary>제자리 radix-2 FFT (정방향, 정규화 없음).</summary>
        void Fft()
        {
            int n = FftN;
            for (int i = 1, j = 0; i < n; i++)
            {
                int bit = n >> 1;
                for (; (j & bit) != 0; bit >>= 1) j ^= bit;
                j ^= bit;
                if (i < j)
                {
                    float tr = re[i]; re[i] = re[j]; re[j] = tr;
                    float ti = im[i]; im[i] = im[j]; im[j] = ti;
                }
            }
            for (int len = 2; len <= n; len <<= 1)
            {
                int halfLen = len >> 1;
                int step = n / len;
                for (int i = 0; i < n; i += len)
                {
                    for (int k = 0; k < halfLen; k++)
                    {
                        float wr = cosT[k * step];
                        float wi = -sinT[k * step];
                        int a = i + k;
                        int b = a + halfLen;
                        float xr = re[b] * wr - im[b] * wi;
                        float xi = re[b] * wi + im[b] * wr;
                        re[b] = re[a] - xr;
                        im[b] = im[a] - xi;
                        re[a] += xr;
                        im[a] += xi;
                    }
                }
            }
        }

        public void Dispose()
        {
            WasapiLoopbackCapture c;
            lock (sync)
            {
                disposed = true;
                wanted = false;
                c = cap;
                cap = null;
            }
            DisposeCapture(c);
        }
    }
}
