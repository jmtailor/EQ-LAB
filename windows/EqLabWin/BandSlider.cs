using System;
using System.Windows;
using System.Windows.Input;
using System.Windows.Media;

namespace EqLabWin
{
    /// <summary>알약형 세로 슬라이더 한 줄(대역 하나). 위가 +12dB, 아래가 -12dB.</summary>
    sealed class BandSlider : FrameworkElement
    {
        const double Pad = 14;
        readonly int index;
        bool dragging;

        public BandSlider(int index)
        {
            this.index = index;
            MinHeight = 120;
            Cursor = Cursors.Hand;
            EqEngine.Changed += () => InvalidateVisual();
        }

        double TrackTop { get { return Pad; } }
        double TrackBottom { get { return ActualHeight - Pad; } }

        double YOf(double g)
        {
            double t = (EqEngine.MaxGain - g) / (EqEngine.MaxGain - EqEngine.MinGain);
            return TrackTop + t * (TrackBottom - TrackTop);
        }

        double GainOf(double y)
        {
            double t = (y - TrackTop) / (TrackBottom - TrackTop);
            return EqEngine.MaxGain - t * (EqEngine.MaxGain - EqEngine.MinGain);
        }

        protected override void OnRender(DrawingContext dc)
        {
            double w = ActualWidth, h = ActualHeight;
            if (w < 10 || h < 60) return;

            dc.DrawRectangle(Brushes.Transparent, null, new Rect(0, 0, w, h));

            double cx = w / 2.0, tw = 12.0;
            double g = EqEngine.GetGain(index);
            bool eqOn = EqEngine.On;

            dc.DrawRoundedRectangle(Theme.TrackBrush, Theme.PenOutline,
                new Rect(cx - tw / 2.0, TrackTop, tw, TrackBottom - TrackTop), 6, 6);

            double y0 = YOf(0.0), y = YOf(g);
            Brush fill = g >= 0 ? Theme.AccentBrush : Theme.CutBrush;
            dc.PushOpacity(eqOn ? 1.0 : 0.45);
            if (Math.Abs(y - y0) > 0.5)
                dc.DrawRoundedRectangle(fill, null,
                    new Rect(cx - tw / 2.0 + 2.0, Math.Min(y, y0), tw - 4.0, Math.Abs(y - y0)), 3, 3);
            dc.Pop();

            dc.DrawRectangle(Theme.MutedBrush, null, new Rect(cx - 12, y0 - 0.5, 24, 1));
            dc.DrawEllipse(Theme.TextBrush, null, new Point(cx, y), 9, 9);
            dc.DrawEllipse(fill, null, new Point(cx, y), 5, 5);
        }

        protected override void OnMouseLeftButtonDown(MouseButtonEventArgs e)
        {
            if (e.ClickCount >= 2)
            {
                EqEngine.SetGain(index, 0);
                e.Handled = true;
                return;
            }
            dragging = true;
            CaptureMouse();
            EqEngine.SetGain(index, GainOf(e.GetPosition(this).Y));
            e.Handled = true;
        }

        protected override void OnMouseMove(MouseEventArgs e)
        {
            if (dragging) EqEngine.SetGain(index, GainOf(e.GetPosition(this).Y));
        }

        protected override void OnMouseLeftButtonUp(MouseButtonEventArgs e)
        {
            if (!dragging) return;
            dragging = false;
            ReleaseMouseCapture();
        }

        protected override void OnMouseWheel(MouseWheelEventArgs e)
        {
            EqEngine.SetGain(index, EqEngine.GetGain(index) + (e.Delta > 0 ? EqEngine.Step : -EqEngine.Step));
            e.Handled = true;
        }
    }
}
