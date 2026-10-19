package com.wuyi.kebiao;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import androidx.appcompat.app.AppCompatActivity;

/* JADX INFO: loaded from: classes.dex */
public class SplashActivity extends AppCompatActivity {
    private static final int SPLASH_DELAY_MS = 500;

    @Override // androidx.fragment.app.FragmentActivity, androidx.activity.ComponentActivity, androidx.core.app.ComponentActivity, android.app.Activity
    protected void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        CrashHandler.install(this);
        setContentView(R.layout.activity_splash);
        ThemeH.init(this);
        ThemeH.statusBar(this);
        new Handler().postDelayed(new Runnable() { // from class: com.wuyi.kebiao.SplashActivity.1
            @Override // java.lang.Runnable
            public void run() {
                SplashActivity.this.startActivity(new Intent(SplashActivity.this, (Class<?>) MainActivity.class));
                SplashActivity.this.finish();
            }
        }, 500L);
    }
}
