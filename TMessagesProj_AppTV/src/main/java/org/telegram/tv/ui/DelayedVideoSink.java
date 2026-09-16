package org.telegram.tv.ui;

import android.os.Handler;
import android.os.HandlerThread;

import org.webrtc.VideoFrame;
import org.webrtc.VideoSink;

import java.util.ArrayDeque;

// Some TVs' audio output path adds latency that isn't reflected in the decoded frame
// timestamps, so video consistently arrives ahead of audio by a fixed offset (confirmed on
// two different TV units, constant for the whole stream — not a growing drift). Delaying
// video presentation by that same fixed offset resyncs the two without touching the shared
// native engine. Frames are WebRTC-refcounted (org.webrtc.VideoFrame implements RefCounted):
// retain() on arrival, release() once handed to the real sink or dropped.
public class DelayedVideoSink implements VideoSink {

    private final VideoSink target;
    private final long delayMs;
    private final HandlerThread thread;
    private final Handler handler;
    private final ArrayDeque<VideoFrame> pending = new ArrayDeque<>();
    private boolean released = false;

    public DelayedVideoSink(VideoSink target, long delayMs) {
        this.target = target;
        this.delayMs = delayMs;
        thread = new HandlerThread("DelayedVideoSink");
        thread.start();
        handler = new Handler(thread.getLooper());
    }

    @Override
    public void onFrame(VideoFrame frame) {
        frame.retain();
        synchronized (pending) {
            if (released) {
                frame.release();
                return;
            }
            pending.add(frame);
        }
        handler.postDelayed(this::dispatchNext, delayMs);
    }

    private void dispatchNext() {
        VideoFrame frame;
        synchronized (pending) {
            if (released) return;
            frame = pending.poll();
        }
        if (frame == null) return;
        target.onFrame(frame);
        frame.release();
    }

    public void release() {
        synchronized (pending) {
            released = true;
            VideoFrame frame;
            while ((frame = pending.poll()) != null) {
                frame.release();
            }
        }
        handler.removeCallbacksAndMessages(null);
        thread.quitSafely();
    }
}
