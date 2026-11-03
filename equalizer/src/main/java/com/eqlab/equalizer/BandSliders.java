package com.eqlab.equalizer;

import android.content.Context;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;

/** 전체 대역(기본 + 추가한 점) 슬라이더를 담은 좌우로 미는 영역. 점이 늘거나 줄면 열을 다시 만든다. */
final class BandSliders extends HorizontalScrollView {
    private static final int COLUMN_DP = 52;

    private final Context ctx;
    private final BandColumn.Callback callback;
    private final LinearLayout row;
    private BandColumn[] columns = new BandColumn[0];

    BandSliders(Context c, BandColumn.Callback cb) {
        super(c);
        ctx = c;
        callback = cb;
        setHorizontalScrollBarEnabled(false);
        setOverScrollMode(OVER_SCROLL_NEVER);
        row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        addView(row);
    }

    void set(EqEngine.Model m, boolean on, int active) {
        if (columns.length != m.n) {
            row.removeAllViews();
            columns = new BandColumn[m.n];
            for (int i = 0; i < m.n; i++) {
                columns[i] = new BandColumn(ctx, i, callback);
                row.addView(columns[i], new LinearLayout.LayoutParams(Theme.dp(ctx, COLUMN_DP), BandColumn.heightPx(ctx)));
            }
        }
        for (int i = 0; i < m.n; i++) {
            columns[i].set(m.gain[i], on, i == active, m.label(i), m.extra[i]);
        }
    }

    /** 새로 추가한 점의 슬라이더가 화면 가운데쯤 보이도록 민다. */
    void scrollToBand(int band) {
        final int colPx = Theme.dp(ctx, COLUMN_DP);
        post(() -> smoothScrollTo(Math.max(0, band * colPx - (getWidth() - colPx) / 2), 0));
    }
}
