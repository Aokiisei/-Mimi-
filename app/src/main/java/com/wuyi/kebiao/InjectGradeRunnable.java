package com.wuyi.kebiao;

/* JADX INFO: loaded from: classes.dex */
public class InjectGradeRunnable implements Runnable {
    private HomeFragment frag;

    public InjectGradeRunnable(HomeFragment homeFragment) {
        this.frag = homeFragment;
    }

    @Override // java.lang.Runnable
    public void run() {
        this.frag.injectGradeParser();
    }
}
