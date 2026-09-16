package org.telegram.tv.activity;

import android.app.Activity;
import android.content.Intent;
import android.media.AudioAttributes;
import org.telegram.messenger.LocaleController;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.graphics.Color;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.tv.R;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.tv.bot.BotSession;
import org.telegram.tv.bot.MessageParser;
import org.telegram.tv.model.StreamEvent;
import org.telegram.tv.ui.EventStatsLoader;
import org.telegram.tv.ui.TeamLogoLoader;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Stories.LivePlayer;
import org.telegram.ui.Stories.recorder.LivePlayerView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class TvMainActivity extends Activity
        implements NotificationCenter.NotificationCenterDelegate, BotSession.Listener {

    private static final String BOT_USERNAME = "CherryStreaming_cbot";
    // Shared by the SPORT column's label rendering and its dynamic-width measurement — keep
    // these in sync or the measured width won't match what's actually drawn.
    private static final float SPORT_TEXT_SIZE_SP = 13f;

    private final int account = UserConfig.selectedAccount;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private BotSession session;
    private TeamLogoLoader logoLoader;
    private EventStatsLoader statsLoader;

    // Waiting-state animation + polling
    private boolean waitingForEvents = false;
    private Runnable dotsRunnable;
    private Runnable waitingTimeoutRunnable;
    private Runnable pollRunnable;

    // UI — loading state
    private LinearLayout loadingContainer;
    private TextView statusText;
    private TextView instructionText;
    private TextView retryButton;

    // UI — events table
    private LinearLayout eventsContainer;
    private LinearLayout eventsList;
    private FrameLayout eventsListFrame;
    private View focusCursor;
    private TextView sportHeaderView;

    // UI — streaming player
    private View playerContainer;
    private View streamLoading;
    private View streamTopBar;
    private TextView streamEventTitle;
    private TextView streamStatus;
    private FrameLayout streamPlayerContainer;

    private LivePlayer livePlayer;
    private LivePlayerView livePlayerView;

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_tv_main);

        loadingContainer      = findViewById(R.id.loading_container);
        statusText            = findViewById(R.id.status_text);
        instructionText       = findViewById(R.id.instruction_text);
        retryButton           = findViewById(R.id.retry_button);
        eventsContainer       = findViewById(R.id.events_container);
        eventsList            = findViewById(R.id.events_list);
        eventsListFrame       = findViewById(R.id.events_list_frame);
        sportHeaderView       = findViewById(R.id.header_sport);
        setupSettingsRow();
        playerContainer       = findViewById(R.id.player_container);
        streamLoading         = findViewById(R.id.stream_loading);
        streamTopBar          = findViewById(R.id.stream_top_bar);
        streamEventTitle      = findViewById(R.id.stream_event_title);
        streamStatus          = findViewById(R.id.stream_status);
        streamPlayerContainer = findViewById(R.id.stream_player_container);

        retryButton.setOnFocusChangeListener((v, hasFocus) ->
            v.setBackgroundColor(hasFocus ? 0xFF1E3A5F : 0x00000000));
        retryButton.setOnClickListener(v -> {
            clearWaitingState();
            session.restart();
        });

        logoLoader = new TeamLogoLoader(getResources().getDisplayMetrics().density);
        statsLoader = new EventStatsLoader(account);
        session    = new BotSession(account, BOT_USERNAME, this);

        NotificationCenter.getInstance(account).addObserver(
            this, NotificationCenter.didReceiveNewMessages);

        android.util.Log.d("TvMain", "onCreate account=" + account
            + " isActivated=" + UserConfig.getInstance(account).isClientActivated());

        if (Build.VERSION.SDK_INT >= 33) {
            backInvokedCallback = BackInvokedRegistration.register(this, this::handleBackInvoked);
        }

        session.start();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (Build.VERSION.SDK_INT >= 33 && backInvokedCallback != null) {
            BackInvokedRegistration.unregister(this, backInvokedCallback);
        }
        cancelDotsAnimation();
        cancelWaitingTimeout();
        cancelPolling();
        logoLoader.shutdown();
        NotificationCenter.getInstance(account).removeObserver(
            this, NotificationCenter.didReceiveNewMessages);
        destroyPlayer();
    }

    @Override
    protected void onPause() {
        super.onPause();
        ConnectionsManager.getInstance(account).setAppPaused(true, false);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (playerContainer.getVisibility() == View.VISIBLE) {
            destroyPlayer();
            playerContainer.setVisibility(View.GONE);
            streamLoading.setVisibility(View.GONE);
            streamTopBar.setVisibility(View.VISIBLE);
            eventsContainer.setVisibility(View.VISIBLE);
            for (int i = 0; i < eventsList.getChildCount(); i++) {
                View child = eventsList.getChildAt(i);
                if (child.isFocusable()) { child.requestFocus(); break; }
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Same reasoning as TvLoginActivity: without this, ConnectionsManager keeps treating
        // the account as backgrounded/paused (set by TvLoginActivity.onPause() on the way here,
        // or by our own onPause() on the way back), and BotSession's requests stall silently.
        ConnectionsManager.getInstance(account).setAppPaused(false, false);
        NotificationCenter.getInstance(account).removeObserver(this, NotificationCenter.didReceiveNewMessages);
        NotificationCenter.getInstance(account).addObserver(this, NotificationCenter.didReceiveNewMessages);
        // If the user went to their phone to open the mini app and came back, check immediately.
        if (waitingForEvents) {
            android.util.Log.d("TvMain", "onResume: still waiting — triggering immediate poll");
            session.pollForNewMessage();
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && playerContainer.getVisibility() == View.VISIBLE) {
            closePlayer();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // TMessagesProj's manifest sets android:enableOnBackInvokedCallback="true" app-wide, which
    // switches BACK on Android 13+ to the predictive-back dispatcher instead of the classic
    // onKeyDown(KEYCODE_BACK) path above — onKeyDown is never called for BACK in that mode. With
    // no callback registered here, the system default just finishes the Activity, so BACK while
    // streaming exits the whole app instead of closing the player. Reproduced on one TV
    // (Android 13+, predictive back active) but not another (older/legacy back dispatch) —
    // matches this exactly. onBackPressed() below covers devices where the framework still
    // bridges to it; the explicit callback below covers this specific TV where it apparently
    // doesn't.
    @Override
    public void onBackPressed() {
        if (playerContainer.getVisibility() == View.VISIBLE) {
            closePlayer();
        } else {
            super.onBackPressed();
        }
    }

    private Object backInvokedCallback; // android.window.OnBackInvokedCallback (API 33+ only —
                                         // kept as Object so this field doesn't need that class
                                         // resolved on pre-33 devices)

    @androidx.annotation.RequiresApi(33)
    private static final class BackInvokedRegistration {
        static Object register(Activity activity, Runnable action) {
            android.window.OnBackInvokedCallback callback = action::run;
            activity.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback);
            return callback;
        }

        static void unregister(Activity activity, Object callback) {
            activity.getOnBackInvokedDispatcher()
                .unregisterOnBackInvokedCallback((android.window.OnBackInvokedCallback) callback);
        }
    }

    private void handleBackInvoked() {
        if (playerContainer.getVisibility() == View.VISIBLE) {
            closePlayer();
        } else {
            finish();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // NotificationCenter — new messages from the bot
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public void didReceivedNotification(int id, int acc, Object... args) {
        if (id != NotificationCenter.didReceiveNewMessages) return;
        long dialogId = (Long) args[0];
        android.util.Log.d("TvMain", "didReceiveNewMessages dialogId=" + dialogId
            + " targetDialogId=" + session.getDialogId());
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
        loadingContainer.setVisibility(View.VISIBLE);
        instructionText.setVisibility(View.VISIBLE);
        instructionText.setText("✅ Canali verificati.\nApri la mini app\ndal tuo smartphone per completare.");
        startDotsAnimation("In attesa del calendario eventi");
        // Bot might have already replied with the events message while we were joining channels.
        session.pollForNewMessage();
    }

    @Override
    public void onEventsReady(MessageObject msg, List<StreamEvent> events) {
        showEventsTable(msg, events);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Waiting-state UI helpers
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
        if (dotsRunnable != null) {
            handler.removeCallbacks(dotsRunnable);
            dotsRunnable = null;
        }
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
            handler.removeCallbacks(waitingTimeoutRunnable);
            waitingTimeoutRunnable = null;
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
        handler.postDelayed(pollRunnable, 2000); // first check after 2 s, then every 3 s
    }

    private void cancelPolling() {
        if (pollRunnable != null) {
            handler.removeCallbacks(pollRunnable);
            pollRunnable = null;
        }
    }

    private void clearWaitingState() {
        waitingForEvents = false;
        cancelDotsAnimation();
        cancelWaitingTimeout();
        cancelPolling();
        instructionText.setVisibility(View.GONE);
        retryButton.setVisibility(View.GONE);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Events table UI
    // ─────────────────────────────────────────────────────────────────────────

    private void showEventsTable(MessageObject msg, List<StreamEvent> events) {
        clearWaitingState();
        eventsList.removeAllViews();
        resetFocusCursor();

        // Size the SPORT column to fit the longest category label among the events actually
        // shown (e.g. "CARABAO CUP"), so labels never wrap — applied to the fixed header too.
        int sportColumnWidth = computeSportColumnWidth(events);
        sportHeaderView.getLayoutParams().width = sportColumnWidth;
        sportHeaderView.requestLayout();

        int catIdx = -1;
        String prevCategory = "";
        List<View> animTargets = new ArrayList<>();

        for (StreamEvent event : events) {
            if (!event.category.equals(prevCategory)) {
                catIdx++;
                prevCategory = event.category;
            }
            View row = createEventRow(event, catIdx, sportColumnWidth);
            eventsList.addView(row);
            animTargets.add(row);

            View div = new View(this);
            div.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1));
            div.setBackgroundColor(0xFF1E2A35);
            eventsList.addView(div);
        }

        // Staggered fade + slide-up entry
        int delay = 0;
        for (View v : animTargets) {
            v.setAlpha(0f);
            v.setTranslationY(dp(14));
            v.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(220)
                    .setStartDelay(delay)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();
            delay += 28;
        }

        loadingContainer.setVisibility(View.GONE);
        eventsContainer.setVisibility(View.VISIBLE);
        for (int i = 0; i < eventsList.getChildCount(); i++) {
            View child = eventsList.getChildAt(i);
            if (child.isFocusable()) { child.requestFocus(); break; }
        }
    }

    private View createEventRow(StreamEvent event, int categoryIndex, int sportColumnWidth) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setFocusable(true);
        row.setClickable(true);
        row.setFocusableInTouchMode(false);
        row.setGravity(Gravity.CENTER_VERTICAL);

        // Zebra: alternate a barely-visible white tint between category groups
        if (categoryIndex % 2 == 1) row.setBackgroundColor(0x0AFFFFFF);

        row.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) moveFocusCursorTo(v);
        });
        row.setOnClickListener(v -> openEventStream(event));

        // Left category column (flush with screen edge) — width matches the SPORT header column
        FrameLayout sportSlot = new FrameLayout(this);
        sportSlot.setBackgroundColor(categoryColor(event.category));
        sportSlot.addView(makeOutlinedSportLabel(event.category.toUpperCase()));
        row.addView(sportSlot, new LinearLayout.LayoutParams(sportColumnWidth, LinearLayout.LayoutParams.MATCH_PARENT));

        // Inner content (takes all remaining width, carries top/bottom/end padding)
        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.HORIZONTAL);
        inner.setGravity(Gravity.CENTER_VERTICAL);
        inner.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        inner.setPadding(dp(16), dp(14), dp(20), dp(14));

        String[] teams = MessageParser.parseTeams(event.eventName);
        if (teams != null) {
            inner.addView(createFootballContent(teams));
        } else {
            TextView nameView = new TextView(this);
            nameView.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            nameView.setText(event.eventName);
            nameView.setTextColor(0xFFE8EDF0);
            nameView.setTextSize(20f);
            nameView.setMaxLines(1);
            nameView.setEllipsize(android.text.TextUtils.TruncateAt.END);
            nameView.setGravity(Gravity.CENTER_VERTICAL);
            inner.addView(nameView);
        }

        // Viewer count — fixed width matching the SPETTATORI header column, centered.
        // Populated asynchronously once resolved, hidden until then (and stays hidden if the
        // channel has no active stream right now).
        TextView watchersView = new TextView(this);
        watchersView.setLayoutParams(new LinearLayout.LayoutParams(
            dp(120), LinearLayout.LayoutParams.MATCH_PARENT));
        watchersView.setTextColor(0xFF8FA6B8);
        watchersView.setTextSize(16f);
        watchersView.setGravity(Gravity.CENTER);
        watchersView.setVisibility(View.GONE);
        inner.addView(watchersView);
        statsLoader.load(event.channelUrl, count -> {
            if (count < 0) return; // no active stream on this channel right now
            watchersView.setText("👁 " + formatWatchers(count));
            watchersView.setVisibility(View.VISIBLE);
        });

        // Start time — plain text (no badge/box: that clipped vertically on rows shorter than
        // the badge itself, e.g. events with no team logos), same font/size as the EVENTO
        // column, in a slot matching the INIZIO header column width.
        TextView timeView = new TextView(this);
        timeView.setLayoutParams(new LinearLayout.LayoutParams(
            dp(120), LinearLayout.LayoutParams.WRAP_CONTENT));
        timeView.setText(timeOnly(event.time));
        timeView.setTextColor(0xFF4FC3F7);
        timeView.setTextSize(20f);
        timeView.setGravity(Gravity.CENTER);
        inner.addView(timeView);

        row.addView(inner);
        return row;
    }

    /**
     * Black-stroke-behind + white-fill-in-front text stack, for legibility on any of the
     * category background colors (some are light enough that plain white text gets lost).
     */
    private View makeOutlinedSportLabel(String text) {
        FrameLayout stack = new FrameLayout(this);

        TextView stroke = newSportLabelTextView(text);
        stroke.setTextColor(0xFF000000);
        stroke.setLayerType(View.LAYER_TYPE_SOFTWARE, null); // Paint.Style.STROKE needs this
        stroke.getPaint().setStyle(android.graphics.Paint.Style.STROKE);
        stroke.getPaint().setStrokeWidth(dp(2));

        TextView fill = newSportLabelTextView(text);
        fill.setTextColor(0xFFFFFFFF);

        stack.addView(stroke);
        stack.addView(fill);
        return stack;
    }

    private TextView newSportLabelTextView(String text) {
        TextView tv = new TextView(this);
        tv.setLayoutParams(new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        tv.setText(text);
        tv.setTextSize(SPORT_TEXT_SIZE_SP);
        tv.setTypeface(null, android.graphics.Typeface.BOLD);
        tv.setGravity(Gravity.CENTER);
        tv.setMaxLines(1);
        tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tv.setPadding(dp(4), dp(4), dp(4), dp(4));
        return tv;
    }

    /**
     * Horizontal layout: [logoA][nameA(weight=1)]  vs  [nameB(weight=1)][logoB]
     * Each name gets equal space so neither overflows or wraps to multiple lines.
     */
    private View createFootballContent(String[] teams) {
        LinearLayout container = new LinearLayout(this);
        container.setLayoutParams(new LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        container.setOrientation(LinearLayout.HORIZONTAL);
        container.setGravity(Gravity.CENTER_VERTICAL);

        ImageView logoA = makeLogoView();
        TextView nameA = makeTeamNameView(teams[0]);  // weight=1, right side of left half
        nameA.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);

        TextView vsView = new TextView(this);
        vsView.setText("vs");
        vsView.setTextColor(0xFF5A7A99);
        vsView.setTextSize(13f);
        vsView.setPadding(dp(10), 0, dp(10), 0);
        vsView.setGravity(Gravity.CENTER);

        TextView nameB = makeTeamNameView(teams[1]);  // weight=1, left side of right half
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
        ImageView iv = new ImageView(this);
        iv.setLayoutParams(new LinearLayout.LayoutParams(dp(44), dp(44)));
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setPadding(dp(2), dp(2), dp(2), dp(2));
        return iv;
    }

    private TextView makeTeamNameView(String name) {
        TextView tv = new TextView(this);
        // weight=1 so both team names split available space equally
        tv.setLayoutParams(new LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        tv.setText(name);
        tv.setTextColor(0xFFE8EDF0);
        tv.setTextSize(20f);
        tv.setMaxLines(1);
        tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tv.setGravity(Gravity.CENTER_VERTICAL);
        tv.setPadding(dp(6), 0, dp(6), 0);
        return tv;
    }

    /**
     * Categories come from the bot as "{emoji} NAME" (e.g. "⚽ Carabao Cup") — the emoji is the
     * reliable signal, since competition names alone don't always say the sport (a name-based
     * keyword match wouldn't catch "Carabao Cup" as football). Checked first; falls back to the
     * old keyword match on the text for any category whose leading emoji isn't recognized.
     */
    private int categoryColor(String category) {
        if (!category.isEmpty()) {
            switch (category.codePointAt(0)) {
                case 0x26BD:   return 0xFF27AE60; // ⚽ calcio
                case 0x1F3C0:  return 0xFFC05000; // 🏀 basket
                case 0x1F3BE:  return 0xFF2874A6; // 🎾 tennis
                case 0x1F3CE:  // 🏎 auto da corsa
                case 0x1F3C1:  // 🏁 bandiera a scacchi
                case 0x1F3CD:  return 0xFF8E1A0E; // 🏍 moto
                case 0x1F3D0:  // 🏐 pallavolo
                case 0x1F3C9:  return 0xFF6A1E8A; // 🏉 rugby
            }
        }

        String up = category.toUpperCase();
        if (up.contains("CALCIO") || up.contains("FOOTBALL") || up.contains("SOCCER")
                || up.contains("UEFA") || up.contains("SERIE") || up.contains("CHAMPIONS")
                || up.contains("PREMIER") || up.contains("LIGA") || up.contains("MONDIALI")
                || up.contains("NATIONS") || up.contains("WORLD")) return 0xFF27AE60;
        if (up.contains("BASKET") || up.contains("NBA"))            return 0xFFC05000;
        if (up.contains("TENNIS"))                                  return 0xFF2874A6;
        if (up.contains("FORMULA") || up.contains("F1") || up.contains("MOTO")) return 0xFF8E1A0E;
        if (up.contains("VOLLEY") || up.contains("RUGBY"))         return 0xFF6A1E8A;
        return 0xFF4FC3F7;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Stream player
    // ─────────────────────────────────────────────────────────────────────────

    private void openEventStream(StreamEvent event) {
        android.util.Log.d("TvMain", "openEventStream: " + event.eventName + " → " + event.channelUrl);
        showStreamLoading("Connessione a " + event.eventName + "…");

        String hash = MessageParser.extractInviteHash(event.channelUrl);
        if (hash == null) { showStreamError("Link non valido"); return; }

        AtomicBoolean done = new AtomicBoolean(false);
        handler.postDelayed(() -> {
            if (!done.get()) showStreamError("Timeout connessione");
        }, 20000);

        TLRPC.TL_messages_checkChatInvite req = new TLRPC.TL_messages_checkChatInvite();
        req.hash = hash;
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) ->
            AndroidUtilities.runOnUIThread(() -> {
                if (!done.compareAndSet(false, true)) return;
                if (response instanceof TLRPC.TL_chatInviteAlready) {
                    TLRPC.Chat chat = ((TLRPC.TL_chatInviteAlready) response).chat;
                    android.util.Log.d("TvMain", "already member: " + chat.title);
                    MessagesController.getInstance(account).putChat(chat, false);
                    getAndPlayStream(chat, event);
                } else {
                    joinChannelForStream(hash, event);
                }
            })
        );
    }

    private void joinChannelForStream(String hash, StreamEvent event) {
        showStreamLoading("Richiesta accesso a " + event.eventName + "…");

        AtomicBoolean done = new AtomicBoolean(false);
        handler.postDelayed(() -> {
            if (!done.get()) showStreamError("Timeout join canale");
        }, 15000);

        TLRPC.TL_messages_importChatInvite req = new TLRPC.TL_messages_importChatInvite();
        req.hash = hash;
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) ->
            AndroidUtilities.runOnUIThread(() -> {
                if (!done.compareAndSet(false, true)) return;
                if (error != null) { showStreamError("Accesso negato: " + error.text); return; }
                TLRPC.Chat chat = BotSession.extractChatFromUpdates((TLRPC.Updates) response);
                if (chat == null) { showStreamError("Canale non trovato"); return; }
                android.util.Log.d("TvMain", "joined: " + chat.title);
                MessagesController.getInstance(account).putChat(chat, false);
                BotSession.muteAndArchive(account, chat, () -> {});
                getAndPlayStream(chat, event);
            })
        );
    }

    private void getAndPlayStream(TLRPC.Chat chat, StreamEvent event) {
        android.util.Log.d("TvMain", "getAndPlayStream: " + chat.title);
        showStreamLoading("Connessione allo stream…");

        TLRPC.TL_channels_getFullChannel req = new TLRPC.TL_channels_getFullChannel();
        TLRPC.TL_inputChannel ic = new TLRPC.TL_inputChannel();
        ic.channel_id  = chat.id;
        ic.access_hash = chat.access_hash;
        req.channel = ic;

        AtomicBoolean done = new AtomicBoolean(false);
        handler.postDelayed(() -> {
            if (done.compareAndSet(false, true)) showStreamError("Timeout recupero canale");
        }, 15000);

        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) ->
            AndroidUtilities.runOnUIThread(() -> {
                if (!done.compareAndSet(false, true)) return;
                if (error != null) { showStreamError("Errore canale: " + error.text); return; }
                if (!(response instanceof TLRPC.TL_messages_chatFull)) {
                    showStreamError("Dati canale non disponibili"); return;
                }
                TLRPC.ChatFull full = ((TLRPC.TL_messages_chatFull) response).full_chat;
                if (full.call == null) {
                    showStreamError("Nessuna live stream attiva in questo canale"); return;
                }
                startLivePlayer(chat, full.call, event);
            })
        );
    }

    private void startLivePlayer(TLRPC.Chat chat, TLRPC.InputGroupCall callRef, StreamEvent event) {
        destroyPlayer();

        streamEventTitle.setText(event.category + " — " + event.eventName + "  " + event.time);
        playerContainer.setVisibility(View.VISIBLE);
        eventsContainer.setVisibility(View.GONE);
        streamLoading.setVisibility(View.VISIBLE);

        long dialogId = -(long) chat.id; // broadcast channels use negative dialog IDs

        // Adaptive bitrate cap: the native engine always requests full quality for RTMP
        // "unified" streams (hardcoded, ignores any per-endpoint quality preference), so we
        // clamp the quality value in the outgoing getFile request itself instead (see
        // LivePlayer.maxVideoQuality). QualityAdaptor watches per-chunk fetch timing and caps
        // once, never upgrading back, to avoid oscillation.
        LivePlayer.maxVideoQuality = -1;
        LivePlayer.chunkListener = new QualityAdaptor();

        livePlayer = new LivePlayer(this, account, null, dialogId, 0, true, callRef);

        // LivePlayer.configureAudio() sets USAGE_MEDIA (static) for RTMP streams, but the
        // AudioTrack is created later, async, when the joinGroupCall response arrives.
        // On TV the HDMI audio path adds latency that Android doesn't report, causing video
        // to appear ahead. USAGE_VOICE_COMMUNICATION bypasses Android's audio effects chain
        // (equaliser, bass-boost), reducing the Android-side latency before the AudioTrack
        // is created. This runs on the main thread so it wins the race against any posted callback.
        if (Build.VERSION.SDK_INT >= 21) {
            org.webrtc.voiceengine.WebRtcAudioTrack.setAudioTrackUsageAttribute(
                    AudioAttributes.USAGE_VOICE_COMMUNICATION);
        }

        // TextureViewRenderer (isSurfaceView=false): SurfaceViewRenderer crashes on TV because
        // LivePlayerView.onFirstFrameRendered() calls .animate().start() from the GL thread.
        livePlayerView = new LivePlayerView(this, account, false);
        streamPlayerContainer.addView(livePlayerView,
            new FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT));
        livePlayer.setDisplaySink(livePlayerView.getSink());

        // Unsubscribe while streaming — continuous group-call participant events from joined
        // channels saturate the main thread and cause video frame drops + A/V jitter.
        NotificationCenter.getInstance(account).removeObserver(this, NotificationCenter.didReceiveNewMessages);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        android.util.Log.d("TvMain", "LivePlayer started call=" + callRef.id + " dialogId=" + dialogId);

        handler.postDelayed(() -> {
            if (livePlayer != null) {
                streamLoading.setVisibility(View.GONE);
                streamTopBar.setVisibility(View.GONE);
            }
        }, 4000);
    }

    private void showStreamLoading(String msg) {
        playerContainer.setVisibility(View.VISIBLE);
        eventsContainer.setVisibility(View.GONE);
        streamLoading.setVisibility(View.VISIBLE);
        streamStatus.setText(msg);
    }

    private void showStreamError(String msg) {
        android.util.Log.w("TvMain", "streamError: " + msg);
        streamLoading.setVisibility(View.VISIBLE);
        streamStatus.setText("❌ " + msg + "\n\nPremi BACK per tornare agli eventi");
    }

    private void closePlayer() {
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        destroyPlayer();
        NotificationCenter.getInstance(account).addObserver(this, NotificationCenter.didReceiveNewMessages);
        playerContainer.setVisibility(View.GONE);
        streamLoading.setVisibility(View.GONE);
        streamTopBar.setVisibility(View.VISIBLE);
        eventsContainer.setVisibility(View.VISIBLE);
        for (int i = 0; i < eventsList.getChildCount(); i++) {
            View child = eventsList.getChildAt(i);
            if (child.isFocusable()) { child.requestFocus(); break; }
        }
    }

    private void destroyPlayer() {
        LivePlayer.maxVideoQuality = -1;
        LivePlayer.chunkListener = null;
        if (livePlayer != null) { livePlayer.destroy(); livePlayer = null; }
        if (livePlayerView != null && streamPlayerContainer != null) {
            streamPlayerContainer.removeView(livePlayerView);
            livePlayerView = null;
        }
    }

    // Adaptive bitrate: starts at quality=2, drops to quality=1 after 3 consecutive late video
    // chunks, then stops monitoring. Never upgrades back to avoid oscillation.
    // "Late" means fetch time exceeded the chunk's own playback duration (500 or 1000ms).
    private static final class QualityAdaptor implements LivePlayer.ChunkListener {
        private int lateStreak = 0;

        @Override
        public void onChunkFetched(int videoChannel, int quality, long fetchMs, long budgetMs) {
            if (videoChannel == 0) return; // audio chunk, skip
            if (LivePlayer.maxVideoQuality == 1) return; // already capped

            if (fetchMs > budgetMs) {
                lateStreak++;
                if (lateStreak >= 3) {
                    LivePlayer.maxVideoQuality = 1;
                    android.util.Log.i("TvMain", "ABR: quality capped to 1 after " + lateStreak + " late chunks");
                }
            } else {
                lateStreak = 0;
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Settings row — fixed at the bottom of the events container
    // ─────────────────────────────────────────────────────────────────────────

    private void setupSettingsRow() {
        // Outer container: full-width, not focusable — pushes buttons to the right
        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.HORIZONTAL);
        outer.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        outer.setPadding(0, dp(4), dp(16), dp(4));

        outer.addView(makeAccountAvatarView());

        outer.addView(makeIconButton(
            "↻",
            getString(R.string.tv_refresh),
            v -> session.pollForNewMessage(() ->
                android.widget.Toast.makeText(this, "Aggiornamento completato", android.widget.Toast.LENGTH_SHORT).show())
        ));

        outer.addView(makeIconButton(
            "☰",
            LocaleController.getString(R.string.Settings),
            v -> {
                startActivity(new Intent(this, TvSettingsActivity.class));
                overridePendingTransition(R.anim.tv_slide_in_right, 0);
            }
        ));

        eventsContainer.addView(outer, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    /** Small circular avatar of the logged-in account, to the left of the refresh button. */
    private View makeAccountAvatarView() {
        BackupImageView avatarView = new BackupImageView(this);
        int size = dp(36);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
        lp.setMarginStart(dp(4));
        avatarView.setLayoutParams(lp);
        avatarView.setRoundRadius(size / 2);

        TLRPC.User self = UserConfig.getInstance(account).getCurrentUser();
        AvatarDrawable avatarDrawable = new AvatarDrawable();
        avatarDrawable.setInfo(self);
        avatarView.setForUserOrChat(self, avatarDrawable);

        return avatarView;
    }

    private LinearLayout makeIconButton(String icon, String label, View.OnClickListener onClick) {
        LinearLayout btn = new LinearLayout(this);
        btn.setOrientation(LinearLayout.HORIZONTAL);
        btn.setGravity(Gravity.CENTER_VERTICAL);
        btn.setFocusable(true);
        btn.setClickable(true);
        btn.setFocusableInTouchMode(false);
        btn.setPadding(dp(14), dp(10), dp(14), dp(10));

        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        btnLp.setMarginStart(dp(8));
        btn.setLayoutParams(btnLp);

        // Label sits in a container that starts at width=0 and expands on focus,
        // clipping the text to reveal it smoothly from left.
        final TextView labelView = new TextView(this);
        labelView.setText(label);
        labelView.setTextColor(0xFFFFFFFF);
        labelView.setTextSize(14f);
        labelView.setPadding(0, 0, dp(10), 0); // gap between text and icon
        labelView.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        final int labelFullWidth = labelView.getMeasuredWidth();

        FrameLayout labelClip = new FrameLayout(this);
        labelClip.setClipChildren(true);
        labelClip.addView(labelView, new FrameLayout.LayoutParams(
            labelFullWidth, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER_VERTICAL | Gravity.END));
        btn.addView(labelClip, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT));

        final TextView iconView = new TextView(this);
        iconView.setText(icon);
        iconView.setTextSize(22f);
        iconView.setTextColor(0x55FFFFFF);
        btn.addView(iconView, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        final android.animation.ValueAnimator[] activeAnim = {null};

        btn.setOnFocusChangeListener((v, hasFocus) -> {
            if (activeAnim[0] != null) activeAnim[0].cancel();
            int startW = labelClip.getWidth();
            int endW = hasFocus ? labelFullWidth : 0;
            android.animation.ValueAnimator anim = android.animation.ValueAnimator.ofInt(startW, endW);
            anim.addUpdateListener(a -> {
                LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) labelClip.getLayoutParams();
                lp.width = (int) a.getAnimatedValue();
                labelClip.setLayoutParams(lp);
            });
            anim.setDuration(hasFocus ? 200 : 150);
            anim.setInterpolator(new DecelerateInterpolator());
            anim.start();
            activeAnim[0] = anim;

            if (hasFocus) {
                hideFocusCursorAnimated();
                iconView.setTextColor(0xFFFFFFFF);
                android.graphics.drawable.GradientDrawable hl = new android.graphics.drawable.GradientDrawable();
                hl.setColor(0x14FFFFFF);
                hl.setCornerRadius(dp(20));
                hl.setStroke(dp(1), 0x55FFFFFF);
                v.setBackground(hl);
            } else {
                iconView.setTextColor(0x55FFFFFF);
                v.setBackground(null);
            }
        });
        btn.setOnClickListener(onClick);
        return btn;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Focus cursor — animated highlight that slides between event rows
    // ─────────────────────────────────────────────────────────────────────────

    private void ensureFocusCursor() {
        if (focusCursor != null) return;
        android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
        gd.setColor(0x1A2CA5E0);
        gd.setCornerRadius(dp(10));
        gd.setStroke(dp(2), 0xFF2CA5E0);

        focusCursor = new View(this);
        focusCursor.setBackground(gd);
        focusCursor.setClickable(false);
        focusCursor.setFocusable(false);
        focusCursor.setAlpha(0f);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dp(60));
        eventsListFrame.addView(focusCursor, lp);
    }

    private void resetFocusCursor() {
        if (focusCursor == null) return;
        focusCursor.animate().cancel();
        focusCursor.setAlpha(0f);
        focusCursor.setY(0f);
    }

    private void hideFocusCursorAnimated() {
        if (focusCursor == null || focusCursor.getAlpha() == 0f) return;
        float currentY = focusCursor.getY();
        focusCursor.animate().cancel();
        focusCursor.animate()
            .alpha(0f)
            .y(currentY + dp(20))
            .setDuration(200)
            .setInterpolator(new DecelerateInterpolator())
            .withEndAction(() -> focusCursor.setY(0f))
            .start();
    }

    private void moveFocusCursorTo(View row) {
        ensureFocusCursor();
        // Wait for layout so getTop()/getHeight() are valid.
        row.post(() -> {
            if (focusCursor == null) return;
            int rowH = row.getHeight();
            int rowY = row.getTop(); // relative to events_list, which is at y=0 in eventsListFrame

            // Update cursor height without animation (it snaps, then slides).
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) focusCursor.getLayoutParams();
            lp.height = rowH;
            focusCursor.setLayoutParams(lp);

            boolean firstFocus = focusCursor.getAlpha() == 0f;
            if (firstFocus) {
                focusCursor.setY(rowY);
                focusCursor.animate()
                        .alpha(1f)
                        .setDuration(120)
                        .start();
            } else {
                focusCursor.animate()
                        .y(rowY)
                        .setDuration(180)
                        .setInterpolator(new DecelerateInterpolator())
                        .start();
            }
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private int dp(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density + 0.5f);
    }

    /** 1234 → "1.234", 12500 → "12,5K" — compact enough for a TV row at viewing distance. */
    private static String formatWatchers(int count) {
        if (count >= 1_000_000) return String.format(java.util.Locale.ITALY, "%.1fM", count / 1_000_000f);
        if (count >= 10_000) return String.format(java.util.Locale.ITALY, "%.1fK", count / 1_000f);
        return String.format(java.util.Locale.ITALY, "%,d", count); // "." as thousands separator
    }

    /** StreamEvent.time is "DD/MM HH:MM" — the INIZIO column only shows the time part. */
    private static String timeOnly(String time) {
        int space = time.indexOf(' ');
        return space >= 0 ? time.substring(space + 1) : time;
    }

    /**
     * Widest natural (unwrapped) width among the distinct category labels in {@code events},
     * at the SPORT column's exact text size/style, so no label ever wraps. Clamped to a
     * sane range in case a list has only very short or one absurdly long category name.
     */
    private int computeSportColumnWidth(List<StreamEvent> events) {
        android.text.TextPaint paint = new android.text.TextPaint();
        paint.setTextSize(android.util.TypedValue.applyDimension(
            android.util.TypedValue.COMPLEX_UNIT_SP, SPORT_TEXT_SIZE_SP, getResources().getDisplayMetrics()));
        paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);

        float maxTextWidth = 0f;
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (StreamEvent event : events) {
            String label = event.category.toUpperCase();
            if (!seen.add(label)) continue;
            maxTextWidth = Math.max(maxTextWidth, paint.measureText(label));
        }

        int width = (int) Math.ceil(maxTextWidth) + dp(4) * 2; // match sportView's own padding
        return Math.max(dp(56), Math.min(width, dp(160)));
    }
}
