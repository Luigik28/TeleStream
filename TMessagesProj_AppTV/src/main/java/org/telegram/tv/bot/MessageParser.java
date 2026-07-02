package org.telegram.tv.bot;

import org.telegram.messenger.MessageObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tv.model.StreamEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure-static message detection and parsing. No Android UI dependencies, no network calls. */
public final class MessageParser {

    private MessageParser() {}

    // ─── Link helpers ──────────────────────────────────────────────────────────

    public static boolean isInviteLink(String url) {
        return url != null && (url.startsWith("https://t.me/+") || url.startsWith("t.me/+")
            || url.startsWith("https://t.me/joinchat/") || url.startsWith("t.me/joinchat/"));
    }

    public static String extractInviteHash(String url) {
        if (url == null) return null;
        if (url.contains("/+")) return url.substring(url.lastIndexOf("/+") + 2);
        if (url.contains("/joinchat/")) return url.substring(url.lastIndexOf("/joinchat/") + 10);
        return null;
    }

    // ─── Message type detection ────────────────────────────────────────────────

    /**
     * An "access required" message has inline keyboard buttons (callbacks or invite URL buttons)
     * that the user must act on before getting the events list.
     */
    public static boolean isAccessRequiredMessage(MessageObject msg) {
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

    /**
     * An "events" message has at least one invite link as a text-url entity in its body.
     * Access-required messages are detected first (they use reply markup buttons, not text entities),
     * so reaching this check means the entity-level links are event entries.
     */
    public static boolean isEventsMessage(MessageObject msg) {
        if (msg.messageOwner.entities == null) return false;
        for (TLRPC.MessageEntity e : msg.messageOwner.entities) {
            if (e instanceof TLRPC.TL_messageEntityTextUrl
                    && isInviteLink(((TLRPC.TL_messageEntityTextUrl) e).url)) return true;
        }
        return false;
    }

    // ─── Content extraction ────────────────────────────────────────────────────

    /**
     * Returns the list of non-verified (no ✅) invite links from the inline keyboard of an
     * access-required message.
     */
    public static List<String> extractChannelUrlsFromMarkup(MessageObject msg) {
        List<String> urls = new ArrayList<>();
        if (!(msg.messageOwner.reply_markup instanceof TLRPC.TL_replyInlineMarkup)) return urls;
        for (TLRPC.TL_keyboardButtonRow row : ((TLRPC.TL_replyInlineMarkup) msg.messageOwner.reply_markup).rows) {
            for (TLRPC.KeyboardButton btn : row.buttons) {
                if (btn instanceof TLRPC.TL_keyboardButtonUrl) {
                    String url = ((TLRPC.TL_keyboardButtonUrl) btn).url;
                    if (isInviteLink(url) && !btn.text.contains("✅")) {
                        urls.add(url);
                    }
                }
            }
        }
        return urls;
    }

    /** Parses all StreamEvents from the bot's events calendar message. */
    public static List<StreamEvent> parseEventsMessage(MessageObject msg) {
        List<StreamEvent> events = new ArrayList<>();
        if (msg.messageOwner == null || msg.messageOwner.message == null) return events;

        String text = msg.messageOwner.message;
        List<TLRPC.MessageEntity> entities = msg.messageOwner.entities;
        if (entities == null || entities.isEmpty()) return events;

        // Build line-start offsets for offset→line lookup
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
                // Stop if another invite-link entity starts on this line
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

            if (time.isEmpty()) continue; // promo/footer links have no time — skip
            events.add(new StreamEvent(category, eventName, time, url));
        }
        return events;
    }

    /**
     * Splits an event name into [teamA, teamB] on common separators.
     * Returns null if no separator is found (non-match event, e.g. F1 race).
     */
    public static String[] parseTeams(String eventName) {
        for (String sep : new String[]{" vs ", " VS ", " – ", " — ", " - "}) {
            int i = eventName.indexOf(sep);
            if (i > 0 && i < eventName.length() - sep.length()) {
                return new String[]{
                    eventName.substring(0, i).trim(),
                    eventName.substring(i + sep.length()).trim()
                };
            }
        }
        return null;
    }

    // ─── Private helpers ───────────────────────────────────────────────────────

    private static boolean looksLikeCategory(String line) {
        if (line.isEmpty()) return false;
        int cp = line.codePointAt(0);
        if (cp > 0x2000) return true; // starts with emoji
        String letters = line.replaceAll("[^a-zA-Z]", "");
        return letters.length() >= 3 && letters.equals(letters.toUpperCase());
    }

    private static String stripLeadingSymbols(String line) {
        int i = 0;
        while (i < line.length()) {
            int cp = line.codePointAt(i);
            if (Character.isLetter(cp)) break;
            i += Character.charCount(cp);
        }
        return line.substring(i).trim();
    }
}
