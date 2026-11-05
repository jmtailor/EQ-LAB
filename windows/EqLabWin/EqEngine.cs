using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text;
using System.Text.Json;
using System.Threading;

namespace EqLabWin
{
    /// <summary>필터 하나(Equalizer APO 의 PK). 기본 11대역은 주파수가 고정이고, 추가한 대역(Extra)은 옮기고 지울 수 있다.</summary>
    sealed class Band
    {
        public double Freq;
        public double Gain;
        public double Q;
        public bool Extra;

        public Band Clone()
        {
            return new Band { Freq = Freq, Gain = Gain, Q = Q, Extra = Extra };
        }
    }

    sealed class Preset
    {
        public string Id;
        public string Name;
        public string Desc;
        public double[] Gains;       // 기본 11대역 게인
        public double[] Qs;          // null 이면 기본 Q
        public List<Band> Extras;    // null 이면 지금 추가한 대역을 그대로 두고 게인만 0으로
        public bool Custom;
    }

    public sealed class SavedBand
    {
        public double Freq { get; set; }
        public double Gain { get; set; }
        public double Q { get; set; }
    }

    public sealed class SavedCustom
    {
        public string Id { get; set; }
        public string Name { get; set; }
        public double[] Gains { get; set; }
        public double[] Qs { get; set; }
        public List<SavedBand> Extras { get; set; } = new List<SavedBand>();
    }

    public sealed class SavedState
    {
        public double[] Gains { get; set; }
        public double[] Qs { get; set; }
        public List<SavedBand> Extras { get; set; } = new List<SavedBand>();
        public bool On { get; set; } = true;
        public string PresetId { get; set; } = "flat";
        public bool AutoPreamp { get; set; } = true;
        public bool Composite { get; set; } = false;
        public List<SavedCustom> Customs { get; set; } = new List<SavedCustom>();
    }

    /// <summary>
    /// 이퀄라이저 상태(대역 목록, 켜짐, 프리셋)의 단일 창구. 상태를 바꾸면 Equalizer APO 설정 파일을
    /// 작업 스레드에서 다시 쓰고(ApoWriter), 설정은 %APPDATA%\EqLabWin\settings.json 에 저장한다.
    /// 대역 목록은 항상 주파수 순서이고, 화면은 이 순서의 번호로 대역을 가리킨다.
    /// </summary>
    static class EqEngine
    {
        public static readonly int[] FixedFreq = { 20, 31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000 };
        public const int FixedCount = 11;
        public const int MaxBands = 24;
        public const double MinHz = 20.0;
        public const double MaxHz = 16000.0;
        /// <summary>이웃한 대역 사이의 최소 주파수 비(약 1/4 옥타브). 이보다 가까우면 화면에서 점을 고르기 어렵다.</summary>
        public const double MinRatio = 1.19;
        public const double MinGain = -12.0;
        public const double MaxGain = 12.0;
        public const double Step = 0.5;
        public const double MinQ = 0.2;
        public const double MaxQ = 20.0;
        public const double ExtraQ = 2.0;

        static readonly object gate = new object();
        static readonly object applyGate = new object();
        static readonly List<Band> bands = MakeDefault();
        static readonly List<Preset> builtins = new List<Preset>
        {
            new Preset { Id = "flat", Name = "플랫", Desc = "원음 그대로", Gains = new double[] { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 } },
            new Preset { Id = "bass", Name = "베이스", Desc = "저음을 묵직하게", Gains = new double[] { 8, 8, 7, 5, 3, 1, 0, -1, 0, 0, 1 } },
            new Preset { Id = "vocal", Name = "보컬", Desc = "목소리를 또렷하게", Gains = new double[] { -3, -3, -2, -1, 1, 3, 4, 4, 2, 0, -1 } },
            new Preset { Id = "club", Name = "클럽", Desc = "리듬과 고음을 강조", Gains = new double[] { 6, 6, 5, 3, 0, 1, 2, 3, 4, 3, 2 } },
            new Preset { Id = "acoustic", Name = "어쿠스틱", Desc = "악기 질감을 살려서", Gains = new double[] { 4, 4, 4, 3, 2, 1, 1, 2, 3, 3, 3 } },
        };
        static readonly List<Preset> customs = new List<Preset>();
        static readonly List<string> logLines = new List<string>();

        static bool on = true;
        static bool autoPreamp = true;
        static bool composite = false;
        static string presetId = "flat";
        static string status = "시작 중...";
        static bool statusOk = false;

        static readonly Timer applyTimer = new Timer(_ => DoApply());
        static readonly Timer saveTimer = new Timer(_ => SaveNow());

        public static event Action Changed;
        public static event Action StatusChanged;

        /// <summary>스펙트럼 화면에 잠깐 띄우는 안내(점을 못 만들었을 때 등).</summary>
        public static volatile string NoticeText;
        public static long NoticeTick;

        static string SettingsPath
        {
            get
            {
                return Path.Combine(
                    Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "EqLabWin", "settings.json");
            }
        }

        // ---------- 기본값 ----------

        /// <summary>기본 대역의 Q. 이웃 대역과의 간격(옥타브)에서 정한다. 옥타브 간격이면 약 1.41.</summary>
        public static double DefaultQ(int i)
        {
            int n = FixedCount;
            double bw;
            if (i == 0) bw = Math.Log((double)FixedFreq[1] / FixedFreq[0], 2.0);
            else if (i == n - 1) bw = Math.Log((double)FixedFreq[n - 1] / FixedFreq[n - 2], 2.0);
            else bw = (Math.Log((double)FixedFreq[i] / FixedFreq[i - 1], 2.0) + Math.Log((double)FixedFreq[i + 1] / FixedFreq[i], 2.0)) / 2.0;
            bw = Math.Max(0.5, Math.Min(1.5, bw));
            return Math.Round(1.0 / (2.0 * Math.Sinh(Math.Log(2.0) / 2.0 * bw)), 2);
        }

        static List<Band> MakeDefault()
        {
            var l = new List<Band>();
            for (int i = 0; i < FixedCount; i++)
                l.Add(new Band { Freq = FixedFreq[i], Gain = 0, Q = DefaultQ(i), Extra = false });
            return l;
        }

        // ---------- 읽기 ----------

        public static Band[] Snapshot()
        {
            lock (gate)
            {
                var a = new Band[bands.Count];
                for (int i = 0; i < a.Length; i++) a[i] = bands[i].Clone();
                return a;
            }
        }

        public static int Count { get { lock (gate) { return bands.Count; } } }

        public static double GetGain(int i)
        {
            lock (gate) { return i >= 0 && i < bands.Count ? bands[i].Gain : 0.0; }
        }

        public static double GetQ(int i)
        {
            lock (gate) { return i >= 0 && i < bands.Count ? bands[i].Q : 1.0; }
        }

        public static bool IsExtra(int i)
        {
            lock (gate) { return i >= 0 && i < bands.Count && bands[i].Extra; }
        }

        /// <summary>대역 구성(기본/추가의 배치)이 바뀌었는지 알아보는 키. 추가 대역을 옮기는 동안에는 바뀌지 않는다.</summary>
        public static string StructKey()
        {
            lock (gate)
            {
                var sb = new StringBuilder();
                foreach (Band b in bands) sb.Append(b.Extra ? 'E' : 'F');
                return sb.ToString();
            }
        }

        public static bool On { get { lock (gate) { return on; } } }
        public static bool AutoPreamp { get { lock (gate) { return autoPreamp; } } }
        public static bool Composite { get { lock (gate) { return composite; } } }
        public static string CurrentPresetId { get { lock (gate) { return presetId; } } }
        public static string Status { get { lock (gate) { return status; } } }
        public static bool StatusOk { get { lock (gate) { return statusOk; } } }

        public static List<Preset> AllPresets()
        {
            lock (gate)
            {
                var l = new List<Preset>(builtins);
                l.AddRange(customs);
                return l;
            }
        }

        public static string PresetKey()
        {
            lock (gate)
            {
                return presetId + "|" + string.Join(",", customs.Select(c => c.Id + c.Name));
            }
        }

        public static bool IsCustom(string id)
        {
            lock (gate) { return customs.Any(c => c.Id == id); }
        }

        /// <summary>
        /// 모든 대역(RBJ 피킹 필터)을 겹친 실제 주파수 응답(dB). 화면 곡선과 자동 프리앰프가 이 값을 쓴다.
        /// 샘플레이트는 48kHz 로 가정한다(표시용).
        /// </summary>
        public static double ResponseDb(Band[] b, double hz)
        {
            const double fs = 48000.0;
            double w = 2.0 * Math.PI * hz / fs;
            double cw = Math.Cos(w);
            double c2w = Math.Cos(2.0 * w);
            double total = 0.0;
            for (int k = 0; k < b.Length; k++)
            {
                if (Math.Abs(b[k].Gain) < 0.001) continue;
                double A = Math.Pow(10.0, b[k].Gain / 40.0);
                double w0 = 2.0 * Math.PI * b[k].Freq / fs;
                double alpha = Math.Sin(w0) / (2.0 * b[k].Q);
                double c0 = Math.Cos(w0);
                double b0 = 1.0 + alpha * A, b1 = -2.0 * c0, b2 = 1.0 - alpha * A;
                double a0 = 1.0 + alpha / A, a1 = -2.0 * c0, a2 = 1.0 - alpha / A;
                double num = b0 * b0 + b1 * b1 + b2 * b2 + 2.0 * (b0 * b1 + b1 * b2) * cw + 2.0 * b0 * b2 * c2w;
                double den = a0 * a0 + a1 * a1 + a2 * a2 + 2.0 * (a0 * a1 + a1 * a2) * cw + 2.0 * a0 * a2 * c2w;
                if (num <= 0.0 || den <= 0.0) continue;
                total += 10.0 * Math.Log10(num / den);
            }
            return total;
        }

        /// <summary>20Hz~20kHz 에서 응답의 최댓값(dB). 클리핑을 막는 프리앰프 계산에 쓴다.</summary>
        public static double PeakResponse(Band[] b)
        {
            double mx = -100.0;
            for (int i = 0; i <= 240; i++)
            {
                double hz = 20.0 * Math.Pow(1000.0, i / 240.0);
                double r = ResponseDb(b, hz);
                if (r > mx) mx = r;
            }
            return mx;
        }

        // ---------- 변경 ----------

        static double Clamp(double v)
        {
            v = Math.Round(v / Step) * Step;
            return Math.Max(MinGain, Math.Min(MaxGain, v));
        }

        static double ClampQ(double q)
        {
            return Math.Round(Math.Max(MinQ, Math.Min(MaxQ, q)), 2);
        }

        static int version;

        /// <summary>대역이나 켜짐 상태가 바뀔 때마다 늘어나는 번호. 화면이 곡선을 다시 계산할지 판단하는 데 쓴다.</summary>
        public static int Version { get { return Volatile.Read(ref version); } }

        static void Touch()
        {
            Interlocked.Increment(ref version);
            var h = Changed;
            if (h != null) h();
            applyTimer.Change(60, Timeout.Infinite);
            saveTimer.Change(400, Timeout.Infinite);
        }

        public static void Notify(string msg)
        {
            NoticeText = msg;
            NoticeTick = Environment.TickCount64;
            var h = Changed;
            if (h != null) h();
        }

        public static void SetGain(int i, double v)
        {
            v = Clamp(v);
            lock (gate)
            {
                if (i < 0 || i >= bands.Count) return;
                if (bands[i].Gain == v) return;
                bands[i].Gain = v;
                presetId = "";
            }
            Touch();
        }

        public static void SetQ(int i, double q)
        {
            q = ClampQ(q);
            lock (gate)
            {
                if (i < 0 || i >= bands.Count) return;
                if (bands[i].Q == q) return;
                bands[i].Q = q;
                presetId = "";
            }
            Touch();
        }

        /// <summary>마우스 휠 한 칸: 게인 0.5dB, Ctrl 을 누르면 Q 를 12%씩(위로 돌리면 Q 증가 = 폭이 좁아짐).</summary>
        public static void Wheel(int i, int delta, bool ctrl)
        {
            if (ctrl)
            {
                double q = GetQ(i);
                SetQ(i, delta > 0 ? q * 1.12 : q / 1.12);
            }
            else
            {
                SetGain(i, GetGain(i) + (delta > 0 ? Step : -Step));
            }
        }

        /// <summary>추가한 대역의 주파수를 옮긴다. 이웃 대역을 넘지 못하게 막는다. 기본 대역은 못 옮긴다.</summary>
        public static void SetFreq(int i, double hz)
        {
            lock (gate)
            {
                if (i < 0 || i >= bands.Count || !bands[i].Extra) return;
                double lo = i > 0 ? bands[i - 1].Freq * MinRatio : MinHz;
                double hi = i < bands.Count - 1 ? bands[i + 1].Freq / MinRatio : MaxHz;
                if (lo > hi) return;
                hz = Math.Round(Math.Max(lo, Math.Min(hi, hz)));
                if (bands[i].Freq == hz) return;
                bands[i].Freq = hz;
                presetId = "";
            }
            Touch();
        }

        /// <summary>그 주파수에 대역을 하나 더한다(게인 0, Q 2.0). 못 만들면 이유를 돌려준다.</summary>
        public static string AddBand(double hz)
        {
            lock (gate)
            {
                if (bands.Count >= MaxBands) return "대역은 최대 " + MaxBands + "개까지 만들 수 있어요";
                hz = Math.Round(Math.Max(MinHz, Math.Min(MaxHz, hz)));
                int pos = 0;
                while (pos < bands.Count && bands[pos].Freq < hz) pos++;
                if (pos > 0 && hz / bands[pos - 1].Freq < MinRatio) return "이웃한 대역과 너무 가까워요";
                if (pos < bands.Count && bands[pos].Freq / hz < MinRatio) return "이웃한 대역과 너무 가까워요";
                bands.Insert(pos, new Band { Freq = hz, Gain = 0, Q = ExtraQ, Extra = true });
                presetId = "";
            }
            Touch();
            return null;
        }

        /// <summary>추가한 대역을 지운다. 기본 대역은 지울 수 없다.</summary>
        public static bool RemoveBand(int i)
        {
            lock (gate)
            {
                if (i < 0 || i >= bands.Count || !bands[i].Extra) return false;
                bands.RemoveAt(i);
                presetId = "";
            }
            Touch();
            return true;
        }

        public static void SetOn(bool v)
        {
            lock (gate) { on = v; }
            Touch();
        }

        public static void SetAutoPreamp(bool v)
        {
            lock (gate) { autoPreamp = v; }
            Touch();
        }

        public static void SetComposite(bool v)
        {
            lock (gate) { composite = v; }
            Touch();
        }

        /// <summary>gate 를 잡은 채로 부른다. 기본 11대역에 값을 넣고, 추가 대역은 규칙에 맞는 것만 주파수 순서로 넣는다.</summary>
        static void ApplyLayout(double[] g, double[] q, IEnumerable<Band> extras)
        {
            bands.Clear();
            for (int i = 0; i < FixedCount; i++)
            {
                bands.Add(new Band
                {
                    Freq = FixedFreq[i],
                    Gain = Clamp(g != null && g.Length == FixedCount ? g[i] : 0.0),
                    Q = ClampQ(q != null && q.Length == FixedCount ? q[i] : DefaultQ(i)),
                    Extra = false
                });
            }
            if (extras == null) return;
            foreach (Band x in extras.OrderBy(t => t.Freq))
            {
                if (bands.Count >= MaxBands) break;
                double hz = Math.Round(x.Freq);
                if (hz < MinHz || hz > MaxHz) continue;
                int pos = 0;
                while (pos < bands.Count && bands[pos].Freq < hz) pos++;
                if (pos > 0 && hz / bands[pos - 1].Freq < MinRatio) continue;
                if (pos < bands.Count && bands[pos].Freq / hz < MinRatio) continue;
                bands.Insert(pos, new Band { Freq = hz, Gain = Clamp(x.Gain), Q = ClampQ(x.Q), Extra = true });
            }
        }

        static double[] FixedGainsLocked()
        {
            return bands.Where(b => !b.Extra).Select(b => b.Gain).ToArray();
        }

        static double[] FixedQsLocked()
        {
            return bands.Where(b => !b.Extra).Select(b => b.Q).ToArray();
        }

        public static void SelectPreset(string id)
        {
            lock (gate)
            {
                Preset p = builtins.FirstOrDefault(x => x.Id == id) ?? customs.FirstOrDefault(x => x.Id == id);
                if (p == null) return;
                List<Band> ex;
                if (p.Extras != null)
                {
                    ex = p.Extras.Select(b => b.Clone()).ToList();
                }
                else
                {
                    // 기본 프리셋: 추가한 대역은 위치와 Q 를 유지하고 게인만 0으로 둔다.
                    ex = bands.Where(b => b.Extra).Select(b => new Band { Freq = b.Freq, Gain = 0, Q = b.Q, Extra = true }).ToList();
                }
                ApplyLayout(p.Gains, p.Qs, ex);
                presetId = p.Id;
            }
            Touch();
        }

        /// <summary>모든 대역을 0dB, 기본 Q 로 되돌리고 추가한 대역도 지운다.</summary>
        public static void Reset()
        {
            lock (gate)
            {
                ApplyLayout(null, null, null);
                presetId = "flat";
            }
            Touch();
        }

        public static void SaveCustom()
        {
            lock (gate)
            {
                var p = new Preset
                {
                    Id = "custom_" + Guid.NewGuid().ToString("N").Substring(0, 8),
                    Name = "내 프리셋 " + (customs.Count + 1),
                    Desc = "직접 저장한 프리셋",
                    Gains = FixedGainsLocked(),
                    Qs = FixedQsLocked(),
                    Extras = bands.Where(b => b.Extra).Select(b => b.Clone()).ToList(),
                    Custom = true
                };
                customs.Add(p);
                presetId = p.Id;
            }
            Touch();
        }

        public static void DeleteCustom(string id)
        {
            lock (gate)
            {
                customs.RemoveAll(c => c.Id == id);
                if (presetId == id) presetId = "";
            }
            Touch();
        }

        public static void ApplyNow()
        {
            applyTimer.Change(0, Timeout.Infinite);
        }

        // ---------- Equalizer APO 반영 ----------

        static void DoApply()
        {
            lock (applyGate)
            {
                Band[] b;
                bool o;
                bool ap;
                lock (gate)
                {
                    b = new Band[bands.Count];
                    for (int i = 0; i < b.Length; i++) b[i] = bands[i].Clone();
                    o = on;
                    ap = autoPreamp;
                }
                ApoResult r;
                try
                {
                    r = ApoWriter.Write(b, o, ap);
                }
                catch (Exception e)
                {
                    r = new ApoResult(false, "설정 파일 쓰기 실패: " + e.Message);
                }
                SetStatus(r.Ok, r.Message);
            }
        }

        public static void SetStatus(bool ok, string msg)
        {
            bool changedOk;
            lock (gate)
            {
                changedOk = statusOk != ok;
                status = msg;
                statusOk = ok;
            }
            if (!ok || changedOk) Log(msg);
            var h = StatusChanged;
            if (h != null) h();
        }

        // ---------- 저장 ----------

        static List<Band> ToBands(List<SavedBand> l)
        {
            var r = new List<Band>();
            if (l == null) return r;
            foreach (SavedBand s in l)
            {
                if (s != null) r.Add(new Band { Freq = s.Freq, Gain = s.Gain, Q = s.Q <= 0 ? ExtraQ : s.Q, Extra = true });
            }
            return r;
        }

        public static void Load()
        {
            try
            {
                if (!File.Exists(SettingsPath)) return;
                var st = JsonSerializer.Deserialize<SavedState>(File.ReadAllText(SettingsPath));
                if (st == null) return;
                lock (gate)
                {
                    ApplyLayout(st.Gains, st.Qs, ToBands(st.Extras));
                    on = st.On;
                    autoPreamp = st.AutoPreamp;
                    composite = st.Composite;
                    presetId = st.PresetId ?? "";
                    customs.Clear();
                    if (st.Customs != null)
                    {
                        foreach (var c in st.Customs)
                        {
                            if (c == null || c.Gains == null || c.Gains.Length != FixedCount || string.IsNullOrEmpty(c.Id)) continue;
                            customs.Add(new Preset
                            {
                                Id = c.Id,
                                Name = string.IsNullOrEmpty(c.Name) ? "내 프리셋" : c.Name,
                                Desc = "직접 저장한 프리셋",
                                Gains = c.Gains,
                                Qs = (c.Qs != null && c.Qs.Length == FixedCount) ? c.Qs : null,
                                Extras = ToBands(c.Extras),
                                Custom = true
                            });
                        }
                    }
                }
            }
            catch (Exception e)
            {
                Log("설정 읽기 실패: " + e.Message);
            }
        }

        static List<SavedBand> FromBands(IEnumerable<Band> l)
        {
            return l.Select(b => new SavedBand { Freq = b.Freq, Gain = b.Gain, Q = b.Q }).ToList();
        }

        public static void SaveNow()
        {
            try
            {
                SavedState st;
                lock (gate)
                {
                    st = new SavedState
                    {
                        Gains = FixedGainsLocked(),
                        Qs = FixedQsLocked(),
                        Extras = FromBands(bands.Where(b => b.Extra)),
                        On = on,
                        PresetId = presetId,
                        AutoPreamp = autoPreamp,
                        Composite = composite,
                        Customs = customs.Select(c => new SavedCustom
                        {
                            Id = c.Id,
                            Name = c.Name,
                            Gains = c.Gains,
                            Qs = c.Qs,
                            Extras = FromBands(c.Extras ?? new List<Band>())
                        }).ToList()
                    };
                }
                string path = SettingsPath;
                Directory.CreateDirectory(Path.GetDirectoryName(path));
                File.WriteAllText(path, JsonSerializer.Serialize(st, new JsonSerializerOptions { WriteIndented = true }));
            }
            catch (Exception e)
            {
                Log("설정 저장 실패: " + e.Message);
            }
        }

        // ---------- 진단 기록 ----------

        public static void Log(string m)
        {
            lock (logLines)
            {
                logLines.Insert(0, DateTime.Now.ToString("HH:mm:ss") + " " + m);
                while (logLines.Count > 24) logLines.RemoveAt(logLines.Count - 1);
            }
        }

        public static string LogText()
        {
            lock (logLines)
            {
                if (logLines.Count == 0) return "아직 기록이 없어요.";
                return string.Join("\r\n", logLines);
            }
        }
    }
}
