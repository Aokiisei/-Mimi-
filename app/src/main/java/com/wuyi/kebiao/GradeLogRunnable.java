package com.wuyi.kebiao;

/**
 * 本文件职责：
 * 主线程回调：把成绩抓取过程的日志文本传给 HomeFragment。
 */

public class GradeLogRunnable implements Runnable {
    private HomeFragment frag;
    private String msg;

    public GradeLogRunnable(HomeFragment homeFragment, String str) {
        this.frag = homeFragment;
        this.msg = str;
    }

    @Override
    public void run() {
        this.frag.notifyGradeLog(this.msg);
    }
}
