# EQ-LAB 개발 가이드 (작업 재개용 단일 기준 문서)

새 세션이나 다른 PC에서 작업을 이어갈 때 이 문서만 읽고 같은 방식으로 개발한다. 최종 갱신: 261103 / 기준 커밋은 `git log` 확인 (최신 빌드 build-10, 슬라이더 개선·주파수 점 추가·스펙트럼 색).

## 1. 프로젝트 요약
- 갤럭시 S22 울트라(Android 16) 대상. 음악 앱(유튜브 뮤직 등) 재생음에 11밴드 EQ를 실시간 적용하고, 실시간 스펙트럼을 보며 조절하는 앱.
- 저장소: https://github.com/jmtailor/EQ-LAB (로컬 클론 경로 예: /home/claude/eq-lab, 원격 origin, 브랜치 main)
- 모듈: `app` = 시험용(com.eqlab, 동결, 수정하지 않음) / `equalizer` = 본 앱(com.eqlab.equalizer, 이후 모든 작업 대상)

## 2. 작업 환경 규칙 (반드시 지킬 것)
1. 사용자는 PC 없이 휴대폰에서만 설치한다. Android Studio, 로컬 빌드를 가정하지 않는다.
2. 클라우드 환경에서는 Android SDK/Gradle 다운로드가 막혀 로컬 컴파일이 불가능하다. 빌드는 항상 GitHub Actions로 한다.
3. 흐름: 코드 수정 → `git add -A && git commit` → `git push origin HEAD:main` → Actions 완료 대기 → 릴리스 `build-N`의 `equalizer.apk` 링크를 사용자에게 전달.
4. Actions 상태는 공개 API로 확인한다(gh 토큰은 무효): `https://api.github.com/repos/jmtailor/EQ-LAB/actions/runs?per_page=1`, 릴리스는 `.../releases/tags/build-N`. 대기는 Monitor로 폴링하고 sleep을 쓰지 않는다.
5. 컴파일 오류가 나면 코드를 다시 읽어 원인을 추정해 수정한다(로그는 인증이 필요해 직접 못 본다).
6. 커밋 메시지 끝에 지정된 Co-Authored-By 와 Claude-Session 줄을 붙인다(세션 안내문 기준).
7. 사용자 응답은 한국어, 짧게. 빌드 링크, 확인할 항목, 못 한 일을 분리해서 알린다. 확인하지 못한 것을 "해결됨"이라고 쓰지 않는다.
8. 사용자가 승인한 범위만 구현한다. 새 기능은 가능 여부와 영향(소리, 배터리, UI)을 먼저 알리고 승인 후 작업한다.

## 3. 코드 규칙 (일관성)
- 언어는 Java만 사용. Kotlin, XML 레이아웃, 외부 라이브러리를 쓰지 않는다. 화면은 코드로 구성한다.
- 패키지 `com.eqlab.equalizer`, minSdk 29, compile/targetSdk 35, Java 17.
- 색상과 치수는 `Theme`(상수, `dp()`, `box()`)만 쓴다. 하드코딩 색 금지. 테마: 어두운 배경 + 청록·민트.
- UI 텍스트는 한국어. 상태 변경은 `EqEngine`만 거친다(게인, 켜짐, 프리셋 저장은 SharedPreferences "eq").
- UI는 `EqEngine.Listener.onChanged()`로 갱신한다(UI 스레드에서). 엔진과 서비스는 같은 프로세스에서 정적 상태를 공유한다.
- 햅틱은 `Haptics`(tick: 1dB 단계, click: 0 통과·토글, heavy: 프리셋·초기화).
- Android 15 이상 edge-to-edge: 루트에 WindowInsets 리스너로 패딩 적용(이미 적용됨, 유지).
- 오류는 삼키지 말고 `EqEngine.log()`로 기록한다(설정 탭의 진단 기록에 표시).
- 터치 뷰는 슬롭과 `requestDisallowInterceptTouchEvent`를 쓴다(가로 스크롤 안의 세로 드래그).
- 서비스 타입: EqService = specialUse, CaptureService = mediaProjection. 매니페스트 수정 시 권한과 foregroundServiceType을 함께 확인한다.

## 4. 구조
- `EqEngine`: 기본 대역 11개(`FIXED_FREQ` 20Hz~16kHz, -12~+12 dB) + 사용자가 추가한 점(`extras` {주파수, 게인}, 합쳐서 최대 `MAX_BANDS`=24), on, presetId, 프리셋(플랫·베이스·보컬·클럽·어쿠스틱 + 사용자), DynamicsProcessing 관리, 진단 로그. 화면은 `EqEngine.model()`이 주는 `Model`(주파수 순으로 합친 복사본: freq/gain/extra, `at(hz)`는 로그 주파수 코사인 보간 곡선)만 읽는다. 대역 번호는 항상 이 합친 순서다.
- 효과 적용·저장(build-10): 상태를 바꾸면 `scheduleApply`가 작업 스레드(`eq-apply`)에 예약하고 한꺼번에 처리한다(바뀐 대역만 `setPreEqBandAllChannelsTo`, 점 추가·삭제·프리셋·켜짐은 전체). 점 개수가 바뀌면 DynamicsProcessing은 Config의 밴드 수를 못 바꾸므로 효과를 새로 만들어 교체한다(`Fx.bands` 비교). 저장은 `saveSoon`(마지막 변경 0.3초 뒤), 화면이 멈출 때 `flush`. 효과를 만지는 IPC는 `EqEngine` 클래스 잠금 밖에서 한다(UI 스레드가 잠금 때문에 막히지 않게).
- 저장 형식: SharedPreferences "eq"의 `gains`(기본 11개 CSV, 예전 10개짜리는 읽을 때 변환), `extras`("주파수:게인,..."), 사용자 프리셋 `custom`은 한 줄에 "이름\t게인11개" 또는 "이름\t게인11개|주파수:게인,...". 규칙에 안 맞는 점은 읽을 때 버린다(20~16000 안쪽, 이웃과 주파수 비 1.19 이상).
- `SessionReceiver`(매니페스트) + `EqService`(동적 수신기): OPEN/CLOSE_AUDIO_EFFECT_CONTROL_SESSION 방송을 받아 `EqEngine.onSessionOpen/Close`. 중복 방송은 `isDuplicate`로 거른다.
- 전역 모드(build-9): `EqService.onCreate`가 `EqEngine.ensureGlobal()`로 오디오 세션 0번에 DynamicsProcessing(priority 0, 시험용 앱 실험 B에서 검증한 값)을 건다. 성공하면 세션별 효과는 붙이지 않고(이미 붙은 것도 해제) 세션 방송은 목록 기록에만 쓴다. 실패하면 로그 "전역 효과 실패, 세션 방식 사용"을 남기고 기존 세션 방식으로 동작한다. 서비스 종료 시 `releaseGlobal()`. 모든 앱 소리(알림음 포함)에 EQ가 걸린다. 사용자가 "항상 전역으로 대체"를 선택함(261103). build-9 실기기 확인 완료(261103): 배너·진단 기록·멜론/유튜브 뮤직 적용·알림음 포함 전역 적용·EQ 끄기·서비스 중지 모두 정상.
- 연결 점검(진단, build-8): `EqEngine.KNOWN_APPS`(유튜브 뮤직·멜론·스포티파이·삼성 뮤직)별로 설치 여부와 "세션 열림" 방송 수신 횟수·마지막 시각을 설정 탭 "앱별 연결 점검"에 보여 준다(앱 프로세스가 살아 있는 동안만 유지). `EqService`는 시작·종료·알림 권한·포그라운드 시작 실패를 기록하고, `AudioPlaybackCallback`으로 재생 중인 미디어 소리 개수와 연결된 세션 수를 기록한다(재생 앱의 패키지는 공개 API로 알 수 없다). 진단 기록은 최대 24줄.
- 효과: `DynamicsProcessing(priority 100, session, Config(VARIANT_FAVOR_FREQUENCY_RESOLUTION, 2ch, preEq `BANDS`밴드))`, 대역 경계는 `FREQ` 배열.
- 스펙트럼: `CaptureService`(AudioPlaybackCapture + MediaProjection, FFT 2048/hop 1024, 32막대) → `SpectrumState` → `SpectrumView`. 캡처는 EQ 적용 전 소리이므로 화면은 원음 + EQ 게인으로 합성한다.
- UI: `MainActivity`(3탭: 이퀄라이저/프리셋/설정, 갱신 요청은 하나로 합치고 칩·버튼은 상태가 바뀔 때만 다시 만든다), `BandSliders`/`BandColumn`(알약형 슬라이더, 점이 늘면 열도 늘어남), `SpectrumView`(가로는 로그 주파수 20Hz~16kHz, 막대는 주파수별 색 그라데이션, 곡선 글로우, 점). 점 조작: 점을 끌면 게인, 추가한 점은 좌우로 주파수도 이동, 빈 곳을 길게 누르면 그 주파수에 점 추가(처음 게인은 그 자리의 곡선 값), 추가한 점을 길게 누르면 삭제(기본 대역은 안내만). 색은 `Theme.SPECTRUM`/`mix`/`spectrumAt`. 프리셋을 고르면 추가한 점은 위치를 유지하고 게인만 프리셋 곡선에 맞추며(사용자 프리셋은 저장된 점으로 교체), 초기화는 점도 지운다.

## 5. 검증된 사실 / 알려진 이슈
- 실기기(SM-S908N, Android 16, 유튜브 뮤직, 블루투스)에서 효과 청취, 스펙트럼, 10분 연속 재생, 화면 꺼짐, 트랙 변경 모두 정상. 유선 이어폰은 미검증(기기에 잭 없음).
- 이미 재생 중인 앱은 앱 설치 후 세션 방송을 못 보내는 경우가 있다 → 곡을 새로 재생하거나, 안 되면 앱 초기화 후 재시도. 설정 탭 진단 기록에 "세션 열림"이 찍히는지 확인한다.
- 다른 앱이 연 오디오 세션 번호를 앱이 직접 찾는 공개 API는 없다(`AudioPlaybackConfiguration`의 세션·uid·플레이어 상태는 시스템 API로 확인됨, AOSP 소스 기준). 그래서 방송을 받는 방식이 유일한 경로이고, 이미 재생 중이던 앱을 나중에 붙이는 방법은 없다.
- 멜론(com.iloen.melon): build-8 실기기(261103)에서 멜론을 재생해도 "앱별 연결 점검"에 방송 0회로 남았다(유튜브 뮤직은 같은 조건에서 방송 수신 후 정상 적용). 멜론 재생 중 상단 배너에는 이전에 연결된 유튜브 뮤직 세션이 그대로 보이고 멜론 소리에는 효과가 적용되지 않았다. 결론: 멜론은 세션 열림 방송을 보내지 않는 것으로 보이며 현재 방식으로는 연결할 수 없다. 시험용 앱(app 모듈) 실험 B(전역 세션 0번)에서 DynamicsProcessing와 Equalizer 모두 멜론 소리가 바뀌는 것을 사용자가 확인했다(261103). 그래서 본 앱에 전역 모드를 넣었고(build-9) 실기기에서 멜론도 정상 적용됨을 확인했다.
- 재설치 직후(재시작 전)에는 서비스가 떠 있고 재생도 감지되는데("재생 감지 미디어 1개 · 연결된 세션 없음") 방송이 오지 않았다. 즉 받는 쪽이 아니라 음악 앱이 방송을 다시 보내지 않는 상태로 보인다(어느 앱이 재생 중이었는지는 공개 API로 알 수 없어 확정은 아님).
- 시험용 앱(EQ Lab)과 동시에 쓰면 같은 세션 효과가 겹칠 수 있다. 종료하거나 삭제.
- 삼성 Dolby Atmos, Adapt Sound가 켜져 있으면 변화가 덜 들린다(설정 탭 안내에 있음).
- 앱을 삭제 후 재설치(빌드마다 서명이 달라 업데이트 설치 불가)한 직후, 곡을 새로 재생하고 앱 초기화까지 해도 "연결된 앱 없음"이 계속될 수 있다(build-7 설치 때 실제 발생). 폰을 재시작하면 정상 연결됐다. 원인은 재설치 전 상태가 남아 있었을 것으로 추정하며 확인하지는 못했다. 코드 결함으로 보지 말고 재시작부터 안내한다.
- 슬라이더를 올리고 내릴 때 약간 버퍼링처럼 끊기는 느낌이 있다(이전 빌드부터 있었음, 긴급 아님, 사용자 확인). 추정 원인(미확인): `EqEngine.setGain`이 터치 이동마다 모든 세션의 전 밴드를 다시 설정하고 저장과 UI 갱신을 동기로 처리한다. build-10에서 개선함(효과 적용·저장을 작업 스레드로 옮기고 화면 갱신을 줄임, 실기기 확인 전).

## 6. 확정 결정과 로드맵
- 세로 고정 v1. 가로 화면, 플로팅 패널, 곡 제목 표시, 자동 프리셋은 실기기 검증 이후 확장 버전에서.
- 승인된 다음 작업(순서대로):
  1. (구현됨, build-7, 실기기 확인 전) 20Hz 대역 추가(11밴드). 예전 31Hz 대역이 그 아래까지 포함했다는 것은 문서상 추정이며 미확인. 저장된 10개 값은 `EqEngine.parseGains`가 읽을 때 31Hz 값을 20Hz 자리로 복제해 11개로 변환한다(기존 소리 유지). 기본 프리셋도 20Hz 값 = 31Hz 값. 스펙트럼 막대는 여전히 31Hz~16kHz만 분석하고 31Hz 위치부터 그린다(20Hz 지점은 곡선과 점만).
  2. 게인 미세 조절: 0.5dB 이하 단위, 슬라이더 길게 누르면 미세 모드. 저장 형식은 정수에서 소수로 변경 필요(SharedPreferences 호환 처리).
  3. (구현됨, build-10, 실기기 확인 전) 주파수 점 추가: 위 UI 설명 참고. DynamicsProcessing 대역은 경계(cutoff)=점의 주파수와 게인뿐이라 화면 곡선(코사인 보간)과 실제 소리(대역별 계단 + 기기 내부 보간)는 근사치로만 같다. 게인 미세 조절(2번)은 사용자가 이번엔 고르지 않아 보류.
- 제약: DynamicsProcessing 대역은 경계 주파수와 게인만 있고 Q(폭)가 없다. 대역이 늘면 CPU 부담이 늘므로 배터리와 지연을 실기기에서 확인한다.
- 새 기능마다 빌드를 새로 만들고, 사용자에게 확인 항목을 번호로 준다.

## 7. 빌드·배포 요약
- 워크플로: `.github/workflows/build.yml` (setup-java 17, gradle 8.9, AGP 8.7.3). push 시 `:app:assembleDebug :equalizer:assembleDebug` 실행 후 릴리스 `build-<run_number>` 에 `equalizer.apk`, `eq-lab-test.apk` 게시.
- APK 주소 형식: `https://github.com/jmtailor/EQ-LAB/releases/download/build-N/equalizer.apk`
- 최신 빌드: build-10 (슬라이더 개선 + 주파수 점 추가 + 스펙트럼 색, 실기기 확인 전). 마지막으로 실기기 확인된 빌드: build-9 (11밴드 + 연결 진단 + 전역 모드). 이전: build-8 진단, build-7 11밴드, build-6 10밴드.

## 8. 윈도우 버전 (`windows/EqLabWin`, 시작: 261105)
- 목적: 안드로이드 EQ-LAB과 같은 디자인의 윈도우용 11밴드 EQ + 스펙트럼. 사용자가 Equalizer APO를 이미 설치해 쓰고 있어 "방식 1: Equalizer APO 연동"으로 확정(261105). 소리 처리는 APO가 하고, 앱은 화면과 설정 파일(`eqlab.txt`) 작성만 한다. 가상 케이블 방식과 자체 APO 드라이버는 하지 않는다.
- 기술: C# 12 / .NET 8 WPF, NAudio 2.2.1(WASAPI loopback). 안드로이드의 "Java만, 외부 라이브러리 없음" 규칙(3장)은 `app`, `equalizer` 모듈에만 적용된다. 윈도우도 화면은 코드로 구성하고(XAML 없음), 색은 `Theme`만 쓰고, 상태 변경은 `EqEngine`만 거치고, 오류는 `EqEngine.Log()`로 남기는 규칙을 같게 지킨다.
- 연동: `config.txt` 맨 아래에 `Include: eqlab.txt` 한 줄만 추가한다(사용자 동의 후, 원본은 `config.txt.eqlab.bak`). 기존 줄(Peace 등)은 건드리지 않는다. 필터는 `Filter n: ON PK Fc <Hz> Gain <dB> Q <Q>`, Q는 이웃 대역 간격(옥타브)에서 계산(옥타브 간격이면 1.41), 자동 프리앰프는 가장 큰 부스트만큼 `Preamp`를 내린다. 게인 단위는 0.5dB.
- 스펙트럼 장치(win-2): loopback은 출력 장치 하나에서만 소리를 받는다. win-1에서 사용자 PC는 캡처 중인데 막대가 비었다(원인 미확정, 재생 장치가 기본 장치와 다를 가능성). 그래서 `SpectrumCapture`가 1.5초마다 출력 장치별 재생 피크(`AudioMeterInformation`)를 보고 자동 모드에서 소리가 나는 장치로 옮겨 가며, 칩 "스펙트럼 장치"로 직접 고를 수 있다. 진단 기록에 장치별 피크, 패킷 수신 횟수, 마지막 패킷 최대 샘플이 나온다. 소리가 없을 때는 막대를 그리지 않는다(곡선 합성 모드에서 곡선이 막대처럼 보이던 문제).
- 알려진 불확실성: WASAPI loopback이 Equalizer APO 적용 전 소리인지 후 소리인지 확인하지 못했다. 그래서 스펙트럼 모드를 "캡처 그대로 / EQ 곡선 더하기"로 토글할 수 있게 했고, 사용자가 실기기에서 어느 쪽이 맞는지 확인해야 한다. APO가 설치된 장치에 체크되어 있지 않으면 파일을 써도 소리가 바뀌지 않는다(Configurator 확인).
- 빌드: 이 환경엔 .NET이 없어 로컬 컴파일 불가. `windows/**` 푸시 → `.github/workflows/windows.yml`(windows-latest, self-contained 단일 exe) → 릴리스 `win-N`의 `EqLabWin.exe`. 컴파일 실패 시 로그가 릴리스 `winlog-N`의 `build.log`로 올라오므로 `https://api.github.com/repos/jmtailor/EQ-LAB/releases/tags/winlog-N`로 확인한다(안드로이드와 달리 로그를 볼 수 있다). 윈도우·문서만 바뀐 푸시는 안드로이드 빌드(`build-N`)를 만들지 않는다(`paths-ignore`).
- 대역 추가와 Q(win-3, 사용자 요청 261105): 대역 목록은 기본 11(주파수 고정, 삭제 불가) + 추가 최대 13(합 24, 이웃과 주파수 비 1.19 이상). 대역마다 Q를 가진다(기본 대역은 이웃 간격에서 계산한 기본 Q, 추가 대역은 2.0, 범위 0.2~20). 조작: 점 끌기=게인(추가 대역은 주파수도), 빈 곳 더블클릭=추가(게인 0), 우클릭=추가 대역 삭제, 휠=게인 0.5dB(슬라이더 열 전체와 점 위), Ctrl+휠=Q ×1.12/÷1.12(위로 돌리면 Q 증가=폭 좁아짐). 화면 곡선과 자동 프리앰프는 안드로이드의 코사인 보간이 아니라 모든 필터를 겹친 실제 응답(RBJ 피킹 필터 48kHz 가정, `EqEngine.ResponseDb`)을 쓴다. 프리셋: 기본 프리셋은 추가 대역의 위치와 Q를 두고 게인만 0으로, 사용자 프리셋은 게인·Q·추가 대역을 함께 저장. 초기화는 추가 대역도 지운다. 저장 형식은 settings.json에 `Qs`, `Extras`가 추가됐고 예전 파일은 기본 Q로 읽는다.
- 새 기능마다 빌드를 새로 만들고, 확인 항목을 번호로 준다.
