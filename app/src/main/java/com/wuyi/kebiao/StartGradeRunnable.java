package com.wuyi.kebiao;

/**
 * 本文件职责：
 * 主线程回调：触发 HomeFragment 开始抓取成绩。
 */

public class StartGradeRunnable implements Runnable {
    private HomeFragment frag;

    public StartGradeRunnable(HomeFragment homeFragment) {
        this.frag = homeFragment;
    }

    @Override
    public void run() {
        this.frag.startGradeCrawl();
    }
}
