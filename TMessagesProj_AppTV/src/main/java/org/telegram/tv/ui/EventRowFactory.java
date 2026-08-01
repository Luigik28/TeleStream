package org.telegram.tv.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.tv.bot.MessageParser;
import org.telegram.tv.model.StreamEvent;

/** Builds event list rows and the football dual-logo layout. */
public class EventRowFactory {

    public interface OnRowFocused {
        void onFocused(View row);
    }

    private final Context context;
    private final TeamLogoLoader logoLoader;

    public EventRowFactory(Context context, TeamLogoLoader logoLoader) {
        this.context = context;
        this.logoLoader = logoLoader;
    }

    public View createRow(StreamEvent event, int rowIndex,
                           OnRowFocused onFocused, View.OnClickListener onClicked) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setFocusable(true);
        row.setClickable(true);
        row.setFocusableInTouchMode(false);
        row.setGravity(Gravity.CENTER_VERTICAL);

        if (rowIndex % 2 == 1) row.setBackgroundColor(0x0AFFFFFF);

        row.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) onFocused.onFocused(v);
        });
        row.setOnClickListener(onClicked);

        // Left category-color strip (flush with screen edge)
        View strip = new View(context);
        strip.setBackgroundColor(SportCategoryResolver.color(event.category));
        row.addView(strip, new LinearLayout.LayoutParams(
            dp(5), LinearLayout.LayoutParams.MATCH_PARENT));

        // Inner content
        LinearLayout inner = new LinearLayout(context);
        inner.setOrientation(LinearLayout.HORIZONTAL);
        inner.setGravity(Gravity.CENTER_VERTICAL);
        inner.setLayoutParams(new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        inner.setPadding(dp(16), dp(14), dp(20), dp(14));

        String[] teams = MessageParser.parseTeams(event.eventName);
        if (teams != null) {
            inner.addView(createFootballContent(teams));
        } else {
            TextView nameView = new TextView(context);
            nameView.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            nameView.setText(event.eventName);
            nameView.setTextColor(0xFFE8EDF0);
            nameView.setTextSize(20f);
            nameView.setMaxLines(1);
            nameView.setEllipsize(TextUtils.TruncateAt.END);
            nameView.setGravity(Gravity.CENTER_VERTICAL);
            inner.addView(nameView);
        }

        // Time badge — pill with subtle blue border
        TextView timeView = new TextView(context);
        LinearLayout.LayoutParams timeLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        timeLp.setMarginStart(dp(16));
        timeView.setLayoutParams(timeLp);
        timeView.setText(event.time);
        timeView.setTextColor(0xFF4FC3F7);
        timeView.setTextSize(18f);
        timeView.setTypeface(null, Typeface.BOLD);
        timeView.setGravity(Gravity.CENTER_VERTICAL);
        timeView.setPadding(dp(12), dp(5), dp(12), dp(5));
        GradientDrawable badge = new GradientDrawable();
        badge.setColor(0x221A2A40);
        badge.setCornerRadius(dp(6));
        badge.setStroke(dp(1), 0x334FC3F7);
        timeView.setBackground(badge);
        inner.addView(timeView);

        row.addView(inner);
        return row;
    }

    public View createDivider() {
        View div = new View(context);
        div.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 1));
        div.setBackgroundColor(0xFF1E2A35);
        return div;
    }

    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Horizontal football layout: [logoA][nameA(weight=1)]  vs  [nameB(weight=1)][logoB]
     * Both name columns share available width equally so neither overflows.
     */
    private View createFootballContent(String[] teams) {
        LinearLayout container = new LinearLayout(context);
        container.setLayoutParams(new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        container.setOrientation(LinearLayout.HORIZONTAL);
        container.setGravity(Gravity.CENTER_VERTICAL);

        ImageView logoA = makeLogoView();
        TextView nameA = makeTeamNameView(teams[0]);
        nameA.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);

        TextView vsView = new TextView(context);
        vsView.setText("vs");
        vsView.setTextColor(0xFF5A7A99);
        vsView.setTextSize(13f);
        vsView.setPadding(dp(10), 0, dp(10), 0);
        vsView.setGravity(Gravity.CENTER);

        TextView nameB = makeTeamNameView(teams[1]);
        ImageView logoB = makeLogoView();

        container.addView(logoA);
        container.addView(nameA);
        container.addView(vsView);
        container.addView(nameB);
        container.addView(logoB);

        logoLoader.load(teams[0], logoA);
        logoLoader.load(teams[1], logoB);
        return container;
    }

    private ImageView makeLogoView() {
        ImageView iv = new ImageView(context);
        iv.setLayoutParams(new LinearLayout.LayoutParams(dp(44), dp(44)));
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setPadding(dp(2), dp(2), dp(2), dp(2));
        return iv;
    }

    private TextView makeTeamNameView(String name) {
        TextView tv = new TextView(context);
        tv.setLayoutParams(new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        tv.setText(name);
        tv.setTextColor(0xFFE8EDF0);
        tv.setTextSize(20f);
        tv.setMaxLines(1);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        tv.setGravity(Gravity.CENTER_VERTICAL);
        tv.setPadding(dp(6), 0, dp(6), 0);
        return tv;
    }

    private int dp(int value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f);
    }
}
