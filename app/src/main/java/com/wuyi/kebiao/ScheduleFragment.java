package com.wuyi.kebiao;

/**
 * 本文件职责：
 * 「日程表」页面 Fragment。
 * WebView 加载 schedule.html，通过 ScheduleBridge 提供日程的增删查接口。
 * 回到本页时自动刷新日程列表（同步主界面课表页或清理设置带来的变化）。
 */

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import androidx.fragment.app.Fragment;

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
            // 日程卡片横滑完成时临时关闭翻页，避免手势被左右翻页抢走
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
    }
}
