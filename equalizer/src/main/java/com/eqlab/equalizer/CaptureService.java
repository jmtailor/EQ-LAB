package com.eqlab.equalizer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

/** 재생 중인 소리를 캡처해 32개 막대의 스펙트럼(dB)을 계산한다. 스펙트럼 표시를 켰을 때만 동작한다. */
public class CaptureService extends Service {
    static final String ACTION_STOP = "STOP";
    private static final int RATE = 44100;
    private static final int N = 2048;
    private static final int HOP = 1024;

    private MediaProjection projection;
    private AudioRecord record;
    private Thread worker;
    private volatile boolean stop;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    @SuppressWarnings("deprecation")
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("cap", "스펙트럼", NotificationManager.IMPORTANCE_LOW));
        Notification n = new Notification.Builder(this, "cap")
                .setContentTitle("스펙트럼 표시 중")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .build();
        startForeground(3, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);

        int resultCode = intent.getIntExtra("rc", 0);
        Intent data = (Intent) intent.getParcelableExtra("data");
        try {
            MediaProjectionManager mgr = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            projection = mgr.getMediaProjection(resultCode, data);
            projection.registerCallback(new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    SpectrumState.status = "시스템이 캡처를 중지함";
                    stopSelf();
                }
            }, new Handler(Looper.getMainLooper()));

            AudioPlaybackCaptureConfiguration cfg = new AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build();
            AudioFormat fmt = new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                    .build();
            int minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_STEREO,
                    AudioFormat.ENCODING_PCM_16BIT);
            record = new AudioRecord.Builder()
                    .setAudioPlaybackCaptureConfig(cfg)
                    .setAudioFormat(fmt)
                    .setBufferSizeInBytes(Math.max(minBuf * 2, 16384))
                    .build();
            record.startRecording();
        } catch (Throwable t) {
            fail("스펙트럼 시작 실패: " + t);
            return START_NOT_STICKY;
        }

        SpectrumState.error = "";
        SpectrumState.status = "표시 중";
        SpectrumState.running = true;
        stop = false;
        worker = new Thread(this::loop, "capture");
        worker.start();
        return START_NOT_STICKY;
    }

    private void fail(String msg) {
        SpectrumState.running = false;
        SpectrumState.error = msg;
        SpectrumState.status = "오류";
        stopSelf();
    }

    private void loop() {
        double[] window = new double[N];
        for (int i = 0; i < N; i++) window[i] = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / (N - 1));
        double[] mono = new double[N];
        double[] re = new double[N];
        double[] im = new double[N];
        double[] mag = new double[N / 2];
        short[] buf = new short[HOP * 2];
        float[] smooth = SpectrumState.newSilence();
        double binHz = (double) RATE / N;
        double ratio = Math.pow(16000.0 / 31.0, 1.0 / (SpectrumState.BARS - 1));
        double halfStep = Math.sqrt(ratio);

        while (!stop) {
            int read = record.read(buf, 0, buf.length);
            if (read < 0) {
                fail("읽기 오류 코드 " + read);
                return;
            }
            int frames = read / 2;
            if (frames <= 0) continue;
            System.arraycopy(mono, frames, mono, 0, N - frames);
            for (int i = 0; i < frames; i++) {
                mono[N - frames + i] = (buf[2 * i] + buf[2 * i + 1]) / 65536.0;
            }
            for (int i = 0; i < N; i++) {
                re[i] = mono[i] * window[i];
                im[i] = 0;
            }
            fft(re, im);
            for (int k = 0; k < N / 2; k++) {
                mag[k] = Math.sqrt(re[k] * re[k] + im[k] * im[k]) / (N / 4.0);
            }

            for (int b = 0; b < SpectrumState.BARS; b++) {
                double fc = 31.0 * Math.pow(ratio, b);
                double lo = fc / halfStep;
                double hi = fc * halfStep;
                int k0 = (int) Math.floor(lo / binHz);
                int k1 = (int) Math.ceil(hi / binHz);
                double amp;
                if (k1 - k0 < 1) {
                    double pos = fc / binHz;
                    int k = Math.max(1, Math.min(N / 2 - 2, (int) pos));
                    double frac = pos - k;
                    amp = mag[k] * (1 - frac) + mag[k + 1] * frac;
                } else {
                    k0 = Math.max(1, k0);
                    k1 = Math.min(N / 2 - 1, k1);
                    double pow = 0;
                    for (int k = k0; k <= k1; k++) pow += mag[k] * mag[k];
                    amp = Math.sqrt(pow / (k1 - k0 + 1));
                }
                float db = (float) (20 * Math.log10(amp + 1e-9));
                smooth[b] = db > smooth[b] ? db : Math.max(db, smooth[b] - 1.4f);
            }
            SpectrumState.db = smooth.clone();
        }
    }

    private static void fft(double[] re, double[] im) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                double t = re[i]; re[i] = re[j]; re[j] = t;
                t = im[i]; im[i] = im[j]; im[j] = t;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = -2 * Math.PI / len;
            double wr = Math.cos(ang);
            double wi = Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                double cr = 1;
                double ci = 0;
                for (int k = 0; k < len / 2; k++) {
                    int a = i + k;
                    int b = i + k + len / 2;
                    double xr = re[b] * cr - im[b] * ci;
                    double xi = re[b] * ci + im[b] * cr;
                    re[b] = re[a] - xr;
                    im[b] = im[a] - xi;
                    re[a] += xr;
                    im[a] += xi;
                    double ncr = cr * wr - ci * wi;
                    ci = cr * wi + ci * wr;
                    cr = ncr;
                }
            }
        }
    }

    @Override
    public void onDestroy() {
        stop = true;
        if (worker != null) {
            try {
                worker.join(500);
            } catch (InterruptedException ignored) {
            }
        }
        try {
            if (record != null) {
                record.stop();
                record.release();
            }
        } catch (Throwable ignored) {
        }
        try {
            if (projection != null) projection.stop();
        } catch (Throwable ignored) {
        }
        if (SpectrumState.running) {
            SpectrumState.running = false;
            SpectrumState.status = "꺼짐";
        }
        SpectrumState.db = SpectrumState.newSilence();
        super.onDestroy();
    }
}
