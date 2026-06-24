package org.telegram.tv;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import org.telegram.messenger.UserConfig;
import org.telegram.tv.login.TvLoginActivity;

public class TvLaunchActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (UserConfig.getInstance(UserConfig.selectedAccount).isClientActivated()) {
            startActivity(new Intent(this, TvMainActivity.class));
            finish();
        } else {
            startActivity(new Intent(this, TvLoginActivity.class));
            finish();
        }
    }
}
