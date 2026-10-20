package com.wuyi.kebiao;

/**
 * 本文件职责：
 * 「小助手」页面 Fragment（底部导航第三个入口）。
 * 罗列校园实用工具入口，目前提供「武夷山公交查询」线路图。
 */

import android.app.Dialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;

public class AssistantFragment extends Fragment {

    private Dialog busDialog;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_assistant, container, false);
        TextView title = view.findViewById(R.id.tv_page_title);
        if (title != null) {
            try {
                title.setTypeface(Typeface.createFromAsset(inflater.getContext().getAssets(), "fonts/mi-serif.otf"));
            } catch (Throwable ignored) {
            }
        }
        // 原生页面所有文本统一衬线字体
        ThemeH.applySerifFont(view);
        View busRow = view.findViewById(R.id.layout_bus_query);
        if (busRow != null) {
            busRow.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showBusRoutes();
                }
            });
        }
        return view;
    }

    // 公交线路图：用 WebView 呈现直线 + 站点竖排文字，可横向滑动查看
    private void showBusRoutes() {
        if (getContext() == null) {
            return;
        }
        WebView webView = new WebView(getContext());
        webView.setBackgroundColor(Color.TRANSPARENT);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setSupportZoom(false);
        webView.addJavascriptInterface(new BusBridge(), "MimiBridge");
        webView.loadUrl("file:///android_asset/www/bus.html");

        busDialog = new Dialog(getContext());
        busDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        busDialog.setContentView(webView);
        Window window = busDialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setDimAmount(0.32f);
        }
        busDialog.setOnDismissListener(dialog -> webView.destroy());
        busDialog.show();
        if (window != null) {
            DisplayMetrics dm = getResources().getDisplayMetrics();
            // 高度按内容收紧，避免线路下方留大片空白
            int h = Math.min(Math.round(dm.heightPixels * 0.86f), Math.round(520 * dm.density));
            window.setLayout(Math.round(dm.widthPixels * 0.96f), h);
        }
    }

    private class BusBridge {
        @JavascriptInterface
        public void close() {
            if (busDialog != null && busDialog.isShowing()) {
                busDialog.dismiss();
            }
        }
    }

    @Override
    public void onDestroyView() {
        if (busDialog != null && busDialog.isShowing()) {
            busDialog.dismiss();
        }
        busDialog = null;
        super.onDestroyView();
    }
}
