package com.wuyi.kebiao;

import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.navigation.fragment.NavHostFragment;
import androidx.navigation.ui.NavigationUI;
import com.google.android.material.bottomnavigation.BottomNavigationView;

/* JADX INFO: loaded from: classes.dex */
public class MainActivity extends AppCompatActivity {
    @Override // androidx.fragment.app.FragmentActivity, androidx.activity.ComponentActivity, androidx.core.app.ComponentActivity, android.app.Activity
    protected void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        CrashHandler.install(this);
        setContentView(R.layout.activity_main);
        ThemeH.init(this);
        ThemeH.statusBar(this);
        BottomNavigationView bottomNavigationView = (BottomNavigationView) findViewById(R.id.bottom_navigation);
        ThemeH.tintBottomNav(bottomNavigationView);
        NavHostFragment navHostFragment = (NavHostFragment) getSupportFragmentManager().findFragmentById(R.id.nav_host_fragment);
        if (navHostFragment != null) {
            NavigationUI.setupWithNavController(bottomNavigationView, navHostFragment.getNavController());
            UpdateChecker.checkAuto(this);
        }
    }
}
