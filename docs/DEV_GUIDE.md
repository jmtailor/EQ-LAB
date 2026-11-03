# EQ-LAB 개발 가이드 (작업 재개용 단일 기준 문서)

새 세션이나 다른 PC에서 작업을 이어갈 때 이 문서만 읽고 같은 방식으로 개발한다. 최종 갱신: 261103 / 기준 커밋은 `git log` 확인 (최신 빌드 build-8, 진단 강화).

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
- `EqEngine`: 게인 11개(20Hz~16kHz, -12~+12 dB, 개수는 `BANDS`), on, presetId, 프리셋(플랫·베이스·보컬·클럽·어쿠스틱 + 사용자), 세션별 DynamicsProcessing 관리, 진단 로그.
- `SessionReceiver`(매니페스트) + `EqService`(동적 수신기): OPEN/CLOSE_AUDIO_EFFECT_CONTROL_SESSION 방송을 받아 `EqEngine.onSessionOpen/Close`. 중복 방송은 `isDuplicate`로 거른다.
- 전역 모드(build-9): `EqService.onCreate`가 `EqEngine.ensureGlobal()`로 오디오 세션 0번에 DynamicsProcessing(priority 0, 시험용 앱 실험 B에서 검증한 값)을 건다. 성공하면 세션별 효과는 붙이지 않고(이미 붙은 것도 해제) 세션 방송은 목록 기록에만 쓴다. 실패하면 로그 "전역 효과 실패, 세션 방식 사용"을 남기고 기존 세션 방식으로 동작한다. 서비스 종료 시 `releaseGlobal()`. 모든 앱 소리(알림음 포함)에 EQ가 걸린다. 사용자가 "항상 전역으로 대체"를 선택함(261103). build-9 실기기 확인 완료(261103): 배너·진단 기록·멜론/유튜브 뮤직 적용·알림음 포함 전역 적용·EQ 끄기·서비스 중지 모두 정상.
- 연결 점검(진단, build-8): `EqEngine.KNOWN_APPS`(유튜브 뮤직·멜론·스포티파이·삼성 뮤직)별로 설치 여부와 "세션 열림" 방송 수신 횟수·마지막 시각을 설정 탭 "앱별 연결 점검"에 보여 준다(앱 프로세스가 살아 있는 동안만 유지). `EqService`는 시작·종료·알림 권한·포그라운드 시작 실패를 기록하고, `AudioPlaybackCallback`으로 재생 중인 미디어 소리 개수와 연결된 세션 수를 기록한다(재생 앱의 패키지는 공개 API로 알 수 없다). 진단 기록은 최대 24줄.
- 효과: `DynamicsProcessing(priority 100, session, Config(VARIANT_FAVOR_FREQUENCY_RESOLUTION, 2ch, preEq `BANDS`밴드))`, 대역 경계는 `FREQ` 배열.
- 스펙트럼: `CaptureService`(AudioPlaybackCapture + MediaProjection, FFT 2048/hop 1024, 32막대) → `SpectrumState` → `SpectrumView`. 캡처는 EQ 적용 전 소리이므로 화면은 원음 + EQ 게인으로 합성한다.
- UI: `MainActivity`(3탭: 이퀄라이저/프리셋/설정), `BandSliders`/`BandColumn`(알약형 슬라이더), `SpectrumView`(막대, 피크 홀드, 곡선, 드래그 점).

## 5. 검증된 사실 / 알려진 이슈
- 실기기(SM-S908N, Android 16, 유튜브 뮤직, 블루투스)에서 효과 청취, 스펙트럼, 10분 연속 재생, 화면 꺼짐, 트랙 변경 모두 정상. 유선 이어폰은 미검증(기기에 잭 없음).
- 이미 재생 중인 앱은 앱 설치 후 세션 방송을 못 보내는 경우가 있다 → 곡을 새로 재생하거나, 안 되면 앱 초기화 후 재시도. 설정 탭 진단 기록에 "세션 열림"이 찍히는지 확인한다.
- 다른 앱이 연 오디오 세션 번호를 앱이 직접 찾는 공개 API는 없다(`AudioPlaybackConfiguration`의 세션·uid·플레이어 상태는 시스템 API로 확인됨, AOSP 소스 기준). 그래서 방송을 받는 방식이 유일한 경로이고, 이미 재생 중이던 앱을 나중에 붙이는 방법은 없다.
- 멜론(com.iloen.melon): build-8 실기기(261103)에서 멜론을 재생해도 "앱별 연결 점검"에 방송 0회로 남았다(유튜브 뮤직은 같은 조건에서 방송 수신 후 정상 적용). 멜론 재생 중 상단 배너에는 이전에 연결된 유튜브 뮤직 세션이 그대로 보이고 멜론 소리에는 효과가 적용되지 않았다. 결론: 멜론은 세션 열림 방송을 보내지 않는 것으로 보이며 현재 방식으로는 연결할 수 없다. 시험용 앱(app 모듈) 실험 B(전역 세션 0번)에서 DynamicsProcessing와 Equalizer 모두 멜론 소리가 바뀌는 것을 사용자가 확인했다(261103). 그래서 본 앱에 전역 모드를 넣었고(build-9) 실기기에서 멜론도 정상 적용됨을 확인했다.
- 재설치 직후(재시작 전)에는 서비스가 떠 있고 재생도 감지되는데("재생 감지 미디어 1개 · 연결된 세션 없음") 방송이 오지 않았다. 즉 받는 쪽이 아니라 음악 앱이 방송을 다시 보내지 않는 상태로 보인다(어느 앱이 재생 중이었는지는 공개 API로 알 수 없어 확정은 아님).
- 시험용 앱(EQ Lab)과 동시에 쓰면 같은 세션 효과가 겹칠 수 있다. 종료하거나 삭제.
- 삼성 Dolby Atmos, Adapt Sound가 켜져 있으면 변화가 덜 들린다(설정 탭 안내에 있음).
- 앱을 삭제 후 재설치(빌드마다 서명이 달라 업데이트 설치 불가)한 직후, 곡을 새로 재생하고 앱 초기화까지 해도 "연결된 앱 없음"이 계속될 수 있다(build-7 설치 때 실제 발생). 폰을 재시작하면 정상 연결됐다. 원인은 재설치 전 상태가 남아 있었을 것으로 추정하며 확인하지는 못했다. 코드 결함으로 보지 말고 재시작부터 안내한다.
- 슬라이더를 올리고 내릴 때 약간 버퍼링처럼 끊기는 느낌이 있다(이전 빌드부터 있었음, 긴급 아님, 사용자 확인). 추정 원인(미확인): `EqEngine.setGain`이 터치 이동마다 모든 세션의 전 밴드를 다시 설정하고 저장과 UI 갱신을 동기로 처리한다. 개선은 사용자 지시가 있을 때만 한다.

## 6. 확정 결정과 로드맵
- 세로 고정 v1. 가로 화면, 플로팅 패널, 곡 제목 표시, 자동 프리셋은 실기기 검증 이후 확장 버전에서.
- 승인된 다음 작업(순서대로):
  1. (구현됨, build-7, 실기기 확인 전) 20Hz 대역 추가(11밴드). 예전 31Hz 대역이 그 아래까지 포함했다는 것은 문서상 추정이며 미확인. 저장된 10개 값은 `EqEngine.parseGains`가 읽을 때 31Hz 값을 20Hz 자리로 복제해 11개로 변환한다(기존 소리 유지). 기본 프리셋도 20Hz 값 = 31Hz 값. 스펙트럼 막대는 여전히 31Hz~16kHz만 분석하고 31Hz 위치부터 그린다(20Hz 지점은 곡선과 점만).
  2. 게인 미세 조절: 0.5dB 이하 단위, 슬라이더 길게 누르면 미세 모드. 저장 형식은 정수에서 소수로 변경 필요(SharedPreferences 호환 처리).
  3. 주파수 점 추가: 스펙트럼 곡선 위 원하는 주파수에 점 추가, 추가 점 앞뒤는 근사치로 연결(대역 경계로 변환), 추가 대역은 총 최대 24개로 앱에서 제한.
- 제약: DynamicsProcessing 대역은 경계 주파수와 게인만 있고 Q(폭)가 없다. 대역이 늘면 CPU 부담이 늘므로 배터리와 지연을 실기기에서 확인한다.
- 새 기능마다 빌드를 새로 만들고, 사용자에게 확인 항목을 번호로 준다.

## 7. 빌드·배포 요약
- 워크플로: `.github/workflows/build.yml` (setup-java 17, gradle 8.9, AGP 8.7.3). push 시 `:app:assembleDebug :equalizer:assembleDebug` 실행 후 릴리스 `build-<run_number>` 에 `equalizer.apk`, `eq-lab-test.apk` 게시.
- APK 주소 형식: `https://github.com/jmtailor/EQ-LAB/releases/download/build-N/equalizer.apk`
- 최신 정상 빌드: build-9 (11밴드 + 연결 진단 + 전역 모드, 실기기 확인됨). 이전: build-8 진단, build-7 11밴드, build-6 10밴드.
