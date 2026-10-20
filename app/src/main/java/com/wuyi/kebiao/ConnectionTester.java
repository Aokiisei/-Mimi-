package com.wuyi.kebiao;

/**
 * 本文件职责：
 * 在子线程请求教务系统首页判断连通性，
 * 再把结果（HTTP 状态码或失败原因）用 Toast 提示出来。
 */

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;
import java.net.HttpURLConnection;
import java.net.URL;

public class ConnectionTester implements Runnable {
    private static Context ctx;
    private static int mode;
    private static String result;

    public static void test(Context context) {
        Toast.makeText(context, "正在测试连接...", 0).show();
        ctx = context;
        mode = 0;
        new Thread(new ConnectionTester()).start();
    }

    @Override
    public void run() {
        if (mode != 0) {
            Toast.makeText(ctx, result, 1).show();
            return;
        }
        try {
            HttpURLConnection httpURLConnection = (HttpURLConnection) new URL("https://jwxt.wuyiu.edu.cn").openConnection();
            httpURLConnection.setConnectTimeout(5000);
            httpURLConnection.setReadTimeout(5000);
            httpURLConnection.connect();
            int responseCode = httpURLConnection.getResponseCode();
            httpURLConnection.disconnect();
            if (responseCode > 0) {
                result = "✅ 教务系统可连接 (HTTP " + responseCode + ")";
            } else {
                result = "⚠️ 教务系统响应异常";
            }
        } catch (Exception e) {
            result = "❌ 无法连接: " + e.getMessage();
        }
        mode = 1;
        new Handler(Looper.getMainLooper()).post(this);
    }
}
