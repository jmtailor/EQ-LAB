using System;
using System.Globalization;
using System.Threading;
using System.Windows;
using System.Windows.Input;
using System.Windows.Media;

namespace EqLabWin
{
    /// <summary>
    /// 스펙트럼 막대(레벨 그라데이션: 낮으면 초록 → 높으면 빨강), 피크 홀드, EQ 곡선(글로우), 끌 수 있는 대역 점
    /// (폭 색: 넓으면 파랑 → 좁으면 보라)을 한 화면에 그린다. 모니터 주사율에 맞춰 그린다.
    /// 가로축은 20Hz~16kHz 로그, 곡선의 세로축은 -12~+12dB.
    /// 점 조작: 끌기(게인, 추가한 점은 주파수도), 더블클릭(점은 0dB로, 빈 곳은 점 추가), 우클릭(추가한 점 삭제),
    /// 휠(게인), Ctrl+휠(Q).
    /// </summary>
    sealed class SpectrumView : FrameworkElement
    {
        const double PadL = 10, PadR = 10, PadT = 12, PadB = 26;
        const float DbFloor = -80f;     // 이 아래는 무음으로 보고 막대를 그리지 않는다
        const float DispFloor = -72f;   // 막대 높이가 0이 되는 레벨(화면 맨 아래)
        const int Lut = 48;

        readonly SpectrumCapture capture;
        readonly float[] shown = new float[SpectrumCapture.Bars];
        readonly float[] peak = new float[SpectrumCapture.Bars];
        readonly float[] peakHold = new float[SpectrumCapture.Bars];
        readonly Brush[] capBrush = new Brush[Lut];

        // 상태가 바뀌었거나 크기가 바뀐 때만 다시 계산하는 값
        int cVer = -1;
        double cW, cH;
        Band[] cBands;
        StreamGeometry cGeo;
        Brush[] cPointBrush;
        readonly double[] cOff = new double[SpectrumCapture.Bars];
        readonly double[] cBx = new double[SpectrumCapture.Bars];
        readonly double[] cBw = new double[SpectrumCapture.Bars];

        LinearGradientBrush levelBrush;
        double lbBottom = double.NaN;

        TimeSpan lastRender = TimeSpan.Zero;
        bool hooked;
        int drag = -1;
        int hover = -1;

        public static string FreqText(double hz)
        {
            if (hz >= 1000.0) return (hz / 1000.0).ToString("0.##", CultureInfo.InvariantCulture) + "k";
            return hz.ToString("0", CultureInfo.InvariantCulture);
        }

        public SpectrumView(SpectrumCapture capture)
        {
            this.capture = capture;
            ClipToBounds = true;
            for (int b = 0; b < SpectrumCapture.Bars; b++)
            {
                shown[b] = DbFloor;
                peak[b] = DbFloor;
            }
            for (int i = 0; i < Lut; i++)
            {
                Color c = Theme.LevelColor(i / (Lut - 1.0));
                capBrush[i] = Theme.Frozen(Color.FromRgb(
                    (byte)Math.Min(255, c.R + 70), (byte)Math.Min(255, c.G + 70), (byte)Math.Min(255, c.B + 70)));
            }
            Loaded += (s, e) => Hook();
            Unloaded += (s, e) => Unhook();
            EqEngine.Changed += () => InvalidateVisual();
        }

        void Hook()
        {
            if (hooked) return;
            CompositionTarget.Rendering += OnFrame;
            hooked = true;
        }

        void Unhook()
        {
            if (!hooked) return;
            CompositionTarget.Rendering -= OnFrame;
            hooked = false;
            lastRender = TimeSpan.Zero;
        }

        /// <summary>모니터 주사율마다(보통 60~144Hz) 한 번 불린다. 같은 프레임에 중복으로 오면 무시한다.</summary>
        void OnFrame(object sender, EventArgs e)
        {
            var args = e as RenderingEventArgs;
            TimeSpan t = args != null ? args.RenderingTime : TimeSpan.Zero;
            if (args != null && t == lastRender) return;
            double dt = lastRender == TimeSpan.Zero ? 0.016 : (t - lastRender).TotalSeconds;
            lastRender = t;
            dt = Math.Max(0.001, Math.Min(0.1, dt));
            Step((float)dt);
            InvalidateVisual();
        }

        // ---------- 좌표 ----------

        double PlotW { get { return ActualWidth - PadL - PadR; } }
        double PlotH { get { return ActualHeight - PadT - PadB; } }

        double XOf(double hz)
        {
            return PadL + Math.Log(hz / 20.0) / Math.Log(800.0) * PlotW;
        }

        double HzOf(double x)
        {
            double t = (x - PadL) / PlotW;
            t = Math.Max(0.0, Math.Min(1.0, t));
            return 20.0 * Math.Pow(800.0, t);
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

        /// <summary>프레임 간격(dt, 초)에 맞춰 막대를 올리고 내린다. 오를 때는 바로, 내릴 때는 초당 80dB.</summary>
        void Step(float dt)
        {
            float[] db = capture.Db;
            bool stale = Environment.TickCount64 - Volatile.Read(ref capture.LastTick) > 250;
            float fall = 80f * dt;
            float peakFall = 30f * dt;
            for (int b = 0; b < SpectrumCapture.Bars; b++)
            {
                float target = stale ? DbFloor : Math.Max(db[b], DbFloor);
                if (target > shown[b]) shown[b] = target;
                else shown[b] = Math.Max(target, shown[b] - fall);

                if (shown[b] >= peak[b])
                {
                    peak[b] = shown[b];
                    peakHold[b] = 0.6f;
                }
                else if (peakHold[b] > 0f) peakHold[b] -= dt;
                else peak[b] = Math.Max(DbFloor, peak[b] - peakFall);
            }
        }

        void EnsureCache(double W, double H)
        {
            int v = EqEngine.Version;
            if (v == cVer && W == cW && H == cH && cBands != null) return;
            cVer = v;
            cW = W;
            cH = H;

            Band[] bs = EqEngine.Snapshot();
            cBands = bs;
            bool addCurve = EqEngine.Composite && EqEngine.On;
            double sqrtR = Math.Sqrt(SpectrumCapture.Ratio);
            for (int b = 0; b < SpectrumCapture.Bars; b++)
            {
                double f = SpectrumCapture.BarFreq[b];
                cOff[b] = addCurve ? EqEngine.ResponseDb(bs, f) : 0.0;
                double x1 = XOf(f / sqrtR), x2 = XOf(f * sqrtR);
                cBw[b] = Math.Max(1.0, (x2 - x1) * 0.8);
                cBx[b] = (x1 + x2) / 2.0 - cBw[b] / 2.0;
            }

            // EQ 곡선: 모든 대역 필터를 겹친 실제 응답
            var geo = new StreamGeometry();
            using (StreamGeometryContext ctx = geo.Open())
            {
                const int steps = 200;
                for (int i = 0; i <= steps; i++)
                {
                    double hz = 20.0 * Math.Pow(800.0, i / (double)steps);
                    var pt = new Point(XOf(hz), YOf(EqEngine.ResponseDb(bs, hz)));
                    if (i == 0) ctx.BeginFigure(pt, false, false);
                    else ctx.LineTo(pt, true, true);
                }
            }
            geo.Freeze();
            cGeo = geo;

            cPointBrush = new Brush[bs.Length];
            for (int i = 0; i < bs.Length; i++) cPointBrush[i] = Theme.Frozen(Theme.BandwidthColor(bs[i].Q));
        }

        void EnsureGradient(double bottom)
        {
            if (levelBrush != null && lbBottom == bottom) return;
            lbBottom = bottom;
            var g = new LinearGradientBrush
            {
                MappingMode = BrushMappingMode.Absolute,
                StartPoint = new Point(0, bottom),
                EndPoint = new Point(0, PadT)
            };
            for (int i = 0; i <= 5; i++)
            {
                double t = i / 5.0;
                Color c = Theme.LevelColor(t);
                g.GradientStops.Add(new GradientStop(Color.FromArgb(225, c.R, c.G, c.B), t));
            }
            g.Freeze();
            levelBrush = g;
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

            EnsureCache(W, H);
            double bottom = PadT + PlotH;
            EnsureGradient(bottom);
            Band[] bs = cBands;
            bool eqOn = EqEngine.On;

            dc.DrawRoundedRectangle(Theme.SurfaceBrush, Theme.PenBorder, new Rect(0.5, 0.5, W - 1, H - 1), 12, 12);

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

            // 스펙트럼 막대: 높이는 레벨, 색은 화면 높이에 고정된 그라데이션(낮은 곳 초록 → 높은 곳 빨강)
            double span = 0.0 - DispFloor;
            for (int b = 0; b < SpectrumCapture.Bars; b++)
            {
                // 소리가 없을 때(바닥)는 곡선 값을 더하지 않고 막대를 그리지 않는다.
                if (shown[b] <= DbFloor + 0.5f) continue;

                double frac = Math.Max(0.0, Math.Min(1.0, (shown[b] + cOff[b] - DispFloor) / span));
                double h = frac * PlotH;
                if (h >= 1.0) dc.DrawRectangle(levelBrush, null, new Rect(cBx[b], bottom - h, cBw[b], h));

                double pf = Math.Max(0.0, Math.Min(1.0, (peak[b] + cOff[b] - DispFloor) / span));
                if (pf > 0.01)
                {
                    int li = (int)Math.Round(pf * (Lut - 1));
                    dc.DrawRectangle(capBrush[li], null, new Rect(cBx[b], bottom - pf * PlotH - 2.0, cBw[b], 2.0));
                }
            }

            // EQ 곡선 (꺼지면 흐리게)
            if (eqOn) dc.DrawGeometry(null, Theme.PenGlow, cGeo);
            dc.PushOpacity(eqOn ? 1.0 : 0.35);
            dc.DrawGeometry(null, Theme.PenCurve, cGeo);
            dc.Pop();

            // 점 (색은 폭: 넓으면 파랑, 좁으면 보라. 기본 대역은 어두운 테두리, 추가한 대역은 흰 테두리)
            for (int i = 0; i < bs.Length; i++)
            {
                var c = new Point(XOf(bs[i].Freq), YOf(bs[i].Gain));
                bool active = drag == i || hover == i;

                if (active)
                {
                    // 폭 표시: -3dB 지점 기준 폭(옥타브)을 점 높이에 가로선으로 보여 준다.
                    double bwOct = 2.0 / Math.Log(2.0) * Math.Asinh(1.0 / (2.0 * bs[i].Q));
                    double xl = Math.Max(PadL, XOf(bs[i].Freq * Math.Pow(2.0, -bwOct / 2.0)));
                    double xr = Math.Min(PadL + PlotW, XOf(bs[i].Freq * Math.Pow(2.0, bwOct / 2.0)));
                    var bwPen = new Pen(cPointBrush[i], 3.0);
                    dc.DrawLine(bwPen, new Point(xl, c.Y), new Point(xr, c.Y));
                    dc.DrawLine(bwPen, new Point(xl, c.Y - 5), new Point(xl, c.Y + 5));
                    dc.DrawLine(bwPen, new Point(xr, c.Y - 5), new Point(xr, c.Y + 5));
                }

                double r = active ? 9.0 : 6.5;
                dc.PushOpacity(eqOn ? 1.0 : 0.5);
                dc.DrawEllipse(cPointBrush[i], bs[i].Extra ? Theme.PenExtra : Theme.PenPoint, c, r, r);
                dc.Pop();

                if (active)
                {
                    string gt = (bs[i].Gain > 0 ? "+" : "") + bs[i].Gain.ToString("0.0", CultureInfo.InvariantCulture)
                                + " dB · " + FreqText(bs[i].Freq) + " Hz · Q " + bs[i].Q.ToString("0.##", CultureInfo.InvariantCulture);
                    double ty = c.Y - 32 < PadT ? c.Y + 14 : c.Y - 32;
                    Text(dc, gt, Math.Max(PadL + 70, Math.Min(PadL + PlotW - 70, c.X)), ty, Theme.TextBrush, 12, true);
                }
            }

            // 안내 문구(점을 못 만들었을 때 등)
            string notice = EqEngine.NoticeText;
            if (notice != null && Environment.TickCount64 - EqEngine.NoticeTick < 2500)
                Text(dc, notice, PadL + PlotW / 2.0, PadT + 6, Theme.WarnBrush, 13, true);
        }

        // ---------- 마우스 ----------

        int HitPoint(Point p, Band[] bs)
        {
            int best = -1;
            double bestD = 18.0 * 18.0;
            for (int i = 0; i < bs.Length; i++)
            {
                double dx = XOf(bs[i].Freq) - p.X;
                double dy = YOf(bs[i].Gain) - p.Y;
                double d = dx * dx + dy * dy;
                if (d < bestD)
                {
                    bestD = d;
                    best = i;
                }
            }
            return best;
        }

        bool InPlot(Point p)
        {
            return p.X >= PadL && p.X <= PadL + PlotW && p.Y >= PadT && p.Y <= PadT + PlotH;
        }

        void UpdateDrag(Point p)
        {
            if (drag < 0) return;
            if (EqEngine.IsExtra(drag)) EqEngine.SetFreq(drag, HzOf(p.X));
            EqEngine.SetGain(drag, GainOf(p.Y));
        }

        protected override void OnMouseLeftButtonDown(MouseButtonEventArgs e)
        {
            Point p = e.GetPosition(this);
            Band[] bs = EqEngine.Snapshot();
            int i = HitPoint(p, bs);
            if (i < 0)
            {
                // 빈 곳을 더블클릭하면 그 주파수에 대역을 추가한다.
                if (e.ClickCount >= 2 && InPlot(p))
                {
                    string err = EqEngine.AddBand(HzOf(p.X));
                    if (err != null) EqEngine.Notify(err);
                    hover = -1;
                    drag = -1;
                    e.Handled = true;
                }
                return;
            }
            if (e.ClickCount >= 2)
            {
                EqEngine.SetGain(i, 0);
                e.Handled = true;
                return;
            }
            drag = i;
            hover = i;
            CaptureMouse();
            UpdateDrag(p);
            InvalidateVisual();
            e.Handled = true;
        }

        protected override void OnMouseMove(MouseEventArgs e)
        {
            Point p = e.GetPosition(this);
            if (drag >= 0)
            {
                UpdateDrag(p);
                InvalidateVisual();
                return;
            }
            int h = HitPoint(p, EqEngine.Snapshot());
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

        protected override void OnMouseRightButtonUp(MouseButtonEventArgs e)
        {
            Point p = e.GetPosition(this);
            int i = HitPoint(p, EqEngine.Snapshot());
            if (i < 0) return;
            if (EqEngine.IsExtra(i))
            {
                EqEngine.RemoveBand(i);
                hover = -1;
                drag = -1;
            }
            else
            {
                EqEngine.Notify("기본 대역은 지울 수 없어요");
            }
            e.Handled = true;
        }

        protected override void OnMouseWheel(MouseWheelEventArgs e)
        {
            int target = drag >= 0 ? drag : hover;
            if (target < 0) target = HitPoint(e.GetPosition(this), EqEngine.Snapshot());
            if (target < 0) return;
            hover = target;
            bool ctrl = (Keyboard.Modifiers & ModifierKeys.Control) != 0;
            EqEngine.Wheel(target, e.Delta, ctrl);
            InvalidateVisual();
            e.Handled = true;
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
