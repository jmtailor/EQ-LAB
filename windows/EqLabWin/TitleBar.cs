using System;
using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Interop;

namespace EqLabWin
{
    /// <summary>
    /// 제목 표시줄 색을 UI 배경색과 맞춘다(DWM 속성). 캡션·글자·테두리 색 지정은 Windows 11(빌드 22000 이상)에서만 되고,
    /// Windows 10 에서는 어두운 제목 표시줄(다크 모드)까지만 적용된다.
    /// </summary>
    static class TitleBar
    {
        [DllImport("dwmapi.dll", PreserveSig = true)]
        static extern int DwmSetWindowAttribute(IntPtr hwnd, int attr, ref int value, int size);

        const int UseImmersiveDarkModeOld = 19;
        const int UseImmersiveDarkMode = 20;
        const int BorderColor = 34;
        const int CaptionColor = 35;
        const int TextColor = 36;

        /// <summary>0xAARRGGBB 를 DWM 이 쓰는 COLORREF(0x00BBGGRR)로 바꾼다.</summary>
        static int Bgr(uint argb)
        {
            int r = (int)((argb >> 16) & 0xFF);
            int g = (int)((argb >> 8) & 0xFF);
            int b = (int)(argb & 0xFF);
            return (b << 16) | (g << 8) | r;
        }

        /// <summary>창의 핸들이 만들어지면 색을 적용하도록 연결한다. 창을 보이기 전에 부른다.</summary>
        public static void Attach(Window w)
        {
            w.SourceInitialized += (s, e) => Apply(w);
        }

        static void Apply(Window w)
        {
            try
            {
                IntPtr h = new WindowInteropHelper(w).Handle;
                if (h == IntPtr.Zero) return;

                int dark = 1;
                if (DwmSetWindowAttribute(h, UseImmersiveDarkMode, ref dark, sizeof(int)) != 0)
                    DwmSetWindowAttribute(h, UseImmersiveDarkModeOld, ref dark, sizeof(int));

                int caption = Bgr(Theme.BgC);
                int text = Bgr(0xFFE8F4F2);
                int border = Bgr(0xFF1F2F30);
                int r = DwmSetWindowAttribute(h, CaptionColor, ref caption, sizeof(int));
                DwmSetWindowAttribute(h, TextColor, ref text, sizeof(int));
                DwmSetWindowAttribute(h, BorderColor, ref border, sizeof(int));
                if (r != 0) EqEngine.Log("제목 표시줄 색 지정은 Windows 11 에서만 적용돼요 (코드 " + r + ")");
            }
            catch (Exception e)
            {
                EqEngine.Log("제목 표시줄 색 적용 실패: " + e.Message);
            }
        }
    }
}
