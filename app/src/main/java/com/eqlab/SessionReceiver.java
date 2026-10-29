package com.eqlab;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.media.audiofx.AudioEffect;

/** 음악 앱이 오디오 세션을 열고 닫을 때 보내는 방송을 받아 기록한다 (실험 A). */
public class SessionReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (action == null) return;
        int session = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, -1);
        String pkg = intent.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME);
        int type = intent.getIntExtra(AudioEffect.EXTRA_CONTENT_TYPE, -1);
        if (AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION.equals(action)) {
            Store.addSession(context, session, pkg);
            Store.log(context, "[세션 열림] id=" + session + " 앱=" + pkg + " 콘텐츠=" + type);
        } else if (AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION.equals(action)) {
            Store.removeSession(context, session);
            Store.log(context, "[세션 닫힘] id=" + session + " 앱=" + pkg);
        }
    }
}
