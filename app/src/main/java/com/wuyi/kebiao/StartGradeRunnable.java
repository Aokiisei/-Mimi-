package com.wuyi.kebiao;

/* JADX INFO: loaded from: classes.dex */
public class StartGradeRunnable implements Runnable {
    private HomeFragment frag;

    public StartGradeRunnable(HomeFragment homeFragment) {
        this.frag = homeFragment;
    }

    @Override // java.lang.Runnable
    public void run() {
        this.frag.startGradeCrawl();
    }
}
