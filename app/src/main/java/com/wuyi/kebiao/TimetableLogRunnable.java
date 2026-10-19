package com.wuyi.kebiao;

/* JADX INFO: loaded from: classes.dex */
public class TimetableLogRunnable implements Runnable {
    private HomeFragment frag;
    private String msg;

    public TimetableLogRunnable(HomeFragment homeFragment, String str) {
        this.frag = homeFragment;
        this.msg = str;
    }

    @Override // java.lang.Runnable
    public void run() {
        this.frag.notifyTimetableLog(this.msg);
    }
}
