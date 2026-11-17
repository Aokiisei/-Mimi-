package com.wuyi.kebiao;

/**
 * 本文件职责：
 * 位置签到的地图选点弹窗。复用「校园地图」里已配置的高德 JS API Key 与安全密钥，
 * 在地图上拖动/点击选点并逆地理编码出地址，确认后回传给签到弹窗。
 */

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

public class XxtMapPickerDialog extends Dialog {

    public interface OnPickListener {
        void onPick(String lat, String lon, String address);
    }

    private static final double DEFAULT_LAT = 27.72698;
    private static final double DEFAULT_LON = 118.00326;

    private final Activity activity;
    private final OnPickListener listener;
    private WebView webView;

    public XxtMapPickerDialog(Activity activity, double lat, double lon, String address,
                              OnPickListener listener) {
        super(activity);
        this.activity = activity;
        this.listener = listener;
        double useLat = lat;
        double useLon = lon;
        if (!valid(useLat, useLon)) {
            useLat = DEFAULT_LAT;
            useLon = DEFAULT_LON;
        }
        setup(useLat, useLon, address == null ? "" : address);
    }

    private static boolean valid(double lat, double lon) {
        return lat >= -90 && lat <= 90 && lon >= -180 && lon <= 180
                && !(lat == 0 && lon == 0) && lat != -1 && lon != -1;
    }

    private void setup(double lat, double lon, String address) {
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        if (getWindow() != null) {
            getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }
        webView = new WebView(activity);
        webView.setBackgroundColor(Color.TRANSPARENT);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }
        webView.setWebViewClient(new WebViewClient());
        webView.addJavascriptInterface(new MapBridge(lat, lon, address), "MimiXxtMap");
        webView.loadUrl("file:///android_asset/www/xxt_map_picker.html");
        setContentView(webView, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    @Override
    public void show() {
        super.show();
        Window window = getWindow();
        if (window != null) {
            WindowManager.LayoutParams lp = window.getAttributes();
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
            lp.height = ViewGroup.LayoutParams.MATCH_PARENT;
            window.setAttributes(lp);
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }
    }

    public final class MapBridge {
        private final double lat;
        private final double lon;
        private final String address;

        MapBridge(double lat, double lon, String address) {
            this.lat = lat;
            this.lon = lon;
            this.address = address;
        }

        @JavascriptInterface
        public String getConfig() {
            String key = "";
            String security = "";
            try {
                android.content.SharedPreferences p = activity.getSharedPreferences("mimi_map", Context.MODE_PRIVATE);
                key = p.getString("amap_key", "");
                security = p.getString("amap_jscode", "");
            } catch (Exception ignored) {
            }
            JSONObject o = new JSONObject();
            try {
                o.put("key", key == null ? "" : key);
                o.put("security", security == null ? "" : security);
                o.put("lat", lat);
                o.put("lon", lon);
                o.put("address", address);
                o.put("title", "选择签到位置");
            } catch (Exception ignored) {
            }
            return o.toString();
        }

        @JavascriptInterface
        public void confirm(final String useLat, final String useLon, final String address) {
            activity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (listener != null) {
                        listener.onPick(useLat, useLon, address == null ? "" : address);
                    }
                    dismiss();
                }
            });
        }

        @JavascriptInterface
        public void close() {
            activity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    dismiss();
                }
            });
        }
    }

    @Override
    public void dismiss() {
        try {
            if (webView != null) {
                webView.removeJavascriptInterface("MimiXxtMap");
                webView.loadUrl("about:blank");
                webView.destroy();
                webView = null;
            }
        } catch (Exception ignored) {
        }
        super.dismiss();
    }
}
