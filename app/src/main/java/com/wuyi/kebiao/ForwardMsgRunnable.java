package com.wuyi.kebiao;

/**
 * 本文件职责：
 * 主线程回调：向 WebView 执行 JS，
 * 把数据透传给页面里的 window.receiveFromAndroid(type, payload)。
 */

public class ForwardMsgRunnable implements Runnable {
    private HomeFragment frag;
    private String payload;
    private String type;

    public ForwardMsgRunnable(HomeFragment homeFragment, String str, String str2) {
        this.frag = homeFragment;
        this.type = str;
        this.payload = str2;
    }

    @Override
    public void run() {
        try {
            this.frag.webView.evaluateJavascript("window.receiveFromAndroid('" + this.type + "', '" + this.payload + "');", null);
        } catch (Throwable unused) {
        }
    }
}
