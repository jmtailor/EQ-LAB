package com.eqlab.equalizer;

import android.content.Context;
import android.os.VibrationEffect;
import android.os.Vibrator;

/** 슬라이더 단계, 중앙 통과, 프리셋 선택 등에 주는 짧은 햅틱 반응. */
final class Haptics {
    private Haptics() {}

    private static Vibrator vibrator;

    static void init(Context c) {
        vibrator = c.getApplicationContext().getSystemService(Vibrator.class);
    }

    private static void play(int effect) {
        try {
            if (vibrator != null && vibrator.hasVibrator()) {
                vibrator.vibrate(VibrationEffect.createPredefined(effect));
            }
        } catch (Throwable ignored) {
        }
    }

    /** 슬라이더가 1dB 단계를 넘을 때. */
    static void tick() {
        play(VibrationEffect.EFFECT_TICK);
    }

    /** 0dB 중앙을 지날 때, 토글 전환. */
    static void click() {
        play(VibrationEffect.EFFECT_CLICK);
    }

    /** 프리셋 선택. */
    static void heavy() {
        play(VibrationEffect.EFFECT_HEAVY_CLICK);
    }
}
