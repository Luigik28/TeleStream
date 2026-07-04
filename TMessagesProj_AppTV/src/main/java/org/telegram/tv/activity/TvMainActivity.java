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
import android.view.ViewGroup;
import android.view.WindowManager;
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
import org.telegram.tv.ui.TeamLogoLoader;
import org.telegram.ui.Stories.LivePlayer;
import org.telegram.ui.Stories.recorder.LivePlayerView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class TvMainActivity extends Activity
        implements NotificationCenter.NotificationCenterDelegate, BotSession.Listener {

    private static final String BOT_USERNAME = "CherryStreaming_cbot";

    private final int account = UserConfig.selectedAccount;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private BotSession session;
    private TeamLogoLoader logoLoader;

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
    private FrameLayout eventsContainer;
    private LinearLayout eventsList;
    private FrameLayout eventsListFrame;
    private View focusCursor;

    // UI — unified navigation panel (collapsed = 72dp icon-only, expanded = 320dp icon+label)
    private LinearLayout navRail;       // the panel itself
    private LinearLayout navRailItems;  // the scrollable category items inside
    private FrameLayout userAvatar;     // avatar container in the panel header
    private TextView userNameView;
    private TextView userUsernameView;
    private LinearLayout navSettings;   // settings row at the bottom
    private View drawerScrim;
    private FrameLayout contentArea;
    private LinearLayout settingsPanel;
    private android.graphics.drawable.ColorDrawable panelBg; // animated when expanding

    // State — drawer + events + selection
    private boolean drawerOpen = false;
    private android.animation.ValueAnimator drawerAnim;
    private List<StreamEvent> allEvents = new ArrayList<>();
    private String selectedCategory = null;
    private View selectedNavView = null;

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
        contentArea           = findViewById(R.id.content_area);
        eventsList            = findViewById(R.id.events_list);
        eventsListFrame       = findViewById(R.id.events_list_frame);
        settingsPanel         = findViewById(R.id.settings_panel);
        navRail               = findViewById(R.id.nav_rail);
        navRailItems          = findViewById(R.id.nav_rail_items);
        drawerScrim           = findViewById(R.id.drawer_scrim);
        userAvatar            = findViewById(R.id.user_avatar);
        userNameView          = findViewById(R.id.user_name);
        userUsernameView      = findViewById(R.id.user_username);
        navSettings           = findViewById(R.id.nav_settings);
        setupDrawer();
        buildSettingsPanel();
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
        session    = new BotSession(account, BOT_USERNAME, this);

        NotificationCenter.getInstance(account).addObserver(
            this, NotificationCenter.didReceiveNewMessages);

        android.util.Log.d("TvMain", "onCreate account=" + account
            + " isActivated=" + UserConfig.getInstance(account).isClientActivated());

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
        destroyPlayer();
    }

    @Override
    protected void onPause() {
        super.onPause();
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (playerContainer.getVisibility() == View.VISIBLE) {
            destroyPlayer();
            playerContainer.setVisibility(View.GONE);
            streamLoading.setVisibility(View.GONE);
            streamTopBar.setVisibility(View.VISIBLE);
            eventsContainer.setVisibility(View.VISIBLE);
            LinearLayout panel = settingsPanel.getVisibility() == View.VISIBLE ? settingsPanel : eventsList;
            for (int i = 0; i < panel.getChildCount(); i++) {
                View child = panel.getChildAt(i);
                if (child.isFocusable()) { child.requestFocus(); break; }
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
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
        if (playerContainer.getVisibility() == View.VISIBLE) {
            if (keyCode == KeyEvent.KEYCODE_BACK) { closePlayer(); return true; }
            return super.onKeyDown(keyCode, event);
        }
        if (keyCode == KeyEvent.KEYCODE_BACK && eventsContainer.getVisibility() == View.VISIBLE) {
            if (drawerOpen) closeDrawer(); else openDrawer();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                && !drawerOpen && eventsContainer.getVisibility() == View.VISIBLE) {
            openDrawer();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && drawerOpen) {
            closeDrawer();
            return true;
        }
        return super.onKeyDown(keyCode, event);
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
        allEvents = new ArrayList<>(events);
        resetFocusCursor();

        buildNavItems(events);

        if (!events.isEmpty()) {
            filterAndShowEvents(selectedCategory);
        }

        eventsListFrame.setVisibility(View.VISIBLE);
        settingsPanel.setVisibility(View.GONE);
        loadingContainer.setVisibility(View.GONE);
        eventsContainer.setVisibility(View.VISIBLE);
        openDrawer();
    }

    private View createEventRow(StreamEvent event, int rowIndex) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setFocusable(true);
        row.setClickable(true);
        row.setFocusableInTouchMode(false);
        row.setGravity(Gravity.CENTER_VERTICAL);

        if (rowIndex % 2 == 1) row.setBackgroundColor(0x0AFFFFFF);

        row.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) moveFocusCursorTo(v);
        });
        row.setOnClickListener(v -> openEventStream(event));

        // Left category-color strip (flush with screen edge)
        View strip = new View(this);
        strip.setBackgroundColor(categoryColor(event.category));
        row.addView(strip, new LinearLayout.LayoutParams(dp(5), LinearLayout.LayoutParams.MATCH_PARENT));

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

        // Time badge — pill with subtle blue border
        TextView timeView = new TextView(this);
        LinearLayout.LayoutParams timeLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        timeLp.setMarginStart(dp(16));
        timeView.setLayoutParams(timeLp);
        timeView.setText(event.time);
        timeView.setTextColor(0xFF4FC3F7);
        timeView.setTextSize(18f);
        timeView.setTypeface(null, android.graphics.Typeface.BOLD);
        timeView.setGravity(Gravity.CENTER_VERTICAL);
        timeView.setPadding(dp(12), dp(5), dp(12), dp(5));
        android.graphics.drawable.GradientDrawable badge = new android.graphics.drawable.GradientDrawable();
        badge.setColor(0x221A2A40);
        badge.setCornerRadius(dp(6));
        badge.setStroke(dp(1), 0x334FC3F7);
        timeView.setBackground(badge);
        inner.addView(timeView);

        row.addView(inner);
        return row;
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

    private int categoryColor(String category) {
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
        LinearLayout panel = settingsPanel.getVisibility() == View.VISIBLE ? settingsPanel : eventsList;
        for (int i = 0; i < panel.getChildCount(); i++) {
            View child = panel.getChildAt(i);
            if (child.isFocusable()) { child.requestFocus(); break; }
        }
    }

    private void destroyPlayer() {
        if (livePlayer != null) { livePlayer.destroy(); livePlayer = null; }
        if (livePlayerView != null && streamPlayerContainer != null) {
            streamPlayerContainer.removeView(livePlayerView);
            livePlayerView = null;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Navigation drawer
    // ─────────────────────────────────────────────────────────────────────────

    private void setupDrawer() {
        // Collapsed state: clip the panel to 72dp (icon-only rail view).
        // Expanding to 320dp reveals the labels — no separate drawer element.
        panelBg = new android.graphics.drawable.ColorDrawable(0x00000000); // transparent initially
        navRail.setBackground(panelBg);
        navRail.setClipBounds(new android.graphics.Rect(0, 0, dp(72), 10000));

        // Scrim: semi-transparent overlay, dims the content when the panel is expanded.
        drawerScrim.setBackgroundColor(0x88000000);
        drawerScrim.setOnClickListener(v -> closeDrawer());

        TLRPC.User user = UserConfig.getInstance(account).getCurrentUser();
        if (user != null) {
            String firstName = user.first_name != null ? user.first_name : "";
            String lastName  = user.last_name  != null ? user.last_name  : "";
            String name = (firstName + " " + lastName).trim();
            String username = (user.username != null && !user.username.isEmpty())
                    ? "@" + user.username : "";
            userNameView.setText(name.isEmpty() ? "Utente" : name);
            userUsernameView.setText(username);
            setupAvatars(user);
        }

        navSettings.setOnFocusChangeListener((v, hasFocus) ->
            applyNavItemFocus(v, hasFocus, settingsPanel.getVisibility() == View.VISIBLE));
        navSettings.setOnClickListener(v -> {
            if (selectedNavView != null) { applyNavSelection(selectedNavView, false); selectedNavView = null; }
            applyNavSelection(navSettings, true);
            showSettingsPanel();
            closeDrawer();
        });
    }

    private void openDrawer() {
        if (drawerOpen) return;
        drawerOpen = true;
        contentArea.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        drawerScrim.setVisibility(View.VISIBLE);
        drawerScrim.animate().cancel();
        drawerScrim.animate().alpha(1f).setDuration(260)
                .setInterpolator(new DecelerateInterpolator()).start();

        if (drawerAnim != null) drawerAnim.cancel();
        // Expand clip from 72dp (rail) to 320dp (full panel), animating the dark background in.
        android.graphics.Rect cur = navRail.getClipBounds();
        int startW = (cur != null) ? cur.right : dp(72);
        final int H = 10000;
        drawerAnim = android.animation.ValueAnimator.ofInt(startW, dp(320));
        drawerAnim.setDuration(260);
        drawerAnim.setInterpolator(new DecelerateInterpolator());
        drawerAnim.addUpdateListener(va -> {
            int w = (int) va.getAnimatedValue();
            navRail.setClipBounds(new android.graphics.Rect(0, 0, w, H));
            // Fade in dark background as the panel expands
            float fraction = (float)(w - dp(72)) / (float)(dp(320) - dp(72));
            panelBg.setAlpha((int)(0xCC * Math.max(0f, Math.min(1f, fraction))));
        });
        drawerAnim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator a) {
                navRail.setClipBounds(null);
                panelBg.setAlpha(0xCC);
            }
        });
        drawerAnim.start();

        handler.postDelayed(() -> {
            View toFocus = selectedNavView != null ? selectedNavView : navRailItems.getChildAt(0);
            if (toFocus != null) toFocus.requestFocus();
        }, 260);
    }

    private void closeDrawer() {
        if (!drawerOpen) return;
        drawerOpen = false;

        if (drawerAnim != null) drawerAnim.cancel();
        final int H = 10000;
        android.graphics.Rect cur = navRail.getClipBounds();
        int startW = (cur != null) ? cur.right : dp(320);
        drawerAnim = android.animation.ValueAnimator.ofInt(startW, dp(72));
        drawerAnim.setDuration(200);
        drawerAnim.setInterpolator(new android.view.animation.AccelerateInterpolator());
        drawerAnim.addUpdateListener(va -> {
            int w = (int) va.getAnimatedValue();
            navRail.setClipBounds(new android.graphics.Rect(0, 0, w, H));
            // Fade out dark background as the panel collapses
            float fraction = (float)(w - dp(72)) / (float)(dp(320) - dp(72));
            panelBg.setAlpha((int)(0xCC * Math.max(0f, Math.min(1f, fraction))));
        });
        drawerAnim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator a) {
                panelBg.setAlpha(0);
            }
        });
        drawerAnim.start();

        drawerScrim.animate().cancel();
        drawerScrim.animate().alpha(0f).setDuration(200)
                .withEndAction(() -> drawerScrim.setVisibility(View.INVISIBLE)).start();
        contentArea.setDescendantFocusability(ViewGroup.FOCUS_BEFORE_DESCENDANTS);
        handler.postDelayed(() -> {
            LinearLayout panel = settingsPanel.getVisibility() == View.VISIBLE
                    ? settingsPanel : eventsList;
            for (int i = 0; i < panel.getChildCount(); i++) {
                View child = panel.getChildAt(i);
                if (child.isFocusable()) { child.requestFocus(); break; }
            }
        }, 210);
    }

    /**
     * Adds a BackupImageView into each avatar container.
     * AvatarDrawable provides the colored-initial fallback; BackupImageView
     * downloads the real Telegram profile photo and displays it clipped to a circle.
     */
    private void setupAvatars(TLRPC.User user) {
        placeAvatarView(userAvatar, user, dp(20)); // 40dp container → 20dp radius = circle
    }

    private void placeAvatarView(FrameLayout container, TLRPC.User user, int roundRadius) {
        org.telegram.ui.Components.AvatarDrawable fallback =
                new org.telegram.ui.Components.AvatarDrawable();
        fallback.setInfo(account, user);

        org.telegram.ui.Components.BackupImageView iv =
                new org.telegram.ui.Components.BackupImageView(this);
        iv.setRoundRadius(roundRadius);
        iv.setForUserOrChat(user, fallback);

        container.removeAllViews();
        container.setBackground(null);
        container.addView(iv, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
    }

    private void buildNavItems(List<StreamEvent> events) {
        navRailItems.removeAllViews();
        selectedNavView = null;
        selectedCategory = null;

        List<String> categories = new ArrayList<>();
        for (StreamEvent e : events) {
            if (!categories.contains(e.category)) categories.add(e.category);
        }

        for (String cat : categories) {
            int iconRes = categoryIconRes(cat);
            navRailItems.addView(createNavItem(iconRes, cat, cat));
        }

        if (!categories.isEmpty()) {
            selectedCategory = categories.get(0);
            selectedNavView = navRailItems.getChildAt(0);
            applyNavSelection(selectedNavView, true);
        }
    }

    /**
     * Creates a unified nav item: a fixed 72dp icon slot (icon centered at x=36dp)
     * followed by a label. Clip to 72dp = icon-only rail; clip to 320dp = icon + label.
     */
    private View createNavItem(int iconRes, String label, String category) {
        LinearLayout item = new LinearLayout(this);
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

        // 72dp icon slot: icon 24dp centered at x=36dp — consistent with panel width at collapse
        FrameLayout iconSlot = new FrameLayout(this);
        android.widget.ImageView iconView = new android.widget.ImageView(this);
        iconView.setImageResource(iconRes);
        iconView.setColorFilter(0xFFCAC4D0, android.graphics.PorterDuff.Mode.SRC_IN);
        iconView.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        FrameLayout.LayoutParams iconLp = new FrameLayout.LayoutParams(dp(24), dp(24));
        iconLp.gravity = Gravity.CENTER;
        iconSlot.addView(iconView, iconLp);
        item.addView(iconSlot, new LinearLayout.LayoutParams(dp(72), LinearLayout.LayoutParams.MATCH_PARENT));

        // Label — visible only when panel is expanded beyond 72dp
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextColor(0xFFE6E1E5);
        tv.setTextSize(14f);
        tv.setMaxLines(1);
        tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tvLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tvLp.setMarginEnd(dp(16));
        tv.setLayoutParams(tvLp);
        item.addView(tv);

        item.setOnFocusChangeListener((v, hasFocus) ->
            applyNavItemFocus(v, hasFocus, v == selectedNavView));
        item.setOnClickListener(v -> {
            if (selectedNavView != null) applyNavSelection(selectedNavView, false);
            applyNavSelection(navSettings, false);
            selectedNavView = v;
            selectedCategory = category;
            applyNavSelection(v, true);
            showEventsPanel();
            filterAndShowEvents(category);
            closeDrawer();
        });
        return item;
    }


    /** Returns the drawable resource ID for the icon that represents a sport category. */
    private int categoryIconRes(String category) {
        String up = category.toUpperCase();
        if (up.contains("CALCIO") || up.contains("FOOTBALL") || up.contains("SOCCER")
                || up.contains("UEFA") || up.contains("SERIE") || up.contains("CHAMPIONS")
                || up.contains("PREMIER") || up.contains("LIGA") || up.contains("MONDIALI")
                || up.contains("NATIONS") || up.contains("WORLD"))    return R.drawable.ic_sport_soccer;
        if (up.contains("BASKET") || up.contains("NBA"))              return R.drawable.ic_sport_basketball;
        if (up.contains("TENNIS") || up.contains("WIMBLEDON"))        return R.drawable.ic_sport_tennis;
        if (up.contains("FORMULA") || up.contains("F1")
                || up.contains("MOTO") || up.contains("GP"))          return R.drawable.ic_sport_motorsport;
        if (up.contains("VOLLEY"))                                     return R.drawable.ic_sport_volleyball;
        if (up.contains("RUGBY"))                                      return R.drawable.ic_sport_rugby;
        return R.drawable.ic_sport_soccer; // fallback
    }

    private void filterAndShowEvents(String category) {
        eventsList.removeAllViews();
        resetFocusCursor();

        List<View> animTargets = new ArrayList<>();
        int rowIdx = 0;
        for (StreamEvent event : allEvents) {
            if (!event.category.equals(category)) continue;
            View row = createEventRow(event, rowIdx++);
            eventsList.addView(row);
            animTargets.add(row);
            View div = new View(this);
            div.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1));
            div.setBackgroundColor(0xFF1E2A35);
            eventsList.addView(div);
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
        resetFocusCursor();
    }

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
        row.setGravity(Gravity.CENTER_VERTICAL);
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

    /**
     * Material 3 TV active indicator: stadium shape (cornerRadius = half item height = 28dp),
     * SecondaryContainer fill when selected, StateLayer (white 10%) on focus.
     */
    private void applyNavItemFocus(View v, boolean hasFocus, boolean isSelected) {
        android.graphics.drawable.GradientDrawable bg =
                new android.graphics.drawable.GradientDrawable();
        bg.setCornerRadius(dp(28)); // stadium (ShapeDefaults.Full for 56dp height)
        if (hasFocus && isSelected) {
            bg.setColor(0x404FC3F7); // SecondaryContainer tint + state layer
        } else if (hasFocus) {
            bg.setColor(0x1FFFFFFF); // state layer: white 12%
        } else if (isSelected) {
            applyNavSelection(v, true);
            return;
        } else {
            v.setBackground(null);
            return;
        }
        v.setBackground(bg);
    }

    private void applyNavSelection(View v, boolean selected) {
        if (selected) {
            // Active indicator: SecondaryContainer equivalent for our blue accent theme
            android.graphics.drawable.GradientDrawable sel =
                    new android.graphics.drawable.GradientDrawable();
            sel.setCornerRadius(dp(28)); // stadium shape
            sel.setColor(0x264FC3F7);    // blue SecondaryContainer at 15% opacity
            v.setBackground(sel);
        } else {
            v.setBackground(null);
        }
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
        lp.leftMargin = dp(88); // align cursor left edge with events_list paddingStart
        eventsListFrame.addView(focusCursor, lp);
    }

    private void resetFocusCursor() {
        if (focusCursor == null) return;
        focusCursor.animate().cancel();
        focusCursor.setAlpha(0f);
        focusCursor.setY(0f);
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
}
