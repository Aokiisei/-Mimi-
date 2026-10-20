package com.wuyi.kebiao;

/**
 * 本文件职责：
 * 主线程回调：触发 HomeFragment 加载爬虫入口页。
 */

public class StartLoadRunnable implements Runnable {
    private HomeFragment frag;

    public StartLoadRunnable(HomeFragment homeFragment) {
        this.frag = homeFragment;
    }

    @Override
    public void run() {
        this.frag.loadCrawlerEntry();
    }
}
