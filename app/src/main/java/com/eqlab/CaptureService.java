package com.eqlab;

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

/** 다른 앱의 재생음을 캡처해서 10밴드 스펙트럼을 계산한다 (실험 C). */
public class CaptureService extends Service {
    private static final int RATE = 44100;
    private static final int N = 2048;      // FFT 크기
    private static final int HOP = 1024;    // 한 번에 읽는 프레임 수

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
        if (intent == null || "STOP".equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("cap", "캡처", NotificationManager.IMPORTANCE_LOW));
        Notification n = new Notification.Builder(this, "cap")
                .setContentTitle("EQ Lab 소리 캡처 중")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .build();
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);

        int resultCode = intent.getIntExtra("rc", 0);
        Intent data = (Intent) intent.getParcelableExtra("data");
        try {
            MediaProjectionManager mgr = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            projection = mgr.getMediaProjection(resultCode, data);
            projection.registerCallback(new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    CaptureState.status = "시스템이 캡처를 중지함";
                    Store.log(CaptureService.this, "[캡처] 시스템이 MediaProjection을 중지함");
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
            fail("캡처 시작 실패: " + t);
            return START_NOT_STICKY;
        }

        CaptureState.running = true;
        CaptureState.error = "";
        CaptureState.status = "캡처 중";
        Store.log(this, "[캡처] 시작됨");
        stop = false;
        worker = new Thread(this::loop, "capture");
        worker.start();
        return START_NOT_STICKY;
    }

    private void fail(String msg) {
        CaptureState.running = false;
        CaptureState.error = msg;
        CaptureState.status = "오류";
        Store.log(this, "[캡처] " + msg);
        stopSelf();
    }

    private void loop() {
        double[] window = new double[N];
        for (int i = 0; i < N; i++) window[i] = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / (N - 1));
        double[] mono = new double[N];
        double[] re = new double[N];
        double[] im = new double[N];
        short[] buf = new short[HOP * 2];
        float[] smooth = new float[10];
        double binHz = (double) RATE / N;
        long windowStart = System.nanoTime();
        int count = 0;
        long silentFrames = 0;

        while (!stop) {
            int read = record.read(buf, 0, buf.length);
            if (read < 0) {
                fail("읽기 오류 코드 " + read);
                return;
            }
            int frames = read / 2;
            if (frames <= 0) continue;
            System.arraycopy(mono, frames, mono, 0, N - frames);
            double sumSq = 0;
            for (int i = 0; i < frames; i++) {
                double v = (buf[2 * i] + buf[2 * i + 1]) / 65536.0;
                mono[N - frames + i] = v;
                sumSq += v * v;
            }
            double rms = 10 * Math.log10(sumSq / frames + 1e-12);
            CaptureState.rmsDb = rms;
            if (rms < -75) silentFrames += frames;
            else silentFrames = 0;
            CaptureState.silentSeconds = silentFrames / (double) RATE;

            for (int i = 0; i < N; i++) {
                re[i] = mono[i] * window[i];
                im[i] = 0;
            }
            fft(re, im);

            for (int b = 0; b < 10; b++) {
                double lo = b == 0 ? 20 : Math.sqrt((double) EffectEngine.FREQ[b - 1] * EffectEngine.FREQ[b]);
                double hi = b == 9 ? 20000 : Math.sqrt((double) EffectEngine.FREQ[b] * EffectEngine.FREQ[b + 1]);
                int k0 = Math.max(1, (int) Math.floor(lo / binHz));
                int k1 = Math.min(N / 2 - 1, Math.max(k0, (int) Math.ceil(hi / binHz)));
                double pow = 0;
                for (int k = k0; k <= k1; k++) pow += re[k] * re[k] + im[k] * im[k];
                pow /= (k1 - k0 + 1);
                double amp = Math.sqrt(pow) / (N / 4.0);
                double db = 20 * Math.log10(amp + 1e-9);
                float lvl = (float) Math.max(0, Math.min(1, (db + 90) / 70));
                smooth[b] = Math.max(lvl, smooth[b] * 0.85f);
            }
            CaptureState.levels = smooth.clone();

            count++;
            long now = System.nanoTime();
            if (now - windowStart >= 1_000_000_000L) {
                CaptureState.updatesPerSec = count;
                count = 0;
                windowStart = now;
            }
        }
    }

    /** 제자리 radix-2 FFT. */
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
        if (CaptureState.running) {
            CaptureState.running = false;
            CaptureState.status = "정지됨";
            Store.log(this, "[캡처] 정지됨");
        }
        super.onDestroy();
    }
}
