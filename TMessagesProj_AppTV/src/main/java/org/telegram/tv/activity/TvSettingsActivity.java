package org.telegram.tv.activity;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.tv.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.tv.login.TvLaunchActivity;

public class TvSettingsActivity extends Activity {

    private final int account = UserConfig.selectedAccount;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0D1117);
        root.setPadding(dp(80), dp(48), dp(80), dp(48));

        // Title
        TextView title = new TextView(this);
        title.setText(LocaleController.getString(R.string.Settings));
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(26f);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // Accent separator
        View sep = new View(this);
        LinearLayout.LayoutParams sepLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(2));
        sepLp.topMargin = dp(12);
        sepLp.bottomMargin = dp(24);
        sep.setLayoutParams(sepLp);
        sep.setBackgroundColor(0xFF2CA5E0);
        root.addView(sep);

        // Info account row
        LinearLayout infoRow = makeRow();
        infoRow.setOnFocusChangeListener((v, hasFocus) -> applyRowFocus(v, hasFocus, 0xFF2CA5E0));
        infoRow.setOnClickListener(v -> showAccountInfo());
        TextView infoText = new TextView(this);
        infoText.setText("ℹ  " + getString(R.string.tv_account_info));
        infoText.setTextColor(0xFFFFFFFF);
        infoText.setTextSize(20f);
        infoRow.addView(infoText, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(infoRow);

        // Separator before destructive logout
        View sep2 = new View(this);
        LinearLayout.LayoutParams sep2Lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
        sep2Lp.topMargin = dp(8);
        sep2Lp.bottomMargin = dp(8);
        sep2.setLayoutParams(sep2Lp);
        sep2.setBackgroundColor(0xFF1E2A35);
        root.addView(sep2);

        // Log out row
        LinearLayout logoutRow = makeRow();
        logoutRow.setOnFocusChangeListener((v, hasFocus) -> applyRowFocus(v, hasFocus, 0xFFE74C3C));
        logoutRow.setOnClickListener(v -> confirmLogout());
        TextView logoutText = new TextView(this);
        logoutText.setText(LocaleController.getString(R.string.LogOut));
        logoutText.setTextColor(0xFFE74C3C);
        logoutText.setTextSize(20f);
        logoutRow.addView(logoutText, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(logoutRow);

        setContentView(root);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            finish();
            overridePendingTransition(0, R.anim.tv_slide_out_right);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private LinearLayout makeRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setFocusable(true);
        row.setClickable(true);
        row.setFocusableInTouchMode(false);
        row.setPadding(dp(16), dp(18), dp(16), dp(18));
        row.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return row;
    }

    private void confirmLogout() {
        new AlertDialog.Builder(this)
            .setTitle(LocaleController.getString(R.string.LogOutTitle))
            .setMessage(getString(R.string.tv_logout_confirm))
            .setPositiveButton(LocaleController.getString(R.string.OK), (d, w) -> doLogout())
            .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
            .show();
    }

    private void doLogout() {
        MessagesController.getInstance(account).performLogout(1);
        Intent intent = new Intent(this, TvLaunchActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }

    private void applyRowFocus(View v, boolean hasFocus, int color) {
        if (hasFocus) {
            int r = (color >> 16) & 0xFF, g = (color >> 8) & 0xFF, b = color & 0xFF;
            android.graphics.drawable.GradientDrawable hl = new android.graphics.drawable.GradientDrawable();
            hl.setColor(Color.argb(24, r, g, b));
            hl.setCornerRadius(dp(10));
            hl.setStroke(dp(2), color);
            v.setBackground(hl);
        } else {
            v.setBackground(null);
        }
    }

    private void showAccountInfo() {
        TLRPC.User user = UserConfig.getInstance(account).getCurrentUser();
        if (user == null) return;
        String name = ((user.first_name != null ? user.first_name : "")
            + " " + (user.last_name != null ? user.last_name : "")).trim();
        String phone = user.phone != null ? "+" + user.phone : "";
        String username = (user.username != null && !user.username.isEmpty()) ? "@" + user.username : "";
        StringBuilder info = new StringBuilder(name);
        if (!username.isEmpty()) info.append("\n").append(username);
        if (!phone.isEmpty()) info.append("\n").append(phone);
        new AlertDialog.Builder(this)
            .setTitle(getString(R.string.tv_account_info))
            .setMessage(info.toString())
            .setPositiveButton(LocaleController.getString(R.string.OK), null)
            .show();
    }

    private int dp(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density + 0.5f);
    }
}
