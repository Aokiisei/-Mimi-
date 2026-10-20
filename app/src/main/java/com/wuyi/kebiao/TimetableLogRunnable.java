package com.wuyi.kebiao;

/**
 * 本文件职责：
 * 主线程回调：把课表抓取过程的日志文本传给 HomeFragment。
 */

public class TimetableLogRunnable implements Runnable {
    private HomeFragment frag;
    private String msg;

    public TimetableLogRunnable(HomeFragment homeFragment, String str) {
        this.frag = homeFragment;
        this.msg = str;
    }

    @Override
    public void run() {
        this.frag.notifyTimetableLog(this.msg);
    }
}
