package com.wuyi.kebiao;

/**
 * 本文件职责：
 * 启动页：展示品牌形象 500ms 后跳转 MainActivity 并结束自身。
 */

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import androidx.appcompat.app.AppCompatActivity;

public class SplashActivity extends AppCompatActivity {
    private static final int SPLASH_DELAY_MS = 500;

    @Override
    protected void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        CrashHandler.install(this);
        setContentView(R.layout.activity_splash);
        ThemeH.init(this);
        ThemeH.statusBar(this);
        ThemeH.applySerifFont(getWindow().getDecorView());
        new Handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                SplashActivity.this.startActivity(new Intent(SplashActivity.this, (Class<?>) MainActivity.class));
                SplashActivity.this.finish();
            }
        }, 500L);
    }
}
