package org.telegram.tv.login;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import com.google.zxing.EncodeHintType;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.ContactsController;
import org.telegram.messenger.MediaDataController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.tv.R;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.tv.TvMainActivity;

import java.util.ArrayList;
import java.util.HashMap;

/**
 * TV QR login screen.
 *
 * Protocol:
 *  - TV calls exportLoginToken once to get a token (valid ~30s), shows as QR.
 *  - Phone scans QR, calls importLoginToken (phone side) to confirm.
 *  - TV schedules the next exportLoginToken call just before the token expires.
 *    If the phone already confirmed, the server returns loginTokenSuccess instead
 *    of a new pending token.
 *
 * importLoginToken is intentionally NOT called by the TV — that is the phone's method.
 * DO NOT poll exportLoginToken rapidly: every call generates a new token, which
 * invalidates the QR that the phone is trying to scan.
 */
public class TvLoginActivity extends Activity implements NotificationCenter.NotificationCenterDelegate {

    private static final int ACCOUNT = 0;
    /** Refresh QR this many seconds before the token expires. */
    private static final int REFRESH_MARGIN_SEC = 5;
    private static final String TAG = "TvLogin";

    private ImageView qrImageView;
    private TextView statusText;
    private Button refreshButton;
    private byte[] pendingToken;   // new token ready to show after user presses Refresh
    private boolean autoShowQr = true; // true → show QR when token arrives; false → show Refresh button

    private final Handler handler = new Handler(Looper.getMainLooper());
    private byte[] currentToken;
    private int tokenExpires = 0;
    private boolean destroyed = false;
    private boolean loginCompleted = false;
    private int pollCount = 0;

    /** Fires just before token expiry to refresh the QR (and detect confirmation). */
    private final Runnable refreshRunnable = this::pollExportToken;

    /** Fires every CHECK_INTERVAL_MS while QR is shown, keeping the connection alive
     *  and calling exportLoginToken to detect confirmation as soon as possible. */
    private static final long CHECK_INTERVAL_MS = 5000L;
    private final Runnable confirmCheckRunnable = this::checkConfirmation;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_tv_login);
        qrImageView = findViewById(R.id.qr_image);
        statusText = findViewById(R.id.status_text);
        refreshButton = findViewById(R.id.refresh_button);
        refreshButton.setOnClickListener(v -> onRefreshPressed());

        // Fallback: if the session is authorized via a server push we may have missed
        NotificationCenter.getInstance(ACCOUNT).addObserver(this, NotificationCenter.mainUserInfoChanged);

        statusText.setText(R.string.Loading);
        pollExportToken();
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.mainUserInfoChanged && !loginCompleted) {
            if (UserConfig.getInstance(ACCOUNT).isClientActivated()) {
                Log.d(TAG, "mainUserInfoChanged: session active, navigating");
                AndroidUtilities.runOnUIThread(this::navigateToMain);
            }
        }
    }

    /**
     * Calls exportLoginToken.
     *
     * On first call: gets the initial QR token.
     * On subsequent calls (just before token expiry):
     *   - if phone already confirmed → loginTokenSuccess → login complete
     *   - if not yet confirmed → new pending token → update QR, reschedule
     *
     * IMPORTANT: every call generates a new token, invalidating the previous QR.
     * Therefore we only call this again when the current token is about to expire.
     */
    private void pollExportToken() {
        if (destroyed || loginCompleted) return;
        handler.removeCallbacks(refreshRunnable);

        TLRPC.TL_auth_exportLoginToken req = new TLRPC.TL_auth_exportLoginToken();
        req.api_id = BuildVars.APP_ID;
        req.api_hash = BuildVars.APP_HASH;
        req.except_ids = new ArrayList<>();

        pollCount++;
        Log.d(TAG, "exportLoginToken call #" + pollCount);

        ConnectionsManager.getInstance(ACCOUNT).sendRequest(req, (response, error) ->
            AndroidUtilities.runOnUIThread(() -> {
                if (destroyed || loginCompleted) return;

                String rt = response != null
                    ? response.getClass().getSimpleName()
                    : ("error=" + (error != null ? error.text : "null"));
                Log.d(TAG, "exportLoginToken #" + pollCount + " → " + rt);

                if (response instanceof TLRPC.TL_auth_loginTokenSuccess) {
                    statusText.setText("Login rilevato! Completamento…");
                    onAuthSuccess((TLRPC.TL_auth_loginTokenSuccess) response);

                } else if (response instanceof TLRPC.TL_auth_loginToken) {
                    TLRPC.TL_auth_loginToken t = (TLRPC.TL_auth_loginToken) response;
                    tokenExpires = t.expires;
                    if (autoShowQr) {
                        // Initial load or Refresh button press: show QR immediately
                        currentToken = t.token;
                        autoShowQr = false; // next scheduled check → show Refresh button
                        showQr(currentToken);
                        scheduleRefresh();
                    } else {
                        // Scheduled expiry check: phone didn't confirm.
                        // Store new token; don't show it until user presses Refresh.
                        pendingToken = t.token;
                        qrImageView.setVisibility(View.INVISIBLE);
                        statusText.setText(R.string.Expired);
                        refreshButton.setVisibility(View.VISIBLE);
                        refreshButton.requestFocus();
                    }

                } else if (response instanceof TLRPC.TL_auth_loginTokenMigrateTo) {
                    onMigrateTo((TLRPC.TL_auth_loginTokenMigrateTo) response);

                } else {
                    statusText.setText("Connessione in corso… (riprovare tra 5s)");
                    handler.postDelayed(refreshRunnable, 5000L);
                }
            }),
            ConnectionsManager.RequestFlagWithoutLogin | ConnectionsManager.RequestFlagFailOnServerErrors
        );
    }

    /** Called when user presses the Refresh button after QR expiry. */
    private void onRefreshPressed() {
        refreshButton.setVisibility(View.GONE);
        long nowSec = System.currentTimeMillis() / 1000L;
        if (pendingToken != null && tokenExpires > nowSec + 3) {
            // Pending token still has at least 3 seconds of validity: show it
            currentToken = pendingToken;
            pendingToken = null;
            autoShowQr = false;
            showQr(currentToken);
            scheduleRefresh();
        } else {
            // Pending token expired or missing: fetch a fresh token and auto-show it
            pendingToken = null;
            autoShowQr = true;
            statusText.setText(R.string.Loading);
            pollExportToken();
        }
    }

    /**
     * Schedule the next exportLoginToken call just before the current token expires.
     * This is the moment we check for confirmation: if the phone confirmed before
     * this fires, the next exportLoginToken returns loginTokenSuccess.
     * We also start a faster check loop every CHECK_INTERVAL_MS seconds.
     */
    private void scheduleRefresh() {
        long nowSec = System.currentTimeMillis() / 1000L;
        long delayMs = Math.max(3000L, (tokenExpires - nowSec - REFRESH_MARGIN_SEC) * 1000L);
        Log.d(TAG, "next exportLoginToken in " + (delayMs / 1000) + "s (token expires in " + (tokenExpires - nowSec) + "s)");
        handler.postDelayed(refreshRunnable, delayMs);
        // Start fast confirmation checks (every 5s) independently of the QR refresh
        handler.removeCallbacks(confirmCheckRunnable);
        handler.postDelayed(confirmCheckRunnable, CHECK_INTERVAL_MS);
    }

    /**
     * Lightweight check for confirmation: calls exportLoginToken but treats any new
     * pending token as "not yet confirmed" without changing the displayed QR.
     * If the phone confirmed, returns loginTokenSuccess and we navigate immediately.
     * If still pending, the server returns the SAME token or a new one; we compare
     * to detect whether to update the QR silently.
     *
     * Note: this runs BETWEEN the scheduled QR refreshes, so it may generate a new
     * token. We keep the old QR visible and only update it if we get a new token
     * and the old one has expired.
     */
    private void checkConfirmation() {
        if (destroyed || loginCompleted || currentToken == null) return;
        long nowSec = System.currentTimeMillis() / 1000L;
        // Don't run if the scheduled expiry check is imminent (within 3s)
        if (tokenExpires - nowSec <= REFRESH_MARGIN_SEC + 3) return;

        Log.d(TAG, "confirmation check (token has " + (tokenExpires - nowSec) + "s left)");

        TLRPC.TL_auth_exportLoginToken req = new TLRPC.TL_auth_exportLoginToken();
        req.api_id = BuildVars.APP_ID;
        req.api_hash = BuildVars.APP_HASH;
        req.except_ids = new ArrayList<>();

        ConnectionsManager.getInstance(ACCOUNT).sendRequest(req, (response, error) ->
            AndroidUtilities.runOnUIThread(() -> {
                if (destroyed || loginCompleted) return;
                if (response instanceof TLRPC.TL_auth_loginTokenSuccess) {
                    Log.d(TAG, "confirmation check → loginTokenSuccess!");
                    statusText.setText("Login rilevato! Completamento…");
                    onAuthSuccess((TLRPC.TL_auth_loginTokenSuccess) response);
                } else if (response instanceof TLRPC.TL_auth_loginTokenMigrateTo) {
                    Log.d(TAG, "confirmation check → loginTokenMigrateTo");
                    onMigrateTo((TLRPC.TL_auth_loginTokenMigrateTo) response);
                } else if (response instanceof TLRPC.TL_auth_loginToken) {
                    TLRPC.TL_auth_loginToken t = (TLRPC.TL_auth_loginToken) response;
                    Log.d(TAG, "confirmation check → still pending (expires in " + (t.expires - nowSec) + "s)");
                    // Update token/expiry but keep the current QR visible
                    currentToken = t.token;
                    tokenExpires = t.expires;
                    // Reschedule the QR refresh with the new expiry
                    handler.removeCallbacks(refreshRunnable);
                    handler.postDelayed(refreshRunnable,
                        Math.max(3000L, (tokenExpires - System.currentTimeMillis() / 1000L - REFRESH_MARGIN_SEC) * 1000L));
                    // Schedule next confirmation check
                    handler.postDelayed(confirmCheckRunnable, CHECK_INTERVAL_MS);
                } else {
                    Log.d(TAG, "confirmation check → error/null, retry");
                    handler.postDelayed(confirmCheckRunnable, CHECK_INTERVAL_MS);
                }
            }),
            ConnectionsManager.RequestFlagWithoutLogin | ConnectionsManager.RequestFlagFailOnServerErrors
        );
    }

    /** Called when exportLoginToken/importLoginToken returns loginTokenSuccess — finalize session. */
    private void onAuthSuccess(TLRPC.TL_auth_loginTokenSuccess success) {
        if (loginCompleted) return;
        Log.d(TAG, "onAuthSuccess START, auth type: "
            + (success.authorization != null ? success.authorization.getClass().getSimpleName() : "null"));

        if (!(success.authorization instanceof TLRPC.TL_auth_authorization)) {
            Log.w(TAG, "Unexpected auth type, retrying");
            statusText.setText("Tipo autorizzazione inatteso, riprovare.");
            handler.postDelayed(refreshRunnable, 3000L);
            return;
        }

        loginCompleted = true;
        destroyed = true;
        handler.removeCallbacksAndMessages(null);

        TLRPC.TL_auth_authorization auth = (TLRPC.TL_auth_authorization) success.authorization;
        Log.d(TAG, "onAuthSuccess: userId=" + auth.user.id + ", dcId=" + auth.user.id);

        // Mirror LoginActivity.onAuthSuccess initialization sequence
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
        ConnectionsManager.getInstance(ACCOUNT).updateDcSettings();
        MessagesController.getInstance(ACCOUNT).checkPromoInfo(true);
        MessagesController.getInstance(ACCOUNT).loadAppConfig();
        MediaDataController.getInstance(ACCOUNT).loadStickersByEmojiOrName(
            AndroidUtilities.STICKERS_PLACEHOLDER_PACK_NAME, false, true);

        Log.d(TAG, "onAuthSuccess: calling navigateToMain");
        navigateToMain();
    }

    private void navigateToMain() {
        Log.d(TAG, "navigateToMain: isFinishing=" + isFinishing() + " isDestroyed=" + isDestroyed());
        if (isFinishing() || isDestroyed()) return;
        Log.d(TAG, "navigating to TvMainActivity");
        startActivity(new Intent(this, TvMainActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        finish();
    }

    /**
     * DC migration: exportLoginToken said the user's account is on a different DC.
     * We move the whole ConnectionsManager to the target DC, wait for the MTProto
     * handshake to complete, then send importLoginToken normally.
     * Using a DC-specific sendRequest doesn't work because CM hasn't established
     * an auth key with the target DC yet and the callback is never invoked.
     */
    private void onMigrateTo(TLRPC.TL_auth_loginTokenMigrateTo migrate) {
        Log.d(TAG, "migrating to DC " + migrate.dc_id + " — moving default DC");
        handler.removeCallbacks(confirmCheckRunnable);

        // Move the entire connection to the target DC. CM queues the subsequent
        // importLoginToken automatically until the DH key exchange completes.
        ConnectionsManager.getInstance(ACCOUNT).setDefaultDatacenterId(migrate.dc_id);
        sendImportLoginToken(migrate);
    }

    private void sendImportLoginToken(TLRPC.TL_auth_loginTokenMigrateTo migrate) {
        if (destroyed || loginCompleted) return;
        Log.d(TAG, "sending importLoginToken to DC" + migrate.dc_id);

        TLRPC.TL_auth_importLoginToken req = new TLRPC.TL_auth_importLoginToken();
        req.token = migrate.token;

        ConnectionsManager.getInstance(ACCOUNT).sendRequest(req, (response, error) ->
            AndroidUtilities.runOnUIThread(() -> {
                if (destroyed || loginCompleted) return;
                String rt = response != null ? response.getClass().getSimpleName()
                    : ("error=" + (error != null ? error.text : "null"));
                Log.d(TAG, "importLoginToken DC" + migrate.dc_id + " → " + rt);
                if (response instanceof TLRPC.TL_auth_loginTokenSuccess) {
                    onAuthSuccess((TLRPC.TL_auth_loginTokenSuccess) response);
                } else if (response instanceof TLRPC.TL_auth_loginTokenMigrateTo) {
                    onMigrateTo((TLRPC.TL_auth_loginTokenMigrateTo) response);
                } else {
                    // No response or error: retry exportLoginToken from scratch
                    Log.w(TAG, "importLoginToken failed, retrying exportLoginToken");
                    autoShowQr = true;
                    handler.postDelayed(refreshRunnable, 3000L);
                }
            }),
            ConnectionsManager.RequestFlagWithoutLogin
        );
    }

    private void showQr(byte[] token) {
        String b64 = Base64.encodeToString(token, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        String url = "tg://login?token=" + b64;
        Log.d(TAG, "QR URL: " + url);
        Log.d(TAG, "token length=" + token.length + " bytes, base64 length=" + b64.length() + " chars");
        Bitmap bitmap = renderQrBitmap(url);
        if (bitmap != null) {
            qrImageView.setImageBitmap(bitmap);
            qrImageView.setVisibility(View.VISIBLE);
        }
        statusText.setText(R.string.AuthAnotherClientInfo3);
    }

    private Bitmap renderQrBitmap(String content) {
        try {
            HashMap<EncodeHintType, Object> hints = new HashMap<>();
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
            hints.put(EncodeHintType.MARGIN, 2);
            return new QRCodeWriter().encode(content, 512, 512, hints, null);
        } catch (Exception e) {
            Log.e(TAG, "QR render failed", e);
            return null;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        destroyed = true;
        handler.removeCallbacksAndMessages(null);
        NotificationCenter.getInstance(ACCOUNT)
            .removeObserver(this, NotificationCenter.mainUserInfoChanged);
        Log.d(TAG, "onDestroy");
    }
}
