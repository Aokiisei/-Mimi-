package com.wuyi.kebiao;

/**
 * 本文件职责：
 * 主线程回调：把可选学期列表 JSON 传给 HomeFragment。
 */

public class GradeTermsRunnable implements Runnable {
    private HomeFragment frag;
    private String json;

    public GradeTermsRunnable(HomeFragment homeFragment, String str) {
        this.frag = homeFragment;
        this.json = str;
    }

    @Override
    public void run() {
        this.frag.notifyGradeTerms(this.json);
    }
}
