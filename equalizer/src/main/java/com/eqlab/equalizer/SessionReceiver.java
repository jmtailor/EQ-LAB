package com.eqlab.equalizer;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.media.audiofx.AudioEffect;

/** 매니페스트에 등록된 수신기. 음악 앱이 보내는 오디오 세션 방송을 앱이 꺼져 있어도 받는다. */
public class SessionReceiver extends BroadcastReceiver {
    private static String lastKey = "";
    private static long lastTime = 0;

    static synchronized boolean isDuplicate(String key) {
        long now = System.currentTimeMillis();
        boolean dup = key.equals(lastKey) && now - lastTime < 1000;
        lastKey = key;
        lastTime = now;
        return dup;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (action == null) return;
        int session = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, -1);
        String pkg = intent.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME);
        if (isDuplicate(action + "|" + session + "|" + pkg)) return;
        EqEngine.load(context);
        if (AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION.equals(action)) {
            EqEngine.onSessionOpen(context, session, pkg);
        } else if (AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION.equals(action)) {
            EqEngine.onSessionClose(session);
        }
    }
}
