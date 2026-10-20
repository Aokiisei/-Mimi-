package com.wuyi.kebiao;

/**
 * 本文件职责：
 * 主线程回调：把公告文本交给 ProfileFragment 弹窗显示。
 */

public class AnnouncementShowRunnable implements Runnable {
    private ProfileFragment frag;
    private String msg;

    public AnnouncementShowRunnable(ProfileFragment profileFragment, String str) {
        this.frag = profileFragment;
        this.msg = str;
    }

    @Override
    public void run() {
        this.frag.notifyAnnouncement(this.msg);
    }
}
