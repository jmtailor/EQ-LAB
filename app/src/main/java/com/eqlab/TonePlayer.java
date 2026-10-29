package com.eqlab;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

import java.util.Random;

/** 효과가 제대로 동작하는지 확인하는 대조용 핑크 노이즈 재생기. 자체 세션 ID를 가진다. */
final class TonePlayer {
    private AudioTrack track;
    private Thread thread;
    private volatile boolean running;
    private int session = -1;

    synchronized boolean isPlaying() {
        return running;
    }

    /** 재생을 시작하고 오디오 세션 ID를 돌려준다. 이미 재생 중이면 기존 세션을 돌려준다. */
    synchronized int start() {
        if (running) return session;
        final int rate = 44100;
        int minBuf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT);
        track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build())
                .setBufferSizeInBytes(Math.max(minBuf * 2, 16384))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
        session = track.getAudioSessionId();
        running = true;
        final AudioTrack t = track;
        thread = new Thread(() -> {
            Random rnd = new Random();
            double b0 = 0, b1 = 0, b2 = 0, b3 = 0, b4 = 0, b5 = 0, b6 = 0;
            short[] blk = new short[4096];
            t.play();
            while (running) {
                for (int i = 0; i < blk.length; i += 2) {
                    double white = rnd.nextDouble() * 2 - 1;
                    b0 = 0.99886 * b0 + white * 0.0555179;
                    b1 = 0.99332 * b1 + white * 0.0750759;
                    b2 = 0.96900 * b2 + white * 0.1538520;
                    b3 = 0.86650 * b3 + white * 0.3104856;
                    b4 = 0.55000 * b4 + white * 0.5329522;
                    b5 = -0.7616 * b5 - white * 0.0168980;
                    double pink = b0 + b1 + b2 + b3 + b4 + b5 + b6 + white * 0.5362;
                    b6 = white * 0.115926;
                    short s = (short) (pink * 0.06 * 32767);
                    blk[i] = s;
                    blk[i + 1] = s;
                }
                t.write(blk, 0, blk.length);
            }
        }, "tone");
        thread.start();
        return session;
    }

    synchronized void stop() {
        running = false;
        if (thread != null) {
            try {
                thread.join(500);
            } catch (InterruptedException ignored) {
            }
            thread = null;
        }
        if (track != null) {
            try {
                track.stop();
            } catch (Throwable ignored) {
            }
            track.release();
            track = null;
        }
        session = -1;
    }
}
