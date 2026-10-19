package com.wuyi.kebiao;

/* JADX INFO: loaded from: classes.dex */
public class AnnouncementShowRunnable implements Runnable {
    private ProfileFragment frag;
    private String msg;

    public AnnouncementShowRunnable(ProfileFragment profileFragment, String str) {
        this.frag = profileFragment;
        this.msg = str;
    }

    @Override // java.lang.Runnable
    public void run() {
        this.frag.notifyAnnouncement(this.msg);
    }
}
