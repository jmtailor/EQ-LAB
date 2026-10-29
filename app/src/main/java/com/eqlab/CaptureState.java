package com.eqlab;

/** 캡처 서비스가 계산한 스펙트럼과 상태를 화면과 공유하는 값들. */
final class CaptureState {
    private CaptureState() {}

    static volatile boolean running = false;
    static volatile float[] levels = new float[10];   // 0..1
    static volatile double rmsDb = -120;
    static volatile int updatesPerSec = 0;
    static volatile double silentSeconds = 0;
    static volatile String status = "정지됨";
    static volatile String error = "";
}
