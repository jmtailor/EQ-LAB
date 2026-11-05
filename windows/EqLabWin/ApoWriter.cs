using System;
using System.Globalization;
using System.IO;
using System.Text;
using Microsoft.Win32;

namespace EqLabWin
{
    sealed class ApoResult
    {
        public bool Ok;
        public string Message;

        public ApoResult(bool ok, string message)
        {
            Ok = ok;
            Message = message;
        }
    }

    /// <summary>
    /// Equalizer APO 설정 폴더에 eqlab.txt 를 쓰고, config.txt 에 "Include: eqlab.txt" 한 줄을 연결한다.
    /// 기존 config.txt 의 다른 줄(예: Peace 설정)은 건드리지 않는다.
    /// </summary>
    static class ApoWriter
    {
        public const string OurFile = "eqlab.txt";
        const string IncludeLine = "Include: eqlab.txt";

        public enum Link { NotInstalled, NotIncluded, Included, Error }

        public static string FindInstall()
        {
            foreach (RegistryView view in new[] { RegistryView.Registry64, RegistryView.Registry32 })
            {
                try
                {
                    using (RegistryKey bk = RegistryKey.OpenBaseKey(RegistryHive.LocalMachine, view))
                    using (RegistryKey k = bk.OpenSubKey(@"SOFTWARE\EqualizerAPO"))
                    {
                        string p = k == null ? null : k.GetValue("InstallPath") as string;
                        if (!string.IsNullOrEmpty(p) && Directory.Exists(p)) return p;
                    }
                }
                catch (Exception e)
                {
                    EqEngine.Log("레지스트리 확인 실패: " + e.Message);
                }
            }
            string def = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles), "EqualizerAPO");
            return Directory.Exists(def) ? def : null;
        }

        public static string ConfigDir()
        {
            string p = FindInstall();
            return p == null ? null : Path.Combine(p, "config");
        }

        static bool IsInclude(string line)
        {
            return string.Equals(line.Trim(), IncludeLine, StringComparison.OrdinalIgnoreCase);
        }

        public static Link CheckLink(out string detail)
        {
            detail = "";
            string dir = ConfigDir();
            if (dir == null || !Directory.Exists(dir)) return Link.NotInstalled;
            string main = Path.Combine(dir, "config.txt");
            try
            {
                if (!File.Exists(main)) return Link.NotIncluded;
                foreach (string line in File.ReadAllLines(main))
                {
                    if (IsInclude(line)) return Link.Included;
                }
                return Link.NotIncluded;
            }
            catch (Exception e)
            {
                detail = e.Message;
                return Link.Error;
            }
        }

        /// <summary>config.txt 끝에 Include 줄을 추가한다. 처음 한 번만 config.txt.eqlab.bak 으로 백업한다.</summary>
        public static ApoResult EnsureInclude()
        {
            string dir = ConfigDir();
            if (dir == null || !Directory.Exists(dir))
                return new ApoResult(false, "Equalizer APO 설치 폴더를 찾지 못했습니다.");
            string main = Path.Combine(dir, "config.txt");
            try
            {
                if (!File.Exists(main))
                {
                    File.WriteAllText(main, IncludeLine + "\r\n", new UTF8Encoding(false));
                    return new ApoResult(true, "연결했습니다.");
                }

                string text;
                Encoding enc;
                using (var sr = new StreamReader(main, Encoding.UTF8, true))
                {
                    text = sr.ReadToEnd();
                    enc = sr.CurrentEncoding;
                }
                foreach (string line in text.Split('\n'))
                {
                    if (IsInclude(line)) return new ApoResult(true, "이미 연결되어 있습니다.");
                }

                string bak = main + ".eqlab.bak";
                if (!File.Exists(bak)) File.Copy(main, bak);

                string lead = (text.Length == 0 || text.EndsWith("\n")) ? "" : "\r\n";
                File.AppendAllText(main, lead + IncludeLine + "\r\n", enc);
                return new ApoResult(true, "연결했습니다.");
            }
            catch (UnauthorizedAccessException)
            {
                return new ApoResult(false, "config.txt 에 쓸 권한이 없습니다. 앱을 관리자 권한으로 실행해 주세요.");
            }
            catch (Exception e)
            {
                return new ApoResult(false, "연결 설정 실패: " + e.Message);
            }
        }

        /// <summary>밴드 이웃과의 간격(옥타브)에서 정한 Q. 옥타브 간격이면 약 1.41.</summary>
        static double QFor(int i)
        {
            int[] f = EqEngine.Freq;
            int n = EqEngine.N;
            double bw;
            if (i == 0) bw = Math.Log((double)f[1] / f[0], 2.0);
            else if (i == n - 1) bw = Math.Log((double)f[n - 1] / f[n - 2], 2.0);
            else bw = (Math.Log((double)f[i] / f[i - 1], 2.0) + Math.Log((double)f[i + 1] / f[i], 2.0)) / 2.0;
            bw = Math.Max(0.5, Math.Min(1.5, bw));
            return 1.0 / (2.0 * Math.Sinh(Math.Log(2.0) / 2.0 * bw));
        }

        /// <summary>현재 게인으로 eqlab.txt 를 다시 쓴다. Equalizer APO 가 파일 변경을 감지해 바로 반영한다.</summary>
        public static ApoResult Write(double[] g, bool on, bool autoPreamp)
        {
            string dir = ConfigDir();
            if (dir == null || !Directory.Exists(dir))
                return new ApoResult(false, "Equalizer APO를 찾지 못했습니다. 설치 후 다시 실행해 주세요.");

            CultureInfo ci = CultureInfo.InvariantCulture;
            var sb = new StringBuilder();
            sb.Append("# EQ-LAB generated file - do not edit\r\n");
            sb.Append("Channel: all\r\n");

            int count = 0;
            if (on)
            {
                double maxBoost = 0;
                for (int i = 0; i < g.Length; i++) if (g[i] > maxBoost) maxBoost = g[i];
                if (autoPreamp && maxBoost > 0.05)
                    sb.Append("Preamp: ").Append((-maxBoost).ToString("0.0", ci)).Append(" dB\r\n");

                for (int i = 0; i < g.Length; i++)
                {
                    if (Math.Abs(g[i]) < 0.05) continue;
                    count++;
                    sb.Append("Filter ").Append(count.ToString(ci)).Append(": ON PK Fc ")
                      .Append(EqEngine.Freq[i].ToString(ci)).Append(" Hz Gain ")
                      .Append(g[i].ToString("0.0", ci)).Append(" dB Q ")
                      .Append(QFor(i).ToString("0.00", ci)).Append("\r\n");
                }
            }
            else
            {
                sb.Append("# EQ off\r\n");
            }

            try
            {
                File.WriteAllText(Path.Combine(dir, OurFile), sb.ToString(), new UTF8Encoding(false));
            }
            catch (UnauthorizedAccessException)
            {
                return new ApoResult(false, "설정 폴더에 쓸 권한이 없습니다. 앱을 관리자 권한으로 실행해 주세요.");
            }

            string detail;
            Link link = CheckLink(out detail);
            if (link == Link.NotIncluded)
                return new ApoResult(false, "파일은 썼지만 config.txt 와 연결되지 않았습니다. '연결 설정'을 눌러 주세요.");
            if (link == Link.Error)
                return new ApoResult(false, "config.txt 를 읽지 못했습니다: " + detail);

            return new ApoResult(true, on
                ? "Equalizer APO 연결됨 · 필터 " + count + "개 적용"
                : "Equalizer APO 연결됨 · EQ 꺼짐");
        }
    }
}
