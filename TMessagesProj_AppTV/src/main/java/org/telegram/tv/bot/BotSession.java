package org.telegram.tv.bot;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_account;
import org.telegram.tv.model.StreamEvent;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Encapsulates all Telegram API networking for the bot session:
 * username resolution, history check, /start, channel joining, mute+archive.
 *
 * All {@link Listener} callbacks are delivered on the main thread.
 */
public final class BotSession {

    public interface Listener {
        void onStatus(String message);
        /**
         * A named step changed state. key is stable across calls for the same step.
         * completed=false → step is active (show spinner); completed=true → done (show checkmark).
         */
        void onStep(String key, boolean completed, String label);
        /** Bot sent /start; waiting for the user to act on their phone. */
        void onWaitingForPhone();
        /** All access-required channels processed; still waiting for phone confirmation. */
        void onChannelsProcessed();
        /** Events calendar is ready to display. */
        void onEventsReady(MessageObject msg, List<StreamEvent> events);
    }

    private static final String TAG = "BotSession";

    private final int account;
    private final String botUsername;
    private final Listener listener;
    private final android.os.Handler handler =
        new android.os.Handler(android.os.Looper.getMainLooper());

    private long dialogId = 0;
    private boolean startSent = false;
    private TLRPC.InputPeer botPeer = null;
    private int lastSeenMessageId = 0;

    public BotSession(int account, String botUsername, Listener listener) {
        this.account     = account;
        this.botUsername = botUsername;
        this.listener    = listener;
    }

    // ─── Public API ────────────────────────────────────────────────────────────

    public void start() {
        listener.onStatus("Avvio TeleStream…");
        resolveBot();
    }

    public void restart() {
        startSent         = false;
        dialogId          = 0;
        botPeer           = null;
        lastSeenMessageId = 0;
        listener.onStatus("Avvio TeleStream…");
        resolveBot();
    }

    /** Returns a random delay in ms that simulates human interaction pace (1500–2500 ms). */
    private static int randomDelay() {
        return 1500 + (int) (Math.random() * 1000);
    }

    /** The bot's dialog ID; 0 until {@link #start()} resolves the username. */
    public long getDialogId() {
        return dialogId;
    }

    /**
     * Called from the Activity's {@code didReceivedNotification} for messages from the bot dialog.
     * Must be called on the main thread.
     *
     * @return true if the message was recognized and acted on (stop processing the batch).
     */
    public boolean handleMessage(MessageObject msg) {
        // Track the highest processed message ID so polling skips already-handled messages.
        lastSeenMessageId = Math.max(lastSeenMessageId, msg.messageOwner.id);
        if (msg.messageOwner.reply_markup instanceof TLRPC.TL_replyInlineMarkup) {
            TLRPC.TL_replyInlineMarkup markup = (TLRPC.TL_replyInlineMarkup) msg.messageOwner.reply_markup;
            for (TLRPC.TL_keyboardButtonRow row : markup.rows) {
                for (TLRPC.KeyboardButton btn : row.buttons) {
                    android.util.Log.d(TAG, "button type=" + btn.getClass().getSimpleName() + " text=" + btn.text);
                }
            }
        }
        android.util.Log.d(TAG, "handleMessage text=" +
            (msg.messageOwner.message != null
                ? msg.messageOwner.message.substring(0, Math.min(80, msg.messageOwner.message.length()))
                : "null"));

        if (MessageParser.isAccessRequiredMessage(msg)) {
            List<String> urls = MessageParser.extractChannelUrlsFromMarkup(msg);
            android.util.Log.d(TAG, "access required — " + urls.size() + " canali");
            int n = urls.size();
            listener.onStep("ch_header", false,
                "Accesso a " + n + (n == 1 ? " canale" : " canali") + "…");
            processChannelsSequentially(urls, 0, () -> listener.onChannelsProcessed());
            return true;
        }
        if (MessageParser.isEventsMessage(msg)) {
            List<StreamEvent> events = MessageParser.parseEventsMessage(msg);
            android.util.Log.d(TAG, "events parsed: " + events.size());
            if (!events.isEmpty()) {
                listener.onEventsReady(msg, events);
                return true;
            }
        }
        android.util.Log.d(TAG, "message not recognized");
        return false;
    }

    // ─── Bot resolution + history check ────────────────────────────────────────

    private void resolveBot() {
        listener.onStep("resolve", false, "Connessione al bot…");
        TLRPC.TL_contacts_resolveUsername req = new TLRPC.TL_contacts_resolveUsername();
        req.username = botUsername;
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) ->
            AndroidUtilities.runOnUIThread(() -> {
                if (!(response instanceof TLRPC.TL_contacts_resolvedPeer)) {
                    listener.onStatus("Errore: bot non trovato");
                    return;
                }
                TLRPC.TL_contacts_resolvedPeer resolved = (TLRPC.TL_contacts_resolvedPeer) response;
                MessagesController.getInstance(account).putUsers(resolved.users, false);
                MessagesController.getInstance(account).putChats(resolved.chats, false);
                if (resolved.peer instanceof TLRPC.TL_peerUser) {
                    dialogId = resolved.peer.user_id;
                    android.util.Log.d(TAG, "resolved: dialogId=" + dialogId);
                    TLRPC.User botUser = null;
                    for (TLRPC.User u : resolved.users) {
                        if (u.id == dialogId) { botUser = u; break; }
                    }
                    TLRPC.TL_inputPeerUser peer = new TLRPC.TL_inputPeerUser();
                    peer.user_id     = dialogId;
                    peer.access_hash = botUser != null ? botUser.access_hash : 0;
                    botPeer = peer;
                    listener.onStep("resolve", true, "Bot connesso");
                    listener.onStep("check", false, "Verifica ultimo messaggio…");
                    checkLastMessageAndProceed(peer);
                }
            })
        );
    }

    private void checkLastMessageAndProceed(TLRPC.InputPeer peer) {
        TLRPC.TL_messages_getHistory req = new TLRPC.TL_messages_getHistory();
        req.peer = peer;
        req.limit = 1;

        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) ->
            AndroidUtilities.runOnUIThread(() -> {
                TLRPC.Message lastMsg = null;
                if (response instanceof TLRPC.messages_Messages) {
                    ArrayList<TLRPC.Message> msgs = ((TLRPC.messages_Messages) response).messages;
                    if (!msgs.isEmpty()) lastMsg = msgs.get(0);
                }
                lastSeenMessageId = lastMsg != null ? lastMsg.id : 0;

                if (lastMsg == null || !isToday(lastMsg.date)) {
                    android.util.Log.d(TAG, "no message today — sending /start");
                    listener.onStep("check", true, "Nessun messaggio odierno");
                    listener.onStep("start", false, "Invio /start al bot…");
                    listener.onWaitingForPhone();
                    handler.postDelayed(() -> sendStart(), randomDelay());
                    return;
                }

                MessageObject msgObj = new MessageObject(account, lastMsg, false, false);
                if (MessageParser.isEventsMessage(msgObj)) {
                    android.util.Log.d(TAG, "today's events found — displaying directly");
                    List<StreamEvent> events = MessageParser.parseEventsMessage(msgObj);
                    if (!events.isEmpty()) {
                        listener.onStep("check", true, "Calendario odierno trovato");
                        listener.onEventsReady(msgObj, events);
                    } else {
                        listener.onStep("check", true, "Nessun calendario odierno");
                        listener.onStep("start", false, "Invio /start al bot…");
                        listener.onWaitingForPhone();
                        handler.postDelayed(() -> sendStart(), randomDelay());
                    }
                } else {
                    android.util.Log.d(TAG, "message today but not events — sending /start");
                    listener.onStep("check", true, "Nessun calendario odierno");
                    listener.onStep("start", false, "Invio /start al bot…");
                    listener.onWaitingForPhone();
                    handler.postDelayed(() -> sendStart(), randomDelay());
                }
            })
        );
    }

    private void sendStart() {
        if (startSent) return;
        startSent = true;
        SendMessagesHelper.getInstance(account).sendMessage(
            SendMessagesHelper.SendMessageParams.of(
                "/start", dialogId, null, null, null, false, null, null, null, true, 0, 0, null, false));
        listener.onStep("start", true, "/start inviato");
    }

    private static boolean isToday(int unixSeconds) {
        Calendar today  = Calendar.getInstance();
        Calendar msgCal = Calendar.getInstance();
        msgCal.setTimeInMillis(unixSeconds * 1000L);
        return today.get(Calendar.YEAR)        == msgCal.get(Calendar.YEAR)
            && today.get(Calendar.DAY_OF_YEAR) == msgCal.get(Calendar.DAY_OF_YEAR);
    }

    // ─── Channel joining: sequential, with per-channel timeouts ────────────────

    private void processChannelsSequentially(List<String> urls, int index, Runnable onAllDone) {
        if (index >= urls.size()) {
            listener.onStep("ch_header", true,
                urls.size() + (urls.size() == 1 ? " canale elaborato" : " canali elaborati"));
            onAllDone.run();
            return;
        }
        String key   = "ch_" + index;
        String label = "Canale " + (index + 1) + " di " + urls.size();
        listener.onStep(key, false, label + "…");
        processOneChannel(urls.get(index), () -> {
            listener.onStep(key, true, label);
            // Human-paced delay before processing the next channel
            handler.postDelayed(
                () -> processChannelsSequentially(urls, index + 1, onAllDone),
                randomDelay());
        });
    }

    private void processOneChannel(String url, Runnable onDone) {
        String hash = MessageParser.extractInviteHash(url);
        if (hash == null) { onDone.run(); return; }

        AtomicBoolean done = new AtomicBoolean(false);
        Runnable once = () -> { if (done.compareAndSet(false, true)) onDone.run(); };
        // Generous timeout: check + possible random delay + join + mute/archive
        handler.postDelayed(() -> {
            if (!done.get()) { android.util.Log.w(TAG, "checkChatInvite timeout"); once.run(); }
        }, 30000);

        TLRPC.TL_messages_checkChatInvite req = new TLRPC.TL_messages_checkChatInvite();
        req.hash = hash;
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) ->
            AndroidUtilities.runOnUIThread(() -> {
                try {
                    if (response instanceof TLRPC.TL_chatInviteAlready) {
                        // Already member: mute+archive immediately (no join needed)
                        TLRPC.Chat chat = ((TLRPC.TL_chatInviteAlready) response).chat;
                        MessagesController.getInstance(account).putChat(chat, false);
                        muteAndArchive(account, chat, once);
                    } else if (response != null) {
                        // Human-paced delay before the JOIN request to avoid rate-limiting
                        handler.postDelayed(() -> joinChannel(hash, once), randomDelay());
                    } else {
                        once.run();
                    }
                } catch (Exception e) {
                    android.util.Log.e(TAG, "checkChatInvite crash", e);
                    once.run();
                }
            })
        );
    }

    private void joinChannel(String hash, Runnable onDone) {
        AtomicBoolean done = new AtomicBoolean(false);
        Runnable once = () -> { if (done.compareAndSet(false, true)) onDone.run(); };
        handler.postDelayed(() -> {
            if (!done.get()) { android.util.Log.w(TAG, "importChatInvite timeout"); once.run(); }
        }, 15000);

        TLRPC.TL_messages_importChatInvite req = new TLRPC.TL_messages_importChatInvite();
        req.hash = hash;
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) ->
            AndroidUtilities.runOnUIThread(() -> {
                try {
                    if (error != null) {
                        android.util.Log.w(TAG, "importChatInvite error: " + error.text);
                        once.run();
                        return;
                    }
                    TLRPC.Chat chat = extractChatFromUpdates((TLRPC.Updates) response);
                    if (chat != null) {
                        MessagesController.getInstance(account).putChat(chat, false);
                        muteAndArchive(account, chat, once);
                    } else {
                        once.run();
                    }
                } catch (Exception e) {
                    android.util.Log.e(TAG, "importChatInvite crash", e);
                    once.run();
                }
            })
        );
    }

    // ─── Public static helpers (also used by TvMainActivity for the stream flow) ──

    public static void muteAndArchive(int account, TLRPC.Chat chat, Runnable onDone) {
        TL_account.updateNotifySettings muteReq = new TL_account.updateNotifySettings();
        TLRPC.TL_inputNotifyPeer inp = new TLRPC.TL_inputNotifyPeer();
        inp.peer = chatToInputPeer(chat);
        muteReq.peer = inp;
        TLRPC.TL_inputPeerNotifySettings ns = new TLRPC.TL_inputPeerNotifySettings();
        ns.flags |= 4; // mute_until field is present
        ns.mute_until = Integer.MAX_VALUE;
        muteReq.settings = ns;

        ConnectionsManager.getInstance(account).sendRequest(muteReq, (r, e) -> {
            TLRPC.TL_folders_editPeerFolders archiveReq = new TLRPC.TL_folders_editPeerFolders();
            TLRPC.TL_inputFolderPeer fp = new TLRPC.TL_inputFolderPeer();
            fp.peer = chatToInputPeer(chat);
            fp.folder_id = 1;
            archiveReq.folder_peers.add(fp);
            ConnectionsManager.getInstance(account).sendRequest(archiveReq, (response, error) ->
                AndroidUtilities.runOnUIThread(() -> {
                    android.util.Log.d(TAG, "archive: " + (error != null ? error.text : "OK"));
                    onDone.run();
                })
            );
        });
    }

    public static TLRPC.InputPeer chatToInputPeer(TLRPC.Chat chat) {
        if (chat instanceof TLRPC.TL_channel || chat instanceof TLRPC.TL_channelForbidden) {
            TLRPC.TL_inputPeerChannel p = new TLRPC.TL_inputPeerChannel();
            p.channel_id  = chat.id;
            p.access_hash = chat.access_hash;
            return p;
        }
        TLRPC.TL_inputPeerChat p = new TLRPC.TL_inputPeerChat();
        p.chat_id = chat.id;
        return p;
    }

    /**
     * Re-runs the same check as {@link #checkLastMessageAndProceed} (identical request params)
     * without the sendStart fallback. Used as a periodic fallback while waiting for events, in
     * case the push notification was missed or the MTProto connection wasn't ready when the bot
     * replied.
     */
    public void pollForNewMessage() {
        if (botPeer == null || dialogId == 0) return;

        // Use identical parameters to checkLastMessageAndProceed so the request
        // is treated the same way by both client and server.
        TLRPC.TL_messages_getHistory req = new TLRPC.TL_messages_getHistory();
        req.peer        = botPeer;
        req.limit       = 1;
        req.offset_id   = 0;
        req.offset_date = 0;
        req.add_offset  = 0;
        req.max_id      = 0;
        req.min_id      = 0;
        req.hash        = 0;

        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) ->
            AndroidUtilities.runOnUIThread(() -> {
                if (!(response instanceof TLRPC.messages_Messages)) return;
                ArrayList<TLRPC.Message> msgs = ((TLRPC.messages_Messages) response).messages;
                if (msgs.isEmpty()) return;
                TLRPC.Message raw = msgs.get(0);
                android.util.Log.d(TAG, "poll: id=" + raw.id + " out=" + raw.out
                    + " today=" + isToday(raw.date)
                    + " entities=" + (raw.entities != null ? raw.entities.size() : "null"));
                if (raw.out) return;
                MessageObject msgObj = new MessageObject(account, raw, false, false);
                if (MessageParser.isEventsMessage(msgObj)) {
                    List<StreamEvent> events = MessageParser.parseEventsMessage(msgObj);
                    if (!events.isEmpty()) {
                        android.util.Log.d(TAG, "poll: found " + events.size() + " events → showing");
                        listener.onEventsReady(msgObj, events);
                    }
                }
            })
        );
    }

    public static TLRPC.Chat extractChatFromUpdates(TLRPC.Updates updates) {
        if (updates instanceof TLRPC.TL_updates) {
            ArrayList<TLRPC.Chat> chats = ((TLRPC.TL_updates) updates).chats;
            if (chats != null && !chats.isEmpty()) return chats.get(0);
        } else if (updates instanceof TLRPC.TL_updatesCombined) {
            ArrayList<TLRPC.Chat> chats = ((TLRPC.TL_updatesCombined) updates).chats;
            if (chats != null && !chats.isEmpty()) return chats.get(0);
        }
        return null;
    }
}
