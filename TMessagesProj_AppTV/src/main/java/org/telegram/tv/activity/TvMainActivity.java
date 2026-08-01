package org.telegram.tv.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.tv.R;
import org.telegram.tv.bot.BotSession;
import org.telegram.tv.model.StreamEvent;
import org.telegram.tv.ui.ChecklistView;
import org.telegram.tv.ui.EventRowFactory;
import org.telegram.tv.ui.FocusCursorController;
import org.telegram.tv.ui.NavDrawerController;
import org.telegram.tv.ui.TeamLogoLoader;

import java.util.ArrayList;
import java.util.List;

public class TvMainActivity extends Activity
        implements NotificationCenter.NotificationCenterDelegate,
                   BotSession.Listener,
                   NavDrawerController.Listener {

    private static final String BOT_USERNAME = "CherryStreaming_cbot";

    private final int account = UserConfig.selectedAccount;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private BotSession session;
    private TeamLogoLoader logoLoader;

    private ChecklistView checklistView;
    private NavDrawerController navDrawer;
    private FocusCursorController focusCursor;
    private EventRowFactory rowFactory;

    private LinearLayout loadingContainer;
    private TextView statusText;
    private TextView instructionText;
    private TextView retryButton;

    private FrameLayout eventsContainer;
    private LinearLayout eventsList;
    private FrameLayout eventsListFrame;
    private LinearLayout settingsPanel;

    private FrameLayout userAvatar;
    private TextView userNameView;
    private TextView userUsernameView;

    private List<StreamEvent> allEvents = new ArrayList<>();

    private boolean waitingForEvents = false;
    private Runnable dotsRunnable;
    private Runnable waitingTimeoutRunnable;
    private Runnable pollRunnable;

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_tv_main);

        loadingContainer = findViewById(R.id.loading_container);
        statusText       = findViewById(R.id.status_text);
        instructionText  = findViewById(R.id.instruction_text);
        retryButton      = findViewById(R.id.retry_button);
        eventsContainer  = findViewById(R.id.events_container);
        eventsList       = findViewById(R.id.events_list);
        eventsListFrame  = findViewById(R.id.events_list_frame);
        settingsPanel    = findViewById(R.id.settings_panel);
        userAvatar       = findViewById(R.id.user_avatar);
        userNameView     = findViewById(R.id.user_name);
        userUsernameView = findViewById(R.id.user_username);

        logoLoader   = new TeamLogoLoader(getResources().getDisplayMetrics().density);
        rowFactory   = new EventRowFactory(this, logoLoader);
        checklistView = new ChecklistView(findViewById(R.id.checklist_container));
        focusCursor  = new FocusCursorController(this, eventsListFrame);
        navDrawer    = new NavDrawerController(this,
                            findViewById(R.id.nav_rail),
                            findViewById(R.id.nav_rail_items),
                            findViewById(R.id.drawer_scrim),
                            findViewById(R.id.content_area),
                            findViewById(R.id.nav_settings),
                            this);

        navDrawer.setupUserAvatar(account, UserConfig.getInstance(account).getCurrentUser(),
            userAvatar, userNameView, userUsernameView);

        retryButton.setOnFocusChangeListener((v, hasFocus) ->
            v.setBackgroundColor(hasFocus ? 0xFF1E3A5F : 0x00000000));
        retryButton.setOnClickListener(v -> {
            resetToLoadingState();
            session.restart();
        });

        buildSettingsPanel();

        session = new BotSession(account, BOT_USERNAME, this);
        NotificationCenter.getInstance(account).addObserver(
            this, NotificationCenter.didReceiveNewMessages);

        android.util.Log.d("TvMain", "onCreate account=" + account
            + " activated=" + UserConfig.getInstance(account).isClientActivated());

        session.start();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cancelDotsAnimation();
        cancelWaitingTimeout();
        cancelPolling();
        logoLoader.shutdown();
        NotificationCenter.getInstance(account).removeObserver(
            this, NotificationCenter.didReceiveNewMessages);
    }

    @Override
    protected void onPause() {
        super.onPause();
        NotificationCenter.getInstance(account).removeObserver(
            this, NotificationCenter.didReceiveNewMessages);
    }

    @Override
    protected void onResume() {
        super.onResume();
        NotificationCenter.getInstance(account).removeObserver(this, NotificationCenter.didReceiveNewMessages);
        NotificationCenter.getInstance(account).addObserver(this, NotificationCenter.didReceiveNewMessages);
        if (waitingForEvents) {
            android.util.Log.d("TvMain", "onResume: still waiting — triggering immediate poll");
            session.pollForNewMessage();
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                && !navDrawer.isOpen() && eventsContainer.getVisibility() == View.VISIBLE) {
            navDrawer.open();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && navDrawer.isOpen()) {
            navDrawer.close();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // NotificationCenter
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public void didReceivedNotification(int id, int acc, Object... args) {
        if (id != NotificationCenter.didReceiveNewMessages) return;
        long dialogId = (Long) args[0];
        if (dialogId != session.getDialogId()) return;

        ArrayList<MessageObject> msgs = (ArrayList<MessageObject>) args[1];
        for (MessageObject msg : msgs) {
            if (msg.isOut() || msg.messageOwner == null) continue;
            if (session.handleMessage(msg)) return;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // BotSession.Listener
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public void onStatus(String message) {
        android.util.Log.d("TvMain", "status: " + message);
        statusText.setText(message);
    }

    @Override
    public void onStep(String key, boolean completed, String label) {
        checklistView.onStep(key, completed, label);
    }

    @Override
    public void onWaitingForPhone() {
        waitingForEvents = true;
        loadingContainer.setVisibility(View.VISIBLE);
        eventsContainer.setVisibility(View.GONE);
        retryButton.setVisibility(View.GONE);
        instructionText.setVisibility(View.VISIBLE);
        instructionText.setText("Apri la mini app\ndal tuo smartphone");
        startDotsAnimation("In attesa del calendario eventi");
        scheduleWaitingTimeout();
        startPolling();
    }

    @Override
    public void onChannelsProcessed() {
        eventsContainer.setVisibility(View.GONE);
        loadingContainer.setVisibility(View.VISIBLE);
        onStep("wait", false, "Attendo risposta dal bot…");
        instructionText.setVisibility(View.VISIBLE);
        instructionText.setText("Canali verificati.\nApri la mini app\ndal tuo smartphone per completare.");
        startDotsAnimation("In attesa del calendario eventi");
        session.pollForNewMessage();
    }

    @Override
    public void onEventsReady(MessageObject msg, List<StreamEvent> events) {
        showEventsTable(events);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // NavDrawerController.Listener
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public void onCategorySelected(String category) {
        showEventsPanel();
        filterAndShowEvents(category);
    }

    @Override
    public void onSettingsSelected() {
        showSettingsPanel();
    }

    @Override
    public void onDrawerClosed() {
        refocusContentPanel();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Waiting state
    // ─────────────────────────────────────────────────────────────────────────

    private void startDotsAnimation(String base) {
        cancelDotsAnimation();
        final String[] frames = {base, base + ".", base + "..", base + "..."};
        final int[] idx = {0};
        dotsRunnable = new Runnable() {
            @Override public void run() {
                statusText.setText(frames[idx[0]++ % frames.length]);
                handler.postDelayed(this, 600);
            }
        };
        handler.post(dotsRunnable);
    }

    private void cancelDotsAnimation() {
        if (dotsRunnable != null) { handler.removeCallbacks(dotsRunnable); dotsRunnable = null; }
    }

    private void scheduleWaitingTimeout() {
        cancelWaitingTimeout();
        waitingTimeoutRunnable = () -> {
            if (!waitingForEvents) return;
            cancelDotsAnimation();
            statusText.setText("Nessuna risposta ricevuta.");
            instructionText.setText(
                "Verifica che la mini app\nsia aperta sul tuo smartphone,\npoi riprova.");
            retryButton.setVisibility(View.VISIBLE);
            retryButton.requestFocus();
        };
        handler.postDelayed(waitingTimeoutRunnable, 90_000);
    }

    private void cancelWaitingTimeout() {
        if (waitingTimeoutRunnable != null) {
            handler.removeCallbacks(waitingTimeoutRunnable); waitingTimeoutRunnable = null;
        }
    }

    private void startPolling() {
        cancelPolling();
        pollRunnable = new Runnable() {
            @Override public void run() {
                if (!waitingForEvents) return;
                session.pollForNewMessage();
                handler.postDelayed(this, 3000);
            }
        };
        handler.postDelayed(pollRunnable, 2000);
    }

    private void cancelPolling() {
        if (pollRunnable != null) { handler.removeCallbacks(pollRunnable); pollRunnable = null; }
    }

    private void clearWaitingState() {
        waitingForEvents = false;
        cancelDotsAnimation();
        cancelWaitingTimeout();
        cancelPolling();
        instructionText.setVisibility(View.GONE);
        retryButton.setVisibility(View.GONE);
        checklistView.reset();
    }

    private void resetToLoadingState() {
        clearWaitingState();
        allEvents.clear();
        eventsContainer.setVisibility(View.GONE);
        loadingContainer.setVisibility(View.VISIBLE);
        statusText.setText("Avvio TeleStream…");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Events table
    // ─────────────────────────────────────────────────────────────────────────

    private void showEventsTable(List<StreamEvent> events) {
        clearWaitingState();
        allEvents = new ArrayList<>(events);
        focusCursor.reset();
        navDrawer.buildNavItems(events);
        if (!events.isEmpty()) filterAndShowEvents(navDrawer.getSelectedCategory());
        eventsListFrame.setVisibility(View.VISIBLE);
        settingsPanel.setVisibility(View.GONE);
        loadingContainer.setVisibility(View.GONE);
        eventsContainer.setVisibility(View.VISIBLE);
        navDrawer.open();
    }

    private void filterAndShowEvents(String category) {
        eventsList.removeAllViews();
        focusCursor.reset();

        List<View> animTargets = new ArrayList<>();
        int rowIdx = 0;
        for (StreamEvent event : allEvents) {
            if (!event.category.equals(category)) continue;
            final StreamEvent e = event;
            View row = rowFactory.createRow(event, rowIdx++,
                focusCursor::moveTo,
                v -> openEventStream(e));
            eventsList.addView(row);
            animTargets.add(row);
            eventsList.addView(rowFactory.createDivider());
        }

        int delay = 0;
        for (View v : animTargets) {
            v.setAlpha(0f);
            v.setTranslationY(dp(14));
            v.animate().alpha(1f).translationY(0f).setDuration(220)
                .setStartDelay(delay).setInterpolator(new DecelerateInterpolator()).start();
            delay += 28;
        }
    }

    private void showEventsPanel() {
        eventsListFrame.setVisibility(View.VISIBLE);
        settingsPanel.setVisibility(View.GONE);
    }

    private void showSettingsPanel() {
        eventsListFrame.setVisibility(View.GONE);
        settingsPanel.setVisibility(View.VISIBLE);
        focusCursor.reset();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Stream
    // ─────────────────────────────────────────────────────────────────────────

    private void openEventStream(StreamEvent event) {
        android.util.Log.d("TvMain", "openEventStream: " + event.eventName + " → " + event.channelUrl);
        logoLoader.clearCache();
        Intent intent = new Intent(this, TvStreamActivity.class);
        intent.putExtra(TvStreamActivity.EXTRA_EVENT, event);
        startActivity(intent);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Settings panel
    // ─────────────────────────────────────────────────────────────────────────

    private void buildSettingsPanel() {
        settingsPanel.removeAllViews();
        settingsPanel.addView(makeSettingsRow("Aggiorna eventi",
            v -> session.pollForNewMessage(), false));

        View sep = new View(this);
        LinearLayout.LayoutParams sepLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 1);
        sepLp.setMargins(0, dp(4), 0, dp(4));
        sep.setLayoutParams(sepLp);
        sep.setBackgroundColor(0xFF1E2A35);
        settingsPanel.addView(sep);

        settingsPanel.addView(makeSettingsRow("Disconnetti",
            v -> confirmLogout(), true));
    }

    private LinearLayout makeSettingsRow(String label,
            View.OnClickListener onClick, boolean destructive) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setFocusable(true);
        row.setClickable(true);
        row.setFocusableInTouchMode(false);
        row.setPadding(dp(24), dp(20), dp(24), dp(20));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(4), 0, dp(4));
        row.setLayoutParams(lp);

        TextView labelView = new TextView(this);
        labelView.setText(label);
        labelView.setTextColor(destructive ? 0xFFE74C3C : 0xFFFFFFFF);
        labelView.setTextSize(20f);
        row.addView(labelView, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        row.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                android.graphics.drawable.GradientDrawable hl =
                    new android.graphics.drawable.GradientDrawable();
                hl.setColor(destructive ? 0x14E74C3C : 0x14FFFFFF);
                hl.setCornerRadius(dp(12));
                hl.setStroke(dp(1), destructive ? 0x55E74C3C : 0x55FFFFFF);
                v.setBackground(hl);
            } else {
                v.setBackground(null);
            }
        });
        row.setOnClickListener(onClick);
        return row;
    }

    private void confirmLogout() {
        new android.app.AlertDialog.Builder(this)
            .setTitle(LocaleController.getString(R.string.LogOutTitle))
            .setMessage(getString(R.string.tv_logout_confirm))
            .setPositiveButton(LocaleController.getString(R.string.OK), (d, w) -> doLogout())
            .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
            .show();
    }

    private void doLogout() {
        MessagesController.getInstance(account).performLogout(1);
        Intent intent = new Intent(this, org.telegram.tv.login.TvLaunchActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private void refocusContentPanel() {
        LinearLayout panel = settingsPanel.getVisibility() == View.VISIBLE
            ? settingsPanel : eventsList;
        for (int i = 0; i < panel.getChildCount(); i++) {
            View child = panel.getChildAt(i);
            if (child.isFocusable()) { child.requestFocus(); break; }
        }
    }

    private int dp(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density + 0.5f);
    }
}
