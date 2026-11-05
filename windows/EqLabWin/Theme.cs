using System;
using System.Windows.Media;

namespace EqLabWin
{
    /// <summary>안드로이드 앱과 같은 청록·민트 색상과 공통 브러시.</summary>
    static class Theme
    {
        public static Color C(uint v)
        {
            return Color.FromArgb((byte)(v >> 24), (byte)(v >> 16), (byte)(v >> 8), (byte)v);
        }

        static SolidColorBrush B(uint v)
        {
            var b = new SolidColorBrush(C(v));
            b.Freeze();
            return b;
        }

        static Pen P(Brush b, double thickness)
        {
            var p = new Pen(b, thickness);
            p.LineJoin = PenLineJoin.Round;
            p.StartLineCap = PenLineCap.Round;
            p.EndLineCap = PenLineCap.Round;
            p.Freeze();
            return p;
        }

        public const uint BgC = 0xFF0B1112;
        public const uint AccentC = 0xFF5FE3C8;

        public static readonly SolidColorBrush BgBrush = B(BgC);
        public static readonly SolidColorBrush SurfaceBrush = B(0xFF121C1D);
        public static readonly SolidColorBrush BorderBrush = B(0xFF1F2F30);
        public static readonly SolidColorBrush TextBrush = B(0xFFE8F4F2);
        public static readonly SolidColorBrush MutedBrush = B(0xFF8FA8A6);
        public static readonly SolidColorBrush AccentBrush = B(AccentC);
        public static readonly SolidColorBrush CutBrush = B(0xFF4B9CB5);
        public static readonly SolidColorBrush CutTextBrush = B(0xFF8CC4D8);
        public static readonly SolidColorBrush TrackBrush = B(0xFF0A1516);
        public static readonly SolidColorBrush OnAccentBrush = B(0xFF06201B);
        public static readonly SolidColorBrush OutlineBrush = B(0xFF2B4143);
        public static readonly SolidColorBrush WarnBrush = B(0xFFFFB04A);
        public static readonly SolidColorBrush GridBrush = B(0x261F2F30);
        public static readonly SolidColorBrush GridStrongBrush = B(0xFF1F2F30);
        public static readonly SolidColorBrush GlowBrush = B(0x405FE3C8);
        public static readonly SolidColorBrush BwBrush = B(0xAA5FE3C8);

        public static readonly Pen PenBorder = P(BorderBrush, 1);
        public static readonly Pen PenOutline = P(OutlineBrush, 1);
        public static readonly Pen PenGrid = P(GridStrongBrush, 1);
        public static readonly Pen PenCurve = P(AccentBrush, 2.5);
        public static readonly Pen PenGlow = P(GlowBrush, 9);
        public static readonly Pen PenPoint = P(BgBrush, 2);
        public static readonly Pen PenExtra = P(TextBrush, 2);
        public static readonly Pen PenBw = P(BwBrush, 3);

        public static readonly FontFamily Family = new FontFamily("Segoe UI, Malgun Gothic");
        public static readonly Typeface Font =
            new Typeface(Family, System.Windows.FontStyles.Normal, System.Windows.FontWeights.Normal, System.Windows.FontStretches.Normal);

        /// <summary>스펙트럼 팔레트: 저음에서 고음으로 갈수록 민트 → 하늘 → 파랑 → 보라 → 분홍 → 주황.</summary>
        static readonly uint[] Spectrum = { 0xFF2DE2B6, 0xFF35C8F0, 0xFF5B8CFF, 0xFFA066FF, 0xFFFF5FC2, 0xFFFFB04A };

        public static Color Mix(Color a, Color b, double f)
        {
            return Color.FromRgb(
                (byte)Math.Round(a.R + (b.R - a.R) * f),
                (byte)Math.Round(a.G + (b.G - a.G) * f),
                (byte)Math.Round(a.B + (b.B - a.B) * f));
        }

        public static Color SpectrumAt(double t)
        {
            t = Math.Max(0.0, Math.Min(1.0, t));
            double p = t * (Spectrum.Length - 1);
            int i = Math.Min(Spectrum.Length - 2, (int)p);
            return Mix(C(Spectrum[i]), C(Spectrum[i + 1]), p - i);
        }

        /// <summary>레벨 색(마그마): 낮으면 남색, 자홍·주황을 거쳐 높을수록 밝은 노랑. t 는 0(낮음)~1(높음).</summary>
        static readonly uint[] LevelStops = { 0xFF3A2A9A, 0xFF8C28A0, 0xFFE65064, 0xFFFFA032, 0xFFFFEB78 };

        public static Color LevelColor(double t)
        {
            t = Math.Max(0.0, Math.Min(1.0, t));
            double p = t * (LevelStops.Length - 1);
            int i = Math.Min(LevelStops.Length - 2, (int)p);
            return Mix(C(LevelStops[i]), C(LevelStops[i + 1]), p - i);
        }

        /// <summary>
        /// 폭 색: 폭이 넓으면(Q 낮음) 한쪽 끝, 좁을수록(Q 높음) 다른 끝. Q 0.2~20 을 로그 척도로 매긴다.
        /// 기본 11대역은 파랑→보라, 추가한 대역은 에메랄드→라임으로 구분한다(스펙트럼의 마그마 색과도 겹치지 않는 색).
        /// </summary>
        public static Color BandwidthColor(double q, bool extra)
        {
            double t = (Math.Log(Math.Max(0.2, Math.Min(20.0, q))) - Math.Log(0.2)) / (Math.Log(20.0) - Math.Log(0.2));
            if (extra) return Mix(C(0xFF1FE08A), C(0xFFB6F23C), t);
            return Mix(C(0xFF4DA3FF), C(0xFFB45CFF), t);
        }

        public static readonly SolidColorBrush ExtraBrush = B(0xFF1FE08A);

        public static SolidColorBrush Frozen(Color c)
        {
            var b = new SolidColorBrush(c);
            b.Freeze();
            return b;
        }
    }
}
