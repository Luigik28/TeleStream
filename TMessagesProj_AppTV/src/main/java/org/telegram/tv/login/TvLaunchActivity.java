package org.telegram.tv.login;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.TextView;

import org.telegram.messenger.UserConfig;
import org.telegram.messenger.tv.R;
import org.telegram.tv.login.TvLoginActivity;
import org.telegram.tv.activity.TvMainActivity;

public class TvLaunchActivity extends Activity {

    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_tv_launch);

        ImageView planeView = findViewById(R.id.splash_plane);
        ImageView tvView    = findViewById(R.id.splash_tv);
        TextView  titleView = findViewById(R.id.splash_title);

        // Wait for layout so display metrics are valid, then start the animation.
        planeView.post(() -> {
            float screenWidth = getResources().getDisplayMetrics().widthPixels;
            planeView.setTranslationX(-screenWidth);

            // Phase 1 — paper plane flies in from the left (700 ms)
            planeView.animate()
                .translationX(0f)
                .setDuration(700)
                .setInterpolator(new DecelerateInterpolator(2f))
                .withEndAction(() -> {
                    // Phase 2 — plane disappears, TV icon scales in (350 ms)
                    planeView.animate().alpha(0f).setDuration(200).start();
                    tvView.setScaleX(0.7f);
                    tvView.setScaleY(0.7f);
                    tvView.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(350).start();

                    // Phase 3 — app title fades in (400 ms, delayed 150 ms)
                    titleView.animate().alpha(1f).setDuration(400).setStartDelay(150).start();

                    // Phase 4 — redirect after the animation settles
                    handler.postDelayed(this::redirect, 900);
                })
                .start();
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
    }

    private void redirect() {
        if (isFinishing()) return;
        Class<?> target = UserConfig.getInstance(UserConfig.selectedAccount).isClientActivated()
            ? TvMainActivity.class
            : TvLoginActivity.class;
        startActivity(new Intent(this, target));
        finish();
    }
}
