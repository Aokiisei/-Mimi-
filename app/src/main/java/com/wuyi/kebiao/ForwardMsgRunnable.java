package com.wuyi.kebiao;

/* JADX INFO: loaded from: classes.dex */
public class ForwardMsgRunnable implements Runnable {
    private HomeFragment frag;
    private String payload;
    private String type;

    public ForwardMsgRunnable(HomeFragment homeFragment, String str, String str2) {
        this.frag = homeFragment;
        this.type = str;
        this.payload = str2;
    }

    @Override // java.lang.Runnable
    public void run() {
        try {
            this.frag.webView.evaluateJavascript("window.receiveFromAndroid('" + this.type + "', '" + this.payload + "');", null);
        } catch (Throwable unused) {
        }
    }
}
