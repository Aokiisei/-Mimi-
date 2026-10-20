package com.wuyi.kebiao;

/**
 * 本文件职责：
 * 后台线程拉取服务器公告（announcement.txt）。
 * 取不到时用兜底文案，再切回主线程交给 AnnouncementShowRunnable 显示。
 */

import android.os.Handler;
import android.os.Looper;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Scanner;

public class AnnouncementFetcher implements Runnable {
    private ProfileFragment frag;

    public AnnouncementFetcher(ProfileFragment profileFragment) {
        this.frag = profileFragment;
    }

    @Override
    public void run() {
        String str = "暂未获取到公告，请检查网络后重试，或访问 https://aokiisei.vxni.ink/ 查看";
        try {
            HttpURLConnection httpURLConnection = (HttpURLConnection) new URL("https://aokiisei.vxni.ink/mimi/announcement.txt").openConnection();
            httpURLConnection.setConnectTimeout(5000);
            httpURLConnection.setReadTimeout(5000);
            if (httpURLConnection.getResponseCode() == 200) {
                Scanner scanner = new Scanner(httpURLConnection.getInputStream(), "UTF-8");
                scanner.useDelimiter("\\A");
                if (scanner.hasNext()) {
                    String strTrim = scanner.next().trim();
                    if (strTrim.length() > 0) {
                        str = strTrim;
                    }
                }
            }
        } catch (Throwable th) {
        }
        new Handler(Looper.getMainLooper()).post(new AnnouncementShowRunnable(this.frag, str));
    }
}
