package org.telegram.tv;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.tv.R;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_account;
import org.telegram.ui.Stories.LivePlayer;
import org.telegram.ui.Stories.recorder.LivePlayerView;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TvMainActivity extends Activity implements NotificationCenter.NotificationCenterDelegate {

    private static final String BOT_USERNAME = "CherryStreaming_cbot";

    private final int account = UserConfig.selectedAccount;
    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());

    private long targetDialogId = 0;
    private boolean startSent = false;

    // UI — loading
    private LinearLayout loadingContainer;
    private TextView statusText;

    // UI — events table
    private View eventsContainer;
    private TextView eventsTitle;
    private LinearLayout eventsList;

    // UI — streaming player
    private View playerContainer;
    private View streamLoading;
    private View streamTopBar;
    private TextView streamEventTitle;
    private TextView streamStatus;
    private FrameLayout streamPlayerContainer;

    // Native live stream player (tgcalls-based, handles Telegram's proprietary RTMP format)
    private LivePlayer livePlayer;
    private LivePlayerView livePlayerView;

    // ─────────────────────────────────────────────────────────────────────────
    // Data model
    // ─────────────────────────────────────────────────────────────────────────

    static class StreamEvent {
        final String category;
        final String eventName;
        final String time;
        final String channelUrl; // t.me/+xxx

        StreamEvent(String c, String e, String t, String u) {
            category = c; eventName = e; time = t; channelUrl = u;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_tv_main);

        loadingContainer  = findViewById(R.id.loading_container);
        statusText        = findViewById(R.id.status_text);
        eventsContainer   = findViewById(R.id.events_container);
        eventsTitle       = findViewById(R.id.events_title);
        eventsList        = findViewById(R.id.events_list);
        playerContainer   = findViewById(R.id.player_container);
        streamLoading          = findViewById(R.id.stream_loading);
        streamTopBar           = findViewById(R.id.stream_top_bar);
        streamEventTitle       = findViewById(R.id.stream_event_title);
        streamStatus           = findViewById(R.id.stream_status);
        streamPlayerContainer  = findViewById(R.id.stream_player_container);

        NotificationCenter.getInstance(account).addObserver(
            this, NotificationCenter.didReceiveNewMessages);

        android.util.Log.d("TvMain", "onCreate account=" + account
            + " isActivated=" + UserConfig.getInstance(account).isClientActivated());
        setStatus("Connessione…");
        resolveBot();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        NotificationCenter.getInstance(account).removeObserver(
            this, NotificationCenter.didReceiveNewMessages);
        if (livePlayer != null) { livePlayer.destroy(); livePlayer = null; }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Stop stream when user presses HOME, switches app, or leaves in any way —
        // prevents audio playing in background.
        if (livePlayer != null) {
            livePlayer.destroy();
            livePlayer = null;
        }
        if (livePlayerView != null && streamPlayerContainer != null) {
            streamPlayerContainer.removeView(livePlayerView);
            livePlayerView = null;
        }
        if (playerContainer.getVisibility() == View.VISIBLE) {
            playerContainer.setVisibility(View.GONE);
            streamLoading.setVisibility(View.GONE);
            streamTopBar.setVisibility(View.VISIBLE);
            eventsContainer.setVisibility(View.VISIBLE);
            if (eventsList.getChildCount() > 0) {
                eventsList.getChildAt(0).requestFocus();
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Re-subscribe in case it was removed during streaming (startLivePlayer removes it).
        // Use remove+add to avoid double-registration.
        NotificationCenter.getInstance(account).removeObserver(this, NotificationCenter.didReceiveNewMessages);
        NotificationCenter.getInstance(account).addObserver(this, NotificationCenter.didReceiveNewMessages);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (playerContainer.getVisibility() == View.VISIBLE) {
                closePlayer();
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Bot resolution and /start
    // ─────────────────────────────────────────────────────────────────────────

    private void resolveBot() {
        TLRPC.TL_contacts_resolveUsername req = new TLRPC.TL_contacts_resolveUsername();
        req.username = BOT_USERNAME;
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) ->
            AndroidUtilities.runOnUIThread(() -> {
                if (!(response instanceof TLRPC.TL_contacts_resolvedPeer)) {
                    setStatus("Errore: bot non trovato");
                    return;
                }
                TLRPC.TL_contacts_resolvedPeer resolved = (TLRPC.TL_contacts_resolvedPeer) response;
                MessagesController.getInstance(account).putUsers(resolved.users, false);
                MessagesController.getInstance(account).putChats(resolved.chats, false);
                if (resolved.peer instanceof TLRPC.TL_peerUser) {
                    targetDialogId = resolved.peer.user_id;
                    android.util.Log.d("TvMain", "resolveBot: peer=TL_peerUser dialogId=" + targetDialogId);
                    TLRPC.User botUser = null;
                    for (TLRPC.User u : resolved.users) {
                        if (u.id == targetDialogId) { botUser = u; break; }
                    }
                    TLRPC.TL_inputPeerUser peer = new TLRPC.TL_inputPeerUser();
                    peer.user_id = targetDialogId;
                    peer.access_hash = botUser != null ? botUser.access_hash : 0;
                    checkLastMessageAndProceed(peer);
                }
            })
        );
    }

    private void checkLastMessageAndProceed(TLRPC.InputPeer peer) {
        setStatus("Controllo ultimo messaggio…");

        TLRPC.TL_messages_getHistory req = new TLRPC.TL_messages_getHistory();
        req.peer = peer;
        req.limit = 1;
        req.offset_id = 0;
        req.offset_date = 0;
        req.add_offset = 0;
        req.max_id = 0;
        req.min_id = 0;
        req.hash = 0;

        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) ->
            AndroidUtilities.runOnUIThread(() -> {
                TLRPC.Message lastMsg = null;
                if (response instanceof TLRPC.messages_Messages) {
                    ArrayList<TLRPC.Message> msgs = ((TLRPC.messages_Messages) response).messages;
                    if (!msgs.isEmpty()) lastMsg = msgs.get(0);
                }

                if (lastMsg == null || !isToday(lastMsg.date)) {
                    android.util.Log.d("TvMain", "no message today — sending /start");
                    sendStart();
                    showWaitingForPhone();
                    return;
                }

                MessageObject msgObj = new MessageObject(account, lastMsg, false, false);
                if (isEventsMessage(msgObj)) {
                    android.util.Log.d("TvMain", "last message is today's events — parsing directly, no /start");
                    List<StreamEvent> events = parseEventsMessage(msgObj);
                    if (!events.isEmpty()) {
                        showEventsTable(msgObj, events);
                    } else {
                        sendStart();
                        showWaitingForPhone();
                    }
                } else {
                    android.util.Log.d("TvMain", "last message today but not events — sending /start");
                    sendStart();
                    showWaitingForPhone();
                }
            })
        );
    }

    private boolean isToday(int unixSeconds) {
        java.util.Calendar today = java.util.Calendar.getInstance();
        java.util.Calendar msgCal = java.util.Calendar.getInstance();
        msgCal.setTimeInMillis(unixSeconds * 1000L);
        return today.get(java.util.Calendar.YEAR) == msgCal.get(java.util.Calendar.YEAR)
            && today.get(java.util.Calendar.DAY_OF_YEAR) == msgCal.get(java.util.Calendar.DAY_OF_YEAR);
    }

    private void showWaitingForPhone() {
        loadingContainer.setVisibility(View.VISIBLE);
        eventsContainer.setVisibility(View.GONE);
        statusText.setText(
            "Apri la mini app dal tuo smartphone\nper completare la verifica.\n\nIn attesa del calendario eventi…");
    }

    private void sendStart() {
        if (startSent) return;
        startSent = true;
        setStatus("Invio /start…");
        SendMessagesHelper.getInstance(account).sendMessage(
            SendMessagesHelper.SendMessageParams.of("/start", targetDialogId, null, null, null, false, null, null, null, true, 0, 0, null, false));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // New message listener
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public void didReceivedNotification(int id, int acc, Object... args) {
        if (id != NotificationCenter.didReceiveNewMessages) return;
        long dialogId = (Long) args[0];
        android.util.Log.d("TvMain", "didReceiveNewMessages dialogId=" + dialogId
            + " targetDialogId=" + targetDialogId);
        if (dialogId != targetDialogId) return;

        ArrayList<MessageObject> msgs = (ArrayList<MessageObject>) args[1];
        for (MessageObject msg : msgs) {
            if (msg.isOut()) continue;
            if (msg.messageOwner == null) continue;
            displayMessage(msg);
            break; // handle only the first incoming message
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Message type detection and dispatch
    // ─────────────────────────────────────────────────────────────────────────

    private void displayMessage(MessageObject msg) {
        // Log all keyboard buttons for debugging
        if (msg.messageOwner.reply_markup instanceof TLRPC.TL_replyInlineMarkup) {
            TLRPC.TL_replyInlineMarkup markup = (TLRPC.TL_replyInlineMarkup) msg.messageOwner.reply_markup;
            for (TLRPC.TL_keyboardButtonRow row : markup.rows) {
                for (TLRPC.KeyboardButton btn : row.buttons) {
                    if (btn instanceof TLRPC.TL_keyboardButtonUrl)
                        android.util.Log.d("TvMain", "button [" + btn.text + "] url=" + ((TLRPC.TL_keyboardButtonUrl) btn).url);
                    else if (btn instanceof TLRPC.TL_keyboardButtonCallback)
                        android.util.Log.d("TvMain", "callback button [" + btn.text + "]");
                    else
                        android.util.Log.d("TvMain", "button [" + btn.text + "]");
                }
            }
        }

        if (isAccessRequiredMessage(msg)) {
            handleAccessRequired(msg);
        } else if (isEventsMessage(msg)) {
            List<StreamEvent> events = parseEventsMessage(msg);
            android.util.Log.d("TvMain", "events parsed: " + events.size());
            if (!events.isEmpty()) {
                showEventsTable(msg, events);
            }
        } else {
            android.util.Log.d("TvMain", "unrecognized message — ignoring");
        }
    }

    private boolean isAccessRequiredMessage(MessageObject msg) {
        if (!(msg.messageOwner.reply_markup instanceof TLRPC.TL_replyInlineMarkup)) return false;
        TLRPC.TL_replyInlineMarkup markup = (TLRPC.TL_replyInlineMarkup) msg.messageOwner.reply_markup;
        for (TLRPC.TL_keyboardButtonRow row : markup.rows) {
            for (TLRPC.KeyboardButton btn : row.buttons) {
                if (btn instanceof TLRPC.TL_keyboardButtonCallback) return true;
                if (btn instanceof TLRPC.TL_keyboardButtonUrl
                        && isInviteLink(((TLRPC.TL_keyboardButtonUrl) btn).url)) return true;
            }
        }
        return false;
    }

    private boolean isEventsMessage(MessageObject msg) {
        String text = msg.messageOwner.message;
        if (text == null) return false;
        if (!text.contains("EVENTI IN PROGRAMMA") && !text.contains("EVENTI")) return false;
        // Must have at least one invite link entity in the text body
        if (msg.messageOwner.entities == null) return false;
        for (TLRPC.MessageEntity e : msg.messageOwner.entities) {
            if (e instanceof TLRPC.TL_messageEntityTextUrl
                    && isInviteLink(((TLRPC.TL_messageEntityTextUrl) e).url)) return true;
        }
        return false;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Access required flow: join channels + mute + archive + show waiting msg
    // ─────────────────────────────────────────────────────────────────────────

    private void handleAccessRequired(MessageObject msg) {
        List<String> channelUrls = new ArrayList<>();
        if (msg.messageOwner.reply_markup instanceof TLRPC.TL_replyInlineMarkup) {
            for (TLRPC.TL_keyboardButtonRow row : ((TLRPC.TL_replyInlineMarkup) msg.messageOwner.reply_markup).rows) {
                for (TLRPC.KeyboardButton btn : row.buttons) {
                    if (btn instanceof TLRPC.TL_keyboardButtonUrl) {
                        String url = ((TLRPC.TL_keyboardButtonUrl) btn).url;
                        if (isInviteLink(url) && !btn.text.contains("✅")) {
                            channelUrls.add(url);
                        }
                    }
                }
            }
        }

        android.util.Log.d("TvMain", "access required — " + channelUrls.size() + " canali da processare");
        setStatus("Elaborazione " + channelUrls.size() + " canali…");

        processChannelsSequentially(channelUrls, 0, () -> {
            android.util.Log.d("TvMain", "channels done — waiting for smartphone verification");
            loadingContainer.setVisibility(View.VISIBLE);
            statusText.setText(
                "✅ Canali verificati.\n\nApri la mini app dal tuo smartphone\nper completare la verifica.\n\nIn attesa del calendario eventi…");
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Channel processing: join, mute, archive
    // ─────────────────────────────────────────────────────────────────────────

    private void processChannelsSequentially(List<String> urls, int index, Runnable onAllDone) {
        setStatus("ricevuti " + urls.size() + " canali, " + index + " index");
        if (index >= urls.size()) { 
            onAllDone.run(); 
            return; 
        }
        processOneChannel(urls.get(index),
            () -> processChannelsSequentially(urls, index + 1, onAllDone));
    }

    private void processOneChannel(String url, Runnable onDone) {
        String hash = extractInviteHash(url);
        if (hash == null) { onDone.run(); return; }

        java.util.concurrent.atomic.AtomicBoolean done = new java.util.concurrent.atomic.AtomicBoolean(false);
        Runnable once = () -> { if (done.compareAndSet(false, true)) onDone.run(); };
        handler.postDelayed(() -> { if (!done.get()) { android.util.Log.w("TvMain", "checkChatInvite timeout"); once.run(); } }, 15000);

        TLRPC.TL_messages_checkChatInvite req = new TLRPC.TL_messages_checkChatInvite();
        req.hash = hash;
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) ->
            AndroidUtilities.runOnUIThread(() -> {
                try {
                    if (response instanceof TLRPC.TL_chatInviteAlready) {
                        TLRPC.Chat chat = ((TLRPC.TL_chatInviteAlready) response).chat;
                        MessagesController.getInstance(account).putChat(chat, false);
                        muteAndArchive(chat, once);
                    } else if (response != null) {
                        joinChannel(hash, once);
                    } else {
                        once.run();
                    }
                } catch (Exception e) {
                    android.util.Log.e("TvMain", "checkChatInvite crash", e);
                    once.run();
                }
            })
        );
    }

    private void joinChannel(String hash, Runnable onDone) {
        java.util.concurrent.atomic.AtomicBoolean done = new java.util.concurrent.atomic.AtomicBoolean(false);
        Runnable once = () -> { if (done.compareAndSet(false, true)) onDone.run(); };
        handler.postDelayed(() -> { if (!done.get()) { android.util.Log.w("TvMain", "importChatInvite timeout"); once.run(); } }, 15000);

        TLRPC.TL_messages_importChatInvite req = new TLRPC.TL_messages_importChatInvite();
        req.hash = hash;
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) ->
            AndroidUtilities.runOnUIThread(() -> {
                try {
                    if (error != null) { android.util.Log.w("TvMain", "importChatInvite error: " + error.text); once.run(); return; }
                    TLRPC.Chat chat = null;
                    if (response instanceof TLRPC.TL_updates) {
                        chat = extractChatFromUpdates((TLRPC.Updates) response);
                    }
                    if (chat != null) {
                        MessagesController.getInstance(account).putChat(chat, false);
                        muteAndArchive(chat, once);
                    } else {
                        once.run();
                    }
                } catch (Exception e) {
                    android.util.Log.e("TvMain", "importChatInvite crash", e);
                    once.run();
                }
            })
        );
    }

    private void muteAndArchive(TLRPC.Chat chat, Runnable onDone) {
        // Mute forever
        TL_account.updateNotifySettings muteReq = new TL_account.updateNotifySettings();
        TLRPC.TL_inputNotifyPeer inp = new TLRPC.TL_inputNotifyPeer();
        inp.peer = chatToInputPeer(chat);
        muteReq.peer = inp;
        TLRPC.TL_inputPeerNotifySettings ns = new TLRPC.TL_inputPeerNotifySettings();
        ns.flags |= 4; // FLAG_2: mute_until present
        ns.mute_until = Integer.MAX_VALUE;
        muteReq.settings = ns;
        ConnectionsManager.getInstance(account).sendRequest(muteReq, (r, e) -> {
            // Archive (folder_id = 1)
            TLRPC.TL_folders_editPeerFolders archiveReq = new TLRPC.TL_folders_editPeerFolders();
            TLRPC.TL_inputFolderPeer fp = new TLRPC.TL_inputFolderPeer();
            fp.peer = chatToInputPeer(chat);
            fp.folder_id = 1;
            archiveReq.folder_peers.add(fp);
            ConnectionsManager.getInstance(account).sendRequest(archiveReq, (response, error) ->
                AndroidUtilities.runOnUIThread(() -> {
                    android.util.Log.d("TvMain", "archive: " + (error != null ? error.text : "OK"));
                    onDone.run();
                })
            );
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Events message parsing
    // ─────────────────────────────────────────────────────────────────────────

    private List<StreamEvent> parseEventsMessage(MessageObject msg) {
        List<StreamEvent> events = new ArrayList<>();
        if (msg.messageOwner == null || msg.messageOwner.message == null) return events;

        String text = msg.messageOwner.message;
        List<TLRPC.MessageEntity> entities = msg.messageOwner.entities;
        if (entities == null || entities.isEmpty()) return events;

        // Build line start offsets for quick lookup
        String[] lines = text.split("\n", -1);
        int[] lineStarts = new int[lines.length];
        int pos = 0;
        for (int i = 0; i < lines.length; i++) {
            lineStarts[i] = pos;
            pos += lines[i].length() + 1;
        }

        Pattern timePattern = Pattern.compile("\\((\\d{2}/\\d{2})\\)\\s+(\\d{2}:\\d{2})");

        for (TLRPC.MessageEntity entity : entities) {
            if (!(entity instanceof TLRPC.TL_messageEntityTextUrl)) continue;
            String url = ((TLRPC.TL_messageEntityTextUrl) entity).url;
            if (!isInviteLink(url)) continue;

            int offset = entity.offset;
            int end = Math.min(offset + entity.length, text.length());
            String eventName = text.substring(offset, end).trim();

            // Find entity's line index
            int entityLineIdx = 0;
            for (int i = 0; i < lineStarts.length; i++) {
                if (lineStarts[i] <= offset) entityLineIdx = i;
                else break;
            }

            // Search backward for the category line (emoji + uppercase name)
            String category = "";
            for (int i = entityLineIdx - 1; i >= 0; i--) {
                String line = lines[i].trim();
                if (line.isEmpty()) continue;
                if (looksLikeCategory(line)) {
                    category = stripLeadingSymbols(line);
                    break;
                }
            }

            // Search forward for "(DD/MM) HH:MM"
            String time = "";
            for (int i = entityLineIdx + 1; i < lines.length; i++) {
                String line = lines[i].trim();
                if (line.isEmpty()) break;
                Matcher m = timePattern.matcher(line);
                if (m.find()) { time = m.group(1) + " " + m.group(2); break; }
                // Stop if another URL entity starts on this line
                boolean hasUrl = false;
                for (TLRPC.MessageEntity ent : entities) {
                    if (ent instanceof TLRPC.TL_messageEntityTextUrl
                            && ent.offset >= lineStarts[i]
                            && ent.offset < lineStarts[i] + lines[i].length()) {
                        hasUrl = true;
                        break;
                    }
                }
                if (hasUrl) break;
            }

            android.util.Log.d("TvMain", "event: [" + category + "] " + eventName + " @ " + time + " → " + url);
            if (time.isEmpty()) continue; // promo/footer links have no time — not a real event
            events.add(new StreamEvent(category, eventName, time, url));
        }

        return events;
    }

    private boolean looksLikeCategory(String line) {
        if (line.isEmpty()) return false;
        int cp = line.codePointAt(0);
        // Line starts with emoji (code point above basic symbols block)
        if (cp > 0x2000) return true;
        // Or line is mostly uppercase letters
        String letters = line.replaceAll("[^a-zA-Z]", "");
        return letters.length() >= 3 && letters.equals(letters.toUpperCase());
    }

    private String stripLeadingSymbols(String line) {
        int i = 0;
        while (i < line.length()) {
            int cp = line.codePointAt(i);
            if (Character.isLetter(cp)) break;
            i += Character.charCount(cp);
        }
        return line.substring(i).trim();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Events table UI
    // ─────────────────────────────────────────────────────────────────────────

    private void showEventsTable(MessageObject msg, List<StreamEvent> events) {
        // Extract title from message (first non-empty line)
        String title = "📌 EVENTI IN PROGRAMMA";
        if (msg.messageOwner.message != null) {
            for (String line : msg.messageOwner.message.split("\n")) {
                if (!line.trim().isEmpty()) { title = line.trim(); break; }
            }
        }
        eventsTitle.setText(title);

        eventsList.removeAllViews();
        String prevCategory = "";
        for (StreamEvent event : events) {
            eventsList.addView(createEventRow(event, prevCategory));
            // Divider
            View div = new View(this);
            div.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1));
            div.setBackgroundColor(0xFF1E2A35);
            eventsList.addView(div);
            prevCategory = event.category;
        }

        loadingContainer.setVisibility(View.GONE);
        eventsContainer.setVisibility(View.VISIBLE);

        // Focus first event row
        if (eventsList.getChildCount() > 0) {
            eventsList.getChildAt(0).requestFocus();
        }
    }

    private View createEventRow(StreamEvent event, String prevCategory) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setFocusable(true);
        row.setClickable(true);
        row.setFocusableInTouchMode(false);
        row.setPadding(dp(16), dp(18), dp(16), dp(18));
        row.setBackground(null);

        row.setOnFocusChangeListener((v, hasFocus) ->
            v.setBackgroundColor(hasFocus ? 0xFF1E3A5F : 0x00000000));

        // Category column (show only when changed)
        TextView catView = new TextView(this);
        LinearLayout.LayoutParams catLp = new LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 3f);
        catView.setLayoutParams(catLp);
        catView.setText(event.category.equals(prevCategory) ? "" : event.category);
        catView.setTextColor(0xFF4FC3F7);
        catView.setTextSize(18f);
        catView.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(catView);

        // Event name column
        TextView nameView = new TextView(this);
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 5f);
        nameView.setLayoutParams(nameLp);
        nameView.setText(event.eventName);
        nameView.setTextColor(0xFFE8EDF0);
        nameView.setTextSize(20f);
        nameView.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(nameView);

        // Time column
        TextView timeView = new TextView(this);
        LinearLayout.LayoutParams timeLp = new LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 2f);
        timeView.setLayoutParams(timeLp);
        timeView.setText(event.time);
        timeView.setTextColor(0xFF99AABB);
        timeView.setTextSize(20f);
        timeView.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        row.addView(timeView);

        row.setOnClickListener(v -> openEventStream(event));

        return row;
    }

    private int dp(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density + 0.5f);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Streaming player (LivePlayer — tgcalls native, handles Telegram RTMP format)
    // ─────────────────────────────────────────────────────────────────────────

    private void openEventStream(StreamEvent event) {
        android.util.Log.d("TvMain", "openEventStream: " + event.eventName + " → " + event.channelUrl);
        showStreamLoading("Connessione a " + event.eventName + "…");

        String hash = extractInviteHash(event.channelUrl);
        if (hash == null) { showStreamError("Link non valido"); return; }

        // Check if already a member
        java.util.concurrent.atomic.AtomicBoolean done = new java.util.concurrent.atomic.AtomicBoolean(false);
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
                    android.util.Log.d("TvMain", "already member of channel: " + chat.title + " (id=" + chat.id + ")");
                    MessagesController.getInstance(account).putChat(chat, false);
                    getAndPlayStream(chat, event);
                } else {
                    // Not a member — join channel, mute it, then get stream
                    joinChannelForStream(hash, event);
                }
            })
        );
    }

    private void joinChannelForStream(String hash, StreamEvent event) {
        showStreamLoading("Richiesta accesso a " + event.eventName + "…");

        java.util.concurrent.atomic.AtomicBoolean done = new java.util.concurrent.atomic.AtomicBoolean(false);
        handler.postDelayed(() -> {
            if (!done.get()) showStreamError("Timeout join canale");
        }, 15000);

        TLRPC.TL_messages_importChatInvite req = new TLRPC.TL_messages_importChatInvite();
        req.hash = hash;
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) ->
            AndroidUtilities.runOnUIThread(() -> {
                if (!done.compareAndSet(false, true)) return;
                if (error != null) { showStreamError("Accesso negato: " + error.text); return; }
                TLRPC.Chat chat = extractChatFromUpdates((TLRPC.Updates) response);
                if (chat == null) { showStreamError("Canale non trovato"); return; }
                android.util.Log.d("TvMain", "joined channel: " + chat.title + " (id=" + chat.id + ")");
                MessagesController.getInstance(account).putChat(chat, false);
                // Mute silently (don't wait for completion)
                muteAndArchive(chat, () -> {});
                getAndPlayStream(chat, event);
            })
        );
    }

    private void getAndPlayStream(TLRPC.Chat chat, StreamEvent event) {
        android.util.Log.d("TvMain", "getAndPlayStream: chat=" + chat.title + " (id=" + chat.id + ")");
        showStreamLoading("Connessione allo stream…");

        TLRPC.TL_channels_getFullChannel req = new TLRPC.TL_channels_getFullChannel();
        TLRPC.TL_inputChannel ic = new TLRPC.TL_inputChannel();
        ic.channel_id = chat.id;
        ic.access_hash = chat.access_hash;
        req.channel = ic;

        java.util.concurrent.atomic.AtomicBoolean done = new java.util.concurrent.atomic.AtomicBoolean(false);
        handler.postDelayed(() -> {
            if (done.compareAndSet(false, true)) {
                android.util.Log.w("TvMain", "getAndPlayStream: TIMEOUT");
                showStreamError("Timeout recupero canale");
            }
        }, 15000);

        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) ->
            AndroidUtilities.runOnUIThread(() -> {
                if (!done.compareAndSet(false, true)) return;
                android.util.Log.d("TvMain", "getFullChannel: " + (error != null ? "error=" + error.text : response.getClass().getSimpleName()));
                if (error != null) { showStreamError("Errore canale: " + error.text); return; }
                if (!(response instanceof TLRPC.TL_messages_chatFull)) { showStreamError("Dati canale non disponibili"); return; }
                TLRPC.ChatFull full = ((TLRPC.TL_messages_chatFull) response).full_chat;
                if (full.call == null) { showStreamError("Nessuna live stream attiva in questo canale"); return; }
                android.util.Log.d("TvMain", "channel has active call id=" + full.call.id);
                startLivePlayer(chat, full.call, event);
            })
        );
    }

    private void startLivePlayer(TLRPC.Chat chat, TLRPC.InputGroupCall callRef, StreamEvent event) {
        // Destroy previous player if any
        if (livePlayer != null) { livePlayer.destroy(); livePlayer = null; }
        if (livePlayerView != null && streamPlayerContainer != null) {
            streamPlayerContainer.removeView(livePlayerView);
            livePlayerView = null;
        }

        streamEventTitle.setText(event.category + " — " + event.eventName + "  " + event.time);
        playerContainer.setVisibility(View.VISIBLE);
        eventsContainer.setVisibility(View.GONE);
        streamLoading.setVisibility(View.VISIBLE);

        // dialogId for broadcast channels is -(chat.id)
        long dialogId = -(long) chat.id;

        // LivePlayer internally handles: join via native tgcalls (proper ICE/SDP),
        // RTMP stream segment download via upload.getFile, and FFmpeg decoding
        // of Telegram's proprietary segment format.
        livePlayer = new LivePlayer(this, account, null, dialogId, 0, true, callRef);

        // TextureViewRenderer (isSurfaceView=false): SurfaceViewRenderer crashes on TV because
        // LivePlayerView.onFirstFrameRendered() calls .animate().start() from the GL thread
        // (no Looper) — AndroidRuntimeException: Animators may only be run on Looper threads.
        livePlayerView = new LivePlayerView(this, account, false);
        streamPlayerContainer.addView(livePlayerView,
            new FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT));

        livePlayer.setDisplaySink(livePlayerView.getSink());

        // Stop receiving channel notification floods while streaming — the continuous
        // didReceiveNewMessages updates (group call participant events from all joined
        // channels) saturate the main thread and cause video frame drops + A/V jitter.
        NotificationCenter.getInstance(account).removeObserver(this, NotificationCenter.didReceiveNewMessages);

        // Hint the display to prefer a refresh rate matching the stream frame rate.
        // Reduces judder from frame rate mismatch on TV panels (e.g. 25fps stream on 60Hz).
        android.view.WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.preferredRefreshRate = 25f;
        getWindow().setAttributes(lp);

        android.util.Log.d("TvMain", "LivePlayer started for call=" + callRef.id + " dialogId=" + dialogId);

        // After 4s: hide loading spinner and top bar (stream should be playing by then).
        // LivePlayer has no explicit "stream ready" callback for RTMP viewers.
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
        if (livePlayer != null) { livePlayer.destroy(); livePlayer = null; }
        if (livePlayerView != null && streamPlayerContainer != null) {
            streamPlayerContainer.removeView(livePlayerView);
            livePlayerView = null;
        }
        // Re-subscribe so the events table can receive bot messages again
        NotificationCenter.getInstance(account).addObserver(this, NotificationCenter.didReceiveNewMessages);
        playerContainer.setVisibility(View.GONE);
        streamLoading.setVisibility(View.GONE);
        streamTopBar.setVisibility(View.VISIBLE); // restore for next stream
        eventsContainer.setVisibility(View.VISIBLE);
        if (eventsList.getChildCount() > 0) {
            eventsList.getChildAt(0).requestFocus();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private boolean isInviteLink(String url) {
        return url != null && (url.startsWith("https://t.me/+") || url.startsWith("t.me/+")
            || url.startsWith("https://t.me/joinchat/") || url.startsWith("t.me/joinchat/"));
    }

    private String extractInviteHash(String url) {
        if (url == null) return null;
        if (url.contains("/+")) return url.substring(url.lastIndexOf("/+") + 2);
        if (url.contains("/joinchat/")) return url.substring(url.lastIndexOf("/joinchat/") + 10);
        return null;
    }

    private TLRPC.InputPeer chatToInputPeer(TLRPC.Chat chat) {
        if (chat instanceof TLRPC.TL_channel || chat instanceof TLRPC.TL_channelForbidden) {
            TLRPC.TL_inputPeerChannel p = new TLRPC.TL_inputPeerChannel();
            p.channel_id = chat.id;
            p.access_hash = chat.access_hash;
            return p;
        }
        TLRPC.TL_inputPeerChat p = new TLRPC.TL_inputPeerChat();
        p.chat_id = chat.id;
        return p;
    }

    private static TLRPC.Chat extractChatFromUpdates(TLRPC.Updates updates) {
        if (updates instanceof TLRPC.TL_updates) {
            ArrayList<TLRPC.Chat> chats = ((TLRPC.TL_updates) updates).chats;
            if (chats != null && !chats.isEmpty()) return chats.get(0);
        } else if (updates instanceof TLRPC.TL_updatesCombined) {
            ArrayList<TLRPC.Chat> chats = ((TLRPC.TL_updatesCombined) updates).chats;
            if (chats != null && !chats.isEmpty()) return chats.get(0);
        }
        return null;
    }

    private void setStatus(String msg) {
        android.util.Log.d("TvMain", "status: " + msg);
        AndroidUtilities.runOnUIThread(() -> statusText.setText(msg));
    }
}
