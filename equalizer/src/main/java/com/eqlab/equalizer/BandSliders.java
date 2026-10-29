package com.eqlab.equalizer;

import android.content.Context;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;

/** 10개 대역 슬라이더를 담은 좌우로 미는 영역. */
final class BandSliders extends HorizontalScrollView {
    private final BandColumn[] columns = new BandColumn[10];

    BandSliders(Context c, BandColumn.Callback cb) {
        super(c);
        setHorizontalScrollBarEnabled(false);
        setOverScrollMode(OVER_SCROLL_NEVER);
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < 10; i++) {
            columns[i] = new BandColumn(c, i, cb);
            row.addView(columns[i], new LinearLayout.LayoutParams(Theme.dp(c, 52), BandColumn.heightPx(c)));
        }
        addView(row);
    }

    void set(int[] gains, boolean on, int active) {
        for (int i = 0; i < 10; i++) columns[i].set(gains[i], on, i == active);
    }
}
