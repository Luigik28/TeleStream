package org.telegram.tv.model;

/** Immutable data class representing one streamable event from the bot's calendar. */
public final class StreamEvent {

    public final String category;
    public final String eventName;
    public final String time;
    public final String channelUrl; // t.me/+xxx invite link

    public StreamEvent(String category, String eventName, String time, String channelUrl) {
        this.category   = category;
        this.eventName  = eventName;
        this.time       = time;
        this.channelUrl = channelUrl;
    }

    @Override
    public String toString() {
        return "[" + category + "] " + eventName + " @ " + time;
    }
}
