package com.eqlab;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 이벤트 로그, 감지된 오디오 세션, 시험 결과를 저장하는 작은 저장소. */
final class Store {
    private Store() {}

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences("eqlab", Context.MODE_PRIVATE);
    }

    // ---- 이벤트 로그 ----

    static synchronized void log(Context c, String msg) {
        String time = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        File f = new File(c.getApplicationContext().getFilesDir(), "events.log");
        try (FileWriter w = new FileWriter(f, true)) {
            w.write(time + "  " + msg + "\n");
        } catch (IOException ignored) {
        }
    }

    static synchronized String readLog(Context c, int maxLines) {
        File f = new File(c.getApplicationContext().getFilesDir(), "events.log");
        if (!f.exists()) return "(기록 없음)";
        List<String> lines = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String s;
            while ((s = r.readLine()) != null) lines.add(s);
        } catch (IOException ignored) {
        }
        if (lines.isEmpty()) return "(기록 없음)";
        int from = Math.max(0, lines.size() - maxLines);
        StringBuilder sb = new StringBuilder();
        for (int i = lines.size() - 1; i >= from; i--) sb.append(lines.get(i)).append('\n');
        return sb.toString();
    }

    static synchronized void clearLog(Context c) {
        File f = new File(c.getApplicationContext().getFilesDir(), "events.log");
        if (f.exists()) //noinspection ResultOfMethodCallIgnored
            f.delete();
    }

    // ---- 감지된 오디오 세션 ----

    static synchronized Map<Integer, String> sessions(Context c) {
        Map<Integer, String> map = new LinkedHashMap<>();
        String raw = prefs(c).getString("sessions", "");
        if (raw.isEmpty()) return map;
        for (String item : raw.split(";")) {
            int bar = item.indexOf('|');
            if (bar < 0) continue;
            try {
                map.put(Integer.parseInt(item.substring(0, bar)), item.substring(bar + 1));
            } catch (NumberFormatException ignored) {
            }
        }
        return map;
    }

    private static void saveSessions(Context c, Map<Integer, String> map) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Integer, String> e : map.entrySet()) {
            if (sb.length() > 0) sb.append(';');
            sb.append(e.getKey()).append('|').append(e.getValue().replace(";", ",").replace("|", "/"));
        }
        prefs(c).edit().putString("sessions", sb.toString()).apply();
    }

    static synchronized void addSession(Context c, int id, String pkg) {
        Map<Integer, String> map = sessions(c);
        map.put(id, pkg == null ? "?" : pkg);
        saveSessions(c, map);
    }

    static synchronized void removeSession(Context c, int id) {
        Map<Integer, String> map = sessions(c);
        map.remove(id);
        saveSessions(c, map);
    }

    // ---- 시험 결과 ----

    static synchronized void addResult(Context c, String line) {
        String old = prefs(c).getString("results", "");
        prefs(c).edit().putString("results", old + line.replace("\n", " ") + "\n").apply();
    }

    static synchronized String results(Context c) {
        String s = prefs(c).getString("results", "");
        return s.isEmpty() ? "(저장된 결과 없음)" : s;
    }

    static synchronized void clearResults(Context c) {
        prefs(c).edit().remove("results").apply();
    }
}
