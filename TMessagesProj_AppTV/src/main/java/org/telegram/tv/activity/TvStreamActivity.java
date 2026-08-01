package org.telegram.tv.activity;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.telegram.messenger.UserConfig;
import org.telegram.messenger.tv.R;
import org.telegram.tv.model.StreamEvent;
import org.telegram.tv.stream.StreamPlayerController;

public class TvStreamActivity extends Activity implements StreamPlayerController.Listener {

    static final String EXTRA_EVENT = "stream_event";

    private StreamPlayerController streamPlayer;
    private View streamLoading;
    private View streamTopBar;
    private TextView streamEventTitle;
    private TextView streamStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_tv_stream);

        streamLoading    = findViewById(R.id.stream_loading);
        streamTopBar     = findViewById(R.id.stream_top_bar);
        streamEventTitle = findViewById(R.id.stream_event_title);
        streamStatus     = findViewById(R.id.stream_status);
        FrameLayout playerContainer = findViewById(R.id.stream_player_container);

        StreamEvent event = (StreamEvent) getIntent().getSerializableExtra(EXTRA_EVENT);
        streamPlayer = new StreamPlayerController(this, UserConfig.selectedAccount, playerContainer, this);
        streamPlayer.openEvent(event);
    }

    @Override
    protected void onPause() {
        super.onPause();
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        streamPlayer.destroy();
        if (!isFinishing()) finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        streamPlayer.destroy();
    }

    // ── StreamPlayerController.Listener ──────────────────────────────────────

    @Override
    public void onLoadingMessage(String message) {
        streamLoading.setVisibility(View.VISIBLE);
        streamStatus.setText(message);
    }

    @Override
    public void onError(String message) {
        streamLoading.setVisibility(View.VISIBLE);
        streamStatus.setText("❌ " + message + "\n\nPremi BACK per tornare agli eventi");
    }

    @Override
    public void onPlayerStarting(StreamEvent event) {
        streamEventTitle.setText(event.category + " — " + event.eventName + "  " + event.time);
        streamLoading.setVisibility(View.VISIBLE);
        streamTopBar.setVisibility(View.VISIBLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    @Override
    public void onPlayerReady() {
        streamLoading.setVisibility(View.GONE);
        streamTopBar.setVisibility(View.GONE);
    }

    @Override
    public void onPlayerStopped() {}
}
