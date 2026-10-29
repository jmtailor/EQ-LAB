package com.eqlab.equalizer;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity implements EqEngine.Listener {
    private static final int REQ_PERM = 1;
    private static final int REQ_PROJECTION = 100;
    private static final String[] TAB_NAMES = {"이퀄라이저", "프리셋", "설정"};

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ScrollView[] pages = new ScrollView[3];
    private final TextView[] navs = new TextView[3];
    private int tab = 0;
    private int activeBand = 0;
    private boolean updating = false;
    private boolean showOrig = true;
    private boolean showPost = true;

    // 이퀄라이저 화면
    private Switch power;
    private TextView nowApp;
    private TextView nowStatus;
    private SpectrumView spectrum;
    private TextView btnOrig;
    private TextView btnPost;
    private TextView btnSpec;
    private LinearLayout chipRow;
    private BandSliders sliders;

    // 프리셋·설정 화면
    private LinearLayout presetList;
    private LinearLayout settingsList;

    // ------------------------------------------------------------------ 생명주기

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EqEngine.load(this);
        Haptics.init(this);
        buildUi();
        showTab(0);
        requestRuntimePermissions();
        if (EqEngine.isOn()) startEqService();
    }

    @Override
    protected void onStart() {
        super.onStart();
        EqEngine.addListener(this);
        refreshAll();
        handler.post(tick);
    }

    @Override
    protected void onStop() {
        EqEngine.removeListener(this);
        handler.removeCallbacks(tick);
        super.onStop();
    }

    @Override
    public void onChanged() {
        runOnUiThread(this::refreshAll);
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            refreshNowPlaying();
            if (tab == 2) buildSettings();
            handler.postDelayed(this, 700);
        }
    };

    // ------------------------------------------------------------------ 공통 도우미

    private int dp(float v) {
        return Theme.dp(this, v);
    }

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(null, android.graphics.Typeface.BOLD);
        return t;
    }

    private LinearLayout.LayoutParams lp(int w, int h, float topDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.topMargin = dp(topDp);
        return p;
    }

    private LinearLayout card(boolean horizontal) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(horizontal ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        l.setBackground(Theme.box(this, Theme.SURFACE, Theme.BORDER, 16));
        return l;
    }

    private TextView pill(String label, boolean on, int onBorder) {
        TextView t = new TextView(this);
        t.setGravity(Gravity.CENTER);
        t.setTextSize(13);
        t.setPadding(dp(16), 0, dp(16), 0);
        stylePill(t, label, on, onBorder);
        return t;
    }

    private void stylePill(TextView t, String label, boolean on, int onBorder) {
        t.setText(label);
        t.setTextColor(on ? Theme.TEXT : Theme.MUTED);
        t.setBackground(Theme.box(this, on ? Theme.SELECTED : 0, on ? onBorder : Theme.BORDER, 22));
    }

    // ------------------------------------------------------------------ 화면 뼈대

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Theme.BG);
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

        FrameLayout content = new FrameLayout(this);
        for (int i = 0; i < 3; i++) {
            ScrollView sv = new ScrollView(this);
            sv.setFillViewport(true);
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setPadding(dp(16), dp(8), dp(16), dp(20));
            sv.addView(col);
            pages[i] = sv;
            content.addView(sv);
        }
        buildEqPage((LinearLayout) pages[0].getChildAt(0));
        presetList = (LinearLayout) pages[1].getChildAt(0);
        settingsList = (LinearLayout) pages[2].getChildAt(0);
        root.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setBackgroundColor(0xFF0E1617);
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            TextView t = text(TAB_NAMES[i], 12, Theme.MUTED, false);
            t.setGravity(Gravity.CENTER);
            t.setOnClickListener(v -> showTab(idx));
            navs[i] = t;
            nav.addView(t, new LinearLayout.LayoutParams(0, dp(56), 1f));
        }
        root.addView(nav);
        setContentView(root);
    }

    private void showTab(int i) {
        tab = i;
        for (int k = 0; k < 3; k++) {
            pages[k].setVisibility(k == i ? View.VISIBLE : View.GONE);
            navs[k].setTextColor(k == i ? Theme.ACCENT : Theme.MUTED);
            navs[k].setTypeface(null, k == i ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        }
        if (i == 1) buildPresets();
        if (i == 2) buildSettings();
    }

    // ------------------------------------------------------------------ 이퀄라이저 화면

    private void buildEqPage(LinearLayout col) {
        // 머리글
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("이퀄라이저", 22, Theme.TEXT, true);
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        power = new Switch(this);
        power.setThumbTintList(ColorStateList.valueOf(Theme.BG));
        power.setTrackTintList(new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{Theme.ACCENT, 0xFF26393B}));
        power.setContentDescription("이퀄라이저 켜기 또는 끄기");
        power.setOnCheckedChangeListener((b, checked) -> {
            if (updating) return;
            Haptics.click();
            EqEngine.setOn(this, checked);
            if (checked) startEqService();
        });
        header.addView(power, new LinearLayout.LayoutParams(dp(60), dp(44)));
        col.addView(header, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(48), 0));

        // 재생 중인 앱
        LinearLayout now = card(true);
        now.setGravity(Gravity.CENTER_VERTICAL);
        now.setPadding(dp(14), dp(10), dp(14), dp(10));
        LinearLayout nowText = new LinearLayout(this);
        nowText.setOrientation(LinearLayout.VERTICAL);
        nowText.addView(text("재생 중인 앱", 12, Theme.MUTED, false));
        nowApp = text("", 15, Theme.TEXT, true);
        nowApp.setSingleLine(true);
        nowApp.setEllipsize(android.text.TextUtils.TruncateAt.END);
        nowText.addView(nowApp);
        now.addView(nowText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        nowStatus = text("", 12, Theme.ACCENT, false);
        now.addView(nowStatus);
        col.addView(now, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 8));

        // 스펙트럼
        spectrum = new SpectrumView(this);
        spectrum.setBackground(Theme.box(this, Theme.TRACK, Theme.BORDER, 16));
        spectrum.setCallback((band, g) -> userGain(band, g));
        col.addView(spectrum, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(170), 10));

        // 원음 / 적용 후 / 스펙트럼 켜기
        LinearLayout toggles = new LinearLayout(this);
        toggles.setGravity(Gravity.CENTER_VERTICAL);
        btnOrig = pill("원음", true, Theme.OUTLINE);
        btnPost = pill("적용 후", true, Theme.ACCENT);
        btnSpec = pill("스펙트럼 켜기", false, Theme.ACCENT);
        btnOrig.setOnClickListener(v -> {
            showOrig = !showOrig;
            Haptics.click();
            refreshAll();
        });
        btnPost.setOnClickListener(v -> {
            showPost = !showPost;
            Haptics.click();
            refreshAll();
        });
        btnSpec.setOnClickListener(v -> toggleSpectrum());
        toggles.addView(btnOrig, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)));
        LinearLayout.LayoutParams p2 = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44));
        p2.leftMargin = dp(8);
        toggles.addView(btnPost, p2);
        LinearLayout.LayoutParams p3 = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44));
        p3.leftMargin = dp(8);
        toggles.addView(btnSpec, p3);
        col.addView(toggles, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(44), 6));

        // 프리셋 칩
        HorizontalScrollView chipScroll = new HorizontalScrollView(this);
        chipScroll.setHorizontalScrollBarEnabled(false);
        chipRow = new LinearLayout(this);
        chipRow.setOrientation(LinearLayout.HORIZONTAL);
        chipScroll.addView(chipRow);
        col.addView(chipScroll, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(44), 6));

        // 대역별 조절
        LinearLayout eqCard = card(false);
        eqCard.setPadding(dp(8), dp(2), dp(8), dp(10));
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout headText = new LinearLayout(this);
        headText.setOrientation(LinearLayout.VERTICAL);
        headText.setPadding(dp(8), 0, 0, 0);
        headText.addView(text("대역별 조절", 14, Theme.TEXT, true));
        headText.addView(text("좌우로 밀어 " + EqEngine.BANDS + "개 대역 조절", 12, Theme.MUTED, false));
        head.addView(headText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView reset = text("초기화", 14, Theme.MUTED, false);
        reset.setGravity(Gravity.CENTER);
        reset.setPadding(dp(12), 0, dp(12), 0);
        reset.setOnClickListener(v -> {
            Haptics.heavy();
            EqEngine.reset(this);
        });
        head.addView(reset, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)));
        eqCard.addView(head);
        sliders = new BandSliders(this, (band, g) -> userGain(band, g));
        eqCard.addView(sliders, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 2));
        col.addView(eqCard, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 8));
    }

    /** 슬라이더나 스펙트럼 점을 사용자가 끌었을 때. */
    private void userGain(int band, int g) {
        int[] cur = EqEngine.gains();
        int old = cur[band];
        activeBand = band;
        if (g != old) {
            boolean crossed = g == 0 || (old < 0 && g > 0) || (old > 0 && g < 0);
            if (crossed) Haptics.click();
            else Haptics.tick();
            EqEngine.setGain(this, band, g);
        } else {
            refreshAll();
        }
    }

    private void refreshAll() {
        int[] gains = EqEngine.gains();
        boolean on = EqEngine.isOn();
        updating = true;
        power.setChecked(on);
        updating = false;
        spectrum.setModel(gains, on, activeBand);
        spectrum.setShow(showOrig, showPost);
        sliders.set(gains, on, activeBand);
        stylePill(btnOrig, "원음", showOrig, Theme.OUTLINE);
        stylePill(btnPost, "적용 후", showPost, Theme.ACCENT);
        stylePill(btnSpec, SpectrumState.running ? "스펙트럼 끄기" : "스펙트럼 켜기", SpectrumState.running, Theme.ACCENT);
        rebuildChips();
        refreshNowPlaying();
        if (tab == 1) buildPresets();
        if (tab == 2) buildSettings();
    }

    private void refreshNowPlaying() {
        if (nowApp == null) return;
        List<String> apps = EqEngine.activePackages();
        boolean on = EqEngine.isOn();
        if (apps.isEmpty()) {
            nowApp.setText("음악 앱에서 곡을 재생하세요");
            nowApp.setTextColor(Theme.MUTED);
        } else {
            nowApp.setText(join(apps));
            nowApp.setTextColor(Theme.TEXT);
        }
        if (!on) {
            nowStatus.setText("꺼짐");
            nowStatus.setTextColor(Theme.MUTED);
        } else if (apps.isEmpty()) {
            nowStatus.setText("재생 대기 중");
            nowStatus.setTextColor(Theme.MUTED);
        } else {
            nowStatus.setText("실시간 적용 중");
            nowStatus.setTextColor(Theme.ACCENT);
        }
    }

    private static String join(List<String> items) {
        StringBuilder sb = new StringBuilder();
        for (String s : items) {
            if (sb.indexOf(s) >= 0) continue;
            if (sb.length() > 0) sb.append(", ");
            sb.append(s);
        }
        return sb.toString();
    }

    private void rebuildChips() {
        chipRow.removeAllViews();
        String cur = EqEngine.presetId();
        for (String[] meta : EqEngine.PRESET_META) {
            addChip(meta[0], meta[1], cur.equals(meta[0]));
        }
        for (String[] cp : EqEngine.customPresets(this)) {
            addChip("c:" + cp[0], cp[0], cur.equals("c:" + cp[0]));
        }
    }

    private void addChip(final String id, String label, boolean selected) {
        TextView t = new TextView(this);
        t.setGravity(Gravity.CENTER);
        t.setTextSize(14);
        t.setPadding(dp(18), 0, dp(18), 0);
        t.setText(label);
        t.setTextColor(selected ? Theme.ON_ACCENT : Theme.TEXT);
        t.setBackground(Theme.box(this, selected ? Theme.ACCENT : 0, selected ? Theme.ACCENT : Theme.OUTLINE, 22));
        t.setOnClickListener(v -> {
            Haptics.heavy();
            EqEngine.applyPreset(this, id);
        });
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44));
        p.rightMargin = dp(8);
        chipRow.addView(t, p);
    }

    // ------------------------------------------------------------------ 스펙트럼 켜기/끄기

    private void toggleSpectrum() {
        Haptics.click();
        if (SpectrumState.running) {
            Intent i = new Intent(this, CaptureService.class);
            i.setAction(CaptureService.ACTION_STOP);
            startService(i);
            handler.postDelayed(this::refreshAll, 400);
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestRuntimePermissions();
            Toast.makeText(this, "녹음 권한을 허용한 뒤 다시 눌러 주세요", Toast.LENGTH_LONG).show();
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
            Toast.makeText(this, "스펙트럼 허용이 취소됐어요", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent i = new Intent(this, CaptureService.class);
        i.putExtra("rc", resultCode);
        i.putExtra("data", data);
        startForegroundService(i);
        handler.postDelayed(this::refreshAll, 600);
    }

    // ------------------------------------------------------------------ 프리셋 화면

    private void buildPresets() {
        presetList.removeAllViews();
        presetList.addView(text("프리셋", 22, Theme.TEXT, true), lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(48), 0));
        presetList.addView(text("기본 프리셋", 13, Theme.MUTED, false), lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 6));
        String cur = EqEngine.presetId();
        for (int i = 0; i < EqEngine.PRESET_META.length; i++) {
            addPresetRow(EqEngine.PRESET_META[i][0], EqEngine.PRESET_META[i][1], EqEngine.PRESET_META[i][2],
                    EqEngine.PRESET_GAINS[i], cur);
        }
        List<String[]> customs = EqEngine.customPresets(this);
        presetList.addView(text("내 프리셋", 13, Theme.MUTED, false), lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 16));
        for (String[] cp : customs) {
            int[] g = EqEngine.parseGains(cp[1]);
            addPresetRow("c:" + cp[0], cp[0], "직접 만든 설정", g, cur);
        }
        TextView save = text("+ 현재 설정을 내 프리셋으로 저장", 15, Theme.TEXT, false);
        save.setGravity(Gravity.CENTER);
        save.setBackground(Theme.box(this, 0, Theme.OUTLINE, 16));
        save.setOnClickListener(v -> {
            Haptics.click();
            EqEngine.saveCustom(this);
        });
        presetList.addView(save, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(64), 10));
    }

    private void addPresetRow(final String id, String name, String desc, int[] gains, String currentId) {
        boolean selected = id.equals(currentId);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(10), dp(14), dp(10));
        row.setBackground(Theme.box(this, selected ? Theme.SELECTED : Theme.SURFACE,
                selected ? Theme.ACCENT : Theme.BORDER, 16));
        row.addView(new CurveThumb(this, gains, selected), new LinearLayout.LayoutParams(dp(72), dp(36)));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(14), 0, 0, 0);
        col.addView(text(name, 15, Theme.TEXT, true));
        col.addView(text(desc, 13, Theme.MUTED, false));
        row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (selected) row.addView(text("사용 중", 12, Theme.ACCENT, true));
        row.setOnClickListener(v -> {
            Haptics.heavy();
            EqEngine.applyPreset(this, id);
        });
        presetList.addView(row, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(64), 10));
    }

    /** 프리셋 목록의 작은 곡선 미리보기. */
    private static final class CurveThumb extends View {
        private final int[] g;
        private final boolean selected;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        CurveThumb(MainActivity c, int[] gains, boolean selected) {
            super(c);
            this.g = gains.clone();
            this.selected = selected;
            paint.setStyle(Paint.Style.STROKE);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            float d = getResources().getDisplayMetrics().density;
            paint.setStrokeWidth(d);
            paint.setColor(Theme.OUTLINE);
            canvas.drawLine(0, h / 2, w, h / 2, paint);
            path.reset();
            for (int i = 0; i < g.length; i++) {
                float x = i / (float) (g.length - 1) * w;
                float y = h / 2 - g[i] / 12f * (h / 2 * 0.9f);
                if (i == 0) path.moveTo(x, y);
                else path.lineTo(x, y);
            }
            paint.setStrokeWidth(2 * d);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setColor(selected ? Theme.ACCENT : 0xFFC3D4D2);
            canvas.drawPath(path, paint);
        }
    }

    // ------------------------------------------------------------------ 설정 화면

    private void buildSettings() {
        settingsList.removeAllViews();
        settingsList.addView(text("설정", 22, Theme.TEXT, true), lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(48), 0));

        boolean svc = EqService.isRunning();
        addSettingRow("이퀄라이저 서비스", svc ? "동작 중 · 앱이 꺼져 있어도 적용돼요" : "중지됨 · 시작해야 적용돼요",
                svc ? "중지" : "시작", v -> {
                    if (EqService.isRunning()) {
                        Intent i = new Intent(this, EqService.class);
                        i.setAction(EqService.ACTION_STOP);
                        startService(i);
                    } else {
                        startEqService();
                    }
                    handler.postDelayed(this::buildSettings, 400);
                });

        boolean mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        addSettingRow("녹음 권한", mic ? "허용됨 · 스펙트럼에 필요해요" : "필요함 · 스펙트럼에 필요해요",
                mic ? null : "허용", v -> requestRuntimePermissions());

        if (Build.VERSION.SDK_INT >= 33) {
            boolean noti = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
            addSettingRow("알림 권한", noti ? "허용됨 · 동작 중 알림을 보여줘요" : "필요함 · 동작 중 알림을 보여줘요",
                    noti ? null : "허용", v -> requestRuntimePermissions());
        }

        TextView tips = text("이렇게 쓰세요\n"
                + "1. 이퀄라이저를 켠 뒤 음악 앱에서 곡을 새로 재생하세요. 이미 재생 중이었다면 다음 곡으로 한 번 넘기면 적용돼요.\n"
                + "2. 갤럭시는 설정 > 소리 및 진동 > 소리 품질 및 효과에서 Dolby Atmos와 Adapt Sound를 끄면 변화를 더 또렷하게 들을 수 있어요.\n"
                + "3. 스펙트럼은 켤 때 화면 녹화 허용 창이 한 번 떠요. 소리만 읽고 화면은 저장하지 않아요.", 13, Theme.MUTED, false);
        tips.setLineSpacing(0, 1.25f);
        tips.setPadding(dp(4), 0, dp(4), 0);
        settingsList.addView(tips, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 16));
        settingsList.addView(text("진단 기록 (서비스 " + (EqService.isRunning() ? "동작" : "중지") + ")", 13, Theme.MUTED, false),
                lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 16));
        settingsList.addView(text(EqEngine.logText(), 12, Theme.TEXT, false),
                lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 6));
        settingsList.addView(text("버전 0.2 (시험 단계)", 12, Theme.MUTED, false),
                lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 16));
    }

    private void addSettingRow(String title, String desc, String buttonLabel, View.OnClickListener l) {
        LinearLayout row = card(true);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(10), dp(14), dp(10));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(text(title, 15, Theme.TEXT, true));
        col.addView(text(desc, 13, Theme.MUTED, false));
        row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (buttonLabel != null) {
            TextView b = pill(buttonLabel, true, Theme.ACCENT);
            b.setOnClickListener(l);
            row.addView(b, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)));
        }
        settingsList.addView(row, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 10));
    }

    // ------------------------------------------------------------------ 서비스·권한

    private void startEqService() {
        try {
            startForegroundService(new Intent(this, EqService.class));
        } catch (Throwable t) {
            Toast.makeText(this, "서비스를 시작하지 못했어요: " + t.getMessage(), Toast.LENGTH_LONG).show();
        }
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

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (tab == 2) buildSettings();
    }
}
