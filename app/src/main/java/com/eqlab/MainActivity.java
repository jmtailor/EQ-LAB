package com.eqlab;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.media.audiofx.AudioEffect;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class MainActivity extends Activity {
    private static final int REQ_PERM = 1;
    private static final int REQ_PROJECTION = 100;
    private static final String[] TABS = {"기기 정보", "세션 감시", "효과 적용", "스펙트럼", "결과 기록"};

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final EffectEngine engine = new EffectEngine();
    private final TonePlayer tone = new TonePlayer();
    private final float[] gains = new float[10];
    private final SessionReceiver dynamicReceiver = new SessionReceiver();

    private ScrollView[] pages = new ScrollView[TABS.length];
    private int currentTab = 0;

    private TextView infoText;
    private TextView sessionsText;
    private TextView logText;
    private TextView effectStatus;
    private Spinner sessionSpinner;
    private EditText sessionInput;
    private RadioButton rbEq;
    private RadioButton rbDp;
    private final List<Integer> spinnerSessionIds = new ArrayList<>();
    private final TextView[] gainLabels = new TextView[10];
    private final SeekBar[] seekBars = new SeekBar[10];
    private TextView captureStatus;
    private SpectrumView spectrum;
    private TextView resultsText;
    private Spinner spApp;
    private Spinner spExp;
    private Spinner spOut;
    private Spinner spResult;
    private EditText memo;

    private String lastPlaybackSummary = "";

    private final AudioManager.AudioPlaybackCallback playbackCallback = new AudioManager.AudioPlaybackCallback() {
        @Override
        public void onPlaybackConfigChanged(List<AudioPlaybackConfiguration> configs) {
            StringBuilder sb = new StringBuilder();
            for (AudioPlaybackConfiguration c : configs) {
                AudioAttributes a = c.getAudioAttributes();
                sb.append("용도=").append(a.getUsage()).append("/콘텐츠=").append(a.getContentType());
                Object uid = callHidden(c, "getClientUid");
                Object sid = callHidden(c, "getSessionId");
                sb.append(" uid=").append(uid).append(" 세션=").append(sid);
                if (uid instanceof Integer) {
                    String[] pk = getPackageManager().getPackagesForUid((Integer) uid);
                    if (pk != null && pk.length > 0) sb.append(" 앱=").append(pk[0]);
                }
                sb.append(" | ");
            }
            String summary = sb.toString();
            if (!summary.equals(lastPlaybackSummary)) {
                lastPlaybackSummary = summary;
                Store.log(MainActivity.this, "[재생 감지] " + (summary.isEmpty() ? "재생 없음" : summary));
            }
        }
    };

    private static Object callHidden(Object target, String name) {
        try {
            Method m = target.getClass().getMethod(name);
            return m.invoke(target);
        } catch (Throwable t) {
            return "?";
        }
    }

    // ------------------------------------------------------------------ 생명주기

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        showTab(0);
        requestRuntimePermissions();
    }

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter f = new IntentFilter();
        f.addAction(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION);
        f.addAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(dynamicReceiver, f, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(dynamicReceiver, f);
        }
        AudioManager am = getSystemService(AudioManager.class);
        am.registerAudioPlaybackCallback(playbackCallback, handler);
        refreshInfo();
        handler.post(tick);
    }

    @Override
    protected void onStop() {
        handler.removeCallbacks(tick);
        try {
            unregisterReceiver(dynamicReceiver);
        } catch (Throwable ignored) {
        }
        getSystemService(AudioManager.class).unregisterAudioPlaybackCallback(playbackCallback);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        engine.release();
        tone.stop();
        super.onDestroy();
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (currentTab == 1) {
                sessionsText.setText(sessionsSummary());
                logText.setText(Store.readLog(MainActivity.this, 60));
            } else if (currentTab == 3) {
                captureStatus.setText(captureSummary());
                spectrum.invalidate();
            }
            handler.postDelayed(this, 300);
        }
    };

    // ------------------------------------------------------------------ UI 조립

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private TextView text(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(14);
        t.setPadding(0, dp(4), 0, dp(4));
        return t;
    }

    private TextView heading(String s) {
        TextView t = text(s);
        t.setTextSize(17);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        t.setPadding(0, dp(14), 0, dp(4));
        return t;
    }

    private Button button(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }

    private LinearLayout row(View... views) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        for (View v : views) {
            r.addView(v, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        return r;
    }

    private Spinner spinner(String... items) {
        Spinner s = new Spinner(this);
        s.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, items));
        return s;
    }

    private LinearLayout newPage(int index) {
        ScrollView sv = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(16), dp(8), dp(16), dp(32));
        sv.addView(col);
        pages[index] = sv;
        return col;
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFFFFFFFF);
        // Android 15 이상은 앱이 시스템 막대 밑까지 그려지므로 막대 높이만큼 여백을 직접 준다.
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int l;
            int t;
            int r;
            int b;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets in = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                l = in.left;
                t = in.top;
                r = in.right;
                b = in.bottom;
            } else {
                l = insets.getSystemWindowInsetLeft();
                t = insets.getSystemWindowInsetTop();
                r = insets.getSystemWindowInsetRight();
                b = insets.getSystemWindowInsetBottom();
            }
            v.setPadding(l, t, r, b);
            return insets;
        });

        HorizontalScrollView tabScroll = new HorizontalScrollView(this);
        tabScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout tabBar = new LinearLayout(this);
        tabBar.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < TABS.length; i++) {
            final int idx = i;
            Button b = button(TABS[i], v -> showTab(idx));
            tabBar.addView(b);
        }
        tabScroll.addView(tabBar);
        root.addView(tabScroll);

        FrameLayout content = new FrameLayout(this);
        buildInfoPage(newPage(0));
        buildSessionPage(newPage(1));
        buildEffectPage(newPage(2));
        buildCapturePage(newPage(3));
        buildResultPage(newPage(4));
        for (ScrollView p : pages) content.addView(p);
        root.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
    }

    private void showTab(int i) {
        currentTab = i;
        for (int k = 0; k < pages.length; k++) {
            pages[k].setVisibility(k == i ? View.VISIBLE : View.GONE);
        }
        if (i == 0) refreshInfo();
        if (i == 2) refreshSessionSpinner();
        if (i == 4) resultsText.setText(Store.results(this));
    }

    // ------------------------------------------------------------------ 0. 기기 정보

    private void buildInfoPage(LinearLayout p) {
        p.addView(heading("기기 정보"));
        infoText = text("");
        infoText.setTextIsSelectable(true);
        p.addView(infoText);
        p.addView(row(
                button("새로고침", v -> refreshInfo()),
                button("복사", v -> copy(infoText.getText().toString()))));
    }

    private void refreshInfo() {
        if (infoText == null) return;
        StringBuilder sb = new StringBuilder();
        sb.append("모델: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n');
        sb.append("Android: ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append("빌드: ").append(Build.DISPLAY).append('\n');
        sb.append("One UI 코드: ").append(systemProperty("ro.build.version.oneui")).append(" (80000 = 8.0)\n");
        sb.append("삼성 SoundAssistant: ").append(installed("com.samsung.android.soundassistant") ? "설치됨 (시험 중에는 끄세요)" : "없음").append('\n');
        sb.append("유튜브 뮤직: ").append(installed("com.google.android.apps.youtube.music") ? "설치됨" : "없음").append("\n\n");

        sb.append("출력 장치:\n");
        AudioManager am = getSystemService(AudioManager.class);
        for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            sb.append(" - ").append(deviceType(d.getType())).append(" ").append(d.getProductName()).append('\n');
        }

        sb.append("\n기기에 등록된 오디오 효과:\n");
        AudioEffect.Descriptor[] list = AudioEffect.queryEffects();
        if (list != null) {
            for (AudioEffect.Descriptor d : list) {
                sb.append(" - ").append(d.name).append(" / ").append(d.implementor).append('\n');
            }
        }
        infoText.setText(sb.toString());
    }

    private boolean installed(String pkg) {
        try {
            getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private String systemProperty(String key) {
        try {
            Class<?> c = Class.forName("android.os.SystemProperties");
            Method m = c.getMethod("get", String.class, String.class);
            String v = (String) m.invoke(null, key, "");
            return v == null || v.isEmpty() ? "(읽을 수 없음)" : v;
        } catch (Throwable t) {
            return "(읽을 수 없음)";
        }
    }

    private String deviceType(int t) {
        switch (t) {
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER: return "본체 스피커";
            case AudioDeviceInfo.TYPE_BUILTIN_EARPIECE: return "수화부";
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES: return "유선 헤드폰";
            case AudioDeviceInfo.TYPE_WIRED_HEADSET: return "유선 헤드셋";
            case AudioDeviceInfo.TYPE_USB_HEADSET: return "USB 헤드셋";
            case AudioDeviceInfo.TYPE_USB_DEVICE: return "USB 장치";
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP: return "블루투스 A2DP";
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO: return "블루투스 SCO";
            default: return "장치 유형 " + t;
        }
    }

    // ------------------------------------------------------------------ 1. 세션 감시

    private void buildSessionPage(LinearLayout p) {
        p.addView(heading("실험 A: 세션 감시"));
        p.addView(text("이 화면을 둔 채로 유튜브 뮤직에서 곡을 재생하세요. 앱이 오디오 세션을 공개하면 아래에 나타납니다. "
                + "'재생 감지' 줄은 시스템이 알려주는 재생 정보이며, 세션 번호가 '?'이면 읽을 수 없는 것입니다."));
        p.addView(heading("현재 열린 세션"));
        sessionsText = text("");
        p.addView(sessionsText);
        p.addView(heading("이벤트 로그 (최신순)"));
        logText = text("");
        logText.setTextSize(12);
        logText.setTextIsSelectable(true);
        p.addView(logText);
        p.addView(row(
                button("로그 지우기", v -> {
                    Store.clearLog(this);
                    logText.setText(Store.readLog(this, 60));
                }),
                button("로그 복사", v -> copy(Store.readLog(this, 200)))));
    }

    private String sessionsSummary() {
        Map<Integer, String> m = Store.sessions(this);
        if (m.isEmpty()) return "(감지된 세션 없음)";
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Integer, String> e : m.entrySet()) {
            sb.append("세션 ").append(e.getKey()).append("  ").append(e.getValue()).append('\n');
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ 2. 효과 적용

    private void buildEffectPage(LinearLayout p) {
        p.addView(heading("실험 A·B: 효과 적용"));
        p.addView(text("1) 대상 세션을 고르고 2) 방식을 고른 뒤 3) '적용'을 누르고 4) 슬라이더를 움직여 귀로 확인하세요. "
                + "먼저 '내장 테스트음'으로 앱이 정상 동작하는지 확인하는 것을 권합니다."));

        p.addView(heading("대상 세션"));
        sessionSpinner = new Spinner(this);
        p.addView(sessionSpinner);
        sessionInput = new EditText(this);
        sessionInput.setHint("세션 ID 직접 입력 (비워두면 위 선택 사용)");
        sessionInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        p.addView(sessionInput);
        p.addView(button("세션 목록 새로고침", v -> refreshSessionSpinner()));

        p.addView(heading("방식"));
        RadioGroup rg = new RadioGroup(this);
        rbEq = new RadioButton(this);
        rbEq.setText("Equalizer (기기 기본 밴드)");
        rbDp = new RadioButton(this);
        rbDp.setText("DynamicsProcessing (10밴드)");
        rg.addView(rbEq);
        rg.addView(rbDp);
        rbDp.setChecked(true);
        p.addView(rg);

        p.addView(row(
                button("적용", v -> applyEffect()),
                button("해제", v -> {
                    engine.release();
                    setStatus("해제됨");
                })));
        effectStatus = text("상태: 아직 적용 안 함");
        p.addView(effectStatus);

        p.addView(heading("내장 테스트음 (핑크 노이즈)"));
        p.addView(row(
                button("재생", v -> {
                    int s = tone.start();
                    setStatus("테스트음 재생 중, 세션 ID=" + s);
                }),
                button("정지", v -> {
                    tone.stop();
                    setStatus("테스트음 정지");
                })));

        p.addView(heading("대역별 게인 (dB)"));
        for (int i = 0; i < 10; i++) {
            final int idx = i;
            TextView label = text("");
            gainLabels[i] = label;
            p.addView(label);
            SeekBar sb = new SeekBar(this);
            sb.setMax(24);
            sb.setProgress(12);
            seekBars[i] = sb;
            sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                    gains[idx] = progress - 12;
                    updateGainLabel(idx);
                    if (fromUser && engine.isAttached()) {
                        try {
                            engine.setGains(gains);
                        } catch (Throwable t) {
                            setStatus("게인 변경 실패: " + t);
                        }
                    }
                }

                @Override
                public void onStartTrackingTouch(SeekBar s) {
                }

                @Override
                public void onStopTrackingTouch(SeekBar s) {
                }
            });
            p.addView(sb);
            updateGainLabel(i);
        }
        p.addView(row(
                button("저음 +12", v -> preset(new int[]{12, 12, 12, 6, 0, 0, 0, 0, 0, 0})),
                button("고음 +12", v -> preset(new int[]{0, 0, 0, 0, 0, 0, 6, 12, 12, 12}))));
        p.addView(row(
                button("저음 -12", v -> preset(new int[]{-12, -12, -12, -6, 0, 0, 0, 0, 0, 0})),
                button("전부 0", v -> preset(new int[]{0, 0, 0, 0, 0, 0, 0, 0, 0, 0}))));
    }

    private void updateGainLabel(int i) {
        int g = (int) gains[i];
        gainLabels[i].setText(EffectEngine.LABEL[i] + " Hz   " + (g > 0 ? "+" + g : String.valueOf(g)) + " dB");
    }

    private void preset(int[] values) {
        for (int i = 0; i < 10; i++) {
            gains[i] = values[i];
            seekBars[i].setProgress(values[i] + 12);
            updateGainLabel(i);
        }
        if (engine.isAttached()) {
            try {
                engine.setGains(gains);
            } catch (Throwable t) {
                setStatus("게인 변경 실패: " + t);
            }
        }
    }

    private void refreshSessionSpinner() {
        List<String> items = new ArrayList<>();
        spinnerSessionIds.clear();
        items.add("내장 테스트음");
        items.add("전역 세션 (0번)");
        Map<Integer, String> m = Store.sessions(this);
        for (Map.Entry<Integer, String> e : m.entrySet()) {
            items.add("감지된 세션 " + e.getKey() + " (" + e.getValue() + ")");
            spinnerSessionIds.add(e.getKey());
        }
        sessionSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, items));
    }

    private void setStatus(String s) {
        effectStatus.setText("상태: " + s);
        Store.log(this, "[효과] " + s);
    }

    private void applyEffect() {
        int session;
        String manual = sessionInput.getText().toString().trim();
        int pos = sessionSpinner.getSelectedItemPosition();
        try {
            if (!manual.isEmpty()) {
                session = Integer.parseInt(manual);
            } else if (pos <= 0) {
                session = tone.start();
            } else if (pos == 1) {
                session = 0;
            } else {
                session = spinnerSessionIds.get(pos - 2);
            }
        } catch (Throwable t) {
            setStatus("세션 선택 오류: " + t);
            return;
        }
        boolean useDp = rbDp.isChecked();
        try {
            String r = engine.attach(session, useDp, gains);
            setStatus("성공 - " + r + "\n" + engine.describe() + "\n슬라이더를 움직여 소리가 바뀌는지 들어보세요.");
        } catch (Throwable t) {
            setStatus("실패 - 세션 " + session + ", " + (useDp ? "DynamicsProcessing" : "Equalizer") + ": " + t);
        }
    }

    // ------------------------------------------------------------------ 3. 스펙트럼

    private void buildCapturePage(LinearLayout p) {
        p.addView(heading("실험 C: 재생음 캡처 + 스펙트럼"));
        p.addView(text("시작을 누르면 시스템 화면 녹화 허용 창이 뜹니다. 허용한 뒤 유튜브 뮤직을 재생하세요. "
                + "막대가 움직이면 캡처 성공, '무음 지속'이 계속 올라가면 앱이 캡처를 막은 것일 수 있습니다. "
                + "소리를 캡처해도 원래 소리는 꺼지지 않습니다."));
        p.addView(row(
                button("캡처 시작", v -> startCapture()),
                button("캡처 정지", v -> {
                    Intent i = new Intent(this, CaptureService.class);
                    i.setAction("STOP");
                    startService(i);
                })));
        spectrum = new SpectrumView(this);
        p.addView(spectrum, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(220)));
        captureStatus = text("");
        p.addView(captureStatus);
        p.addView(button("내장 테스트음 재생 (캡처 확인용)", v -> {
            int s = tone.start();
            Toast.makeText(this, "테스트음 재생 중 (세션 " + s + ")", Toast.LENGTH_SHORT).show();
        }));
        p.addView(button("테스트음 정지", v -> tone.stop()));
    }

    private String captureSummary() {
        return "상태: " + CaptureState.status
                + "\n갱신 횟수: 초당 " + CaptureState.updatesPerSec + "회 (통과 기준 20회 이상)"
                + "\n입력 레벨: " + String.format(java.util.Locale.US, "%.1f", CaptureState.rmsDb) + " dB"
                + "\n무음 지속: " + String.format(java.util.Locale.US, "%.1f", CaptureState.silentSeconds) + " 초"
                + (CaptureState.error.isEmpty() ? "" : "\n오류: " + CaptureState.error);
    }

    private void startCapture() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestRuntimePermissions();
            Toast.makeText(this, "마이크(녹음) 권한을 허용한 뒤 다시 누르세요", Toast.LENGTH_LONG).show();
            return;
        }
        MediaProjectionManager mgr = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        startActivityForResult(mgr.createScreenCaptureIntent(), REQ_PROJECTION);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PROJECTION) return;
        if (resultCode != RESULT_OK || data == null) {
            CaptureState.status = "사용자가 캡처를 허용하지 않음";
            Store.log(this, "[캡처] 허용 거부됨");
            return;
        }
        Intent i = new Intent(this, CaptureService.class);
        i.putExtra("rc", resultCode);
        i.putExtra("data", data);
        startForegroundService(i);
    }

    // ------------------------------------------------------------------ 4. 결과 기록

    private void buildResultPage(LinearLayout p) {
        p.addView(heading("결과 기록"));
        spApp = spinner("유튜브 뮤직 (스트리밍)", "유튜브 뮤직 (다운로드)", "삼성 기본 플레이어", "내장 테스트음", "기타");
        spExp = spinner("A 세션 감시", "A 세션에 효과 적용", "B 전역(0번) 효과", "C 캡처·스펙트럼", "D 권한 확장");
        spOut = spinner("본체 스피커", "유선 이어폰", "블루투스", "기타");
        spResult = spinner("성공 (소리가 바로 바뀜)", "부분 (약하거나 늦음)", "실패 (안 바뀜)", "오류·튕김");
        p.addView(text("앱"));
        p.addView(spApp);
        p.addView(text("실험"));
        p.addView(spExp);
        p.addView(text("출력 장치"));
        p.addView(spOut);
        p.addView(text("결과"));
        p.addView(spResult);
        memo = new EditText(this);
        memo.setHint("메모 (예: 저음 +12에서 확실히 변함)");
        p.addView(memo);
        p.addView(button("이 결과 저장", v -> {
            String line = spApp.getSelectedItem() + " | " + spExp.getSelectedItem() + " | "
                    + spOut.getSelectedItem() + " | " + spResult.getSelectedItem() + " | " + memo.getText();
            Store.addResult(this, line);
            memo.setText("");
            resultsText.setText(Store.results(this));
        }));
        p.addView(heading("저장된 결과"));
        resultsText = text("");
        resultsText.setTextSize(13);
        resultsText.setTextIsSelectable(true);
        p.addView(resultsText);
        p.addView(row(
                button("전체 복사", v -> copy(deviceHeader() + Store.results(this))),
                button("결과 지우기", v -> {
                    Store.clearResults(this);
                    resultsText.setText(Store.results(this));
                })));
    }

    private String deviceHeader() {
        return "[" + Build.MANUFACTURER + " " + Build.MODEL + " / Android " + Build.VERSION.RELEASE + "]\n";
    }

    // ------------------------------------------------------------------ 공통

    private void copy(String s) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("EQ Lab", s));
        Toast.makeText(this, "복사했습니다", Toast.LENGTH_SHORT).show();
    }

    private void requestRuntimePermissions() {
        List<String> need = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.RECORD_AUDIO);
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!need.isEmpty()) requestPermissions(need.toArray(new String[0]), REQ_PERM);
    }
}
