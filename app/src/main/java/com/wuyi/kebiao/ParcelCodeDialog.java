package com.wuyi.kebiao;

/**
 * 本文件职责：
 * 「快递身份码」弹窗。
 * 内置菜鸟 / 淘宝 / 拼多多三个官方身份码网页，登录、验证与出码全部在官方页面内完成，
 * 应用不读取、不保存账号、验证码与 Cookie 内容；只对拼多多官方脚本注入一段同源请求
 * 映射（assets/www/pdd_same_origin.js，来源 HandyCode，MIT），避免网页登录后被判定未登录。
 */

import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.zip.GZIPInputStream;

public final class ParcelCodeDialog {

    private static final String CAINIAO_ENTRY = "https://page.cainiao.com/cn-app-web/identity-code/index.html#/";
    private static final String TAOBAO_ENTRY = "https://pages-fast.m.taobao.com/wow/z/uniapp/1011717/last-mile-fe/end-collect-platform/identity-code";
    private static final String PDD_ENTRY = "https://m.pinduoduo.net/mdkd/package?tab=ID_CODE";
    private static final String JD_ENTRY = "https://trade.m.jd.com/order/orderlist_jdm.shtml";

    // 标签顺序按使用频率排列：淘宝、拼多多、京东、菜鸟
    private static final int IDX_TAOBAO = 0;
    private static final int IDX_PDD = 1;
    private static final int IDX_JD = 2;
    private static final int IDX_CAINIAO = 3;

    private static final String[] TAB_TITLES = {"淘宝", "拼多多", "京东", "菜鸟"};
    private static final String[] TAB_ENTRIES = {TAOBAO_ENTRY, PDD_ENTRY, JD_ENTRY, CAINIAO_ENTRY};

    // 只允许官方页面在弹窗内导航，其他链接交给系统浏览器
    private static final String[] ALLOWED_HOSTS = {
            "taobao.com", "tmall.com", "alipay.com", "cainiao.com", "alibaba.com",
            "pinduoduo.net", "yangkeduo.com", "jd.com"
    };

    private final Context context;
    private final float density;
    private final Dialog dialog;

    private final FrameLayout content;
    private final ProgressBar progress;
    private final TextView hint;
    private TextView sessionAction;
    private final TextView[] tabs = new TextView[TAB_TITLES.length];
    private final WebView[] pages = new WebView[TAB_TITLES.length];

    private final String sameOriginScript;
    private int current = -1;

    // 拼多多官方应用脚本缓存：同一版本只下载一次，避免每次刷新都重新拉取
    private String pddBundleUrl;
    private byte[] pddBundleData;

    public ParcelCodeDialog(Context context) {
        this.context = context;
        this.density = context.getResources().getDisplayMetrics().density;
        this.sameOriginScript = readAsset(context, "www/pdd_same_origin.js");

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.bg_cl_card);
        if (Build.VERSION.SDK_INT >= 21) {
            root.setClipToOutline(true);
        }
        root.addView(buildHeader());
        root.addView(buildTabs());

        hint = new TextView(context);
        hint.setTextSize(11.0f);
        hint.setTextColor(0xFFB0AEA5);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(dp(14), 0, dp(14), (int) (6.0f * density + 0.5f));
        root.addView(hint);

        progress = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setVisibility(View.GONE);
        if (Build.VERSION.SDK_INT >= 21) {
            progress.setProgressTintList(ColorStateList.valueOf(0xFFC96442));
        }
        root.addView(progress, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, dp(2))));

        content = new FrameLayout(context);
        content.setBackgroundColor(Color.WHITE);
        root.addView(content, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f));

        ThemeH.applySerifFont(root);

        dialog = new Dialog(context);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(root);
        dialog.setCanceledOnTouchOutside(true);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setDimAmount(0.32f);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        dialog.setOnShowListener(d -> {
            Window w = dialog.getWindow();
            if (w != null) {
                DisplayMetrics dm = context.getResources().getDisplayMetrics();
                w.setLayout(Math.round(dm.widthPixels * 0.96f), Math.round(dm.heightPixels * 0.90f));
            }
            select(0, true);
        });
        dialog.setOnDismissListener(d -> release());
        dialog.setOnKeyListener((d, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP) {
                WebView web = currentPage();
                if (web != null && web.canGoBack()) {
                    web.goBack();
                } else {
                    dialog.dismiss();
                }
                return true;
            }
            return false;
        });
    }

    public void show() {
        dialog.show();
    }

    public void dismiss() {
        if (dialog.isShowing()) {
            dialog.dismiss();
        }
    }

    private View buildHeader() {
        LinearLayout bar = new LinearLayout(context);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(16), dp(12), dp(8), dp(4));

        TextView title = new TextView(context);
        title.setText("快递身份码");
        title.setTextSize(16.0f);
        title.setTextColor(0xFF141413);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        bar.addView(title, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));

        TextView logout = (TextView) new TextView(context);
        logout.setText("未登录");
        logout.setTextSize(13.0f);
        logout.setTextColor(0xFFB0AEA5);
        logout.setPadding(dp(8), dp(6), dp(8), dp(6));
        logout.setOnClickListener(v -> {
            if (current >= 0 && isLoggedIn(current)) {
                logoutCurrentPlatform();
            }
        });
        sessionAction = logout;
        bar.addView(logout);

        TextView refresh = new TextView(context);
        refresh.setText("刷新");
        refresh.setTextSize(13.0f);
        refresh.setTextColor(0xFFC96442);
        refresh.setPadding(dp(10), dp(6), dp(10), dp(6));
        refresh.setOnClickListener(v -> {
            WebView web = currentPage();
            if (web != null) {
                web.reload();
            }
        });
        bar.addView(refresh);

        TextView close = new TextView(context);
        close.setText("关闭");
        close.setTextSize(13.0f);
        close.setTextColor(0xFF87867F);
        close.setPadding(dp(10), dp(6), dp(10), dp(6));
        close.setOnClickListener(v -> dialog.dismiss());
        bar.addView(close);
        return bar;
    }

    private View buildTabs() {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(12), 0, dp(12), dp(6));
        for (int i = 0; i < TAB_TITLES.length; i++) {
            final int index = i;
            TextView tab = new TextView(context);
            tab.setText(TAB_TITLES[i]);
            tab.setTextSize(14.0f);
            tab.setGravity(Gravity.CENTER);
            tab.setPadding(0, dp(7), 0, dp(7));
            tab.setLayoutParams(new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));
            tab.setOnClickListener(v -> select(index, false));
            tabs[i] = tab;
            row.addView(tab);
        }
        return row;
    }

    private void select(int index, boolean force) {
        if (index < 0 || index >= TAB_ENTRIES.length) {
            return;
        }
        if (!force && index == current) {
            return;
        }
        // 菜鸟用的是淘宝登录态：从淘宝切回菜鸟时重新加载，确保用最新会话出码
        if (!force && index == IDX_CAINIAO && pages[IDX_CAINIAO] != null) {
            pages[IDX_CAINIAO].reload();
        }
        if (pages[index] == null) {
            WebView web = createWebView(index);
            pages[index] = web;
            content.addView(web, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
            web.loadUrl(TAB_ENTRIES[index]);
        }
        for (int i = 0; i < pages.length; i++) {
            if (pages[i] != null) {
                pages[i].setVisibility(i == index ? View.VISIBLE : View.GONE);
            }
        }
        current = index;
        updateTabStyles();
        updateHint(index);
        updateSessionAction();
    }

    /** 未登录只显示「未登录」，登录后才显示可点击的「退出登录」。 */
    private void updateSessionAction() {
        if (sessionAction == null) {
            return;
        }
        boolean loggedIn = current >= 0 && isLoggedIn(current);
        sessionAction.setText(loggedIn ? "退出登录" : "未登录");
        sessionAction.setTextColor(loggedIn ? 0xFF87867F : 0xFFB0AEA5);
        sessionAction.setClickable(loggedIn);
        sessionAction.setEnabled(loggedIn);
    }

    /**
     * 用「当前页面是否停在登录页 + 是否持有该平台的登录 Cookie」共同判断登录态，
     * 单看 Cookie 容易把匿名 Cookie 误判为已登录。
     */
    private boolean isLoggedIn(int index) {
        WebView web = pages[index];
        String url = web == null ? null : web.getUrl();
        if (isLoginPage(url)) {
            return false;
        }
        switch (index) {
            case IDX_TAOBAO:
            case IDX_CAINIAO:
                // 菜鸟复用淘宝登录态
                return hasAnyCookie(new String[]{"https://www.taobao.com", "https://www.tmall.com"},
                        new String[]{"tracknick", "lgc", "unb"});
            case IDX_PDD:
                return hasAnyCookie(
                        new String[]{"https://m.pinduoduo.net", "https://www.pinduoduo.com", "https://mobile.yangkeduo.com"},
                        new String[]{"pdd_user_id", "pdd_user_uin", "PDDAccessToken"});
            case IDX_JD:
                return hasAnyCookie(new String[]{"https://www.jd.com", "https://trade.m.jd.com"},
                        new String[]{"pt_key", "pt_pin", "thor", "unick"});
            default:
                return false;
        }
    }

    private static boolean isLoginPage(String url) {
        if (url == null || url.isEmpty()) {
            return false;
        }
        String lower = url.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("login.taobao.com")
                || lower.contains("login.tmall.com")
                || lower.contains("passport.taobao.com")
                || lower.contains("plogin.m.jd.com")
                || (lower.contains("pinduoduo") && lower.contains("/login"));
    }

    private static boolean hasAnyCookie(String[] urls, String[] names) {
        try {
            CookieManager cookieManager = CookieManager.getInstance();
            for (String url : urls) {
                String raw = cookieManager.getCookie(url);
                if (raw == null || raw.isEmpty()) {
                    continue;
                }
                for (String item : raw.split(";")) {
                    String pair = item.trim();
                    int eq = pair.indexOf('=');
                    if (eq <= 0) {
                        continue;
                    }
                    String name = pair.substring(0, eq).trim();
                    for (String target : names) {
                        if (target.equalsIgnoreCase(name)) {
                            return true;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private void updateHint(int index) {
        if (index == IDX_PDD) {
            hint.setText("拼多多网页登录会与手机端互相顶号，建议用小号登录");
        } else {
            hint.setText("登录与出码均在官方网页完成，本应用不保存账号信息");
        }
    }

    /**
     * 单独退出当前平台的登录：只清该平台的 Cookie 与当前页面的本地存储，
     * 不影响其他平台，也不影响教务账号。
     */
    private void logoutCurrentPlatform() {
        final int index = current;
        if (index < 0 || index >= TAB_ENTRIES.length) {
            return;
        }
        final WebView web = pages[index];
        if (web == null) {
            return;
        }
        web.evaluateJavascript("try{localStorage.clear();sessionStorage.clear();}catch(e){}", null);
        switch (index) {
            case IDX_TAOBAO:
                expireCookies(
                        new String[]{"https://www.taobao.com", "https://login.taobao.com",
                                "https://pages-fast.m.taobao.com", "https://www.tmall.com",
                                "https://www.alipay.com"},
                        new String[]{"taobao.com", "tmall.com", "alipay.com", "alibaba.com"});
                // 菜鸟复用淘宝登录态，已打开的菜鸟页同步刷新
                reloadIfLoaded(IDX_CAINIAO);
                break;
            case IDX_CAINIAO:
                expireCookies(
                        new String[]{"https://page.cainiao.com", "https://www.cainiao.com",
                                "https://www.taobao.com", "https://login.taobao.com"},
                        new String[]{"cainiao.com", "taobao.com", "tmall.com", "alipay.com", "alibaba.com"});
                reloadIfLoaded(IDX_TAOBAO);
                break;
            case IDX_PDD:
                expireCookies(
                        new String[]{"https://m.pinduoduo.net", "https://mobile.yangkeduo.com",
                                "https://www.pinduoduo.com"},
                        new String[]{"pinduoduo.net", "yangkeduo.com", "pinduoduo.com"});
                break;
            case IDX_JD:
                expireCookies(
                        new String[]{"https://trade.m.jd.com", "https://plogin.m.jd.com",
                                "https://www.jd.com", "https://passport.jd.com"},
                        new String[]{"jd.com"});
                break;
            default:
                break;
        }
        try {
            CookieManager.getInstance().flush();
        } catch (Throwable ignored) {
        }
        updateSessionAction();
        web.postDelayed(() -> {
            if (pages[index] == web) {
                web.clearHistory();
                web.loadUrl(TAB_ENTRIES[index]);
            }
        }, 250L);
    }

    private void reloadIfLoaded(final int index) {
        if (index < 0 || index >= pages.length) {
            return;
        }
        final WebView web = pages[index];
        if (web == null) {
            return;
        }
        web.postDelayed(() -> {
            if (pages[index] == web) {
                web.reload();
            }
        }, 250L);
    }

    /**
     * 按域名逐个过期 Cookie。getCookie 只能拿到当前 path 可见的 Cookie，
     * 所以用多个入口地址探测；同时清主机域与父域两种写法，确保登录 Cookie 被移除。
     */
    private static void expireCookies(String[] probes, String[] domains) {
        try {
            CookieManager cookieManager = CookieManager.getInstance();
            String expire = "=; Max-Age=0; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/";
            for (String probe : probes) {
                String raw = cookieManager.getCookie(probe);
                if (raw == null || raw.isEmpty()) {
                    continue;
                }
                for (String item : raw.split(";")) {
                    String pair = item.trim();
                    int eq = pair.indexOf('=');
                    if (eq <= 0) {
                        continue;
                    }
                    String name = pair.substring(0, eq).trim();
                    if (name.isEmpty()) {
                        continue;
                    }
                    cookieManager.setCookie(probe, name + expire);
                    for (String domain : domains) {
                        cookieManager.setCookie(probe, name + expire + "; domain=." + domain);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private void updateTabStyles() {
        for (int i = 0; i < tabs.length; i++) {
            boolean chosen = i == current;
            tabs[i].setTextColor(chosen ? 0xFF141413 : 0xFF87867F);
            tabs[i].setTypeface(tabs[i].getTypeface(),
                    chosen ? Typeface.BOLD : Typeface.NORMAL);
            tabs[i].getPaint().setTextSkewX(0f);
            if (chosen) {
                GradientDrawable pill = new GradientDrawable();
                pill.setColor(0x1FC96442);
                pill.setCornerRadius(9.0f * density);
                tabs[i].setBackground(pill);
            } else {
                tabs[i].setBackground(null);
            }
        }
    }

    private WebView createWebView(int index) {
        WebView web = new WebView(context);
        web.setBackgroundColor(Color.WHITE);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        if (Build.VERSION.SDK_INT >= 21) {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        }

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= 21) {
            cookieManager.setAcceptThirdPartyCookies(web, true);
        }

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                if (index != current) {
                    return;
                }
                if (newProgress >= 100) {
                    progress.setVisibility(View.GONE);
                } else {
                    progress.setVisibility(View.VISIBLE);
                    progress.setProgress(newProgress);
                }
            }
        });

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleUrl(view, request.getUrl().toString(), request.isForMainFrame());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleUrl(view, url, true);
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (index == IDX_PDD && isPddAppBundle(url)) {
                    return pddBundleResponse(url);
                }
                return null;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                // 及时把官方页面写入的登录 Cookie 落盘，避免退出弹窗或进程被回收后登录态丢失
                try {
                    CookieManager.getInstance().flush();
                } catch (Throwable ignored) {
                }
                if (index == current) {
                    updateSessionAction();
                    // 登录完成后的 Cookie 可能晚于 onPageFinished 写入，再补一次判定
                    view.postDelayed(() -> {
                        if (index == current && dialog.isShowing()) {
                            updateSessionAction();
                        }
                    }, 1200L);
                }
            }
        });
        return web;
    }

    private boolean handleUrl(WebView view, String url, boolean mainFrame) {
        if (url == null) {
            return true;
        }
        String lower = url.toLowerCase(Locale.ROOT);
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            // 子框架（登录、验证码组件）不拦，只约束主框架，避免破坏官方登录流程
            if (!mainFrame || isAllowedHost(url)) {
                return false;
            }
            openExternal(url);
            return true;
        }
        // pinduoduo://、taobao://、openApp.jdMobile:// 等官方 App 深链交给系统处理
        if (lower.startsWith("pinduoduo://") || lower.startsWith("taobao://")
                || lower.startsWith("tbopen://") || lower.startsWith("alipay")
                || lower.startsWith("alipays://") || lower.startsWith("openapp.jdmobile://")) {
            openExternal(url);
            return true;
        }
        return true;
    }

    private static boolean isAllowedHost(String raw) {
        try {
            Uri uri = Uri.parse(raw);
            if (!"https".equalsIgnoreCase(uri.getScheme())) {
                return false;
            }
            String host = uri.getHost();
            if (host == null) {
                return false;
            }
            host = host.toLowerCase(Locale.ROOT);
            for (String root : ALLOWED_HOSTS) {
                if (host.equals(root) || host.endsWith("." + root)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private void openExternal(String url) {
        try {
            Uri uri = Uri.parse(url);
            Intent intent = new Intent(Intent.ACTION_VIEW, uri);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if ("pinduoduo".equals(scheme)) {
                intent.setPackage("com.xunmeng.pinduoduo");
            } else if ("taobao".equals(scheme) || "tbopen".equals(scheme)) {
                intent.setPackage("com.taobao.taobao");
            }
            context.startActivity(intent);
        } catch (Throwable ignored) {
            // 没有对应 App 或浏览器时保持原页面不动
        }
    }

    private WebView currentPage() {
        return current >= 0 && current < pages.length ? pages[current] : null;
    }

    // ===== 拼多多：官方应用脚本前注入同源映射 =====

    private static boolean isPddAppBundle(String url) {
        try {
            Uri uri = Uri.parse(url);
            String host = uri.getHost();
            String path = uri.getPath();
            return "pfile.pddpic.com".equalsIgnoreCase(host) && path != null
                    && path.matches("/mdkd/mdkd/_next/static/chunks/pages/_app-[0-9a-f]+\\.js");
        } catch (Throwable ignored) {
            return false;
        }
    }

    private WebResourceResponse pddBundleResponse(String url) {
        if (sameOriginScript.isEmpty()) {
            return null;
        }
        if (url.equals(pddBundleUrl) && pddBundleData != null) {
            return javaScriptResponse(pddBundleData);
        }
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("Accept-Encoding", "identity");
            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) {
                return null;
            }
            InputStream in = conn.getInputStream();
            String encoding = conn.getContentEncoding();
            boolean gzip = encoding != null && encoding.toLowerCase(Locale.ROOT).contains("gzip");
            byte[] body = readAll(gzip ? new GZIPInputStream(in) : in);
            in.close();

            byte[] head = (sameOriginScript + "\n").getBytes(StandardCharsets.UTF_8);
            byte[] merged = new byte[head.length + body.length];
            System.arraycopy(head, 0, merged, 0, head.length);
            System.arraycopy(body, 0, merged, head.length, body.length);

            pddBundleUrl = url;
            pddBundleData = merged;
            return javaScriptResponse(merged);
        } catch (Throwable ignored) {
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static WebResourceResponse javaScriptResponse(byte[] data) {
        return new WebResourceResponse("application/javascript", "utf-8",
                new ByteArrayInputStream(data));
    }

    private void release() {
        for (int i = 0; i < pages.length; i++) {
            WebView web = pages[i];
            pages[i] = null;
            if (web != null) {
                content.removeView(web);
                web.stopLoading();
                web.destroy();
            }
        }
        try {
            CookieManager.getInstance().flush();
        } catch (Throwable ignored) {
        }
    }

    private int dp(int value) {
        return (int) (value * density + 0.5f);
    }

    private static String readAsset(Context context, String path) {
        try (InputStream in = context.getAssets().open(path)) {
            byte[] data = readAll(in);
            return new String(data, StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static byte[] readAll(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) > 0) {
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }
}
