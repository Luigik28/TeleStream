package org.telegram.tv.stream;

import android.content.Context;
import android.media.AudioAttributes;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.widget.FrameLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessagesController;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.tv.bot.BotSession;
import org.telegram.tv.bot.MessageParser;
import org.telegram.tv.model.StreamEvent;
import org.telegram.ui.Stories.LivePlayer;
import org.telegram.ui.Stories.recorder.LivePlayerView;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manages the full lifecycle of a live stream session:
 * channel membership check → join if needed → getFullChannel → LivePlayer start/stop.
 *
 * All Listener callbacks are delivered on the main thread.
 */
public class StreamPlayerController {

    public interface Listener {
        /** Update the status label shown in the loading overlay. */
        void onLoadingMessage(String message);
        /** A non-recoverable error occurred; show the message and wait for BACK. */
        void onError(String message);
        /** LivePlayer is initializing; show the player container and hide events. */
        void onPlayerStarting(StreamEvent event);
        /** ~4 s after start — hide the loading spinner. */
        void onPlayerReady();
        /** Player was destroyed (e.g. BACK pressed or onPause). */
        void onPlayerStopped();
    }

    private static final String TAG = "StreamPlayer";

    // Sharp AQUOS TVE21F: mstar.hardware.audioserver HAL adds ~500 ms of audio output latency
    // (it consumes 64-70 % of one CPU core doing software mixing). Video renders immediately via
    // SurfaceView, so the picture arrives at the eye ~500 ms before the sound reaches the ear.
    // Delaying video frames by this amount re-aligns A/V. Set to 0 to disable.
    private static final int VIDEO_SYNC_DELAY_MS = 500;

    private final int account;
    private final Context context;
    private final FrameLayout playerContainer;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private LivePlayer livePlayer;
    private LivePlayerView livePlayerView;
    private DelayedVideoSink delayedSink;

    // Kept alive until 2 s after LivePlayer starts to hold the heap open (see configureHeapForStreaming).
    private final java.util.List<byte[]> heapPrime = new java.util.ArrayList<>();

    public StreamPlayerController(Context context, int account,
                                   FrameLayout playerContainer, Listener listener) {
        this.context = context;
        this.account = account;
        this.playerContainer = playerContainer;
        this.listener = listener;
    }

    public boolean isActive() { return livePlayer != null; }

    // ─── Public entry point ────────────────────────────────────────────────────

    public void openEvent(StreamEvent event) {
        listener.onLoadingMessage("Connessione a " + event.eventName + "…");

        String hash = MessageParser.extractInviteHash(event.channelUrl);
        if (hash == null) { listener.onError("Link non valido"); return; }

        AtomicBoolean done = new AtomicBoolean(false);
        handler.postDelayed(() -> {
            if (!done.get()) listener.onError("Timeout connessione");
        }, 20000);

        TLRPC.TL_messages_checkChatInvite req = new TLRPC.TL_messages_checkChatInvite();
        req.hash = hash;
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) ->
            AndroidUtilities.runOnUIThread(() -> {
                if (!done.compareAndSet(false, true)) return;
                if (response instanceof TLRPC.TL_chatInviteAlready) {
                    TLRPC.Chat chat = ((TLRPC.TL_chatInviteAlready) response).chat;
                    MessagesController.getInstance(account).putChat(chat, false);
                    getAndPlayStream(chat, event);
                } else {
                    joinChannelForStream(hash, event);
                }
            })
        );
    }

    /** Releases the LivePlayer and removes the LivePlayerView from its container. */
    public void destroy() {
        heapPrime.clear();
        org.telegram.ui.Stories.LivePlayer.maxVideoQuality = -1;
        org.telegram.ui.Stories.LivePlayer.chunkListener = null;
        if (delayedSink != null) { delayedSink.release(); delayedSink = null; }
        if (livePlayer != null) { livePlayer.destroy(); livePlayer = null; }
        if (livePlayerView != null) {
            playerContainer.removeView(livePlayerView);
            livePlayerView = null;
        }
        listener.onPlayerStopped();
    }

    // ─── Private flow ──────────────────────────────────────────────────────────

    private void joinChannelForStream(String hash, StreamEvent event) {
        listener.onLoadingMessage("Richiesta accesso a " + event.eventName + "…");

        AtomicBoolean done = new AtomicBoolean(false);
        handler.postDelayed(() -> {
            if (!done.get()) listener.onError("Timeout join canale");
        }, 15000);

        TLRPC.TL_messages_importChatInvite req = new TLRPC.TL_messages_importChatInvite();
        req.hash = hash;
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) ->
            AndroidUtilities.runOnUIThread(() -> {
                if (!done.compareAndSet(false, true)) return;
                if (error != null) { listener.onError("Accesso negato: " + error.text); return; }
                TLRPC.Chat chat = BotSession.extractChatFromUpdates((TLRPC.Updates) response);
                if (chat == null) { listener.onError("Canale non trovato"); return; }
                MessagesController.getInstance(account).putChat(chat, false);
                BotSession.muteAndArchive(account, chat, () -> {});
                getAndPlayStream(chat, event);
            })
        );
    }

    private void getAndPlayStream(TLRPC.Chat chat, StreamEvent event) {
        listener.onLoadingMessage("Connessione allo stream…");

        TLRPC.TL_channels_getFullChannel req = new TLRPC.TL_channels_getFullChannel();
        TLRPC.TL_inputChannel ic = new TLRPC.TL_inputChannel();
        ic.channel_id  = chat.id;
        ic.access_hash = chat.access_hash;
        req.channel = ic;

        AtomicBoolean done = new AtomicBoolean(false);
        handler.postDelayed(() -> {
            if (done.compareAndSet(false, true)) listener.onError("Timeout recupero canale");
        }, 15000);

        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) ->
            AndroidUtilities.runOnUIThread(() -> {
                if (!done.compareAndSet(false, true)) return;
                if (error != null) { listener.onError("Errore canale: " + error.text); return; }
                if (!(response instanceof TLRPC.TL_messages_chatFull)) {
                    listener.onError("Dati canale non disponibili"); return;
                }
                TLRPC.ChatFull full = ((TLRPC.TL_messages_chatFull) response).full_chat;
                if (full.call == null) {
                    listener.onError("Nessuna live stream attiva in questo canale"); return;
                }
                startLivePlayer(chat, full.call, event);
            })
        );
    }

    private void startLivePlayer(TLRPC.Chat chat, TLRPC.InputGroupCall callRef, StreamEvent event) {
        if (livePlayer != null) { livePlayer.destroy(); livePlayer = null; }
        if (livePlayerView != null) { playerContainer.removeView(livePlayerView); livePlayerView = null; }

        configureHeapForStreaming();
        logDeviceCapabilities();
        listener.onPlayerStarting(event);

        long dialogId = -(long) chat.id; // broadcast channels use negative dialog IDs

        livePlayer = new LivePlayer(context, account, null, dialogId, 0, true, callRef);

        // USAGE_MEDIA routes audio through the TV's hardware DSP/mixer pipeline.
        // USAGE_UNKNOWN was tested and caused 98 % app CPU (WebRTC fell back to full
        // software processing). USAGE_MEDIA offloads to the Mstar HAL correctly.
        if (Build.VERSION.SDK_INT >= 21) {
            org.webrtc.voiceengine.WebRtcAudioTrack.setAudioTrackUsageAttribute(
                AudioAttributes.USAGE_MEDIA);
        }

        org.telegram.ui.Stories.LivePlayer.maxVideoQuality = -1;
        org.telegram.ui.Stories.LivePlayer.chunkListener = new QualityAdaptor();

        // SurfaceViewRenderer (isSurfaceView=true): renders in a dedicated hardware compositor
        // layer — no extra GPU copy per frame, matching how Netflix/YouTube render on this TV.
        // The previous crash (onFirstFrameRendered calling .animate() from GL thread) is fixed
        // in LivePlayerView by posting all View operations to the main thread via runOnUIThread.
        livePlayerView = new LivePlayerView(context, account, true);
        playerContainer.addView(livePlayerView,
            new FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT));

        // Apply video delay to compensate for mstar HAL audio latency (see VIDEO_SYNC_DELAY_MS).
        org.webrtc.VideoSink sink = livePlayerView.getSink();
        if (VIDEO_SYNC_DELAY_MS > 0) {
            delayedSink = new DelayedVideoSink(sink, VIDEO_SYNC_DELAY_MS);
            livePlayer.setDisplaySink(delayedSink);
        } else {
            livePlayer.setDisplaySink(sink);
        }

        android.util.Log.d(TAG, "LivePlayer started call=" + callRef.id + " dialogId=" + dialogId);

        // Release pre-warming buffers 2 s after LivePlayer starts.
        // By then WebRTC has begun native frame allocations; ART reclaims the buffers
        // as a concurrent GC and re-targets the heap to ~44–56 MB.
        handler.postDelayed(() -> {
            if (!heapPrime.isEmpty()) {
                heapPrime.clear();
                android.util.Log.i(TAG, "heap prime released — heap free="
                    + Runtime.getRuntime().freeMemory() / 1_048_576 + " MB");
            }
        }, 2000);

        schedulePerformanceSnapshots();

        handler.postDelayed(() -> {
            if (livePlayer != null) listener.onPlayerReady();
        }, 4000);
    }

    /**
     * Tells ART to keep 75 % of the heap free after each GC (utilization target 0.25)
     * and to never shrink the heap below 48 MB.
     *
     * Without this, ART maintains a tiny ~19 MB heap on memory-constrained TV SoCs
     * (Sharp Aquos TVE21F observed). When WebRTC frame buffers exhaust the 1 MB of headroom,
     * ART runs a stop-the-world GC that pauses all threads for 50-100 ms, causing frame drops.
     *
     * With utilization=0.25 the heap grows to live_bytes/0.25 after each GC.
     * If live objects = 11 MB after GC, target heap = 44 MB → ~33 MB free → far fewer GC pauses.
     *
     * Uses reflection because dalvik.system.VMRuntime is a hidden (@hide) API.
     * On Android 9-14 it sits in the grey list (accessible with a warning). If blocked,
     * the catch silences the failure and the app continues with default ART settings.
     */
    private void configureHeapForStreaming() {
        try {
            Class<?> vmc = Class.forName("dalvik.system.VMRuntime");
            java.lang.reflect.Method getRuntime = vmc.getDeclaredMethod("getRuntime");
            Object runtime = getRuntime.invoke(null);

            // 0.25 = keep 75 % free; forces heap to grow before filling up.
            java.lang.reflect.Method setUtil = vmc.getDeclaredMethod(
                "setTargetHeapUtilization", float.class);
            setUtil.invoke(runtime, 0.25f);

            // 48 MB floor: persists for the app session so each stream starts with a warm heap.
            java.lang.reflect.Method setMin = vmc.getDeclaredMethod(
                "setMinimumHeapSize", long.class);
            setMin.invoke(runtime, 48L * 1024 * 1024);

            android.util.Log.i(TAG, "ART heap configured: util=0.25, minSize=48 MB");
        } catch (Exception e) {
            android.util.Log.w(TAG, "ART heap config unavailable: " + e.getMessage());
        }

        // Pre-grow the heap if it is still below 40 MB (first stream of the session).
        //
        // Single 32 MB allocations fail on armeabi-v7a: the 32-bit virtual address space is
        // saturated with mapped libraries and there is no contiguous 32 MB region available.
        // Instead we allocate 8 × 4 MB buffers (individually feasible) for 32 MB total.
        //
        // Critically, the buffers are stored in the `heapPrime` field — NOT a local variable —
        // so they stay live and hold the heap open until explicitly released 2 s into streaming
        // (see startLivePlayer). When released, ART reclaims them as a concurrent GC (no
        // stop-the-world pause) because it has ample free space alongside the dead buffers,
        // then re-targets the heap to live_bytes/0.25 ≈ 44–56 MB.
        heapPrime.clear();
        if (Runtime.getRuntime().totalMemory() < 40L * 1024 * 1024) {
            long allocated = 0;
            try {
                for (int i = 0; i < 8; i++) {
                    heapPrime.add(new byte[4 * 1024 * 1024]);
                    allocated += 4;
                }
            } catch (OutOfMemoryError ignored) {}
            android.util.Log.i(TAG, "heap pre-grown: " + allocated + " MB across "
                + heapPrime.size() + " buffers (held until stream start +2 s)");
        }
    }

    private void logDeviceCapabilities() {
        android.util.Log.i(TAG, "=== Device capabilities ===");
        android.util.Log.i(TAG, "CPU ABI primary : " + Build.SUPPORTED_ABIS[0]);
        android.util.Log.i(TAG, "CPU ABI list    : " + java.util.Arrays.toString(Build.SUPPORTED_ABIS));
        android.util.Log.i(TAG, "Cores           : " + Runtime.getRuntime().availableProcessors());
        android.util.Log.i(TAG, "RAM free (MB)   : " + (Runtime.getRuntime().freeMemory()  / 1_048_576));
        android.util.Log.i(TAG, "RAM total (MB)  : " + (Runtime.getRuntime().totalMemory() / 1_048_576));
        android.util.Log.i(TAG, "RAM max (MB)    : " + (Runtime.getRuntime().maxMemory()   / 1_048_576));
        android.util.Log.i(TAG, "Android SDK     : " + Build.VERSION.SDK_INT);
        android.util.Log.i(TAG, "Device model    : " + Build.MODEL);

        // Check H.264 and H.265 hardware decoder availability
        android.media.MediaCodecList list = new android.media.MediaCodecList(android.media.MediaCodecList.ALL_CODECS);
        boolean hasHwH264 = false, hasHwH265 = false;
        for (android.media.MediaCodecInfo info : list.getCodecInfos()) {
            if (info.isEncoder()) continue;
            for (String mime : info.getSupportedTypes()) {
                boolean hw = !info.getName().startsWith("OMX.google")
                          && !info.getName().startsWith("c2.android");
                if (mime.equalsIgnoreCase("video/avc"))  hasHwH264 |= hw;
                if (mime.equalsIgnoreCase("video/hevc")) hasHwH265 |= hw;
            }
        }
        android.util.Log.i(TAG, "HW H.264 decoder: " + hasHwH264);
        android.util.Log.i(TAG, "HW H.265 decoder: " + hasHwH265);
        android.util.Log.i(TAG, "===========================");
    }

    private void schedulePerformanceSnapshots() {
        // Log a CPU/memory snapshot every 5 s for the first 30 s of playback.
        // Read with: adb logcat -s StreamPlayer
        final int[] tick = {0};
        Runnable snapshot = new Runnable() {
            @Override public void run() {
                if (livePlayer == null || tick[0] >= 6) return;
                tick[0]++;
                long freeMb = Runtime.getRuntime().freeMemory() / 1_048_576;
                long totalMb = Runtime.getRuntime().totalMemory() / 1_048_576;
                android.util.Log.d(TAG, "perf tick " + tick[0]
                    + " — RAM free/total: " + freeMb + "/" + totalMb + " MB");
                handler.postDelayed(this, 5000);
            }
        };
        handler.postDelayed(snapshot, 5000);
    }

    // Adaptive bitrate: starts at quality=2, drops to quality=1 after 3 consecutive late video
    // chunks, then stops monitoring. Never upgrades back to avoid oscillation.
    // "Late" means fetch time exceeded the 500 ms segment budget.
    private static final class QualityAdaptor implements org.telegram.ui.Stories.LivePlayer.ChunkListener {
        private int lateStreak = 0;

        @Override
        public void onChunkFetched(int videoChannel, int quality, long fetchMs, long budgetMs) {
            if (videoChannel == 0) return; // audio chunk, skip
            if (org.telegram.ui.Stories.LivePlayer.maxVideoQuality == 1) return; // already capped

            if (fetchMs > budgetMs) {
                lateStreak++;
                if (lateStreak >= 3) {
                    org.telegram.ui.Stories.LivePlayer.maxVideoQuality = 1;
                    android.util.Log.i("StreamPlayer", "ABR: quality capped to 1 after " + lateStreak + " late chunks");
                }
            } else {
                lateStreak = 0;
            }
        }
    }

    // Wraps a VideoSink and delays each frame by a fixed number of milliseconds.
    // At 30 fps and 500 ms delay, ~15 frames are in flight. Each frame is a WebRTC hardware
    // texture buffer (GPU memory, not Java heap), so retaining them is safe on this device.
    private static final class DelayedVideoSink implements org.webrtc.VideoSink {

        private final org.webrtc.VideoSink target;
        private final int delayMs;
        private final android.os.HandlerThread thread;
        private final android.os.Handler handler;
        private volatile boolean released = false;

        DelayedVideoSink(org.webrtc.VideoSink target, int delayMs) {
            this.target = target;
            this.delayMs = delayMs;
            thread = new android.os.HandlerThread("tv-video-delay");
            thread.start();
            handler = new android.os.Handler(thread.getLooper());
        }

        @Override
        public void onFrame(org.webrtc.VideoFrame frame) {
            if (released) return;
            frame.retain();
            handler.postDelayed(() -> {
                if (!released) target.onFrame(frame);
                frame.release();
            }, delayMs);
        }

        void release() {
            released = true;
            handler.removeCallbacksAndMessages(null);
            thread.quitSafely();
        }
    }
}
