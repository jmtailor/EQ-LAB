package com.eqlab.equalizer;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.audiofx.DynamicsProcessing;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 이퀄라이저 상태(10밴드 게인, 켜짐, 프리셋)와, 음악 앱이 공개한 오디오 세션마다 붙인
 * DynamicsProcessing 효과를 관리한다. 서비스와 화면이 같은 프로세스에서 공유한다.
 */
final class EqEngine {
    private EqEngine() {}

    static final int[] FREQ = {31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000};
    static final String[] LABEL = {"31", "62", "125", "250", "500", "1k", "2k", "4k", "8k", "16k"};

    static final String[][] PRESET_META = {
            {"flat", "플랫", "원음 그대로"},
            {"bass", "베이스", "저음을 묵직하게"},
            {"vocal", "보컬", "목소리를 또렷하게"},
            {"club", "클럽", "리듬과 고음을 강조"},
            {"acoustic", "어쿠스틱", "악기 질감을 살려서"}
    };
    static final int[][] PRESET_GAINS = {
            {0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
            {8, 7, 5, 3, 1, 0, -1, 0, 0, 1},
            {-3, -2, -1, 1, 3, 4, 4, 2, 0, -1},
            {6, 5, 3, 0, 1, 2, 3, 4, 3, 2},
            {4, 4, 3, 2, 1, 1, 2, 3, 3, 3}
    };

    interface Listener {
        void onChanged();
    }

    private static final int[] gains = new int[10];
    private static boolean on = true;
    private static String presetId = "flat";
    private static boolean loaded = false;
    private static final Map<Integer, DynamicsProcessing> effects = new HashMap<>();
    private static final Map<Integer, String> sessionPkg = new LinkedHashMap<>();
    private static final List<Listener> listeners = new CopyOnWriteArrayList<>();

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

    // ------------------------------------------------------------ 저장·불러오기

    static synchronized void load(Context c) {
        if (loaded) return;
        loaded = true;
        SharedPreferences p = prefs(c);
        on = p.getBoolean("on", true);
        presetId = p.getString("preset", "flat");
        String csv = p.getString("gains", "");
        if (!csv.isEmpty()) {
            String[] parts = csv.split(",");
            for (int i = 0; i < 10 && i < parts.length; i++) {
                try {
                    gains[i] = clamp(Integer.parseInt(parts[i].trim()));
                } catch (NumberFormatException ignored) {
                }
            }
        }
    }

    private static void save(Context c) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            if (i > 0) sb.append(',');
            sb.append(gains[i]);
        }
        prefs(c).edit().putBoolean("on", on).putString("preset", presetId).putString("gains", sb.toString()).apply();
    }

    private static int clamp(int v) {
        return Math.max(-12, Math.min(12, v));
    }

    // ------------------------------------------------------------ 상태 읽기

    static synchronized int[] gains() {
        return gains.clone();
    }

    static synchronized boolean isOn() {
        return on;
    }

    static synchronized String presetId() {
        return presetId;
    }

    static synchronized int sessionCount() {
        return effects.size();
    }

    static synchronized List<String> activePackages() {
        return new ArrayList<>(sessionPkg.values());
    }

    // ------------------------------------------------------------ 조작

    static synchronized void setGain(Context c, int band, int db) {
        gains[band] = clamp(db);
        presetId = "custom";
        applyToAll();
        save(c);
        fire();
    }

    static synchronized void applyPreset(Context c, String id) {
        int[] src = null;
        for (int i = 0; i < PRESET_META.length; i++) {
            if (PRESET_META[i][0].equals(id)) src = PRESET_GAINS[i];
        }
        if (src == null && id.startsWith("c:")) {
            for (String[] cp : customPresets(c)) {
                if (("c:" + cp[0]).equals(id)) src = parse(cp[1]);
            }
        }
        if (src == null) return;
        for (int i = 0; i < 10; i++) gains[i] = clamp(src[i]);
        presetId = id;
        applyToAll();
        save(c);
        fire();
    }

    static synchronized void reset(Context c) {
        Arrays.fill(gains, 0);
        presetId = "flat";
        applyToAll();
        save(c);
        fire();
    }

    static synchronized void setOn(Context c, boolean value) {
        on = value;
        applyToAll();
        save(c);
        fire();
    }

    // ------------------------------------------------------------ 사용자 프리셋

    private static int[] parse(String csv) {
        int[] out = new int[10];
        String[] parts = csv.split(",");
        for (int i = 0; i < 10 && i < parts.length; i++) {
            try {
                out[i] = Integer.parseInt(parts[i].trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    /** 각 항목은 {이름, "g0,g1,...,g9"}. */
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

    static synchronized void saveCustom(Context c) {
        List<String[]> list = customPresets(c);
        String name = "내 프리셋 " + (list.size() + 1);
        StringBuilder csv = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            if (i > 0) csv.append(',');
            csv.append(gains[i]);
        }
        String old = prefs(c).getString("custom", "");
        prefs(c).edit().putString("custom", old + name + "\t" + csv + "\n").apply();
        presetId = "c:" + name;
        save(c);
        fire();
    }

    // ------------------------------------------------------------ 오디오 세션과 효과

    /** 음악 앱이 오디오 세션을 열었을 때: 효과를 붙이고 현재 게인을 적용한다. */
    static synchronized void onSessionOpen(Context c, int session, String pkg) {
        if (session <= 0) return;
        releaseSession(session);
        try {
            DynamicsProcessing.Config cfg = new DynamicsProcessing.Config.Builder(
                    DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                    2, true, 10, false, 0, false, 0, false).build();
            DynamicsProcessing dp = new DynamicsProcessing(0, session, cfg);
            configure(dp);
            effects.put(session, dp);
            sessionPkg.put(session, label(c, pkg));
        } catch (Throwable t) {
            sessionPkg.remove(session);
        }
        fire();
    }

    static synchronized void onSessionClose(int session) {
        releaseSession(session);
        fire();
    }

    private static void releaseSession(int session) {
        DynamicsProcessing old = effects.remove(session);
        sessionPkg.remove(session);
        if (old != null) {
            try {
                old.release();
            } catch (Throwable ignored) {
            }
        }
    }

    private static void configure(DynamicsProcessing dp) {
        dp.setPreEqAllChannelsTo(new DynamicsProcessing.Eq(true, true, 10));
        for (int i = 0; i < 10; i++) {
            float g = on ? gains[i] : 0f;
            dp.setPreEqBandAllChannelsTo(i, new DynamicsProcessing.EqBand(true, FREQ[i], g));
        }
        dp.setEnabled(on);
    }

    private static void applyToAll() {
        Iterator<Map.Entry<Integer, DynamicsProcessing>> it = effects.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, DynamicsProcessing> e = it.next();
            try {
                configure(e.getValue());
            } catch (Throwable t) {
                try {
                    e.getValue().release();
                } catch (Throwable ignored) {
                }
                sessionPkg.remove(e.getKey());
                it.remove();
            }
        }
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
