package com.wuyi.kebiao;

/**
 * 本文件职责：
 * 子线程把用户反馈 POST 到服务器 feedback.php，
 * 附带机型与 SDK 版本，结果用 Toast 提示。
 */

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

public class FeedbackSender implements Runnable {
    private final String content;
    private final Context ctx;

    public FeedbackSender(Context context, String str) {
        this.ctx = context;
        this.content = str;
    }

    @Override
    public void run() {
        String str;
        try {
            HttpURLConnection httpURLConnection = (HttpURLConnection) new URL("https://aokiisei.vxni.ink/mimi/feedback.php").openConnection();
            httpURLConnection.setRequestMethod("POST");
            httpURLConnection.setDoOutput(true);
            httpURLConnection.setConnectTimeout(10000);
            httpURLConnection.setReadTimeout(10000);
            httpURLConnection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8");
            byte[] bytes = ("content=" + URLEncoder.encode(this.content, "UTF-8") + "&model=" + URLEncoder.encode(Build.MODEL, "UTF-8") + "&sdk=" + Build.VERSION.SDK_INT).getBytes("UTF-8");
            OutputStream outputStream = httpURLConnection.getOutputStream();
            outputStream.write(bytes);
            outputStream.flush();
            outputStream.close();
            int responseCode = httpURLConnection.getResponseCode();
            httpURLConnection.disconnect();
            str = responseCode == 200 ? "✅ 反馈已提交，感谢你的建议！" : "❌ 提交失败，请检查网络，或确认后台已部署 feedback.php";
        } catch (Throwable th) {
            str = "❌ 提交失败，请检查网络，或确认后台已部署 feedback.php";
        }
        new Handler(Looper.getMainLooper()).post(new UpdateChecker.ToastRunnable(this.ctx, str));
    }
}
