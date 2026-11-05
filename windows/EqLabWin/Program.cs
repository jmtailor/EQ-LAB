using System;
using System.Windows;
using System.Windows.Threading;

namespace EqLabWin
{
    static class Program
    {
        [STAThread]
        static void Main()
        {
            EqEngine.Load();
            var app = new Application();
            app.DispatcherUnhandledException += OnUnhandled;
            app.Run(new MainWindow());
        }

        static void OnUnhandled(object sender, DispatcherUnhandledExceptionEventArgs e)
        {
            // 오류를 삼키지 않고 진단 기록에 남긴다.
            EqEngine.Log("처리되지 않은 오류: " + e.Exception);
            e.Handled = true;
        }
    }
}
