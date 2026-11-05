using System;
using System.Windows;
using System.Windows.Input;
using System.Windows.Media;

namespace EqLabWin
{
    /// <summary>알약형 세로 슬라이더 한 줄(대역 하나). 위가 +12dB, 아래가 -12dB. 휠 조작은 열 전체에서 받는다(MainWindow).</summary>
    sealed class BandSlider : FrameworkElement
    {
        const double Pad = 14;
        readonly int index;
        readonly Action repaint;
        bool dragging;

        public BandSlider(int index)
        {
            this.index = index;
            MinHeight = 120;
            Cursor = Cursors.Hand;
            repaint = () => InvalidateVisual();
            // 슬라이더 줄을 다시 만들 때 옛 슬라이더가 남아 갱신을 받지 않도록 화면에 있는 동안만 구독한다.
            Loaded += (s, e) => EqEngine.Changed += repaint;
            Unloaded += (s, e) => EqEngine.Changed -= repaint;
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
            // 색은 이 대역의 폭(Q): 기본 대역은 파랑→보라, 추가 대역은 에메랄드→라임
            Brush fill = Theme.Frozen(Theme.BandwidthColor(EqEngine.GetQ(index), EqEngine.IsExtra(index)));
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
    }
}
