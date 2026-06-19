package org.telegram.tv.login;

import android.app.Activity;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import com.google.zxing.EncodeHintType;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.ContactsController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.tv.R;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.HashMap;

public class TvLoginActivity extends Activity {

    private static final int ACCOUNT = 0;
    private static final long POLL_INTERVAL_MS = 3000L;
    private static final int TOKEN_REFRESH_MARGIN_SEC = 10;

    private ImageView qrImageView;
    private TextView statusText;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private byte[] currentToken;
    private int tokenExpires;
    private boolean destroyed = false;

    private final Runnable pollRunnable = this::pollImportToken;
    private final Runnable refreshRunnable = this::requestLoginToken;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_tv_login);
        qrImageView = findViewById(R.id.qr_image);
        statusText = findViewById(R.id.status_text);
        requestLoginToken();
    }

    // Step 1: ask server for a fresh login token, show QR
    private void requestLoginToken() {
        handler.removeCallbacks(pollRunnable);
        handler.removeCallbacks(refreshRunnable);

        TLRPC.TL_auth_exportLoginToken req = new TLRPC.TL_auth_exportLoginToken();
        req.api_id = BuildVars.APP_ID;
        req.api_hash = BuildVars.APP_HASH;
        req.except_ids = new ArrayList<>();

        ConnectionsManager.getInstance(ACCOUNT).sendRequest(req, (response, error) ->
            AndroidUtilities.runOnUIThread(() -> {
                if (destroyed) return;
                if (response instanceof TLRPC.TL_auth_loginToken) {
                    onTokenReceived((TLRPC.TL_auth_loginToken) response);
                } else if (response instanceof TLRPC.TL_auth_loginTokenSuccess) {
                    onAuthSuccess((TLRPC.TL_auth_loginTokenSuccess) response);
                } else if (response instanceof TLRPC.TL_auth_loginTokenMigrateTo) {
                    onMigrateTo((TLRPC.TL_auth_loginTokenMigrateTo) response);
                } else {
                    // Network error: retry after a delay
                    handler.postDelayed(refreshRunnable, 5000L);
                }
            })
        );
    }

    private void onTokenReceived(TLRPC.TL_auth_loginToken loginToken) {
        currentToken = loginToken.token;
        tokenExpires = loginToken.expires;
        showQr(currentToken);
        scheduleNextPoll();
        scheduleTokenRefresh();
    }

    // Step 2: poll via importLoginToken to detect when the phone has scanned
    private void pollImportToken() {
        if (destroyed || currentToken == null) return;

        TLRPC.TL_auth_importLoginToken req = new TLRPC.TL_auth_importLoginToken();
        req.token = currentToken;

        ConnectionsManager.getInstance(ACCOUNT).sendRequest(req, (response, error) ->
            AndroidUtilities.runOnUIThread(() -> {
                if (destroyed) return;
                if (response instanceof TLRPC.TL_auth_loginTokenSuccess) {
                    onAuthSuccess((TLRPC.TL_auth_loginTokenSuccess) response);
                } else {
                    // Not scanned yet (or error): keep polling
                    scheduleNextPoll();
                }
            })
        );
    }

    // Step 3: token was accepted by the phone, finalize login
    private void onAuthSuccess(TLRPC.TL_auth_loginTokenSuccess success) {
        if (!(success.authorization instanceof TLRPC.TL_auth_authorization)) return;
        destroyed = true;
        handler.removeCallbacksAndMessages(null);

        TLRPC.TL_auth_authorization auth = (TLRPC.TL_auth_authorization) success.authorization;
        MessagesController.getInstance(ACCOUNT).cleanup();
        ConnectionsManager.getInstance(ACCOUNT).setUserId(auth.user.id);
        UserConfig.getInstance(ACCOUNT).clearConfig();
        MessagesController.getInstance(ACCOUNT).cleanup();
        UserConfig.getInstance(ACCOUNT).setCurrentUser(auth.user);
        UserConfig.getInstance(ACCOUNT).saveConfig(true);
        MessagesStorage.getInstance(ACCOUNT).cleanup(true);

        ArrayList<TLRPC.User> users = new ArrayList<>();
        users.add(auth.user);
        MessagesStorage.getInstance(ACCOUNT).putUsersAndChats(users, null, true, true);
        MessagesController.getInstance(ACCOUNT).putUser(auth.user, false);
        ContactsController.getInstance(ACCOUNT).checkAppAccount();
        MessagesController.getInstance(ACCOUNT).updateDcSettings();

        // TODO: navigate to TvMainActivity once implemented
        finish();
    }

    // DC migration: switch datacenter then retry importLoginToken
    private void onMigrateTo(TLRPC.TL_auth_loginTokenMigrateTo migrate) {
        TLRPC.TL_auth_importLoginToken req = new TLRPC.TL_auth_importLoginToken();
        req.token = migrate.token;

        ConnectionsManager.getInstance(ACCOUNT).switchBackend(false);

        ConnectionsManager.getInstance(ACCOUNT).sendRequest(req, (response, error) ->
            AndroidUtilities.runOnUIThread(() -> {
                if (destroyed) return;
                if (response instanceof TLRPC.TL_auth_loginTokenSuccess) {
                    onAuthSuccess((TLRPC.TL_auth_loginTokenSuccess) response);
                } else {
                    requestLoginToken();
                }
            })
        );
    }

    private void showQr(byte[] token) {
        String url = "tg://login?token=" + Base64.encodeToString(
            token, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        Bitmap bitmap = renderQrBitmap(url);
        if (bitmap != null) {
            qrImageView.setImageBitmap(bitmap);
            qrImageView.setVisibility(View.VISIBLE);
        }
        statusText.setText(R.string.tv_login_instruction);
    }

    private Bitmap renderQrBitmap(String content) {
        try {
            HashMap<EncodeHintType, Object> hints = new HashMap<>();
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
            hints.put(EncodeHintType.MARGIN, 2);
            return new QRCodeWriter().encode(content, 512, 512, hints, null);
        } catch (Exception e) {
            return null;
        }
    }

    private void scheduleNextPoll() {
        handler.removeCallbacks(pollRunnable);
        handler.postDelayed(pollRunnable, POLL_INTERVAL_MS);
    }

    private void scheduleTokenRefresh() {
        handler.removeCallbacks(refreshRunnable);
        long nowSec = System.currentTimeMillis() / 1000;
        long delayMs = Math.max(0L, (tokenExpires - nowSec - TOKEN_REFRESH_MARGIN_SEC)) * 1000L;
        handler.postDelayed(refreshRunnable, delayMs);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        destroyed = true;
        handler.removeCallbacksAndMessages(null);
    }
}
