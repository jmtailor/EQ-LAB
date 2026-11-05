# EQ-LAB for Windows

안드로이드 EQ-LAB과 같은 디자인(청록·민트)의 윈도우용 11밴드 이퀄라이저. 소리 처리는 이미 설치된
[Equalizer APO](https://sourceforge.net/projects/equalizerapo/)에 맡기고, 이 앱은 화면(슬라이더, 스펙트럼, 프리셋)과
설정 파일 작성만 한다. 그래서 모든 프로그램의 소리에 적용되고 지연이 거의 없다.

## 동작 방식

1. 앱이 Equalizer APO 설정 폴더(`...\EqualizerAPO\config`)에 `eqlab.txt` 를 쓴다
   (`Preamp`, `Filter n: ON PK Fc ... Hz Gain ... dB Q ...`).
2. 처음 한 번, 사용자 동의를 받아 `config.txt` 맨 아래에 `Include: eqlab.txt` 한 줄을 추가한다.
   원본은 `config.txt.eqlab.bak` 으로 백업하고 기존 줄(예: Peace)은 건드리지 않는다.
3. Equalizer APO 는 설정 파일이 바뀌면 바로 다시 읽는다. 앱을 꺼도 EQ는 유지된다.
4. 스펙트럼은 WASAPI loopback(NAudio)으로 기본 출력 장치의 재생음을 받아 FFT 4096, 32막대로 계산한다.

## 조작

- 스펙트럼 위 점을 끌면 게인, 추가한 점은 좌우로 끌면 주파수도 바뀐다.
- 빈 곳을 더블클릭하면 그 주파수에 대역을 추가한다(기본 11개 + 추가 최대 13개). 추가한 점을 우클릭하면 지운다.
- 마우스 휠은 게인(0.5dB), Ctrl+휠은 Q(폭)를 바꾼다. 슬라이더 열 어디서든, 스펙트럼 위 점에서도 된다.
- 점을 가리키면 게인·주파수·Q와 폭(가로선)이 보인다.
- 색: 스펙트럼 막대는 마그마 색(낮으면 남색 → 자홍 → 주황 → 높으면 밝은 노랑). 기본 11대역의 점과 슬라이더는 폭이 넓으면 파랑, 좁으면 보라. 추가한 대역은 넓으면 에메랄드, 좁으면 라임(흰 테두리).
- 아이콘: `windows/tools/make_icon.py` 가 크기별 도안(256~16px)을 그려 `app.ico` 를 만든다. 작을수록 막대를 빼고 곡선을 굵게 한다.

## 사용 전 조건

- Equalizer APO 가 설치되어 있고, Configurator 에서 사용하는 출력 장치에 체크되어 있어야 한다(설치 후 재부팅 필요).
- 앱은 관리자 권한으로 실행된다(Program Files 안의 설정 폴더에 쓰기 위해).
- Peace 같은 다른 EQ 화면과 동시에 쓰면 필터가 겹쳐 이중으로 걸린다. 한쪽만 쓸 것.

## 빌드

로컬 빌드는 필요 없다. `windows/**` 를 푸시하면 GitHub Actions(`windows.yml`)가 self-contained 단일 exe 를
만들어 릴리스 `win-N` 에 `EqLabWin.exe` 로 올린다. 컴파일에 실패하면 로그가 릴리스 `winlog-N` 에 올라간다.

## 구조

| 파일 | 역할 |
|---|---|
| `EqEngine.cs` | 상태(11밴드 게인, 켜짐, 프리셋)의 단일 창구, 저장(`%APPDATA%\EqLabWin\settings.json`), 진단 기록 |
| `ApoWriter.cs` | Equalizer APO 탐색, `eqlab.txt` 작성, `config.txt` 연결 |
| `SpectrumCapture.cs` | WASAPI loopback + FFT, 32막대 dBFS |
| `SpectrumView.cs` | 스펙트럼 막대, 피크 홀드, EQ 곡선, 끌 수 있는 점 |
| `BandSlider.cs` | 알약형 세로 슬라이더 |
| `MainWindow.cs` | 화면 구성(코드), 프리셋·옵션, 연결 설정, 진단 창 |
| `Theme.cs` | 색상·브러시 |
