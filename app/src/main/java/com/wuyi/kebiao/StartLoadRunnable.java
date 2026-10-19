package com.wuyi.kebiao;

/* JADX INFO: loaded from: classes.dex */
public class StartLoadRunnable implements Runnable {
    private HomeFragment frag;

    public StartLoadRunnable(HomeFragment homeFragment) {
        this.frag = homeFragment;
    }

    @Override // java.lang.Runnable
    public void run() {
        this.frag.loadCrawlerEntry();
    }
}
