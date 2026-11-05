using System;
using System.Globalization;
using System.Threading;
using System.Windows;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Threading;

namespace EqLabWin
{
    /// <summary>
    /// 스펙트럼 막대(주파수별 색), 피크 홀드, EQ 곡선(글로우), 끌 수 있는 11개 점을 한 화면에 그린다.
    /// 가로축은 20Hz~16kHz 로그, 곡선의 세로축은 -12~+12dB.
    /// </summary>
    sealed class SpectrumView : FrameworkElement
    {
        const double PadL = 10, PadR = 10, PadT = 12, PadB = 26;
        const float DbFloor = -80f;

        readonly SpectrumCapture capture;
        readonly float[] shown = new float[SpectrumCapture.Bars];
        readonly float[] peak = new float[SpectrumCapture.Bars];
        readonly int[] peakHold = new int[SpectrumCapture.Bars];
        readonly Brush[] barBrush = new Brush[SpectrumCapture.Bars];
        readonly Brush[] peakBrush = new Brush[SpectrumCapture.Bars];
        readonly DispatcherTimer timer;

        int drag = -1;
        int hover = -1;

        public SpectrumView(SpectrumCapture capture)
        {
            this.capture = capture;
            ClipToBounds = true;
            for (int b = 0; b < SpectrumCapture.Bars; b++)
            {
                shown[b] = DbFloor;
                peak[b] = DbFloor;
                Color c = Theme.SpectrumAt(b / (SpectrumCapture.Bars - 1.0));
                barBrush[b] = Theme.Frozen(Color.FromArgb(190, c.R, c.G, c.B));
                peakBrush[b] = Theme.Frozen(Color.FromArgb(255,
                    (byte)Math.Min(255, c.R + 60), (byte)Math.Min(255, c.G + 60), (byte)Math.Min(255, c.B + 60)));
            }
            timer = new DispatcherTimer(DispatcherPriority.Render) { Interval = TimeSpan.FromMilliseconds(33) };
            timer.Tick += (s, e) => { Step(); InvalidateVisual(); };
            Loaded += (s, e) => timer.Start();
            Unloaded += (s, e) => timer.Stop();
            EqEngine.Changed += () => InvalidateVisual();
        }

        // ---------- 좌표 ----------

        double PlotW { get { return ActualWidth - PadL - PadR; } }
        double PlotH { get { return ActualHeight - PadT - PadB; } }

        double XOf(double hz)
        {
            return PadL + Math.Log(hz / 20.0) / Math.Log(800.0) * PlotW;
        }

        double YOf(double gain)
        {
            double half = PlotH / 2.0;
            return PadT + half - gain / 12.0 * half * 0.9;
        }

        double GainOf(double y)
        {
            double half = PlotH / 2.0;
            return (PadT + half - y) / (half * 0.9) * 12.0;
        }

        // ---------- 갱신 ----------

        void Step()
        {
            float[] db = capture.Db;
            bool stale = Environment.TickCount64 - Volatile.Read(ref capture.LastTick) > 250;
            for (int b = 0; b < SpectrumCapture.Bars; b++)
            {
                float target = stale ? DbFloor : Math.Max(db[b], DbFloor);
                if (target > shown[b]) shown[b] = target;
                else shown[b] = Math.Max(target, shown[b] - 2.5f);

                if (shown[b] >= peak[b])
                {
                    peak[b] = shown[b];
                    peakHold[b] = 20;
                }
                else if (peakHold[b] > 0) peakHold[b]--;
                else peak[b] = Math.Max(DbFloor, peak[b] - 1.0f);
            }
        }

        // ---------- 그리기 ----------

        void Text(DrawingContext dc, string s, double x, double y, Brush brush, double size, bool centerX)
        {
            var ft = new FormattedText(s, CultureInfo.InvariantCulture, FlowDirection.LeftToRight,
                Theme.Font, size, brush, VisualTreeHelper.GetDpi(this).PixelsPerDip);
            dc.DrawText(ft, new Point(centerX ? x - ft.Width / 2.0 : x, y));
        }

        protected override void OnRender(DrawingContext dc)
        {
            double W = ActualWidth, H = ActualHeight;
            if (W < 120 || H < 100) return;

            dc.DrawRoundedRectangle(Theme.SurfaceBrush, Theme.PenBorder, new Rect(0.5, 0.5, W - 1, H - 1), 12, 12);

            double bottom = PadT + PlotH;
            double[] g = EqEngine.Snapshot();
            bool eqOn = EqEngine.On;

            // 격자: 주파수 세로선과 게인 가로선
            int[] gridHz = { 50, 100, 200, 500, 1000, 2000, 5000, 10000 };
            string[] gridLabel = { "50", "100", "200", "500", "1k", "2k", "5k", "10k" };
            for (int i = 0; i < gridHz.Length; i++)
            {
                double x = XOf(gridHz[i]);
                dc.DrawLine(Theme.PenGrid, new Point(x, PadT), new Point(x, bottom));
                Text(dc, gridLabel[i], x, bottom + 5, Theme.MutedBrush, 11, true);
            }
            int[] gridDb = { -12, -6, 0, 6, 12 };
            for (int i = 0; i < gridDb.Length; i++)
            {
                double y = YOf(gridDb[i]);
                dc.DrawLine(Theme.PenGrid, new Point(PadL, y), new Point(PadL + PlotW, y));
                string lab = gridDb[i] > 0 ? "+" + gridDb[i] : gridDb[i].ToString(CultureInfo.InvariantCulture);
                Text(dc, lab, PadL + 4, y - 14, Theme.MutedBrush, 10, false);
            }

            // 스펙트럼 막대
            bool addCurve = EqEngine.Composite && eqOn;
            double sqrtR = Math.Sqrt(SpectrumCapture.Ratio);
            for (int b = 0; b < SpectrumCapture.Bars; b++)
            {
                double f = SpectrumCapture.BarFreq[b];
                double off = addCurve ? EqEngine.Interp(g, f) : 0.0;
                double x1 = XOf(f / sqrtR), x2 = XOf(f * sqrtR);
                double bw = (x2 - x1) * 0.72;
                double bx = (x1 + x2) / 2.0 - bw / 2.0;

                // 소리가 없을 때(바닥)는 곡선 값을 더하지 않고 막대를 그리지 않는다.
                if (shown[b] <= DbFloor + 0.5f) continue;

                double frac = Math.Max(0.0, Math.Min(1.0, (shown[b] + off - DbFloor) / (0.0 - DbFloor)));
                double h = frac * PlotH;
                if (h >= 1.0) dc.DrawRectangle(barBrush[b], null, new Rect(bx, bottom - h, bw, h));

                double pf = Math.Max(0.0, Math.Min(1.0, (peak[b] + off - DbFloor) / (0.0 - DbFloor)));
                if (pf > 0.01) dc.DrawRectangle(peakBrush[b], null, new Rect(bx, bottom - pf * PlotH - 1.5, bw, 2.0));
            }

            // EQ 곡선 (켜져 있을 때만 강조, 꺼지면 흐리게)
            var geo = new StreamGeometry();
            using (StreamGeometryContext ctx = geo.Open())
            {
                const int steps = 160;
                for (int i = 0; i <= steps; i++)
                {
                    double hz = 20.0 * Math.Pow(800.0, i / (double)steps);
                    var pt = new Point(XOf(hz), YOf(EqEngine.Interp(g, hz)));
                    if (i == 0) ctx.BeginFigure(pt, false, false);
                    else ctx.LineTo(pt, true, true);
                }
            }
            geo.Freeze();
            if (eqOn) dc.DrawGeometry(null, Theme.PenGlow, geo);
            dc.PushOpacity(eqOn ? 1.0 : 0.35);
            dc.DrawGeometry(null, Theme.PenCurve, geo);
            dc.Pop();

            // 점
            for (int i = 0; i < EqEngine.N; i++)
            {
                var c = new Point(XOf(EqEngine.Freq[i]), YOf(g[i]));
                bool active = drag == i || hover == i;
                double r = active ? 9.0 : 6.5;
                Brush fill = g[i] >= 0 ? Theme.AccentBrush : Theme.CutBrush;
                dc.PushOpacity(eqOn ? 1.0 : 0.5);
                dc.DrawEllipse(fill, Theme.PenPoint, c, r, r);
                dc.Pop();
                if (active)
                {
                    string gt = (g[i] > 0 ? "+" : "") + g[i].ToString("0.0", CultureInfo.InvariantCulture) + " dB · "
                                + EqEngine.FreqLabel[i] + " Hz";
                    double ty = c.Y - 30 < PadT ? c.Y + 14 : c.Y - 30;
                    Text(dc, gt, Math.Max(PadL + 40, Math.Min(PadL + PlotW - 40, c.X)), ty, Theme.TextBrush, 12, true);
                }
            }
        }

        // ---------- 마우스 ----------

        int HitPoint(Point p)
        {
            double[] g = EqEngine.Snapshot();
            int best = -1;
            double bestD = 18.0 * 18.0;
            for (int i = 0; i < EqEngine.N; i++)
            {
                double dx = XOf(EqEngine.Freq[i]) - p.X;
                double dy = YOf(g[i]) - p.Y;
                double d = dx * dx + dy * dy;
                if (d < bestD)
                {
                    bestD = d;
                    best = i;
                }
            }
            return best;
        }

        protected override void OnMouseLeftButtonDown(MouseButtonEventArgs e)
        {
            Point p = e.GetPosition(this);
            int i = HitPoint(p);
            if (i < 0) return;
            if (e.ClickCount >= 2)
            {
                EqEngine.SetGain(i, 0);
                e.Handled = true;
                return;
            }
            drag = i;
            CaptureMouse();
            EqEngine.SetGain(i, GainOf(p.Y));
            InvalidateVisual();
            e.Handled = true;
        }

        protected override void OnMouseMove(MouseEventArgs e)
        {
            Point p = e.GetPosition(this);
            if (drag >= 0)
            {
                EqEngine.SetGain(drag, GainOf(p.Y));
                InvalidateVisual();
                return;
            }
            int h = HitPoint(p);
            if (h != hover)
            {
                hover = h;
                Cursor = h >= 0 ? Cursors.Hand : Cursors.Arrow;
                InvalidateVisual();
            }
        }

        protected override void OnMouseLeftButtonUp(MouseButtonEventArgs e)
        {
            if (drag >= 0)
            {
                drag = -1;
                ReleaseMouseCapture();
                InvalidateVisual();
            }
        }

        protected override void OnMouseLeave(MouseEventArgs e)
        {
            if (drag < 0 && hover != -1)
            {
                hover = -1;
                Cursor = Cursors.Arrow;
                InvalidateVisual();
            }
        }
    }
}
