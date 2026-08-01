package org.telegram.tv.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.tgnet.TLRPC;
import org.telegram.tv.model.StreamEvent;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;

import java.util.ArrayList;
import java.util.List;

/**
 * Controls the collapsible navigation rail:
 * expand/collapse animation, nav item building, avatar display, and selection state.
 */
public class NavDrawerController {

    public interface Listener {
        void onCategorySelected(String category);
        void onSettingsSelected();
        /** Called ~210 ms after close animation ends — refocus content from here. */
        void onDrawerClosed();
    }

    private final Context context;
    private final LinearLayout navRail;
    private final LinearLayout navRailItems;
    private final View drawerScrim;
    private final FrameLayout contentArea;
    private final LinearLayout navSettings;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private GradientDrawable panelBg;
    private ValueAnimator drawerAnim;
    private boolean drawerOpen = false;
    private boolean settingsSelected = false;

    private View selectedNavView;
    private String selectedCategory;

    public NavDrawerController(Context context,
            LinearLayout navRail, LinearLayout navRailItems,
            View drawerScrim, FrameLayout contentArea, LinearLayout navSettings,
            Listener listener) {
        this.context = context;
        this.navRail = navRail;
        this.navRailItems = navRailItems;
        this.drawerScrim = drawerScrim;
        this.contentArea = contentArea;
        this.navSettings = navSettings;
        this.listener = listener;
        setup();
    }

    // ─── Public API ────────────────────────────────────────────────────────────

    public boolean isOpen() { return drawerOpen; }
    public String getSelectedCategory() { return selectedCategory; }

    public void open() {
        if (drawerOpen) return;
        drawerOpen = true;
        contentArea.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        drawerScrim.setVisibility(View.VISIBLE);
        drawerScrim.animate().cancel();
        drawerScrim.animate().alpha(1f).setDuration(260)
            .setInterpolator(new DecelerateInterpolator()).start();

        if (drawerAnim != null) drawerAnim.cancel();
        Rect cur = navRail.getClipBounds();
        int startW = cur != null ? cur.right : dp(72);
        drawerAnim = ValueAnimator.ofInt(startW, dp(320));
        drawerAnim.setDuration(260);
        drawerAnim.setInterpolator(new DecelerateInterpolator());
        drawerAnim.addUpdateListener(va -> {
            int w = (int) va.getAnimatedValue();
            navRail.setClipBounds(new Rect(0, 0, w, 10000));
            float frac = (float)(w - dp(72)) / (float)(dp(320) - dp(72));
            panelBg.setAlpha((int)(0xFF * Math.max(0f, Math.min(1f, frac))));
        });
        drawerAnim.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator a) {
                navRail.setClipBounds(null);
                panelBg.setAlpha(0xFF);
            }
        });
        drawerAnim.start();

        handler.postDelayed(() -> {
            View toFocus = selectedNavView != null ? selectedNavView : navRailItems.getChildAt(0);
            if (toFocus != null) toFocus.requestFocus();
        }, 260);
    }

    public void close() {
        if (!drawerOpen) return;
        drawerOpen = false;

        if (drawerAnim != null) drawerAnim.cancel();
        Rect cur = navRail.getClipBounds();
        int startW = cur != null ? cur.right : dp(320);
        drawerAnim = ValueAnimator.ofInt(startW, dp(72));
        drawerAnim.setDuration(200);
        drawerAnim.setInterpolator(new AccelerateInterpolator());
        drawerAnim.addUpdateListener(va -> {
            int w = (int) va.getAnimatedValue();
            navRail.setClipBounds(new Rect(0, 0, w, 10000));
            float frac = (float)(w - dp(72)) / (float)(dp(320) - dp(72));
            panelBg.setAlpha((int)(0xFF * Math.max(0f, Math.min(1f, frac))));
        });
        drawerAnim.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator a) { panelBg.setAlpha(0); }
        });
        drawerAnim.start();

        drawerScrim.animate().cancel();
        drawerScrim.animate().alpha(0f).setDuration(200)
            .withEndAction(() -> drawerScrim.setVisibility(View.INVISIBLE)).start();
        contentArea.setDescendantFocusability(ViewGroup.FOCUS_BEFORE_DESCENDANTS);
        handler.postDelayed(listener::onDrawerClosed, 210);
    }

    public void buildNavItems(List<StreamEvent> events) {
        navRailItems.removeAllViews();
        selectedNavView = null;
        selectedCategory = null;
        settingsSelected = false;

        List<String> categories = new ArrayList<>();
        for (StreamEvent e : events) {
            if (!categories.contains(e.category)) categories.add(e.category);
        }
        for (String cat : categories) {
            navRailItems.addView(createNavItem(cat));
        }
        if (!categories.isEmpty()) {
            selectedCategory = categories.get(0);
            selectedNavView = navRailItems.getChildAt(0);
            applySelection(selectedNavView, true);
        }
    }

    public void setupUserAvatar(int account, TLRPC.User user,
                                 FrameLayout avatarContainer,
                                 TextView nameView, TextView usernameView) {
        if (user == null) return;
        String firstName = user.first_name != null ? user.first_name : "";
        String lastName  = user.last_name  != null ? user.last_name  : "";
        String name = (firstName + " " + lastName).trim();
        String username = (user.username != null && !user.username.isEmpty())
            ? "@" + user.username : "";
        nameView.setText(name.isEmpty() ? "Utente" : name);
        usernameView.setText(username);

        AvatarDrawable fallback = new AvatarDrawable();
        fallback.setInfo(account, user);
        BackupImageView iv = new BackupImageView(context);
        iv.setRoundRadius(dp(20));
        iv.setForUserOrChat(user, fallback);
        avatarContainer.removeAllViews();
        avatarContainer.setBackground(null);
        avatarContainer.addView(iv, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
    }

    // ─── Private setup + item building ────────────────────────────────────────

    private void setup() {
        panelBg = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
            new int[]{0xFF0D1520, 0xFF182636});
        panelBg.setAlpha(0);
        navRail.setBackground(panelBg);
        navRail.setClipBounds(new Rect(0, 0, dp(72), 10000));

        drawerScrim.setBackgroundColor(0x88000000);
        drawerScrim.setOnClickListener(v -> close());

        navSettings.setOnFocusChangeListener((v, hasFocus) ->
            applyItemFocus(v, hasFocus, settingsSelected));
        navSettings.setOnClickListener(v -> {
            if (selectedNavView != null) { applySelection(selectedNavView, false); selectedNavView = null; }
            settingsSelected = true;
            applySelection(navSettings, true);
            listener.onSettingsSelected();
            close();
        });
    }

    private View createNavItem(String category) {
        LinearLayout item = new LinearLayout(context);
        item.setOrientation(LinearLayout.HORIZONTAL);
        item.setGravity(Gravity.CENTER_VERTICAL);
        item.setFocusable(true);
        item.setClickable(true);
        item.setFocusableInTouchMode(false);
        item.setTag(category);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(56));
        lp.setMargins(0, dp(2), 0, dp(2));
        item.setLayoutParams(lp);

        FrameLayout iconSlot = new FrameLayout(context);
        ImageView iconView = new ImageView(context);
        iconView.setImageResource(SportCategoryResolver.iconRes(category));
        iconView.setColorFilter(0xFFCAC4D0, android.graphics.PorterDuff.Mode.SRC_IN);
        iconView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        FrameLayout.LayoutParams iconLp = new FrameLayout.LayoutParams(dp(24), dp(24));
        iconLp.gravity = Gravity.CENTER;
        iconSlot.addView(iconView, iconLp);
        item.addView(iconSlot, new LinearLayout.LayoutParams(
            dp(72), LinearLayout.LayoutParams.MATCH_PARENT));

        TextView tv = new TextView(context);
        tv.setText(category);
        tv.setTextColor(0xFFE6E1E5);
        tv.setTextSize(14f);
        tv.setMaxLines(1);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tvLp = new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tvLp.setMarginEnd(dp(16));
        tv.setLayoutParams(tvLp);
        item.addView(tv);

        item.setOnFocusChangeListener((v, hasFocus) ->
            applyItemFocus(v, hasFocus, v == selectedNavView));
        item.setOnClickListener(v -> {
            if (selectedNavView != null) applySelection(selectedNavView, false);
            settingsSelected = false;
            applySelection(navSettings, false);
            selectedNavView = v;
            selectedCategory = category;
            applySelection(v, true);
            listener.onCategorySelected(category);
            close();
        });

        return item;
    }

    private void applyItemFocus(View v, boolean hasFocus, boolean isSelected) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(28));
        if (hasFocus && isSelected) {
            bg.setColor(0x404FC3F7);
        } else if (hasFocus) {
            bg.setColor(0x1FFFFFFF);
        } else if (isSelected) {
            applySelection(v, true);
            return;
        } else {
            v.setBackground(null);
            return;
        }
        v.setBackground(bg);
    }

    private void applySelection(View v, boolean selected) {
        if (selected) {
            GradientDrawable sel = new GradientDrawable();
            sel.setCornerRadius(dp(28));
            sel.setColor(0x264FC3F7);
            v.setBackground(sel);
        } else {
            v.setBackground(null);
        }
    }

    private int dp(int value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f);
    }
}
