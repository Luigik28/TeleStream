package org.telegram.tv;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.tv.R;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;

public class TvMainActivity extends Activity implements NotificationCenter.NotificationCenterDelegate {

    private static final String TARGET_CHAT_NAME = "SBLOCCA Eventi";

    private final int account = UserConfig.selectedAccount;

    private long targetDialogId = 0;
    private boolean startSent = false;

    private LinearLayout loadingContainer;
    private TextView statusText;
    private ScrollView scrollView;
    private TextView messageText;
    private LinearLayout linksContainer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_tv_main);

        loadingContainer = findViewById(R.id.loading_container);
        statusText = findViewById(R.id.status_text);
        scrollView = findViewById(R.id.scroll_view);
        messageText = findViewById(R.id.message_text);
        linksContainer = findViewById(R.id.links_container);

        NotificationCenter nc = NotificationCenter.getInstance(account);
        nc.addObserver(this, NotificationCenter.didReceiveNewMessages);
        nc.addObserver(this, NotificationCenter.dialogsNeedReload);

        statusText.setText("Ricerca \"" + TARGET_CHAT_NAME + "\"…");
        findChatAndSendStart();
    }

    private void findChatAndSendStart() {
        targetDialogId = findDialogByName(TARGET_CHAT_NAME);
        if (targetDialogId != 0) {
            sendStart();
        } else {
            MessagesController.getInstance(account).loadDialogs(0, 0, 100, false);
        }
    }

    private long findDialogByName(String name) {
        MessagesController mc = MessagesController.getInstance(account);
        for (TLRPC.Dialog dialog : mc.getAllDialogs()) {
            if (name.equalsIgnoreCase(mc.getPeerName(dialog.id))) {
                return dialog.id;
            }
        }
        return 0;
    }

    private void sendStart() {
        if (startSent) return;
        startSent = true;
        statusText.setText("Connessione a \"" + TARGET_CHAT_NAME + "\"…");
        SendMessagesHelper.getInstance(account).sendMessage(
            SendMessagesHelper.SendMessageParams.of("/start", targetDialogId)
        );
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.dialogsNeedReload) {
            AndroidUtilities.runOnUIThread(() -> {
                if (targetDialogId != 0) return;
                targetDialogId = findDialogByName(TARGET_CHAT_NAME);
                if (targetDialogId != 0) {
                    sendStart();
                } else {
                    statusText.setText("Chat \"" + TARGET_CHAT_NAME + "\" non trovata.\nVerifica di avere la chat in lista.");
                }
            });
        } else if (id == NotificationCenter.didReceiveNewMessages) {
            long dialogId = (Long) args[0];
            if (dialogId != targetDialogId) return;
            @SuppressWarnings("unchecked")
            ArrayList<MessageObject> msgs = (ArrayList<MessageObject>) args[1];
            for (MessageObject msg : msgs) {
                if (!msg.isOut()) {
                    AndroidUtilities.runOnUIThread(() -> displayMessage(msg));
                    return;
                }
            }
        }
    }

    private void displayMessage(MessageObject msg) {
        loadingContainer.setVisibility(View.GONE);
        scrollView.setVisibility(View.VISIBLE);

        String text = msg.messageOwner.message != null ? msg.messageOwner.message : "";
        messageText.setText(text);

        linksContainer.removeAllViews();
        if (msg.messageOwner.entities != null) {
            for (TLRPC.MessageEntity entity : msg.messageOwner.entities) {
                extractLink(text, entity);
            }
        }
    }

    private void extractLink(String text, TLRPC.MessageEntity entity) {
        String url = null;
        String label = null;

        if (entity instanceof TLRPC.TL_messageEntityTextUrl) {
            url = ((TLRPC.TL_messageEntityTextUrl) entity).url;
            label = safeSubstring(text, entity.offset, entity.offset + entity.length);
        } else if (entity instanceof TLRPC.TL_messageEntityUrl) {
            label = safeSubstring(text, entity.offset, entity.offset + entity.length);
            url = label;
        }

        if (url != null && !url.isEmpty()) {
            addLinkButton(label != null ? label : url, url);
        }
    }

    private void addLinkButton(String label, String url) {
        TextView btn = new TextView(this);
        btn.setText("▶  " + label);
        btn.setTextColor(0xFF4FC3F7);
        btn.setTextSize(20);
        btn.setPadding(dp(24), dp(16), dp(24), dp(16));
        btn.setBackground(getDrawable(R.drawable.tv_link_button_bg));
        btn.setFocusable(true);
        btn.setClickable(true);
        btn.setFocusableInTouchMode(true);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        lp.setMargins(0, 0, 0, dp(12));
        btn.setLayoutParams(lp);

        final String finalUrl = url;
        btn.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(finalUrl)));
            } catch (Exception ignored) {}
        });

        linksContainer.addView(btn);
    }

    private static String safeSubstring(String s, int start, int end) {
        if (s == null) return "";
        start = Math.max(0, start);
        end = Math.min(s.length(), end);
        return start < end ? s.substring(start, end) : "";
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        NotificationCenter nc = NotificationCenter.getInstance(account);
        nc.removeObserver(this, NotificationCenter.didReceiveNewMessages);
        nc.removeObserver(this, NotificationCenter.dialogsNeedReload);
    }
}
