package com.wuyi.kebiao;

/**
 * 本文件职责：
 * 主线程回调：把抓取到的成绩 JSON 传给 HomeFragment。
 */

public class GradeNotifyRunnable implements Runnable {
    private HomeFragment frag;
    private String json;

    public GradeNotifyRunnable(HomeFragment homeFragment, String str) {
        this.frag = homeFragment;
        this.json = str;
    }

    @Override
    public void run() {
        this.frag.notifyFrontGrades(this.json);
    }
}
