package com.eqlab.equalizer;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioPlaybackConfiguration;
import android.media.audiofx.DynamicsProcessing;
import android.os.Handler;
import android.os.HandlerThread;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 이퀄라이저 상태(기본 11대역 + 사용자가 추가한 점, 켜짐, 프리셋)와, 전역(세션 0번) 또는
 * 음악 앱 세션마다 붙인 DynamicsProcessing 효과를 관리한다. 서비스와 화면이 같은 프로세스에서 공유한다.
 * 효과 적용과 저장은 별도 스레드에서 모아서 처리해 슬라이더를 끌 때 화면이 끊기지 않게 한다.
 */
final class EqEngine {
    private EqEngine() {}

    /** 기본 대역 주파수. 지우거나 옮길 수 없다. */
    static final int[] FIXED_FREQ = {20, 31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000};
    static final int FIXED = FIXED_FREQ.length;
    /** 기본 + 추가 점을 합친 최대 대역 수. */
    static final int MAX_BANDS = 24;
    static final int MIN_HZ = 20;
    static final int MAX_HZ = 16000;
    /** 이웃한 점 사이의 최소 주파수 비(약 1/4 옥타브). 이보다 가까우면 화면에서 점을 고르기 어렵다. */
    private static final double MIN_RATIO = 1.19;
    /** 20Hz 대역을 넣기 전(10밴드)에 저장된 값의 개수. */
    private static final int LEGACY_BANDS = 10;

    static final String[][] PRESET_META = {
            {"flat", "플랫", "원음 그대로"},
            {"bass", "베이스", "저음을 묵직하게"},
            {"vocal", "보컬", "목소리를 또렷하게"},
            {"club", "클럽", "리듬과 고음을 강조"},
            {"acoustic", "어쿠스틱", "악기 질감을 살려서"}
    };
    // 맨 앞 값이 20Hz. 기존 10밴드의 31Hz 대역이 그 아래까지 포함했으므로 같은 값으로 두어 소리를 유지한다.
    static final int[][] PRESET_GAINS = {
            {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
            {8, 8, 7, 5, 3, 1, 0, -1, 0, 0, 1},
            {-3, -3, -2, -1, 1, 3, 4, 4, 2, 0, -1},
            {6, 6, 5, 3, 0, 1, 2, 3, 4, 3, 2},
            {4, 4, 4, 3, 2, 1, 1, 2, 3, 3, 3}
    };

    interface Listener {
        void onChanged();
    }

    /** 화면이 그리는 대역 목록. 기본 대역과 추가 점을 주파수 순서로 합친 복사본이다. */
    static final class Model {
        final int n;
        final int[] freq;
        final int[] gain;
        final boolean[] extra;

        Model(int n) {
            this.n = n;
            this.freq = new int[n];
            this.gain = new int[n];
            this.extra = new boolean[n];
        }

        String label(int i) {
            return freqLabel(freq[i]);
        }

        /** 로그 주파수 위에서 코사인으로 이은 곡선의 게인(dB). */
        float at(double hz) {
            if (n == 0) return 0f;
            if (hz <= freq[0]) return gain[0];
            if (hz >= freq[n - 1]) return gain[n - 1];
            int i = 0;
            while (i < n - 2 && hz >= freq[i + 1]) i++;
            double a = Math.log(freq[i]);
            double b = Math.log(freq[i + 1]);
            double t = (Math.log(hz) - a) / (b - a);
            double s = (1 - Math.cos(Math.PI * t)) / 2;
            return (float) (gain[i] + (gain[i + 1] - gain[i]) * s);
        }
    }

    /** 한 오디오 세션(또는 전역)에 붙은 효과와, 만들 때의 대역 수. */
    private static final class Fx {
        final int session;
        final DynamicsProcessing dp;
        final int bands;

        Fx(int session, DynamicsProcessing dp, int bands) {
            this.session = session;
            this.dp = dp;
            this.bands = bands;
        }
    }

    private static final int[] fixedGain = new int[FIXED];
    /** 추가한 점 {주파수, 게인}. 주파수 오름차순. */
    private static final List<int[]> extras = new ArrayList<>();
    private static boolean on = true;
    private static String presetId = "flat";
    private static boolean loaded = false;
    private static final Map<Integer, Fx> effects = new HashMap<>();
    /** 전역(오디오 세션 0번) 효과. 있으면 세션별 효과는 붙이지 않는다(이중 적용 방지). 없으면 세션 방식으로 동작한다. */
    private static Fx globalFx = null;
    private static final Map<Integer, String> sessionPkg = new LinkedHashMap<>();
    private static final List<Listener> listeners = new CopyOnWriteArrayList<>();

    /** 효과 적용과 저장을 맡는 작업 스레드. */
    private static final Handler work;

    static {
        HandlerThread t = new HandlerThread("eq-apply");
        t.start();
        work = new Handler(t.getLooper());
    }

    private static final java.util.ArrayDeque<String> logLines = new java.util.ArrayDeque<>();

    static synchronized void log(String m) {
        logLines.addFirst(clock(System.currentTimeMillis()) + " " + m);
        while (logLines.size() > 24) logLines.removeLast();
    }

    private static String clock(long millis) {
        return new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.KOREA).format(new java.util.Date(millis));
    }

    // ------------------------------------------------------------ 연결 점검(진단)

    /** 점검 대상 음악 앱 {패키지, 표시 이름}. 매니페스트 queries 와 같은 목록을 유지한다. */
    static final String[][] KNOWN_APPS = {
            {"com.google.android.apps.youtube.music", "유튜브 뮤직"},
            {"com.iloen.melon", "멜론"},
            {"com.spotify.music", "스포티파이"},
            {"com.sec.android.app.music", "삼성 뮤직"}
    };

    /** 패키지 -> {열림 방송 횟수, 마지막 수신 시각}. 앱 프로세스가 살아 있는 동안만 유지된다. */
    private static final Map<String, long[]> seen = new LinkedHashMap<>();
    private static int duplicateCount = 0;
    private static String lastPlayback = "";

    /** 이미 처리한 것과 같은 방송(수신기와 서비스가 둘 다 받음)을 무시했을 때. */
    static synchronized void noteDuplicate() {
        duplicateCount++;
    }

    /** 앱이 세션 열림 방송을 보냈을 때(중복 제외). */
    static synchronized void noteOpen(String pkg) {
        String key = pkg == null ? "(앱 이름 없음)" : pkg;
        long[] v = seen.get(key);
        if (v == null) {
            v = new long[2];
            seen.put(key, v);
        }
        v[0]++;
        v[1] = System.currentTimeMillis();
    }

    /** 재생 중인 소리가 있는데 연결된 세션이 없는지 알려 주는 기록. 패키지는 공개 API로 알 수 없다. */
    static synchronized void notePlayback(List<AudioPlaybackConfiguration> configs) {
        int media = 0;
        int other = 0;
        if (configs != null) {
            for (AudioPlaybackConfiguration cfg : configs) {
                try {
                    AudioAttributes a = cfg.getAudioAttributes();
                    if (a.getUsage() == AudioAttributes.USAGE_MEDIA) media++;
                    else other++;
                } catch (Throwable t) {
                    other++;
                }
            }
        }
        String key = "재생 감지 미디어 " + media + "개, 기타 " + other + "개";
        if (key.equals(lastPlayback)) return;
        lastPlayback = key;
        String note;
        if (globalFx != null) note = " · 전역 효과 적용 중";
        else if (media > 0 && effects.isEmpty()) note = " · 연결된 세션 없음";
        else note = " · 연결된 세션 " + effects.size() + "개";
        log(key + note);
    }

    /** 설정 탭에 보여 줄 앱별 점검 문구. 방송을 받은 적이 없으면 그렇게 표시한다. */
    static synchronized String supportText(Context c) {
        StringBuilder sb = new StringBuilder();
        PackageManager pm = c.getPackageManager();
        for (String[] app : KNOWN_APPS) {
            boolean installed;
            try {
                pm.getPackageInfo(app[0], 0);
                installed = true;
            } catch (Throwable t) {
                installed = false;
            }
            if (sb.length() > 0) sb.append('\n');
            sb.append(app[1]).append(" · ").append(installed ? "설치됨" : "설치 안 됨").append(" · ");
            long[] v = seen.get(app[0]);
            if (v == null) sb.append("방송 받은 적 없음");
            else sb.append("방송 ").append(v[0]).append("회 (마지막 ").append(clock(v[1])).append(")");
        }
        for (Map.Entry<String, long[]> e : seen.entrySet()) {
            boolean known = false;
            for (String[] app : KNOWN_APPS) known |= app[0].equals(e.getKey());
            if (known) continue;
            sb.append('\n').append(e.getKey()).append(" · 방송 ").append(e.getValue()[0])
                    .append("회 (마지막 ").append(clock(e.getValue()[1])).append(")");
        }
        if (duplicateCount > 0) sb.append("\n중복 방송 무시 ").append(duplicateCount).append("회");
        return sb.toString();
    }

    static synchronized String logText() {
        if (logLines.isEmpty()) return "아직 기록이 없어요. 음악 앱에서 곡을 새로 재생해 보세요.";
        return android.text.TextUtils.join("\n", logLines);
    }

    static void addListener(Listener l) {
        listeners.add(l);
    }

    static void removeListener(Listener l) {
        listeners.remove(l);
    }

    private static void fire() {
        for (Listener l : listeners) l.onChanged();
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences("eq", Context.MODE_PRIVATE);
    }

    // ------------------------------------------------------------ 주파수 표시와 점 규칙

    static String freqLabel(int hz) {
        if (hz < 1000) return String.valueOf(hz);
        if (hz % 1000 == 0) return (hz / 1000) + "k";
        String s = String.format(Locale.US, "%.2f", hz / 1000.0);
        if (s.endsWith("0")) s = s.substring(0, s.length() - 1);
        return s + "k";
    }

    /** 점을 놓을 주파수를 보기 좋은 값으로 맞춘다(저음은 1Hz, 고음으로 갈수록 거칠게). */
    static int niceFreq(double hz) {
        double step = hz < 100 ? 1 : hz < 1000 ? 5 : hz < 5000 ? 50 : 100;
        int v = (int) (Math.round(hz / step) * step);
        return Math.max(MIN_HZ, Math.min(MAX_HZ, v));
    }

    private static boolean tooClose(int a, int b) {
        double r = a > b ? (double) a / b : (double) b / a;
        return r < MIN_RATIO;
    }

    /** 이 주파수에 점을 둘 수 있는지(범위 안이고 다른 점과 충분히 떨어져 있는지). 개수 제한은 따로 본다. */
    private static boolean fits(List<int[]> list, int f) {
        if (f <= MIN_HZ || f >= MAX_HZ) return false;
        for (int fx : FIXED_FREQ) {
            if (tooClose(f, fx)) return false;
        }
        for (int[] e : list) {
            if (tooClose(f, e[0])) return false;
        }
        return true;
    }

    private static void insertSorted(List<int[]> list, int f, int g) {
        int pos = 0;
        while (pos < list.size() && list.get(pos)[0] < f) pos++;
        list.add(pos, new int[]{f, clamp(g)});
    }

    // ------------------------------------------------------------ 저장·불러오기

    static synchronized void load(Context c) {
        if (loaded) return;
        loaded = true;
        SharedPreferences p = prefs(c);
        on = p.getBoolean("on", true);
        presetId = p.getString("preset", "flat");
        String csv = p.getString("gains", "");
        if (!csv.isEmpty()) {
            int[] saved = parseGains(csv);
            for (int i = 0; i < FIXED; i++) fixedGain[i] = clamp(saved[i]);
        }
        extras.clear();
        extras.addAll(parseExtras(p.getString("extras", "")));
    }

    private static String extrasText() {
        StringBuilder sb = new StringBuilder();
        for (int[] e : extras) {
            if (sb.length() > 0) sb.append(',');
            sb.append(e[0]).append(':').append(e[1]);
        }
        return sb.toString();
    }

    private static String fixedText() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < FIXED; i++) {
            if (i > 0) sb.append(',');
            sb.append(fixedGain[i]);
        }
        return sb.toString();
    }

    private static void save(Context c) {
        prefs(c).edit().putBoolean("on", on).putString("preset", presetId)
                .putString("gains", fixedText()).putString("extras", extrasText()).apply();
    }

    private static Context appCtx;
    private static final Runnable saveRunnable = new Runnable() {
        @Override
        public void run() {
            synchronized (EqEngine.class) {
                if (appCtx != null) save(appCtx);
            }
        }
    };

    /** 슬라이더를 끄는 동안 매번 저장하지 않고, 마지막 변경 0.3초 뒤에 한 번 저장한다. */
    private static void saveSoon(Context c) {
        appCtx = c.getApplicationContext();
        work.removeCallbacks(saveRunnable);
        work.postDelayed(saveRunnable, 300);
    }

    /** 화면이 멈출 때 기다리던 저장을 바로 끝낸다. */
    static synchronized void flush(Context c) {
        appCtx = c.getApplicationContext();
        work.removeCallbacks(saveRunnable);
        save(appCtx);
    }

    private static int clamp(int v) {
        return Math.max(-12, Math.min(12, v));
    }

    // ------------------------------------------------------------ 상태 읽기

    private static Model buildModel() {
        int n = FIXED + extras.size();
        Model m = new Model(n);
        int i = 0;
        int j = 0;
        for (int k = 0; k < n; k++) {
            if (takeFixed(i, j)) {
                m.freq[k] = FIXED_FREQ[i];
                m.gain[k] = fixedGain[i];
                m.extra[k] = false;
                i++;
            } else {
                int[] e = extras.get(j);
                m.freq[k] = e[0];
                m.gain[k] = e[1];
                m.extra[k] = true;
                j++;
            }
        }
        return m;
    }

    /** 합친 순서에서 다음 점이 기본 대역인지(아니면 추가 점인지). */
    private static boolean takeFixed(int i, int j) {
        if (i >= FIXED) return false;
        if (j >= extras.size()) return true;
        return FIXED_FREQ[i] < extras.get(j)[0];
    }

    /** 합친 순서의 번호를 {0=기본 대역/1=추가 점, 그 그룹 안의 번호}로 바꾼다. */
    private static int[] locate(int band) {
        int i = 0;
        int j = 0;
        for (int k = 0; k <= band; k++) {
            boolean fixed = takeFixed(i, j);
            if (k == band) return new int[]{fixed ? 0 : 1, fixed ? i : j};
            if (fixed) i++;
            else j++;
        }
        return new int[]{0, 0};
    }

    private static boolean validBand(int band) {
        return band >= 0 && band < FIXED + extras.size();
    }

    static synchronized Model model() {
        return buildModel();
    }

    static synchronized boolean isOn() {
        return on;
    }

    static synchronized String presetId() {
        return presetId;
    }

    static synchronized boolean isGlobal() {
        return globalFx != null;
    }

    static synchronized int sessionCount() {
        return effects.size();
    }

    static synchronized List<String> activePackages() {
        return new ArrayList<>(sessionPkg.values());
    }

    // ------------------------------------------------------------ 조작

    /** band 는 기본 대역과 추가 점을 주파수 순서로 합친 번호. */
    static synchronized void setGain(Context c, int band, int db) {
        if (!validBand(band)) return;
        int[] loc = locate(band);
        if (loc[0] == 0) fixedGain[loc[1]] = clamp(db);
        else extras.get(loc[1])[1] = clamp(db);
        presetId = "custom";
        scheduleApply(false, band);
        saveSoon(c);
        fire();
    }

    /**
     * 곡선 위 hz 위치에 점을 추가한다. 처음 게인은 그 자리의 현재 곡선 값이라 소리가 크게 바뀌지 않는다.
     * 합친 순서의 새 번호를 돌려주고, 실패하면 -1(최대 개수) 또는 -2(범위 밖이거나 다른 점과 너무 가까움).
     */
    static synchronized int addPoint(Context c, int hz) {
        if (FIXED + extras.size() >= MAX_BANDS) return -1;
        int f = niceFreq(hz);
        if (!fits(extras, f)) return -2;
        int g = Math.round(buildModel().at(f));
        insertSorted(extras, f, g);
        presetId = "custom";
        scheduleApply(true, -1);
        saveSoon(c);
        fire();
        Model m = buildModel();
        for (int k = 0; k < m.n; k++) {
            if (m.freq[k] == f) return k;
        }
        return -2;
    }

    /** 추가한 점을 지운다. 기본 대역이면 false. */
    static synchronized boolean removePoint(Context c, int band) {
        if (!validBand(band)) return false;
        int[] loc = locate(band);
        if (loc[0] != 1) return false;
        extras.remove(loc[1]);
        presetId = "custom";
        scheduleApply(true, -1);
        saveSoon(c);
        fire();
        return true;
    }

    /** 추가한 점의 주파수를 옮긴다. 이웃 점과 너무 가까워지지 않는 범위로 제한하고, 최종 주파수(불가하면 -1)를 돌려준다. */
    static synchronized int moveExtra(Context c, int band, int hz) {
        if (!validBand(band)) return -1;
        int[] loc = locate(band);
        if (loc[0] != 1) return -1;
        Model m = buildModel();
        double lo = band > 0 ? m.freq[band - 1] * MIN_RATIO : MIN_HZ + 1;
        double hi = band < m.n - 1 ? m.freq[band + 1] / MIN_RATIO : MAX_HZ - 1;
        double loI = Math.ceil(lo);
        double hiI = Math.floor(hi);
        if (loI > hiI) return -1;
        int f = (int) Math.max(loI, Math.min(hiI, niceFreq(hz)));
        int[] e = extras.get(loc[1]);
        if (e[0] == f) return f;
        e[0] = f;
        presetId = "custom";
        scheduleApply(false, band);
        saveSoon(c);
        fire();
        return f;
    }

    static synchronized void applyPreset(Context c, String id) {
        int[] src = null;
        List<int[]> srcExtras = null;
        for (int i = 0; i < PRESET_META.length; i++) {
            if (PRESET_META[i][0].equals(id)) src = PRESET_GAINS[i];
        }
        if (src == null && id.startsWith("c:")) {
            for (String[] cp : customPresets(c)) {
                if (("c:" + cp[0]).equals(id)) {
                    src = parseGains(cp[1]);
                    srcExtras = extrasOf(cp[1]);
                }
            }
        }
        if (src == null) return;
        for (int i = 0; i < FIXED; i++) fixedGain[i] = clamp(src[i]);
        if (srcExtras != null) {
            extras.clear();
            extras.addAll(srcExtras);
        } else {
            // 기본 프리셋: 추가한 점은 위치를 유지하고, 프리셋 곡선 값에 맞춘다.
            Model base = new Model(FIXED);
            for (int i = 0; i < FIXED; i++) {
                base.freq[i] = FIXED_FREQ[i];
                base.gain[i] = clamp(src[i]);
            }
            for (int[] e : extras) e[1] = clamp(Math.round(base.at(e[0])));
        }
        presetId = id;
        scheduleApply(true, -1);
        saveSoon(c);
        fire();
    }

    /** 초기화: 모든 게인을 0으로 하고 추가한 점도 지운다. */
    static synchronized void reset(Context c) {
        Arrays.fill(fixedGain, 0);
        extras.clear();
        presetId = "flat";
        scheduleApply(true, -1);
        saveSoon(c);
        fire();
    }

    static synchronized void setOn(Context c, boolean value) {
        on = value;
        scheduleApply(true, -1);
        saveSoon(c);
        fire();
    }

    // ------------------------------------------------------------ 사용자 프리셋

    /**
     * 쉼표로 이어진 게인 문자열에서 기본 대역 값(FIXED개)을 읽는다. "|" 뒤의 추가 점 부분은 무시한다.
     * 10개짜리(20Hz 대역 추가 전에 저장된 값)는 맨 앞에 31Hz 값을 20Hz 자리로 복제해 11개로 만든다.
     */
    static int[] parseGains(String csv) {
        int bar = csv.indexOf('|');
        String head = bar >= 0 ? csv.substring(0, bar) : csv;
        String[] parts = head.split(",");
        int shift = parts.length == LEGACY_BANDS ? 1 : 0;
        int[] out = new int[FIXED];
        for (int i = 0; i < parts.length && i + shift < FIXED; i++) {
            try {
                out[i + shift] = Integer.parseInt(parts[i].trim());
            } catch (NumberFormatException ignored) {
            }
        }
        if (shift == 1) out[0] = out[1];
        return out;
    }

    /** "주파수:게인,주파수:게인" 문자열에서 추가 점을 읽는다. 규칙에 맞지 않는 점은 버린다. */
    static List<int[]> parseExtras(String s) {
        List<int[]> out = new ArrayList<>();
        if (s == null || s.isEmpty()) return out;
        for (String token : s.split(",")) {
            String[] fg = token.split(":");
            if (fg.length != 2) continue;
            try {
                int f = Integer.parseInt(fg[0].trim());
                int g = Integer.parseInt(fg[1].trim());
                if (FIXED + out.size() >= MAX_BANDS) break;
                if (fits(out, f)) insertSorted(out, f, g);
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    /** 사용자 프리셋 문자열("게인들|추가점들")에서 추가 점 부분만 읽는다. */
    static List<int[]> extrasOf(String presetCsv) {
        int bar = presetCsv.indexOf('|');
        return parseExtras(bar >= 0 ? presetCsv.substring(bar + 1) : "");
    }

    /** 각 항목은 {이름, "g0,...,g10" 또는 "g0,...,g10|주파수:게인,..."}. 예전 10개짜리 값도 읽을 때 변환한다. */
    static synchronized List<String[]> customPresets(Context c) {
        List<String[]> list = new ArrayList<>();
        String raw = prefs(c).getString("custom", "");
        if (raw.isEmpty()) return list;
        for (String line : raw.split("\n")) {
            int tab = line.indexOf('\t');
            if (tab > 0) list.add(new String[]{line.substring(0, tab), line.substring(tab + 1)});
        }
        return list;
    }

    /** 사용자 프리셋 목록이 바뀌었는지 비교하는 데 쓰는 키. */
    static synchronized String customKey(Context c) {
        return prefs(c).getString("custom", "");
    }

    static synchronized void saveCustom(Context c) {
        List<String[]> list = customPresets(c);
        String name = "내 프리셋 " + (list.size() + 1);
        String csv = fixedText();
        if (!extras.isEmpty()) csv = csv + "|" + extrasText();
        String old = prefs(c).getString("custom", "");
        prefs(c).edit().putString("custom", old + name + "\t" + csv + "\n").apply();
        presetId = "c:" + name;
        save(c);
        fire();
    }

    // ------------------------------------------------------------ 전역 효과와 오디오 세션

    private static Fx createFx(int session, Model m, boolean enabled) {
        DynamicsProcessing.Config cfg = new DynamicsProcessing.Config.Builder(
                DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                2, true, m.n, false, 0, false, 0, false).build();
        DynamicsProcessing dp = new DynamicsProcessing(session == 0 ? 0 : 100, session, cfg);
        configure(dp, m, enabled);
        return new Fx(session, dp, m.n);
    }

    private static void configure(DynamicsProcessing dp, Model m, boolean enabled) {
        dp.setPreEqAllChannelsTo(new DynamicsProcessing.Eq(true, true, m.n));
        for (int i = 0; i < m.n; i++) {
            float g = enabled ? m.gain[i] : 0f;
            dp.setPreEqBandAllChannelsTo(i, new DynamicsProcessing.EqBand(true, m.freq[i], g));
        }
        dp.setEnabled(enabled);
    }

    private static void releaseQuiet(Fx fx) {
        try {
            fx.dp.release();
        } catch (Throwable ignored) {
        }
    }

    /**
     * 전역 효과(세션 0번)를 만든다. 방송을 보내지 않는 앱(멜론 등)도 적용되게 하려는 것이다.
     * 성공하면 이미 붙은 세션별 효과를 모두 풀어 이중 적용을 막고, 실패하면 세션 방식으로 계속 동작한다.
     */
    static synchronized boolean ensureGlobal() {
        if (globalFx != null) return true;
        try {
            Fx fx = createFx(0, buildModel(), on);
            globalFx = fx;
            for (Fx old : new ArrayList<>(effects.values())) releaseQuiet(old);
            effects.clear();
            log("전역 효과 생성 제어권=" + fx.dp.hasControl() + " 활성=" + fx.dp.getEnabled() + " 켜짐=" + on);
            fire();
            return true;
        } catch (Throwable t) {
            globalFx = null;
            log("전역 효과 실패, 세션 방식 사용: " + t);
            return false;
        }
    }

    static synchronized void releaseGlobal() {
        if (globalFx == null) return;
        releaseQuiet(globalFx);
        globalFx = null;
        log("전역 효과 해제");
        fire();
    }

    /** 음악 앱이 오디오 세션을 열었을 때: 효과를 붙이고 현재 게인을 적용한다(전역 모드에서는 목록만 기록). */
    static synchronized void onSessionOpen(Context c, int session, String pkg) {
        log("세션 열림 " + session + " / " + pkg);
        if (session <= 0) return;
        if (globalFx != null) {
            sessionPkg.put(session, label(c, pkg));
            log("전역 모드 · 세션별 효과는 붙이지 않음");
            fire();
            return;
        }
        releaseSession(session);
        try {
            Fx fx = createFx(session, buildModel(), on);
            log("효과 생성 세션=" + session + " 제어권=" + fx.dp.hasControl() + " 활성=" + fx.dp.getEnabled() + " 켜짐=" + on);
            effects.put(session, fx);
            sessionPkg.put(session, label(c, pkg));
        } catch (Throwable t) {
            log("효과 생성 실패: " + t);
            sessionPkg.remove(session);
        }
        fire();
    }

    static synchronized void onSessionClose(int session) {
        log("세션 닫힘 " + session);
        releaseSession(session);
        fire();
    }

    private static void releaseSession(int session) {
        Fx old = effects.remove(session);
        sessionPkg.remove(session);
        if (old != null) releaseQuiet(old);
    }

    // ------------------------------------------------------------ 효과 적용 (작업 스레드)

    private static boolean applyScheduled = false;
    private static boolean structDirty = false;
    private static long dirtyMask = 0L;

    private static final Runnable applyRunnable = new Runnable() {
        @Override
        public void run() {
            applyNow();
        }
    };

    /**
     * 효과 갱신을 예약한다(잠금을 쥔 채로 부른다). full 이면 전체를, 아니면 band 하나만 다시 설정한다.
     * 예약이 이미 있으면 합쳐서, 슬라이더를 빠르게 끌어도 효과 호출이 쌓이지 않는다.
     */
    private static void scheduleApply(boolean full, int band) {
        if (full) structDirty = true;
        else if (band >= 0 && band < 63) dirtyMask |= 1L << band;
        if (applyScheduled) return;
        applyScheduled = true;
        work.post(applyRunnable);
    }

    private static void applyNow() {
        Model m;
        boolean enabled;
        boolean full;
        long mask;
        List<Fx> targets = new ArrayList<>();
        synchronized (EqEngine.class) {
            applyScheduled = false;
            m = buildModel();
            enabled = on;
            full = structDirty;
            mask = dirtyMask;
            structDirty = false;
            dirtyMask = 0L;
            if (globalFx != null) targets.add(globalFx);
            targets.addAll(effects.values());
        }
        for (Fx fx : targets) {
            try {
                if (fx.bands != m.n) {
                    // 점이 늘거나 줄어 대역 수가 바뀌면 효과를 새로 만든다.
                    Fx fresh = createFx(fx.session, m, enabled);
                    synchronized (EqEngine.class) {
                        if (replaceFx(fx, fresh)) releaseQuiet(fx);
                        else releaseQuiet(fresh);
                    }
                } else if (full) {
                    configure(fx.dp, m, enabled);
                } else {
                    for (int i = 0; i < m.n && i < 63; i++) {
                        if ((mask & (1L << i)) != 0L) {
                            float g = enabled ? m.gain[i] : 0f;
                            fx.dp.setPreEqBandAllChannelsTo(i, new DynamicsProcessing.EqBand(true, m.freq[i], g));
                        }
                    }
                }
            } catch (Throwable t) {
                synchronized (EqEngine.class) {
                    log("효과 갱신 실패: " + t);
                    dropFx(fx);
                }
            }
        }
    }

    private static boolean replaceFx(Fx old, Fx fresh) {
        if (globalFx == old) {
            globalFx = fresh;
            return true;
        }
        if (effects.get(old.session) == old) {
            effects.put(old.session, fresh);
            return true;
        }
        return false;
    }

    private static void dropFx(Fx fx) {
        releaseQuiet(fx);
        if (globalFx == fx) {
            globalFx = null;
        } else if (effects.get(fx.session) == fx) {
            effects.remove(fx.session);
            sessionPkg.remove(fx.session);
        }
        fire();
    }

    private static String label(Context c, String pkg) {
        if (pkg == null) return "알 수 없는 앱";
        try {
            PackageManager pm = c.getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Throwable t) {
            return pkg;
        }
    }
}
