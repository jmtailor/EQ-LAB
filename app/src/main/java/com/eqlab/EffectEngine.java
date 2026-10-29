package com.eqlab;

import android.media.audiofx.DynamicsProcessing;
import android.media.audiofx.Equalizer;

/** 오디오 세션에 10밴드 EQ를 거는 엔진. Equalizer와 DynamicsProcessing 두 방식을 지원한다. */
final class EffectEngine {
    static final int[] FREQ = {31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000};
    static final String[] LABEL = {"31", "62", "125", "250", "500", "1k", "2k", "4k", "8k", "16k"};

    private Equalizer eq;
    private DynamicsProcessing dp;
    private float[] gains = new float[10];

    boolean isAttached() {
        return eq != null || dp != null;
    }

    /** 세션에 효과를 붙이고 결과 설명을 돌려준다. 실패하면 예외가 난다. */
    String attach(int session, boolean useDp, float[] g) {
        release();
        gains = g.clone();
        StringBuilder sb = new StringBuilder();
        if (useDp) {
            DynamicsProcessing.Config cfg = new DynamicsProcessing.Config.Builder(
                    DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                    2, true, 10, false, 0, false, 0, false).build();
            dp = new DynamicsProcessing(0, session, cfg);
            applyDp();
            dp.setEnabled(true);
            sb.append("DynamicsProcessing 생성됨, 세션=").append(session)
                    .append(", 제어권=").append(dp.hasControl())
                    .append(", 활성=").append(dp.getEnabled());
        } else {
            eq = new Equalizer(0, session);
            eq.setEnabled(true);
            applyEq();
            sb.append("Equalizer 생성됨, 세션=").append(session)
                    .append(", 밴드수=").append(eq.getNumberOfBands())
                    .append(", 제어권=").append(eq.hasControl())
                    .append(", 활성=").append(eq.getEnabled());
        }
        return sb.toString();
    }

    void setGains(float[] g) {
        gains = g.clone();
        if (dp != null) applyDp();
        if (eq != null) applyEq();
    }

    private void applyDp() {
        dp.setPreEqAllChannelsTo(new DynamicsProcessing.Eq(true, true, 10));
        for (int i = 0; i < 10; i++) {
            dp.setPreEqBandAllChannelsTo(i, new DynamicsProcessing.EqBand(true, FREQ[i], gains[i]));
        }
    }

    private void applyEq() {
        short[] range = eq.getBandLevelRange();
        int n = eq.getNumberOfBands();
        for (short b = 0; b < n; b++) {
            double hz = eq.getCenterFreq(b) / 1000.0;
            int best = 0;
            double bestDist = Double.MAX_VALUE;
            for (int i = 0; i < 10; i++) {
                double d = Math.abs(Math.log(hz) - Math.log(FREQ[i]));
                if (d < bestDist) {
                    bestDist = d;
                    best = i;
                }
            }
            int level = Math.round(gains[best] * 100f);
            level = Math.max(range[0], Math.min(range[1], level));
            eq.setBandLevel(b, (short) level);
        }
    }

    /** 현재 Equalizer의 밴드 구성을 보여주는 진단 문자열. */
    String describe() {
        if (eq == null) return "";
        StringBuilder sb = new StringBuilder("Equalizer 밴드: ");
        for (short b = 0; b < eq.getNumberOfBands(); b++) {
            sb.append(eq.getCenterFreq(b) / 1000).append("Hz ");
        }
        return sb.toString();
    }

    void release() {
        try {
            if (dp != null) dp.release();
        } catch (Throwable ignored) {
        }
        try {
            if (eq != null) eq.release();
        } catch (Throwable ignored) {
        }
        dp = null;
        eq = null;
    }
}
