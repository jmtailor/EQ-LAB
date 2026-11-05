using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text.Json;
using System.Threading;

namespace EqLabWin
{
    sealed class Preset
    {
        public string Id;
        public string Name;
        public string Desc;
        public double[] Gains;
        public bool Custom;
    }

    public sealed class SavedCustom
    {
        public string Id { get; set; }
        public string Name { get; set; }
        public double[] Gains { get; set; }
    }

    public sealed class SavedState
    {
        public double[] Gains { get; set; }
        public bool On { get; set; } = true;
        public string PresetId { get; set; } = "flat";
        public bool AutoPreamp { get; set; } = true;
        public bool Composite { get; set; } = false;
        public List<SavedCustom> Customs { get; set; } = new List<SavedCustom>();
    }

    /// <summary>
    /// 이퀄라이저 상태(11밴드 게인, 켜짐, 프리셋)의 단일 창구. 상태를 바꾸면 Equalizer APO 설정 파일을
    /// 작업 스레드에서 다시 쓰고(ApoWriter), 설정은 %APPDATA%\EqLabWin\settings.json 에 저장한다.
    /// </summary>
    static class EqEngine
    {
        public static readonly int[] Freq = { 20, 31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000 };
        public static readonly string[] FreqLabel = { "20", "31", "62", "125", "250", "500", "1k", "2k", "4k", "8k", "16k" };
        public const int N = 11;
        public const double MinGain = -12.0;
        public const double MaxGain = 12.0;
        public const double Step = 0.5;

        static readonly object gate = new object();
        static readonly object applyGate = new object();
        static readonly double[] gains = new double[N];
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

        static string SettingsPath
        {
            get
            {
                return Path.Combine(
                    Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "EqLabWin", "settings.json");
            }
        }

        // ---------- 읽기 ----------

        public static double[] Snapshot()
        {
            lock (gate) { return (double[])gains.Clone(); }
        }

        public static double GetGain(int i)
        {
            lock (gate) { return gains[i]; }
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

        /// <summary>로그 주파수 기준 코사인 보간으로 만든 EQ 곡선의 게인(dB). 안드로이드 화면 곡선과 같은 방식.</summary>
        public static double Interp(double[] g, double hz)
        {
            if (hz <= Freq[0]) return g[0];
            if (hz >= Freq[N - 1]) return g[N - 1];
            int i = 0;
            while (i < N - 2 && hz > Freq[i + 1]) i++;
            double t = Math.Log(hz / Freq[i]) / Math.Log((double)Freq[i + 1] / Freq[i]);
            double s = (1.0 - Math.Cos(Math.PI * t)) / 2.0;
            return g[i] + (g[i + 1] - g[i]) * s;
        }

        // ---------- 변경 ----------

        static double Clamp(double v)
        {
            v = Math.Round(v / Step) * Step;
            return Math.Max(MinGain, Math.Min(MaxGain, v));
        }

        static void Touch()
        {
            var h = Changed;
            if (h != null) h();
            applyTimer.Change(60, Timeout.Infinite);
            saveTimer.Change(400, Timeout.Infinite);
        }

        public static void SetGain(int i, double v)
        {
            if (i < 0 || i >= N) return;
            v = Clamp(v);
            lock (gate)
            {
                if (gains[i] == v) return;
                gains[i] = v;
                presetId = "";
            }
            Touch();
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

        public static void SelectPreset(string id)
        {
            lock (gate)
            {
                Preset p = builtins.FirstOrDefault(x => x.Id == id) ?? customs.FirstOrDefault(x => x.Id == id);
                if (p == null) return;
                for (int i = 0; i < N; i++) gains[i] = Clamp(p.Gains[i]);
                presetId = p.Id;
            }
            Touch();
        }

        public static void Reset()
        {
            SelectPreset("flat");
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
                    Gains = (double[])gains.Clone(),
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
                double[] g;
                bool o;
                bool ap;
                lock (gate)
                {
                    g = (double[])gains.Clone();
                    o = on;
                    ap = autoPreamp;
                }
                ApoResult r;
                try
                {
                    r = ApoWriter.Write(g, o, ap);
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

        public static void Load()
        {
            try
            {
                if (!File.Exists(SettingsPath)) return;
                var st = JsonSerializer.Deserialize<SavedState>(File.ReadAllText(SettingsPath));
                if (st == null) return;
                lock (gate)
                {
                    if (st.Gains != null && st.Gains.Length == N)
                    {
                        for (int i = 0; i < N; i++) gains[i] = Clamp(st.Gains[i]);
                    }
                    on = st.On;
                    autoPreamp = st.AutoPreamp;
                    composite = st.Composite;
                    presetId = st.PresetId ?? "";
                    customs.Clear();
                    if (st.Customs != null)
                    {
                        foreach (var c in st.Customs)
                        {
                            if (c == null || c.Gains == null || c.Gains.Length != N || string.IsNullOrEmpty(c.Id)) continue;
                            customs.Add(new Preset
                            {
                                Id = c.Id,
                                Name = string.IsNullOrEmpty(c.Name) ? "내 프리셋" : c.Name,
                                Desc = "직접 저장한 프리셋",
                                Gains = c.Gains,
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

        public static void SaveNow()
        {
            try
            {
                SavedState st;
                lock (gate)
                {
                    st = new SavedState
                    {
                        Gains = (double[])gains.Clone(),
                        On = on,
                        PresetId = presetId,
                        AutoPreamp = autoPreamp,
                        Composite = composite,
                        Customs = customs.Select(c => new SavedCustom { Id = c.Id, Name = c.Name, Gains = c.Gains }).ToList()
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
