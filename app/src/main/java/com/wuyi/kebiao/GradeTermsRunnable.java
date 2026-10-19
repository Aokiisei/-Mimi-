package com.wuyi.kebiao;

/* JADX INFO: loaded from: classes.dex */
public class GradeTermsRunnable implements Runnable {
    private HomeFragment frag;
    private String json;

    public GradeTermsRunnable(HomeFragment homeFragment, String str) {
        this.frag = homeFragment;
        this.json = str;
    }

    @Override // java.lang.Runnable
    public void run() {
        this.frag.notifyGradeTerms(this.json);
    }
}
