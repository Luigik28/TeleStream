package org.telegram.tv.ui;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessagesController;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_phone;
import org.telegram.tv.bot.MessageParser;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves the current viewer count (participants_count) of the live stream on a channel,
 * from its t.me invite link. Caches by invite hash so multiple events on the same channel
 * only resolve it once. Must be called on the main thread; callbacks are delivered there too.
 *
 * The resolve chain is checkChatInvite → channels.getFullChannel → phone.getGroupCall — this
 * only works for channels the account has already joined (checkChatInvite otherwise won't
 * return the chat object needed for the next step), which BotSession already ensures for every
 * channel referenced by a listed event.
 */
public final class EventStatsLoader {

    private static final String TAG = "EventStatsLoader";
    public static final int UNKNOWN = -1; // no active stream / resolve failed

    public interface Listener {
        void onCountLoaded(int watchers);
    }

    private final int account;
    private final Map<String, Integer> cache = new HashMap<>();
    private final Map<String, List<Listener>> pending = new HashMap<>();

    public EventStatsLoader(int account) {
        this.account = account;
    }

    /** Loads the viewer count for the channel behind {@code channelUrl}. */
    public void load(String channelUrl, Listener listener) {
        String hash = MessageParser.extractInviteHash(channelUrl);
        if (hash == null) return;

        Integer cached = cache.get(hash);
        if (cached != null) {
            listener.onCountLoaded(cached);
            return;
        }

        List<Listener> waiters = pending.get(hash);
        if (waiters != null) {
            waiters.add(listener);
            return; // already resolving this channel — piggyback on that request
        }

        waiters = new ArrayList<>();
        waiters.add(listener);
        pending.put(hash, waiters);
        resolveChat(hash);
    }

    private void notifyAndCache(String hash, int count) {
        cache.put(hash, count);
        List<Listener> waiters = pending.remove(hash);
        if (waiters != null) {
            for (Listener l : waiters) l.onCountLoaded(count);
        }
    }

    private void resolveChat(String hash) {
        TLRPC.TL_messages_checkChatInvite req = new TLRPC.TL_messages_checkChatInvite();
        req.hash = hash;
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) ->
            AndroidUtilities.runOnUIThread(() -> {
                if (response instanceof TLRPC.TL_chatInviteAlready) {
                    TLRPC.Chat chat = ((TLRPC.TL_chatInviteAlready) response).chat;
                    MessagesController.getInstance(account).putChat(chat, false);
                    fetchFullChannel(hash, chat);
                } else {
                    // Not a member (shouldn't normally happen — BotSession joins every
                    // referenced channel up front) or the invite is stale.
                    notifyAndCache(hash, UNKNOWN);
                }
            })
        );
    }

    private void fetchFullChannel(String hash, TLRPC.Chat chat) {
        TLRPC.TL_channels_getFullChannel req = new TLRPC.TL_channels_getFullChannel();
        TLRPC.TL_inputChannel inputChannel = new TLRPC.TL_inputChannel();
        inputChannel.channel_id = chat.id;
        inputChannel.access_hash = chat.access_hash;
        req.channel = inputChannel;
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) ->
            AndroidUtilities.runOnUIThread(() -> {
                if (response instanceof TLRPC.TL_messages_chatFull) {
                    TLRPC.ChatFull full = ((TLRPC.TL_messages_chatFull) response).full_chat;
                    if (full.call != null) {
                        fetchGroupCall(hash, full.call);
                    } else {
                        notifyAndCache(hash, UNKNOWN); // no stream live on this channel right now
                    }
                } else {
                    android.util.Log.w(TAG, "getFullChannel failed for hash=" + hash
                        + (error != null ? " error=" + error.text : ""));
                    notifyAndCache(hash, UNKNOWN);
                }
            })
        );
    }

    private void fetchGroupCall(String hash, TLRPC.InputGroupCall inputCall) {
        TL_phone.getGroupCall req = new TL_phone.getGroupCall();
        req.call = inputCall;
        req.limit = 1;
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) ->
            AndroidUtilities.runOnUIThread(() -> {
                if (response instanceof TL_phone.groupCall) {
                    TLRPC.GroupCall call = ((TL_phone.groupCall) response).call;
                    notifyAndCache(hash, Math.max(0, call.participants_count));
                } else {
                    android.util.Log.w(TAG, "getGroupCall failed for hash=" + hash
                        + (error != null ? " error=" + error.text : ""));
                    notifyAndCache(hash, UNKNOWN);
                }
            })
        );
    }
}
