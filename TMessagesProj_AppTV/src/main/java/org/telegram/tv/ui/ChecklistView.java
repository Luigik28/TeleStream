package org.telegram.tv.ui;

import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.LinkedHashMap;

/**
 * Controller for the step-by-step checklist shown during bot session initialization.
 * Wraps the LinearLayout container already declared in the layout XML.
 */
public class ChecklistView {

    private final LinearLayout container;
    private final LinkedHashMap<String, LinearLayout> rows = new LinkedHashMap<>();

    public ChecklistView(LinearLayout container) {
        this.container = container;
    }

    public void onStep(String key, boolean completed, String label) {
        if (rows.containsKey(key)) {
            updateRow(rows.get(key), completed, label);
        } else {
            LinearLayout row = buildRow(completed, label);
            rows.put(key, row);
            container.addView(row);
        }
    }

    public void reset() {
        container.removeAllViews();
        rows.clear();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Row builders
    // ─────────────────────────────────────────────────────────────────────────

    private LinearLayout buildRow(boolean completed, String label) {
        LinearLayout row = new LinearLayout(container.getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(48));
        rowLp.setMargins(0, dp(3), 0, dp(3));
        row.setLayoutParams(rowLp);
        applyRowBg(row, completed);

        // 3dp colored left accent bar
        View accent = new View(container.getContext());
        accent.setBackgroundColor(completed ? 0xFF3A7A55 : 0xFF2CA5E0);
        row.addView(accent, new LinearLayout.LayoutParams(
            dp(3), LinearLayout.LayoutParams.MATCH_PARENT));

        // 48dp icon slot
        FrameLayout iconSlot = new FrameLayout(container.getContext());
        row.addView(iconSlot, new LinearLayout.LayoutParams(
            dp(48), LinearLayout.LayoutParams.MATCH_PARENT));
        fillIcon(iconSlot, completed);

        // Label
        TextView tv = new TextView(container.getContext());
        tv.setText(label);
        tv.setTextColor(completed ? 0xFF5A7A8A : 0xFFDDE4EA);
        tv.setTextSize(15f);
        tv.setMaxLines(1);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tvLp = new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tvLp.setMarginEnd(dp(8));
        tv.setLayoutParams(tvLp);
        row.addView(tv);

        return row;
    }

    private void updateRow(LinearLayout row, boolean completed, String label) {
        applyRowBg(row, completed);

        ((View) row.getChildAt(0)).setBackgroundColor(completed ? 0xFF3A7A55 : 0xFF2CA5E0);

        FrameLayout iconSlot = (FrameLayout) row.getChildAt(1);
        iconSlot.removeAllViews();
        fillIcon(iconSlot, completed);

        TextView tv = (TextView) row.getChildAt(2);
        tv.setText(label);
        tv.setTextColor(completed ? 0xFF5A7A8A : 0xFFDDE4EA);
    }

    private void applyRowBg(LinearLayout row, boolean completed) {
        if (!completed) {
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0x132CA5E0);
            bg.setCornerRadius(dp(6));
            row.setBackground(bg);
        } else {
            row.setBackground(null);
        }
    }

    private void fillIcon(FrameLayout slot, boolean completed) {
        if (completed) {
            TextView check = new TextView(slot.getContext());
            check.setText("✓");
            check.setTextColor(0xFF4CAF50);
            check.setTextSize(18f);
            check.setTypeface(null, Typeface.BOLD);
            check.setGravity(Gravity.CENTER);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
            lp.gravity = Gravity.CENTER;
            slot.addView(check, lp);
        } else {
            ProgressBar pb = new ProgressBar(
                slot.getContext(), null, android.R.attr.progressBarStyleSmall);
            pb.setIndeterminate(true);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(22), dp(22));
            lp.gravity = Gravity.CENTER;
            slot.addView(pb, lp);
        }
    }

    private int dp(int value) {
        return (int) (value * container.getResources().getDisplayMetrics().density + 0.5f);
    }
}
