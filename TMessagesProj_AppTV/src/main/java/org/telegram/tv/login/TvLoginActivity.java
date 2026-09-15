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
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.ContactsController;
import org.telegram.messenger.MediaDataController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.TelegramQRCodeWriter;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.tv.R;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.tv.activity.TvMainActivity;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;

/**
 * TV QR login screen.
 *
 * Protocol:
 *  - TV calls exportLoginToken once to get a token (valid ~30s server-side), shows it as QR,
 *    and leaves that QR untouched on screen for its whole validity window.
 *  - Phone scans QR, calls importLoginToken (phone side) to confirm.
 *  - While the QR is shown, the TV checks confirmation status every POLL_INTERVAL_MS by calling
 *    exportLoginToken again, but only acts on the response if it's loginTokenSuccess/migrateTo —
 *    a still-pending response never changes what's on screen.
 *  - Once the token's own `expires` timestamp passes, the TV stops polling and shows a manual
 *    Refresh button instead of silently fetching/showing a new QR.
 *
 * importLoginToken is intentionally NOT called by the TV — that is the phone's method.
 */
public class TvLoginActivity extends Activity implements NotificationCenter.NotificationCenterDelegate {

    private static final int ACCOUNT = 0;
    private static final String TAG = "TvLogin";
    /** How often we check whether the phone has confirmed the currently displayed QR. */
    private static final long POLL_INTERVAL_MS = 2000L;

    private ImageView qrImageView;
    private TextView statusText;
    private Button refreshButton;

    /** Token currently shown as QR, and the second (epoch) it expires at. */
    private byte[] currentToken;
    private int tokenExpires = 0;

    /** A token fetched by a confirmation check that differs from currentToken — kept so
     *  pressing Refresh can show it immediately instead of doing another round trip. */
    private byte[] pendingToken;
    private int pendingTokenExpires = 0;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean destroyed = false;
    private boolean loginCompleted = false;
    private int pollCount = 0;

    /** Fetches a brand-new token and displays it (initial load / after Refresh / retry-on-error). */
    private final Runnable fetchRunnable = this::pollExportToken;

    /** Fires every POLL_INTERVAL_MS while a QR is on screen, to check for confirmation. */
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
    protected void onResume() {
        super.onResume();
        // Keep the (pre-login) connection active while this screen is visible — otherwise
        // ConnectionsManager treats it as backgrounded and throttles/pauses the connection,
        // delaying our confirmation polling by many seconds. Same call the official
        // LoginActivity makes for its own not-yet-authenticated account.
        ConnectionsManager.getInstance(ACCOUNT).setAppPaused(false, false);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ConnectionsManager.getInstance(ACCOUNT).setAppPaused(true, false);
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
     * Fetches a fresh token via exportLoginToken and shows it as QR right away.
     * Used for the initial load, for a manual Refresh with no usable pending token, and to
     * retry after a transient error.
     */
    private void pollExportToken() {
        if (destroyed || loginCompleted) return;
        handler.removeCallbacks(fetchRunnable);
        handler.removeCallbacks(confirmCheckRunnable);

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
                    pendingToken = null;
                    currentToken = t.token;
                    tokenExpires = t.expires;
                    showQr(currentToken);
                    startConfirmationLoop();

                } else if (response instanceof TLRPC.TL_auth_loginTokenMigrateTo) {
                    onMigrateTo((TLRPC.TL_auth_loginTokenMigrateTo) response);

                } else {
                    int floodWaitSec = parseFloodWaitSeconds(error);
                    if (floodWaitSec > 0) {
                        Log.w(TAG, "exportLoginToken → FLOOD_WAIT_" + floodWaitSec + ", backing off");
                        statusText.setText("Troppi tentativi, riprovo tra " + floodWaitSec + "s…");
                        handler.postDelayed(fetchRunnable, (floodWaitSec + 1) * 1000L);
                    } else {
                        statusText.setText("Connessione in corso… (riprovare tra 5s)");
                        handler.postDelayed(fetchRunnable, 5000L);
                    }
                }
            }),
            ConnectionsManager.RequestFlagWithoutLogin | ConnectionsManager.RequestFlagFailOnServerErrors
        );
    }

    /** Called when user presses the Refresh button after QR expiry. */
    private void onRefreshPressed() {
        refreshButton.setVisibility(View.GONE);
        long nowSec = System.currentTimeMillis() / 1000L;
        if (pendingToken != null && pendingTokenExpires > nowSec + 3) {
            // We already have a newer token from a background confirmation check: show it
            // instead of doing another round trip.
            currentToken = pendingToken;
            tokenExpires = pendingTokenExpires;
            pendingToken = null;
            showQr(currentToken);
            startConfirmationLoop();
        } else {
            pendingToken = null;
            statusText.setText(R.string.Loading);
            pollExportToken();
        }
    }

    private void startConfirmationLoop() {
        handler.removeCallbacks(confirmCheckRunnable);
        handler.postDelayed(confirmCheckRunnable, POLL_INTERVAL_MS);
    }

    /**
     * Runs every POLL_INTERVAL_MS while a QR is on screen. Calls exportLoginToken purely to read
     * confirmation status: a still-pending result never changes the displayed QR — only
     * loginTokenSuccess (→ proceed) or loginTokenMigrateTo (→ DC migration) act on it. If the
     * server hands back a token different from the one on screen, or the local expiry timestamp
     * has passed, we stop polling and show the Refresh button instead of auto-refreshing.
     */
    private void checkConfirmation() {
        if (destroyed || loginCompleted || currentToken == null) return;
        long nowSec = System.currentTimeMillis() / 1000L;
        if (nowSec >= tokenExpires) {
            Log.d(TAG, "token expired locally, requiring manual refresh");
            markExpired();
            return;
        }

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
                    if (Arrays.equals(t.token, currentToken)) {
                        // Still the same token: nothing to do, keep the QR as-is.
                        handler.postDelayed(confirmCheckRunnable, POLL_INTERVAL_MS);
                    } else {
                        // Server gave us a different token than the one on screen. We must not
                        // silently swap the visible QR, so treat it as expired; stash the new
                        // token so Refresh can use it without another round trip.
                        Log.d(TAG, "confirmation check → token changed, requiring manual refresh");
                        pendingToken = t.token;
                        pendingTokenExpires = t.expires;
                        markExpired();
                    }

                } else {
                    int floodWaitSec = parseFloodWaitSeconds(error);
                    if (floodWaitSec > 0) {
                        Log.w(TAG, "confirmation check → FLOOD_WAIT_" + floodWaitSec + ", backing off");
                        handler.postDelayed(confirmCheckRunnable, (floodWaitSec + 1) * 1000L);
                    } else {
                        Log.d(TAG, "confirmation check → error/null, retry");
                        handler.postDelayed(confirmCheckRunnable, POLL_INTERVAL_MS);
                    }
                }
            }),
            ConnectionsManager.RequestFlagWithoutLogin | ConnectionsManager.RequestFlagFailOnServerErrors
        );
    }

    /** Stops polling and shows the manual Refresh button; no automatic QR refresh. */
    private void markExpired() {
        handler.removeCallbacks(confirmCheckRunnable);
        qrImageView.setVisibility(View.INVISIBLE);
        statusText.setText(R.string.Expired);
        refreshButton.setVisibility(View.VISIBLE);
        refreshButton.requestFocus();
    }

    /** Returns the FLOOD_WAIT_X seconds if the error is a flood-wait, -1 otherwise. */
    private static int parseFloodWaitSeconds(TLRPC.TL_error error) {
        if (error != null && error.text != null && error.text.startsWith("FLOOD_WAIT_")) {
            try {
                return Integer.parseInt(error.text.substring("FLOOD_WAIT_".length()));
            } catch (NumberFormatException ignored) {
            }
        }
        return -1;
    }

    /** Called when exportLoginToken/importLoginToken returns loginTokenSuccess — finalize session. */
    private void onAuthSuccess(TLRPC.TL_auth_loginTokenSuccess success) {
        if (loginCompleted) return;
        Log.d(TAG, "onAuthSuccess START, auth type: "
            + (success.authorization != null ? success.authorization.getClass().getSimpleName() : "null"));

        if (!(success.authorization instanceof TLRPC.TL_auth_authorization)) {
            Log.w(TAG, "Unexpected auth type, retrying");
            statusText.setText("Tipo autorizzazione inatteso, riprovare.");
            handler.postDelayed(fetchRunnable, 3000L);
            return;
        }

        loginCompleted = true;
        destroyed = true;
        handler.removeCallbacksAndMessages(null);

        TLRPC.TL_auth_authorization auth = (TLRPC.TL_auth_authorization) success.authorization;
        Log.d(TAG, "onAuthSuccess: userId=" + auth.user.id + ", dcId=" + auth.user.id);

        // Save user FIRST so the session survives process death.
        // Do NOT call clearConfig() here — it wipes SharedPreferences synchronously
        // and the async re-save via doOnIdle may never fire before the process ends.
        UserConfig.getInstance(ACCOUNT).setCurrentUser(auth.user);
        UserConfig.getInstance(ACCOUNT).saveConfig(true);

        // Then reset runtime state for the new account
        ConnectionsManager.getInstance(ACCOUNT).setUserId(auth.user.id);
        MessagesController.getInstance(ACCOUNT).cleanup();
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
                    handler.postDelayed(fetchRunnable, 3000L);
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
            hints.put(EncodeHintType.MARGIN, 0);
            return new TelegramQRCodeWriter().encode(content, 512, 512, hints, null);
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
