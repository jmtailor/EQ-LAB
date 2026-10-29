package com.eqlab.equalizer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.media.audiofx.AudioEffect;
import android.os.Build;
import android.os.IBinder;

/**
 * 앱이 꺼져 있어도 음악 앱이 여는 오디오 세션을 감지해서 EQ를 자동으로 붙여 주는 서비스.
 * 상단 알림이 계속 보이는 포그라운드 서비스로 동작한다.
 */
public class EqService extends Service {
    static final String ACTION_STOP = "com.eqlab.equalizer.STOP";
    private static volatile boolean running = false;

    static boolean isRunning() {
        return running;
    }

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action == null) return;
            int session = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, -1);
            String pkg = intent.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME);
            if (AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION.equals(action)) {
                EqEngine.onSessionOpen(context, session, pkg);
            } else if (AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION.equals(action)) {
                EqEngine.onSessionClose(session);
            }
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        EqEngine.load(this);
        IntentFilter f = new IntentFilter();
        f.addAction(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION);
        f.addAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, f, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(receiver, f);
        }
        running = true;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("eq", "이퀄라이저 동작", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Intent stop = new Intent(this, EqService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stop, PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, "eq")
                .setContentTitle("이퀄라이저 동작 중")
                .setContentText("음악 앱을 재생하면 자동으로 적용돼요")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, "중지", stopPi).build())
                .setOngoing(true)
                .build();
        startForeground(2, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        try {
            unregisterReceiver(receiver);
        } catch (Throwable ignored) {
        }
        super.onDestroy();
    }
}
