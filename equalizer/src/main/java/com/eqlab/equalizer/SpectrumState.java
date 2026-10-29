package com.eqlab.equalizer;

/** 캡처 서비스가 계산한 32개 막대의 레벨(dB)과 상태. 화면이 읽어 간다. */
final class SpectrumState {
    private SpectrumState() {}

    static final int BARS = 32;

    static volatile float[] db = newSilence();
    static volatile boolean running = false;
    static volatile String status = "꺼짐";
    static volatile String error = "";

    static float[] newSilence() {
        float[] a = new float[BARS];
        for (int i = 0; i < BARS; i++) a[i] = -120f;
        return a;
    }
}
