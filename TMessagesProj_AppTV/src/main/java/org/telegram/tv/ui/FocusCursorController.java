package org.telegram.tv.ui;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;

/** Animated overlay that slides to highlight the focused event row. */
public class FocusCursorController {

    private View cursor;
    private final Context context;
    private final FrameLayout parent;

    public FocusCursorController(Context context, FrameLayout parent) {
        this.context = context;
        this.parent = parent;
    }

    /** Hides the cursor and resets its position. Call when the events list is rebuilt. */
    public void reset() {
        if (cursor == null) return;
        cursor.animate().cancel();
        cursor.setAlpha(0f);
        cursor.setY(0f);
    }

    /** Slides the cursor to overlay the given row view. */
    public void moveTo(View row) {
        ensureCursor();
        row.post(() -> {
            if (cursor == null) return;
            int rowH = row.getHeight();
            int rowY = row.getTop();

            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) cursor.getLayoutParams();
            lp.height = rowH;
            cursor.setLayoutParams(lp);

            boolean first = cursor.getAlpha() == 0f;
            if (first) {
                cursor.setY(rowY);
                cursor.animate().alpha(1f).setDuration(120).start();
            } else {
                cursor.animate().y(rowY).setDuration(180)
                    .setInterpolator(new DecelerateInterpolator()).start();
            }
        });
    }

    private void ensureCursor() {
        if (cursor != null) return;

        GradientDrawable gd = new GradientDrawable();
        gd.setColor(0x1A2CA5E0);
        gd.setCornerRadius(dp(10));
        gd.setStroke(dp(2), 0xFF2CA5E0);

        cursor = new View(context);
        cursor.setBackground(gd);
        cursor.setClickable(false);
        cursor.setFocusable(false);
        cursor.setAlpha(0f);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, dp(60));
        lp.leftMargin = dp(88);
        parent.addView(cursor, lp);
    }

    private int dp(int value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f);
    }
}
