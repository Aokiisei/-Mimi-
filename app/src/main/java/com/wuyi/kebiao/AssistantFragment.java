package com.wuyi.kebiao;

/**
 * 本文件职责：
 * 「小助手」页面 Fragment（底部导航第三个入口）。
 * 罗列校园实用工具入口：武夷山公交查询、校园地图标注。
 */

import android.Manifest;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import java.net.URLEncoder;

public class AssistantFragment extends Fragment {

    private static final String MAP_PREFS = "mimi_map";

    private Dialog busDialog;
    private Dialog mapDialog;

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
        View mapRow = view.findViewById(R.id.layout_campus_map);
        if (mapRow != null) {
            mapRow.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showCampusMap();
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

    // 校园地图标注：用户自备高德 JS API Key，地图上人人可加点、看点
    private void showCampusMap() {
        if (getContext() == null) {
            return;
        }
        WebView webView = new WebView(getContext());
        webView.setBackgroundColor(Color.TRANSPARENT);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setDomStorageEnabled(true);
        settings.setSupportZoom(false);
        // 地图页的「定位」按钮依赖网页定位，需要允许页面申请定位权限
        settings.setGeolocationEnabled(true);
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onGeolocationPermissionsShowPrompt(String origin,
                                                           GeolocationPermissions.Callback callback) {
                callback.invoke(origin, true, false);
            }
        });
        webView.addJavascriptInterface(new MapBridge(getContext().getApplicationContext()), "MimiBridge");
        webView.loadUrl("file:///android_asset/www/map.html");
        requestLocationPermission();

        mapDialog = new Dialog(getContext());
        mapDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        mapDialog.setContentView(webView);
        Window window = mapDialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setDimAmount(0.32f);
        }
        mapDialog.setOnDismissListener(dialog -> webView.destroy());
        mapDialog.show();
        if (window != null) {
            DisplayMetrics dm = getResources().getDisplayMetrics();
            window.setLayout(Math.round(dm.widthPixels * 0.96f), Math.round(dm.heightPixels * 0.86f));
        }
    }

    private void requestLocationPermission() {
        if (getActivity() == null) {
            return;
        }
        try {
            if (ContextCompat.checkSelfPermission(requireActivity(),
                    Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(requireActivity(), new String[]{
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                }, 41);
            }
        } catch (Exception ignored) {
        }
    }

    private class MapBridge {
        private final Context appContext;

        MapBridge(Context context) {
            this.appContext = context;
        }

        @JavascriptInterface
        public void close() {
            if (mapDialog != null && mapDialog.isShowing()) {
                mapDialog.dismiss();
            }
        }

        @JavascriptInterface
        public String getAmapKey() {
            return prefs().getString("amap_key", "");
        }

        @JavascriptInterface
        public String getAmapSecurity() {
            return prefs().getString("amap_jscode", "");
        }

        @JavascriptInterface
        public void saveAmapKey(String key, String jscode) {
            prefs().edit()
                    .putString("amap_key", key == null ? "" : key.trim())
                    .putString("amap_jscode", jscode == null ? "" : jscode.trim())
                    .apply();
        }

        private SharedPreferences prefs() {
            return appContext.getSharedPreferences(MAP_PREFS, Context.MODE_PRIVATE);
        }

        // 标注署名用机型，便于区分是谁加的
        @JavascriptInterface
        public String getDeviceName() {
            String brand = Build.BRAND == null ? "" : Build.BRAND.trim();
            String model = Build.MODEL == null ? "" : Build.MODEL.trim();
            if (model.startsWith(brand)) {
                return model;
            }
            return (brand + " " + model).trim();
        }

        // 本机随机标识：只用于「删自己的点」和「同一人只举报一次」，不含任何个人信息
        @JavascriptInterface
        public String getDeviceId() {
            SharedPreferences sp = prefs();
            String id = sp.getString("device_id", "");
            if (id == null || id.length() < 8) {
                id = java.util.UUID.randomUUID().toString().replace("-", "");
                sp.edit().putString("device_id", id).apply();
            }
            return id;
        }

        /**
         * 唤起外部高德地图的「路线规划」页（不是直接开始驾车导航）。
         * 用 androidamap://route，进入后由用户自己选择驾车/步行/骑行等方式。
         */
        @JavascriptInterface
        public void openNavi(String lat, String lng, String name) {
            try {
                double la = Double.parseDouble(lat);
                double ln = Double.parseDouble(lng);
                if (la < -90 || la > 90 || ln < -180 || ln > 180) return;
                String nm = URLEncoder.encode(name == null ? "" : name, "UTF-8");

                // 1) 明确指定高德包名，避免被浏览器或其它地图抢走
                Intent navi = new Intent(Intent.ACTION_VIEW, Uri.parse(
                        "androidamap://route?sourceApplication=mimi&dname=" + nm
                                + "&dlat=" + la + "&dlon=" + ln + "&dev=0&t=0"));
                navi.setPackage("com.autonavi.minimap");
                navi.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    appContext.startActivity(navi);
                    return;
                } catch (Exception notInstalled) {
                    // 没装高德，走下面的网页版
                }

                // 2) 网页版兜底：同样是路线规划页，可选出行方式
                String url = "https://uri.amap.com/navigation?to=" + ln + "," + la + ","
                        + nm + "&mode=car&policy=1&src=mimi&coordinate=gaode&callnative=1";
                Intent web = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                web.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                appContext.startActivity(web);
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public void onDestroyView() {
        if (busDialog != null && busDialog.isShowing()) {
            busDialog.dismiss();
        }
        if (mapDialog != null && mapDialog.isShowing()) {
            mapDialog.dismiss();
        }
        busDialog = null;
        mapDialog = null;
        super.onDestroyView();
    }
}
