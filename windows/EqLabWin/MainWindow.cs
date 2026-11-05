using System;
using System.Globalization;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;

namespace EqLabWin
{
    /// <summary>화면 전체. 레이아웃은 코드로 구성하고, 상태는 EqEngine 만 거친다.</summary>
    sealed class MainWindow : Window
    {
        readonly SpectrumCapture capture = new SpectrumCapture();
        readonly SpectrumView spectrum;
        readonly TextBlock statusText = new TextBlock();
        readonly Border statusBox = new Border();
        readonly StackPanel headerButtons = new StackPanel();
        readonly TextBlock[] gainText = new TextBlock[EqEngine.N];
        readonly WrapPanel presetPanel = new WrapPanel();
        readonly WrapPanel optionPanel = new WrapPanel();

        string lastPresetKey;
        string lastOptionKey;
        bool spectrumOn = true;

        public MainWindow()
        {
            Title = "EQ-LAB for Windows";
            Width = 1120;
            Height = 800;
            MinWidth = 900;
            MinHeight = 680;
            Background = Theme.BgBrush;
            Foreground = Theme.TextBrush;
            FontFamily = Theme.Family;
            FontSize = 13;

            spectrum = new SpectrumView(capture) { MinHeight = 240 };

            var root = new Grid { Margin = new Thickness(18) };
            root.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
            root.RowDefinitions.Add(new RowDefinition { Height = new GridLength(1, GridUnitType.Star) });
            root.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
            root.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
            root.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });

            Grid header = BuildHeader();
            Grid.SetRow(header, 0);
            root.Children.Add(header);

            Grid.SetRow(spectrum, 1);
            spectrum.Margin = new Thickness(0, 14, 0, 0);
            root.Children.Add(spectrum);

            Grid sliders = BuildSliders();
            Grid.SetRow(sliders, 2);
            root.Children.Add(sliders);

            presetPanel.Margin = new Thickness(0, 16, 0, 0);
            Grid.SetRow(presetPanel, 3);
            root.Children.Add(presetPanel);

            optionPanel.Margin = new Thickness(0, 4, 0, 0);
            Grid.SetRow(optionPanel, 4);
            root.Children.Add(optionPanel);

            Content = root;

            EqEngine.Changed += Refresh;
            EqEngine.StatusChanged += () => Dispatcher.BeginInvoke(new Action(UpdateStatus));

            Refresh();
            UpdateStatus();

            Loaded += (s, e) =>
            {
                capture.Start();
                ConnectCheck();
            };
        }

        protected override void OnClosed(EventArgs e)
        {
            base.OnClosed(e);
            capture.Dispose();
            EqEngine.SaveNow();
        }

        // ---------- 만들기 ----------

        Grid BuildHeader()
        {
            var header = new Grid();
            header.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            header.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });

            var title = new StackPanel();
            title.Children.Add(new TextBlock
            {
                Text = "EQ-LAB",
                FontSize = 24,
                FontWeight = FontWeights.Bold,
                Foreground = Theme.AccentBrush
            });
            title.Children.Add(new TextBlock
            {
                Text = "Windows 이퀄라이저 · Equalizer APO 연동",
                Foreground = Theme.MutedBrush,
                Margin = new Thickness(0, 2, 0, 0)
            });
            Grid.SetColumn(title, 0);
            header.Children.Add(title);

            statusText.TextWrapping = TextWrapping.Wrap;
            statusText.FontSize = 12;
            statusBox.Child = statusText;
            statusBox.Padding = new Thickness(12, 7, 12, 7);
            statusBox.CornerRadius = new CornerRadius(10);
            statusBox.BorderThickness = new Thickness(1);
            statusBox.Background = Theme.SurfaceBrush;
            statusBox.MaxWidth = 380;
            statusBox.VerticalAlignment = VerticalAlignment.Center;
            statusBox.Margin = new Thickness(0, 0, 12, 0);

            var right = new StackPanel { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Right };
            right.Children.Add(statusBox);
            headerButtons.Orientation = Orientation.Horizontal;
            headerButtons.VerticalAlignment = VerticalAlignment.Center;
            right.Children.Add(headerButtons);
            Grid.SetColumn(right, 1);
            header.Children.Add(right);
            return header;
        }

        Grid BuildSliders()
        {
            var grid = new Grid { Height = 250, Margin = new Thickness(0, 12, 0, 0) };
            for (int i = 0; i < EqEngine.N; i++)
            {
                grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });

                var col = new Grid();
                col.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
                col.RowDefinitions.Add(new RowDefinition { Height = new GridLength(1, GridUnitType.Star) });
                col.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });

                gainText[i] = new TextBlock
                {
                    HorizontalAlignment = HorizontalAlignment.Center,
                    FontSize = 13,
                    FontWeight = FontWeights.SemiBold
                };
                Grid.SetRow(gainText[i], 0);
                col.Children.Add(gainText[i]);

                var slider = new BandSlider(i);
                Grid.SetRow(slider, 1);
                col.Children.Add(slider);

                var label = new TextBlock
                {
                    Text = EqEngine.FreqLabel[i],
                    HorizontalAlignment = HorizontalAlignment.Center,
                    Foreground = Theme.MutedBrush,
                    Margin = new Thickness(0, 2, 0, 0)
                };
                Grid.SetRow(label, 2);
                col.Children.Add(label);

                Grid.SetColumn(col, i);
                grid.Children.Add(col);
            }
            return grid;
        }

        static Border MakeChip(string text, bool selected, Action click, string tip, double bottom)
        {
            var tb = new TextBlock
            {
                Text = text,
                Foreground = selected ? Theme.OnAccentBrush : Theme.TextBrush,
                FontSize = 13,
                VerticalAlignment = VerticalAlignment.Center
            };
            var b = new Border
            {
                Child = tb,
                Padding = new Thickness(14, 7, 14, 7),
                Margin = new Thickness(0, 0, 8, bottom),
                CornerRadius = new CornerRadius(16),
                Background = selected ? Theme.AccentBrush : Theme.SurfaceBrush,
                BorderBrush = selected ? Theme.AccentBrush : Theme.OutlineBrush,
                BorderThickness = new Thickness(1),
                Cursor = Cursors.Hand
            };
            if (tip != null) b.ToolTip = tip;
            b.MouseLeftButtonUp += (s, e) => click();
            return b;
        }

        // ---------- 갱신 ----------

        static string FormatGain(double g)
        {
            if (Math.Abs(g) < 0.05) return "0";
            return (g > 0 ? "+" : "") + g.ToString("0.0", CultureInfo.InvariantCulture);
        }

        void Refresh()
        {
            double[] g = EqEngine.Snapshot();
            for (int i = 0; i < EqEngine.N; i++)
            {
                gainText[i].Text = FormatGain(g[i]);
                gainText[i].Foreground = g[i] > 0.05 ? Theme.AccentBrush : (g[i] < -0.05 ? Theme.CutTextBrush : Theme.MutedBrush);
            }

            string pkey = EqEngine.PresetKey();
            if (pkey != lastPresetKey)
            {
                lastPresetKey = pkey;
                BuildPresets();
            }

            string okey = EqEngine.On + "|" + EqEngine.AutoPreamp + "|" + EqEngine.Composite + "|" + spectrumOn
                          + "|" + EqEngine.CurrentPresetId;
            if (okey != lastOptionKey)
            {
                lastOptionKey = okey;
                BuildOptions();
            }
        }

        void BuildPresets()
        {
            presetPanel.Children.Clear();
            string cur = EqEngine.CurrentPresetId;
            foreach (Preset p in EqEngine.AllPresets())
            {
                Preset pp = p;
                presetPanel.Children.Add(MakeChip(pp.Name, pp.Id == cur, () => EqEngine.SelectPreset(pp.Id), pp.Desc, 8));
            }
        }

        void BuildOptions()
        {
            string cur = EqEngine.CurrentPresetId;

            headerButtons.Children.Clear();
            headerButtons.Children.Add(MakeChip(EqEngine.On ? "EQ 켜짐" : "EQ 꺼짐", EqEngine.On,
                () => EqEngine.SetOn(!EqEngine.On), "EQ를 켜거나 꺼요 (꺼도 설정은 유지돼요)", 0));
            headerButtons.Children.Add(MakeChip("연결 설정", false, ConnectCheck,
                "Equalizer APO 와 이 앱을 연결해요", 0));
            headerButtons.Children.Add(MakeChip("진단 기록", false, ShowLog, "연결 상태와 오류 기록을 봐요", 0));

            optionPanel.Children.Clear();
            optionPanel.Children.Add(MakeChip("내 프리셋으로 저장", false, EqEngine.SaveCustom, null, 8));
            if (EqEngine.IsCustom(cur))
                optionPanel.Children.Add(MakeChip("선택한 프리셋 삭제", false, () => EqEngine.DeleteCustom(cur), null, 8));
            optionPanel.Children.Add(MakeChip("초기화", false, EqEngine.Reset, "모든 대역을 0dB로", 8));
            optionPanel.Children.Add(MakeChip("클리핑 방지 (자동 프리앰프)", EqEngine.AutoPreamp,
                () => EqEngine.SetAutoPreamp(!EqEngine.AutoPreamp),
                "가장 큰 부스트만큼 전체 볼륨을 낮춰 소리 깨짐을 막아요", 8));
            optionPanel.Children.Add(MakeChip("스펙트럼 표시", spectrumOn, ToggleSpectrum, "재생 중인 소리를 분석해 막대로 보여요", 8));
            optionPanel.Children.Add(MakeChip(EqEngine.Composite ? "스펙트럼: EQ 곡선 더하기" : "스펙트럼: 캡처 그대로", false,
                () => EqEngine.SetComposite(!EqEngine.Composite),
                "막대가 EQ를 바꿔도 변하지 않으면 '더하기'로, 이중으로 움직이면 '캡처 그대로'로 두세요", 8));
        }

        void ToggleSpectrum()
        {
            spectrumOn = !spectrumOn;
            if (spectrumOn) capture.Start();
            else capture.Stop();
            Refresh();
        }

        void UpdateStatus()
        {
            bool ok = EqEngine.StatusOk;
            statusText.Text = EqEngine.Status;
            statusText.Foreground = ok ? Theme.AccentBrush : Theme.WarnBrush;
            statusBox.BorderBrush = ok ? Theme.AccentBrush : Theme.WarnBrush;
        }

        // ---------- Equalizer APO 연결 ----------

        void ConnectCheck()
        {
            string detail;
            ApoWriter.Link link = ApoWriter.CheckLink(out detail);
            if (link == ApoWriter.Link.NotInstalled)
            {
                EqEngine.SetStatus(false, "Equalizer APO를 찾지 못했습니다. 설치 후 다시 실행해 주세요.");
                return;
            }
            if (link == ApoWriter.Link.Error)
            {
                EqEngine.SetStatus(false, "config.txt 를 읽지 못했습니다: " + detail);
                return;
            }
            if (link == ApoWriter.Link.NotIncluded)
            {
                MessageBoxResult r = MessageBox.Show(this,
                    "Equalizer APO 설정 파일(config.txt) 맨 아래에 'Include: eqlab.txt' 한 줄을 추가해 이 앱과 연결합니다.\n"
                    + "원본은 config.txt.eqlab.bak 으로 백업하고, 기존 필터는 건드리지 않습니다.\n\n진행할까요?",
                    "EQ-LAB 연결 설정", MessageBoxButton.YesNo, MessageBoxImage.Question);
                if (r != MessageBoxResult.Yes)
                {
                    EqEngine.SetStatus(false, "연결 안 됨 - 오른쪽 위 '연결 설정'을 눌러 주세요.");
                    return;
                }
                ApoResult res = ApoWriter.EnsureInclude();
                if (!res.Ok)
                {
                    EqEngine.SetStatus(false, res.Message);
                    return;
                }
            }
            EqEngine.ApplyNow();
        }

        void ShowLog()
        {
            string install = ApoWriter.FindInstall();
            string detail;
            string link = install == null ? "-" : ApoWriter.CheckLink(out detail).ToString();
            string text = "Equalizer APO 경로: " + (install ?? "찾지 못함") + "\r\n"
                          + "config.txt 연결: " + link + "\r\n"
                          + "스펙트럼: " + capture.State + "\r\n"
                          + "상태: " + EqEngine.Status + "\r\n\r\n"
                          + EqEngine.LogText();
            var box = new TextBox
            {
                Text = text,
                IsReadOnly = true,
                Background = Theme.SurfaceBrush,
                Foreground = Theme.TextBrush,
                FontFamily = new FontFamily("Consolas, Malgun Gothic"),
                TextWrapping = TextWrapping.Wrap,
                VerticalScrollBarVisibility = ScrollBarVisibility.Auto,
                BorderThickness = new Thickness(0),
                Padding = new Thickness(14)
            };
            var w = new Window
            {
                Title = "진단 기록",
                Width = 680,
                Height = 440,
                Owner = this,
                Background = Theme.BgBrush,
                Content = box
            };
            w.ShowDialog();
        }
    }
}
