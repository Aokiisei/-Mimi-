package com.wuyi.kebiao;

/* JADX INFO: loaded from: classes.dex */
public class GradeNotifyRunnable implements Runnable {
    private HomeFragment frag;
    private String json;

    public GradeNotifyRunnable(HomeFragment homeFragment, String str) {
        this.frag = homeFragment;
        this.json = str;
    }

    @Override // java.lang.Runnable
    public void run() {
        this.frag.notifyFrontGrades(this.json);
    }
}
