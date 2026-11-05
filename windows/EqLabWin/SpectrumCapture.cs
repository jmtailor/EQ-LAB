using System;
using System.Collections.Generic;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using NAudio.CoreAudioApi;
using NAudio.Wave;

namespace EqLabWin
{
    /// <summary>
    /// 출력 장치에서 재생 중인 소리를 WASAPI loopback 으로 받아 32개 막대(31Hz~16kHz, 로그 간격)의
    /// 레벨(dBFS)을 계산한다. 화면은 Db 를 읽어 간다.
    /// 장치는 '자동'이면 기본 출력 장치에서 시작하고, 거기서 소리가 없는데 다른 출력 장치에서 소리가 나면
    /// 그 장치로 옮겨 간다. 장치를 직접 고를 수도 있다(CycleDevice).
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
        public volatile string PreferredName = "자동";

        readonly object sync = new object();
        readonly float[] buf = new float[FftN];
        readonly float[] win = new float[FftN];
        readonly float[] re = new float[FftN];
        readonly float[] im = new float[FftN];
        readonly float[] cosT = new float[FftN / 2];
        readonly float[] sinT = new float[FftN / 2];
        readonly float winSum;

        WasapiLoopbackCapture cap;
        Timer watch;
        bool disposed;
        bool wanted;
        int count;
        int channels = 2;
        int bits = 32;
        int rate = 48000;

        string preferredId;            // null = 자동
        string currentId;
        string currentName = "-";
        string meterInfo = "아직 확인 전";
        long packets;
        long lastDataTick;
        float recentPeak;

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

        // ---------- 시작·정지 ----------

        public void Start()
        {
            lock (sync)
            {
                wanted = true;
                if (disposed) return;
                if (watch == null) watch = new Timer(_ => Watch(), null, 2000, 1500);
                if (cap == null) StartLocked(null);
            }
        }

        /// <summary>sync 잠금을 잡은 채로 부른다. forceId 가 없으면 고른 장치, 그것도 없으면 기본 출력 장치.</summary>
        void StartLocked(string forceId)
        {
            try
            {
                using (var en = new MMDeviceEnumerator())
                {
                    MMDevice dev = null;
                    string want = forceId ?? preferredId;
                    if (!string.IsNullOrEmpty(want))
                    {
                        try { dev = en.GetDevice(want); }
                        catch (Exception) { dev = null; }
                    }
                    if (dev == null) dev = en.GetDefaultAudioEndpoint(DataFlow.Render, Role.Multimedia);

                    var c = new WasapiLoopbackCapture(dev);
                    c.DataAvailable += OnData;
                    c.RecordingStopped += OnStopped;
                    channels = c.WaveFormat.Channels;
                    bits = c.WaveFormat.BitsPerSample;
                    rate = c.WaveFormat.SampleRate;
                    count = 0;
                    c.StartRecording();
                    cap = c;
                    currentId = dev.ID;
                    currentName = dev.FriendlyName;
                    State = "캡처 중 (" + rate + "Hz, " + channels + "채널, " + bits + "비트) · " + currentName;
                }
            }
            catch (Exception e)
            {
                State = "캡처 실패: " + e.Message;
                EqEngine.Log(State);
                ScheduleRetry();
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

        void SwitchTo(string id)
        {
            WasapiLoopbackCapture old;
            lock (sync)
            {
                if (!wanted || disposed) return;
                old = cap;
                cap = null;
                StartLocked(id);
            }
            DisposeCapture(old);
            EqEngine.Log("스펙트럼 장치: " + currentName);
        }

        /// <summary>자동 → 출력 장치 1 → 출력 장치 2 … → 자동 순서로 바꾼다.</summary>
        public void CycleDevice()
        {
            var ids = new List<string> { null };
            var names = new List<string> { "자동" };
            using (var en = new MMDeviceEnumerator())
            {
                foreach (MMDevice d in en.EnumerateAudioEndPoints(DataFlow.Render, DeviceState.Active))
                {
                    ids.Add(d.ID);
                    names.Add(d.FriendlyName);
                }
            }
            int cur = ids.IndexOf(preferredId);
            if (cur < 0) cur = 0;
            int next = (cur + 1) % ids.Count;
            preferredId = ids[next];
            PreferredName = names[next];
            SwitchTo(preferredId);
        }

        void ScheduleRetry()
        {
            Task.Delay(1500).ContinueWith(t =>
            {
                lock (sync)
                {
                    if (wanted && !disposed && cap == null) StartLocked(null);
                }
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
                    // 이미 다른 캡처로 바뀌었거나 일부러 멈춘 경우의 중지 알림은 무시한다.
                    if (!ReferenceEquals(sender, cap)) return;
                    c = cap;
                    cap = null;
                    retry = wanted && !disposed;
                    if (retry) State = "캡처가 멈춰 다시 시도 중";
                }
                if (e.Exception != null) EqEngine.Log("캡처 중지: " + e.Exception.Message);
                DisposeCapture(c);
                if (retry) ScheduleRetry();
            });
        }

        // ---------- 장치 감시 ----------

        /// <summary>1.5초마다 출력 장치별 재생 피크를 보고, 자동 모드에서 소리가 나는 장치로 옮겨 간다.</summary>
        void Watch()
        {
            try
            {
                string curId;
                string pref;
                lock (sync)
                {
                    if (!wanted || disposed) return;
                    curId = currentId;
                    pref = preferredId;
                }

                float curPeak = 0f;
                float bestPeak = 0f;
                string bestId = null;
                string defId = null;
                var sb = new StringBuilder();
                using (var en = new MMDeviceEnumerator())
                {
                    defId = en.GetDefaultAudioEndpoint(DataFlow.Render, Role.Multimedia).ID;
                    foreach (MMDevice d in en.EnumerateAudioEndPoints(DataFlow.Render, DeviceState.Active))
                    {
                        float p = 0f;
                        try { p = d.AudioMeterInformation.MasterPeakValue; }
                        catch (Exception) { }
                        sb.Append(d.FriendlyName);
                        if (d.ID == curId) sb.Append(" [캡처 중]");
                        if (d.ID == defId) sb.Append(" [기본]");
                        sb.Append(" · 재생 피크 ").Append(p.ToString("0.000")).Append("\r\n");
                        if (d.ID == curId) curPeak = p;
                        if (p > bestPeak)
                        {
                            bestPeak = p;
                            bestId = d.ID;
                        }
                    }
                }
                meterInfo = sb.ToString();

                if (!string.IsNullOrEmpty(pref)) return;
                if (curPeak >= 0.0005f) return;
                if (bestId != null && bestPeak > 0.002f && bestId != curId) SwitchTo(bestId);
                else if (bestId == null && defId != curId) SwitchTo(defId);
            }
            catch (Exception e)
            {
                meterInfo = "장치 확인 실패: " + e.Message;
            }
        }

        public string Diagnostics()
        {
            long last = Interlocked.Read(ref lastDataTick);
            string age = last == 0 ? "아직 없음" : (Environment.TickCount64 - last) + "ms 전";
            return "스펙트럼 장치: " + PreferredName + " (현재 " + currentName + ")\r\n"
                   + "패킷 수신: " + Interlocked.Read(ref packets) + "회, 마지막 수신 " + age
                   + ", 마지막 패킷 최대 샘플 " + recentPeak.ToString("0.0000") + "\r\n"
                   + "출력 장치별 재생 상태:\r\n" + meterInfo;
        }

        // ---------- 분석 ----------

        void OnData(object sender, WaveInEventArgs e)
        {
            int ch = channels;
            int bytes = bits / 8;
            if (ch < 1 || (bits != 32 && bits != 16)) return;
            int frame = ch * bytes;
            int frames = e.BytesRecorded / frame;
            byte[] data = e.Buffer;
            float mx = 0f;
            for (int f = 0; f < frames; f++)
            {
                int off = f * frame;
                float sum = 0f;
                for (int c = 0; c < ch; c++)
                {
                    float v = bits == 32
                        ? BitConverter.ToSingle(data, off + c * 4)
                        : BitConverter.ToInt16(data, off + c * 2) / 32768f;
                    sum += v;
                    float a = v < 0f ? -v : v;
                    if (a > mx) mx = a;
                }
                Push(sum / ch);
            }
            recentPeak = mx;
            Interlocked.Exchange(ref lastDataTick, Environment.TickCount64);
            Interlocked.Increment(ref packets);
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
            Timer w;
            lock (sync)
            {
                disposed = true;
                wanted = false;
                c = cap;
                cap = null;
                w = watch;
                watch = null;
            }
            if (w != null) w.Dispose();
            DisposeCapture(c);
        }
    }
}
