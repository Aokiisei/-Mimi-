package com.wuyi.kebiao;

/**
 * 本文件职责：
 * 「日程表」页面 Fragment。
 * WebView 加载 schedule.html，通过 ScheduleBridge 提供日程的增删查接口。
 * 回到本页时自动刷新日程列表（同步主界面课表页或清理设置带来的变化）。
 */

import android.content.Context;
import android.os.Bundle;
import android.os.Environment;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import androidx.fragment.app.Fragment;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class ScheduleFragment extends Fragment {
    private WebView webView;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_schedule, container, false);
        this.webView = (WebView) view.findViewById(R.id.webView);
        WebSettings settings = this.webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setCacheMode(2);
        this.webView.setWebChromeClient(new WebChromeClient());
        this.webView.addJavascriptInterface(new ScheduleBridge(), "Android");
        this.webView.loadUrl("file:///android_asset/www/schedule.html");
        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (this.webView != null) {
            this.webView.evaluateJavascript("window.refreshAll && window.refreshAll()", null);
        }
    }

    public class ScheduleBridge {
        @JavascriptInterface
        public void setPagerSwipe(boolean enabled) {
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).setPagerSwipe(enabled);
            }
        }

        @JavascriptInterface
        public String getSchedules() {
            return ScheduleStore.loadJson(ScheduleFragment.this.requireContext());
        }

        @JavascriptInterface
        public void saveSchedule(String json) {
            ScheduleStore.save(ScheduleFragment.this.requireContext(), json);
        }

        @JavascriptInterface
        public void deleteSchedule(String id) {
            ScheduleStore.delete(ScheduleFragment.this.requireContext(), id);
        }

        @JavascriptInterface
        public String getCountdowns() {
            return ScheduleStore.loadCountdowns(ScheduleFragment.this.requireContext());
        }

        @JavascriptInterface
        public void saveCountdown(String json) {
            ScheduleStore.saveCountdown(ScheduleFragment.this.requireContext(), json);
        }

        @JavascriptInterface
        public void deleteCountdown(String id) {
            ScheduleStore.deleteCountdown(ScheduleFragment.this.requireContext(), id);
        }

        @JavascriptInterface
        public String getGlassUi() {
            String str = ThemeH.glass;
            return str != null ? str : "0";
        }

        /**
         * 导出日程与倒计日数据到文件（不清理任何缓存，数据保留原样）。
         * @param json  待导出的完整 JSON 字符串
         * @param hint  文件名提示（不含扩展名）
         * @return      成功返回绝对路径，失败返回错误信息
         */
        @JavascriptInterface
        public String exportData(String json, String hint) {
            Context ctx = ScheduleFragment.this.getContext();
            if (ctx == null || TextUtils.isEmpty(json)) return "导出失败：上下文不可用";
            try {
                String name = TextUtils.isEmpty(hint) ? "mimi-schedule" : hint.replaceAll("[^a-zA-Z0-9_-]", "_");
                String ts = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
                String filename = name + "-" + ts + ".json";
                File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                if (dir == null || !dir.exists() && !dir.mkdirs()) {
                    dir = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                }
                if (dir == null) dir = ctx.getFilesDir();
                File f = new File(dir, filename);
                try (FileOutputStream fos = new FileOutputStream(f)) {
                    fos.write(json.getBytes("UTF-8"));
                }
                return f.getAbsolutePath();
            } catch (IOException e) {
                return "导出失败：" + e.getMessage();
            } catch (Exception e) {
                return "导出失败：" + e.getMessage();
            }
        }
    }
}
