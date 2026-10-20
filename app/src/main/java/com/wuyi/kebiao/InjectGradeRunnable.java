package com.wuyi.kebiao;

/**
 * 本文件职责：
 * 主线程回调：触发 HomeFragment 注入成绩解析脚本。
 */

public class InjectGradeRunnable implements Runnable {
    private HomeFragment frag;

    public InjectGradeRunnable(HomeFragment homeFragment) {
        this.frag = homeFragment;
    }

    @Override
    public void run() {
        this.frag.injectGradeParser();
    }
}
