package com.wuyi.kebiao;

/**
 * 本文件职责：
 * 「学习通签到」独立弹窗。内部用 WebView 承载签到页面（账号、签到、设置），
 * 通过 JS 桥完成登录、活动刷新、一键签到、扫码/相册识别、地图选点、拍照图片选择、
 * 自动巡检开关与日志查看。账号、Cookie、默认位置与图片全部持久化在本机。
 */

import android.Manifest;
import android.app.Activity;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class XxtSignDialog extends Dialog {

    public interface Host {
        void xxtStartForResult(Intent intent, int requestCode);

        void xxtRequestNotificationPermission(int requestCode);

        void xxtRequestCameraPermission(int requestCode);

        void xxtRequestLocationPermission(int requestCode);
    }

    public static final int RC_QR_GALLERY = 7411;
    public static final int RC_QR_CAMERA = 7412;
    public static final int RC_PHOTO = 7413;
    public static final int RC_NOTIFICATION = 7414;
    public static final int RC_QR_SCAN = 7415;
    public static final int RC_LOCATION = 7416;
    public static final int RC_PHOTO_CAMERA = 7417;

    private final Activity activity;
    private final Host host;
    private final Map<String, XxtSignEngine.Activity> activityIndex = new HashMap<>();

    private WebView webView;
    private File cameraFile;
    private File photoCameraFile;
    private XxtQrScannerDialog scanner;
    private volatile boolean refreshing = false;
    /** 人脸签到：用户已跳去官方 App，回来后自动重试 */
    private volatile boolean awaitingFace = false;
    private volatile String faceAccountId = "";
    private volatile String faceAid = "";

    public XxtSignDialog(Activity activity, Host host) {
        super(activity);
        this.activity = activity;
        this.host = host;
        setup();
    }

    private void setup() {
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        Window window = getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }
        FrameLayout root = new FrameLayout(activity);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#F5F4ED"));
        bg.setCornerRadius(dp(18));
        root.setBackground(bg);

        webView = new WebView(activity);
        webView.setBackgroundColor(Color.parseColor("#F5F4ED"));
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setTextZoom(100);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }
        webView.setWebViewClient(new WebViewClient());
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        webView.setVerticalScrollBarEnabled(false);
        webView.setHorizontalScrollBarEnabled(false);
        webView.addJavascriptInterface(new Bridge(), "MimiXxt");
        webView.loadUrl("file:///android_asset/www/xxt_sign.html");

        root.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    @Override
    public void show() {
        super.show();
        Window window = getWindow();
        if (window == null) {
            return;
        }
        DisplayMetrics dm = activity.getResources().getDisplayMetrics();
        int width = (int) (dm.widthPixels * 0.96f);
        int height = (int) (dm.heightPixels * 0.9f);
        window.setLayout(width, height);
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
    }

    private float dp(float value) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                activity.getResources().getDisplayMetrics());
    }

    // ------------------------------------------------------------ 结果回调
    public void handleActivityResult(int requestCode, int resultCode, Intent data) {
        if (resultCode != Activity.RESULT_OK) {
            return;
        }
        if (requestCode == RC_QR_GALLERY) {
            if (data == null || data.getData() == null) {
                return;
            }
            final Uri uri = data.getData();
            decodeQrAsync(new Runnable() {
                @Override
                public void run() {
                    String raw = XxtQrUtil.decodeUri(activity, uri);
                    emitQr(raw);
                }
            });
        } else if (requestCode == RC_QR_CAMERA) {
            if (cameraFile == null || !cameraFile.exists()) {
                return;
            }
            final String path = cameraFile.getAbsolutePath();
            decodeQrAsync(new Runnable() {
                @Override
                public void run() {
                    String raw = XxtQrUtil.decodeFile(path);
                    emitQr(raw);
                }
            });
        } else if (requestCode == RC_PHOTO) {
            if (data == null || data.getData() == null) {
                return;
            }
            final Uri uri = data.getData();
            new Thread(new Runnable() {
                @Override
                public void run() {
                    String path = copyPhoto(uri);
                    if (path.isEmpty()) {
                        emitToast("图片保存失败");
                        return;
                    }
                    XxtStore.setPhotoPath(activity, path);
                    JSONObject d = new JSONObject();
                    try {
                        d.put("path", path);
                        d.put("name", new File(path).getName());
                    } catch (Exception ignored) {
                    }
                    emit("photo", d);
                }
            }).start();
        } else if (requestCode == RC_PHOTO_CAMERA) {
            if (photoCameraFile == null || !photoCameraFile.exists() || photoCameraFile.length() == 0) {
                emitToast("没有拍到照片，请重试");
                return;
            }
            final File shot = photoCameraFile;
            new Thread(new Runnable() {
                @Override
                public void run() {
                    // 相机原图保留 EXIF，学习通的照片校验更容易通过
                    String path = keepCameraPhoto(shot);
                    if (path.isEmpty()) {
                        emitToast("图片保存失败");
                        return;
                    }
                    XxtStore.setPhotoPath(activity, path);
                    JSONObject d = new JSONObject();
                    try {
                        d.put("path", path);
                        d.put("name", new File(path).getName());
                    } catch (Exception ignored) {
                    }
                    emit("photo", d);
                }
            }).start();
        }
    }

    private void decodeQrAsync(final Runnable decoder) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                decoder.run();
            }
        }).start();
    }

    private void emitQr(String raw) {
        JSONObject d = new JSONObject();
        try {
            d.put("raw", raw == null ? "" : raw);
        } catch (Exception ignored) {
        }
        emit("qr", d);
    }

    private String copyPhoto(Uri uri) {
        try {
            File dir = new File(activity.getFilesDir(), "xxt");
            if (!dir.exists() && !dir.mkdirs()) {
                return "";
            }
            File out = new File(dir, "sign_photo.jpg");
            // 相册原图往往好几 MB，甚至是不支持的 HEIC；先缩放压缩成 JPEG，上传更稳
            byte[] bytes = readScaledJpeg(uri);
            if (bytes != null && bytes.length > 0) {
                FileOutputStream fos = new FileOutputStream(out);
                fos.write(bytes);
                fos.close();
                return out.getAbsolutePath();
            }
            InputStream in = activity.getContentResolver().openInputStream(uri);
            if (in == null) {
                return "";
            }
            FileOutputStream raw = new FileOutputStream(out);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                raw.write(buf, 0, n);
            }
            raw.close();
            in.close();
            return out.getAbsolutePath();
        } catch (Exception e) {
            return "";
        }
    }

    /** 读取相册图片并压成最长边 1280 的 JPEG，失败返回 null 由调用方回退到原始拷贝 */
    private byte[] readScaledJpeg(Uri uri) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            InputStream probe = activity.getContentResolver().openInputStream(uri);
            if (probe == null) {
                return null;
            }
            BitmapFactory.decodeStream(probe, null, bounds);
            probe.close();
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return null;
            }
            int sample = 1;
            while (bounds.outWidth / (sample * 2) >= 1280 || bounds.outHeight / (sample * 2) >= 1280) {
                sample *= 2;
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            InputStream in = activity.getContentResolver().openInputStream(uri);
            if (in == null) {
                return null;
            }
            Bitmap bitmap = BitmapFactory.decodeStream(in, null, opts);
            in.close();
            if (bitmap == null) {
                return null;
            }
            int max = Math.max(bitmap.getWidth(), bitmap.getHeight());
            if (max > 1280) {
                float scale = 1280f / max;
                Bitmap scaled = Bitmap.createScaledBitmap(bitmap,
                        Math.max(1, (int) (bitmap.getWidth() * scale)),
                        Math.max(1, (int) (bitmap.getHeight() * scale)), true);
                if (scaled != bitmap) {
                    bitmap.recycle();
                    bitmap = scaled;
                }
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, bos);
            bitmap.recycle();
            return bos.toByteArray();
        } catch (Throwable th) {
            return null;
        }
    }

    private void openScanner() {
        if (scanner != null && scanner.isShowing()) {
            return;
        }
        scanner = new XxtQrScannerDialog(activity, new XxtQrScannerDialog.Listener() {
            @Override
            public void onQr(String raw) {
                emitQr(raw);
            }
        });
        scanner.show();
    }

    /** 相机权限回调，由宿主 Activity/Fragment 转发 */
    public void handleCameraPermission(boolean granted) {
        if (granted) {
            openScanner();
        } else {
            toast("未获得相机权限，请从相册选择二维码");
        }
    }

    /** 定位权限回调 */
    public void handleLocationPermission(boolean granted) {
        toast(granted ? "定位权限已开启，再点一次「手机定位」" : "未获得定位权限");
    }

    /** 拍照签到的相机权限回调 */
    public void handlePhotoCameraPermission(boolean granted) {
        if (granted) {
            openPhotoCamera();
        } else {
            toast("未获得相机权限，请改用选图签到");
        }
    }

    private void openPhotoCamera() {
        try {
            File dir = new File(activity.getCacheDir(), "xxt");
            if (!dir.exists() && !dir.mkdirs()) {
                toast("无法创建图片目录");
                return;
            }
            photoCameraFile = new File(dir, "sign_camera.jpg");
            if (photoCameraFile.exists()) {
                photoCameraFile.delete();
            }
            Uri uri = FileProvider.getUriForFile(activity, "com.mimi.kebiao.fileprovider", photoCameraFile);
            Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            intent.putExtra(MediaStore.EXTRA_OUTPUT, uri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            host.xxtStartForResult(intent, RC_PHOTO_CAMERA);
        } catch (Throwable th) {
            toast("打不开相机：" + th.getMessage());
        }
    }

    /** 相机原图直接保存（不重新压缩，保留 EXIF 信息） */
    private String keepCameraPhoto(File shot) {
        try {
            File dir = new File(activity.getFilesDir(), "xxt");
            if (!dir.exists() && !dir.mkdirs()) {
                return "";
            }
            File out = new File(dir, "sign_photo.jpg");
            InputStream in = new java.io.FileInputStream(shot);
            FileOutputStream fos = new FileOutputStream(out);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                fos.write(buf, 0, n);
            }
            fos.close();
            in.close();
            shot.delete();
            return out.getAbsolutePath();
        } catch (Exception e) {
            return "";
        }
    }

    private void emit(final String type, final JSONObject data) {
        final JSONObject event = new JSONObject();
        try {
            event.put("type", type);
            event.put("data", data == null ? new JSONObject() : data);
        } catch (Exception ignored) {
        }
        final String js = "window.MimiXxtEvent && window.MimiXxtEvent(" + event + ");";
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (webView != null) {
                    try {
                        webView.evaluateJavascript(js, null);
                    } catch (Exception ignored) {
                    }
                }
            }
        });
    }

    private void emitToast(final String text) {
        JSONObject d = new JSONObject();
        try {
            d.put("text", text == null ? "" : text);
        } catch (Exception ignored) {
        }
        emit("toast", d);
    }

    private void toast(final String text) {
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(activity, text, Toast.LENGTH_SHORT).show();
            }
        });
    }

    /**
     * 从学习通官方 App 回来后由宿主调用：之前若卡在人脸识别，这里自动重试一次签到。
     * 人脸完成后服务端不再拦截，就能继续走定位签到。
     */
    public void onHostResume() {
        if (!awaitingFace) {
            return;
        }
        awaitingFace = false;
        final String accountId = faceAccountId;
        final String aid = faceAid;
        new Thread(new Runnable() {
            @Override
            public void run() {
                JSONObject data = new JSONObject();
                try {
                    XxtStore.Account account = XxtStore.find(activity, accountId);
                    if (account == null) {
                        data.put("ok", false);
                        data.put("message", "账号不存在，无法继续签到");
                        emit("faceResume", data);
                        return;
                    }
                    XxtSession session = new XxtSession(account);
                    String err = session.ensureLogin();
                    XxtStore.saveAccount(activity, account);
                    if (!err.isEmpty()) {
                        data.put("ok", false);
                        data.put("message", "登录已过期，请重新登录：" + err);
                        emit("faceResume", data);
                        return;
                    }
                    XxtSignEngine.Activity act = activityIndex.get(aid);
                    if (act == null) {
                        act = new XxtSignEngine.Activity();
                        act.accountId = account.id;
                        act.accountName = account.display();
                        act.aid = aid;
                        act.detail = XxtApi.signDetail(session, aid);
                        act.courseId = XxtApi.strAny(act.detail, "courseId");
                        act.classId = XxtApi.strAny(act.detail, "classId");
                        activityIndex.put(aid, act);
                    }
                    XxtSignEngine.Options retryOpt = new XxtSignEngine.Options();
                    // 用户已在官方 App 扫过人脸，这里绕过活动的人脸标记直接提交；
                    // 若服务端仍强制校验，结果里会带回提示，用户再去官方 App 签完即可
                    retryOpt.ignoreFace = true;
                    XxtSignEngine.Result r = XxtSignEngine.sign(activity, session, act, retryOpt);
                    XxtStore.addLog(account.display() + " · " + act.title() + " → " + r.message);
                    data.put("ok", r.ok());
                    data.put("status", r.status);
                    data.put("message", (r.ok() ? "人脸完成，已继续签到：" : "人脸后重试：") + r.message);
                } catch (Exception e) {
                    try {
                        data.put("ok", false);
                        data.put("message", e.getMessage() == null ? "继续签到失败" : e.getMessage());
                    } catch (Exception ignored) {
                    }
                }
                emit("faceResume", data);
            }
        }, "xxt-face-resume").start();
    }

    // ---------------------------------------------------------------- JS 桥
    public final class Bridge {

        @JavascriptInterface
        public String bootstrap() {
            JSONObject out = new JSONObject();
            try {
                out.put("ok", true);
                out.put("accounts", accountsJson());
                out.put("settings", settingsJson());
                out.put("monitor", XxtMonitorService.isRunning());
                out.put("logs", logsJson());
            } catch (Exception ignored) {
            }
            return out.toString();
        }

        @JavascriptInterface
        public String refresh() {
            if (refreshing) {
                return "{\"ok\":false,\"message\":\"正在刷新，请稍候\"}";
            }
            refreshing = true;
            activityIndex.clear();
            JSONObject out = new JSONObject();
            JSONArray accounts = new JSONArray();
            try {
                List<XxtStore.Account> list = XxtStore.accounts(activity);
                for (XxtStore.Account account : list) {
                    JSONObject item = new JSONObject();
                    item.put("id", account.id);
                    item.put("name", account.display());
                    item.put("phone", account.phone);
                    XxtSession session = new XxtSession(account);
                    String err = session.ensureLogin();
                    XxtStore.saveAccount(activity, account);
                    if (!err.isEmpty()) {
                        item.put("error", err);
                        item.put("acts", new JSONArray());
                        accounts.put(item);
                        XxtStore.addLog("刷新失败 " + account.display() + "：" + err);
                        continue;
                    }
                    List<XxtSignEngine.Activity> found = XxtSignEngine.findActive(activity, account, session);
                    // 只保留课堂范围内的活动，范围外的课程不参与刷新与自动签到
                    List<XxtSignEngine.Activity> acts = new ArrayList<>();
                    int skipped = 0;
                    for (XxtSignEngine.Activity act : found) {
                        if (!XxtStore.inScope(activity, act.courseId)) {
                            skipped++;
                            continue;
                        }
                        XxtSignEngine.prepare(session, act);
                        activityIndex.put(act.aid, act);
                        acts.add(act);
                    }
                    item.put("error", "");
                    item.put("acts", XxtSignEngine.activitiesJson(activity, acts));
                    accounts.put(item);
                    XxtStore.addLog("刷新 " + account.display() + "：发现 " + acts.size()
                            + " 个进行中活动" + (skipped > 0 ? ("（已按课堂范围忽略 " + skipped + " 个）") : ""));
                }
                out.put("ok", true);
                out.put("accounts", accounts);
                out.put("logs", logsJson());
            } catch (Exception e) {
                try {
                    out.put("ok", false);
                    out.put("message", e.getMessage() == null ? "刷新失败" : e.getMessage());
                } catch (Exception ignored) {
                }
            } finally {
                refreshing = false;
            }
            return out.toString();
        }

        @JavascriptInterface
        public String login(String phone, String pwd) {
            phone = phone == null ? "" : phone.trim();
            pwd = pwd == null ? "" : pwd;
            if (phone.isEmpty() || pwd.isEmpty()) {
                return "{\"ok\":false,\"message\":\"请输入学习通手机号和密码\"}";
            }
            XxtStore.Account account = new XxtStore.Account();
            account.id = phone;
            account.phone = phone;
            account.password = pwd;
            account.schild = "mimi" + Long.toHexString(System.currentTimeMillis()).substring(4);
            XxtSession session = new XxtSession(account);
            String err;
            try {
                err = session.login();
            } catch (Exception e) {
                err = e.getMessage() == null ? "登录异常" : e.getMessage();
            }
            if (!err.isEmpty()) {
                JSONObject out = new JSONObject();
                try {
                    out.put("ok", false);
                    out.put("message", err);
                } catch (Exception ignored) {
                }
                XxtStore.addLog("登录失败 " + phone + "：" + err);
                return out.toString();
            }
            XxtStore.saveAccount(activity, account);
            XxtApi.clearCourseCache();
            XxtStore.addLog("登录成功 " + account.display());
            JSONObject out = new JSONObject();
            try {
                out.put("ok", true);
                out.put("message", "登录成功");
                out.put("accounts", accountsJson());
            } catch (Exception ignored) {
            }
            return out.toString();
        }

        @JavascriptInterface
        public String logout(String id) {
            XxtStore.removeAccount(activity, id);
            activityIndex.clear();
            JSONObject out = new JSONObject();
            try {
                out.put("ok", true);
                out.put("accounts", accountsJson());
            } catch (Exception ignored) {
            }
            return out.toString();
        }

        @JavascriptInterface
        public String signAll() {
            JSONArray results = new JSONArray();
            try {
                List<XxtStore.Account> list = XxtStore.accounts(activity);
                for (XxtStore.Account account : list) {
                    XxtSession session = new XxtSession(account);
                    String err = session.ensureLogin();
                    XxtStore.saveAccount(activity, account);
                    if (!err.isEmpty()) {
                        JSONObject r = new JSONObject();
                        r.put("accountName", account.display());
                        r.put("title", "登录");
                        r.put("message", err);
                        r.put("ok", false);
                        results.put(r);
                        continue;
                    }
                    List<XxtSignEngine.Activity> acts = XxtSignEngine.findActive(activity, account, session);
                    for (XxtSignEngine.Activity act : acts) {
                        activityIndex.put(act.aid, act);
                    }
                    for (XxtSignEngine.Result r : XxtSignEngine.signAll(activity, account, session, acts)) {
                        results.put(r.toJson());
                    }
                    int gap = XxtStore.accountGap(activity);
                    if (gap > 0) {
                        try {
                            Thread.sleep(gap * 1000L);
                        } catch (InterruptedException ignored) {
                        }
                    }
                }
            } catch (Exception e) {
                return "{\"ok\":false,\"message\":\"" + escape(e.getMessage() == null ? "一键签到失败" : e.getMessage()) + "\"}";
            }
            JSONObject out = new JSONObject();
            try {
                out.put("ok", true);
                out.put("results", results);
                out.put("logs", logsJson());
            } catch (Exception ignored) {
            }
            return out.toString();
        }

        @JavascriptInterface
        public String signOne(String accountId, String aid, String code) {
            return signInternal(accountId, aid, code, "");
        }

        @JavascriptInterface
        public String signGesture(String accountId, String aid, String gestureCode) {
            return signInternal(accountId, aid, "", gestureCode);
        }

        private String signInternal(String accountId, String aid, String code, String gestureCode) {
            try {
                XxtStore.Account account = XxtStore.find(activity, accountId);
                if (account == null) {
                    return "{\"ok\":false,\"message\":\"账号不存在\"}";
                }
                XxtSession session = new XxtSession(account);
                String err = session.ensureLogin();
                XxtStore.saveAccount(activity, account);
                if (!err.isEmpty()) {
                    return "{\"ok\":false,\"message\":\"" + escape(err) + "\"}";
                }
                XxtSignEngine.Activity act = activityIndex.get(aid);
                if (act == null) {
                    act = new XxtSignEngine.Activity();
                    act.accountId = account.id;
                    act.accountName = account.display();
                    act.aid = aid;
                    act.detail = XxtApi.signDetail(session, aid);
                    act.courseId = XxtApi.strAny(act.detail, "courseId");
                    act.classId = XxtApi.strAny(act.detail, "classId");
                    activityIndex.put(aid, act);
                }
                XxtSignEngine.Options opt = new XxtSignEngine.Options();
                opt.signCode = code == null ? "" : code.trim();
                opt.gestureCode = gestureCode == null ? "" : gestureCode.trim();
                XxtSignEngine.Result r = XxtSignEngine.sign(activity, session, act, opt);
                XxtStore.addLog(account.display() + " · " + act.title() + " → " + r.message);
                if ("NEED_FACE".equals(r.status)) {
                    awaitingFace = true;
                    faceAccountId = account.id;
                    faceAid = aid;
                }
                JSONObject out = new JSONObject();
                out.put("ok", r.ok());
                out.put("status", r.status);
                out.put("message", r.message);
                out.put("logs", logsJson());
                return out.toString();
            } catch (Exception e) {
                return "{\"ok\":false,\"message\":\"" + escape(e.getMessage() == null ? "签到失败" : e.getMessage()) + "\"}";
            }
        }

        @JavascriptInterface
        public String markFacePending(String accountId, String aid) {
            awaitingFace = accountId != null && !accountId.isEmpty()
                    && aid != null && !aid.isEmpty();
            faceAccountId = accountId == null ? "" : accountId;
            faceAid = aid == null ? "" : aid;
            JSONObject out = new JSONObject();
            try {
                out.put("ok", true);
            } catch (Exception ignored) {
            }
            return out.toString();
        }

        @JavascriptInterface
        public String scanQr(String raw) {
            QrPayload payload = QrPayload.parse(raw);
            if (!payload.usable()) {
                return "{\"ok\":false,\"message\":\"没有识别到有效的签到二维码\"}";
            }
            JSONArray results = new JSONArray();
            try {
                List<XxtStore.Account> list = XxtStore.accounts(activity);
                if (list.isEmpty()) {
                    return "{\"ok\":false,\"message\":\"请先登录学习通账号\"}";
                }
                for (XxtStore.Account account : list) {
                    XxtSession session = new XxtSession(account);
                    String err = session.ensureLogin();
                    XxtStore.saveAccount(activity, account);
                    if (!err.isEmpty()) {
                        JSONObject r = new JSONObject();
                        r.put("accountName", account.display());
                        r.put("title", "二维码签到");
                        r.put("message", err);
                        r.put("ok", false);
                        results.put(r);
                        continue;
                    }
                    String aid = payload.aid;
                    if (aid.isEmpty()) {
                        aid = findQrActivity(session);
                    }
                    if (aid.isEmpty()) {
                        JSONObject r = new JSONObject();
                        r.put("accountName", account.display());
                        r.put("title", "二维码签到");
                        r.put("message", "当前没有进行中的二维码签到");
                        r.put("ok", false);
                        results.put(r);
                        continue;
                    }
                    XxtSignEngine.Activity act = activityIndex.get(aid);
                    if (act == null) {
                        act = new XxtSignEngine.Activity();
                        act.accountId = account.id;
                        act.accountName = account.display();
                        act.aid = aid;
                        act.otherId = XxtApi.TYPE_QRCODE;
                        act.detail = XxtApi.signDetail(session, aid);
                        act.courseId = XxtApi.strAny(act.detail, "courseId");
                        act.classId = XxtApi.strAny(act.detail, "classId");
                        activityIndex.put(aid, act);
                    }
                    XxtSignEngine.Options opt = new XxtSignEngine.Options();
                    opt.enc = payload.enc;
                    opt.rcode = payload.raw;
                    opt.appType = XxtApi.APP_TYPE_SCAN;
                    if (opt.enc.isEmpty()) {
                        opt.enc = XxtApi.refreshQr(session, aid);
                    }
                    XxtSignEngine.Result r = XxtSignEngine.sign(activity, session, act, opt);
                    results.put(r.toJson());
                    XxtStore.addLog("[扫码] " + account.display() + " → " + r.message);
                }
            } catch (Exception e) {
                return "{\"ok\":false,\"message\":\"" + escape(e.getMessage() == null ? "扫码签到失败" : e.getMessage()) + "\"}";
            }
            JSONObject out = new JSONObject();
            try {
                out.put("ok", true);
                out.put("results", results);
                out.put("logs", logsJson());
            } catch (Exception ignored) {
            }
            return out.toString();
        }

        private String findQrActivity(XxtSession session) {
            for (XxtSignEngine.Activity act : activityIndex.values()) {
                if (act.otherId == XxtApi.TYPE_QRCODE) {
                    return act.aid;
                }
            }
            return "";
        }

        @JavascriptInterface
        public void pickQrGallery() {
            final Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("image/*");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            activity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    host.xxtStartForResult(Intent.createChooser(intent, "选择二维码图片"), RC_QR_GALLERY);
                }
            });
        }

        @JavascriptInterface
        public String saveAccountGap(int seconds) {
            XxtStore.setAccountGap(activity, seconds);
            JSONObject out = new JSONObject();
            try {
                out.put("ok", true);
                out.put("settings", settingsJson());
            } catch (Exception ignored) {
            }
            return out.toString();
        }

        @JavascriptInterface
        public String phoneLocation() {
            JSONObject out = new JSONObject();
            try {
                if (ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_FINE_LOCATION)
                        != PackageManager.PERMISSION_GRANTED) {
                    host.xxtRequestLocationPermission(RC_LOCATION);
                    out.put("ok", false);
                    out.put("message", "已申请定位权限，允许后再点一次");
                    return out.toString();
                }
                android.location.LocationManager lm = (android.location.LocationManager)
                        activity.getSystemService(Context.LOCATION_SERVICE);
                android.location.Location best = null;
                String[] providers = new String[]{
                        android.location.LocationManager.GPS_PROVIDER,
                        android.location.LocationManager.NETWORK_PROVIDER};
                for (String provider : providers) {
                    try {
                        android.location.Location l = lm.getLastKnownLocation(provider);
                        if (l != null && (best == null || l.getTime() > best.getTime())) {
                            best = l;
                        }
                    } catch (SecurityException ignored) {
                    }
                }
                if (best == null) {
                    out.put("ok", false);
                    out.put("message", "还没取到手机定位，请到空旷处重试");
                    return out.toString();
                }
                String lat = String.format(Locale.US, "%.6f", best.getLatitude());
                String lon = String.format(Locale.US, "%.6f", best.getLongitude());
                XxtStore.saveLocation(activity, lat, lon, "手机定位");
                out.put("ok", true);
                out.put("lat", lat);
                out.put("lon", lon);
                out.put("settings", settingsJson());
            } catch (Exception e) {
                try {
                    out.put("ok", false);
                    out.put("message", "手机定位失败");
                } catch (Exception ignored) {
                }
            }
            return out.toString();
        }

        @JavascriptInterface
        public String openXxtApp() {
            PackageManager pm = activity.getPackageManager();
            String[] packages = new String[]{"com.chaoxing.mobile", "com.chaoxing.phone"};
            for (String pkg : packages) {
                Intent intent = pm.getLaunchIntentForPackage(pkg);
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    try {
                        activity.startActivity(intent);
                        return "{\"ok\":true}";
                    } catch (Exception ignored) {
                    }
                }
            }
            try {
                Intent view = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("chaoxing://"));
                view.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                activity.startActivity(view);
                return "{\"ok\":true}";
            } catch (Exception ignored) {
            }
            return "{\"ok\":false,\"message\":\"未检测到学习通 App，请先安装\"}";
        }

        @JavascriptInterface
        public void pickQrCamera() {
            activity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA)
                            == PackageManager.PERMISSION_GRANTED) {
                        openScanner();
                    } else {
                        host.xxtRequestCameraPermission(RC_QR_SCAN);
                    }
                }
            });
        }

        @JavascriptInterface
        public void pickPhoto() {
            final Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("image/*");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            activity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    host.xxtStartForResult(Intent.createChooser(intent, "选择拍照签到图片"), RC_PHOTO);
                }
            });
        }

        @JavascriptInterface
        public void pickPhotoCamera() {
            activity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA)
                            == PackageManager.PERMISSION_GRANTED) {
                        openPhotoCamera();
                    } else {
                        host.xxtRequestCameraPermission(RC_PHOTO_CAMERA);
                    }
                }
            });
        }

        @JavascriptInterface
        public void pickLocation(final String lat, final String lon) {
            activity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    double la = parse(lat, 27.72698);
                    double ln = parse(lon, 118.00326);
                    new XxtMapPickerDialog(activity, la, ln, XxtStore.address(activity),
                            new XxtMapPickerDialog.OnPickListener() {
                                @Override
                                public void onPick(String useLat, String useLon, String address) {
                                    XxtStore.saveLocation(activity, useLat, useLon, address);
                                    JSONObject d = new JSONObject();
                                    try {
                                        d.put("lat", useLat);
                                        d.put("lon", useLon);
                                        d.put("address", address);
                                    } catch (Exception ignored) {
                                    }
                                    emit("location", d);
                                }
                            }).show();
                }
            });
        }

        @JavascriptInterface
        public String saveScope(String ids) {
            XxtStore.setExcludeIds(activity, ids);
            JSONObject out = new JSONObject();
            try {
                out.put("ok", true);
                out.put("settings", settingsJson());
            } catch (Exception ignored) {
            }
            return out.toString();
        }

        @JavascriptInterface
        public String ocsSave(String config) {
            String raw = config == null ? "" : config.trim();
            JSONObject out = new JSONObject();
            try {
                List<OcsBank.Bank> banks = OcsBank.parse(raw);
                XxtStore.setOcsConfig(activity, raw);
                JSONArray names = new JSONArray();
                for (OcsBank.Bank bank : banks) {
                    names.put(bank.name);
                }
                out.put("ok", true);
                out.put("banks", names);
                out.put("message", banks.isEmpty() ? "已清空 OCS 题库配置"
                        : "已保存 " + banks.size() + " 个题库");
                out.put("settings", settingsJson());
                XxtStore.addLog(banks.isEmpty() ? "已清空 OCS 题库配置"
                        : "已保存 OCS 题库：共 " + banks.size() + " 个");
            } catch (Exception e) {
                try {
                    out.put("ok", false);
                    out.put("message", "配置无效：" + (e.getMessage() == null
                            ? "请检查 JSON 格式" : e.getMessage()));
                } catch (Exception ignored) {
                }
            }
            return out.toString();
        }

        @JavascriptInterface
        public String ocsTest(String title, String options) {
            JSONObject out = new JSONObject();
            String question = title == null ? "" : title.trim();
            if (question.isEmpty()) {
                try {
                    out.put("ok", false);
                    out.put("message", "请先输入题目内容");
                } catch (Exception ignored) {
                }
                return out.toString();
            }
            List<OcsBank.Bank> banks;
            try {
                banks = OcsBank.parse(XxtStore.ocsConfig(activity));
            } catch (Exception e) {
                try {
                    out.put("ok", false);
                    out.put("message", "OCS 配置无效：" + (e.getMessage() == null
                            ? "请重新保存配置" : e.getMessage()));
                } catch (Exception ignored) {
                }
                return out.toString();
            }
            if (banks.isEmpty()) {
                try {
                    out.put("ok", false);
                    out.put("message", "请先在设置里粘贴并保存 OCS 题库配置");
                } catch (Exception ignored) {
                }
                return out.toString();
            }
            List<OcsBank.Result> results = OcsBank.search(banks,
                    question, normalizeOptions(options), "0");
            JSONArray arr = new JSONArray();
            String firstAnswer = "";
            for (OcsBank.Result r : results) {
                arr.put(r.toJson());
                if (firstAnswer.isEmpty() && r.ok()) {
                    firstAnswer = r.answer;
                }
            }
            try {
                out.put("ok", true);
                out.put("results", arr);
                out.put("message", firstAnswer.isEmpty() ? "没有查到答案"
                        : "查题成功：" + firstAnswer);
            } catch (Exception ignored) {
            }
            return out.toString();
        }

        private String normalizeOptions(String options) {
            if (options == null) {
                return "";
            }
            String[] lines = options.split("\\r?\\n|\\|");
            StringBuilder sb = new StringBuilder();
            int index = 0;
            for (String line : lines) {
                String v = line.trim();
                if (v.isEmpty()) {
                    continue;
                }
                if (index > 0) {
                    sb.append('\n');
                }
                // 用户可能只粘贴了选项文本，这里补上 A. B. C. 前缀
                if (!v.matches("^[A-Fa-f][.、:：)）].*")) {
                    sb.append((char) ('A' + index)).append(". ");
                }
                sb.append(v);
                index++;
            }
            return sb.toString();
        }

        @JavascriptInterface
        public String saveSettings(boolean auto, int interval) {
            XxtStore.setAutoEnabled(activity, auto);
            XxtStore.setIntervalSeconds(activity, interval);
            if (auto) {
                activity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        host.xxtRequestNotificationPermission(RC_NOTIFICATION);
                    }
                });
                try {
                    XxtMonitorService.start(activity);
                } catch (Exception e) {
                    return "{\"ok\":false,\"message\":\"" + escape("启动巡检失败：" + e.getMessage()) + "\"}";
                }
                XxtStore.addLog("已开启自动巡检，间隔 " + XxtStore.intervalSeconds(activity) + " 秒");
            } else {
                XxtMonitorService.stop(activity);
                XxtStore.addLog("已关闭自动巡检");
            }
            JSONObject out = new JSONObject();
            try {
                out.put("ok", true);
                out.put("monitor", auto);
            } catch (Exception ignored) {
            }
            return out.toString();
        }

        @JavascriptInterface
        public String clearHandled() {
            XxtStore.clearHandled(activity);
            return "{\"ok\":true}";
        }

        @JavascriptInterface
        public String logs() {
            JSONObject out = new JSONObject();
            try {
                out.put("ok", true);
                out.put("logs", logsJson());
            } catch (Exception ignored) {
            }
            return out.toString();
        }

        @JavascriptInterface
        public String clearLogs() {
            XxtStore.clearLogs();
            return logs();
        }

        // ------------------------------------------------------------ 刷课
        @JavascriptInterface
        public String studyCourses(String accountId) {
            JSONObject out = new JSONObject();
            try {
                XxtStore.Account account = XxtStore.find(activity, accountId);
                if (account == null) {
                    out.put("ok", false);
                    out.put("message", "账号不存在");
                    return out.toString();
                }
                XxtSession session = new XxtSession(account);
                String err = session.ensureLogin();
                XxtStore.saveAccount(activity, account);
                if (!err.isEmpty()) {
                    out.put("ok", false);
                    out.put("message", err);
                    return out.toString();
                }
                List<XxtStudyApi.Course> courses = new XxtStudyApi(session).courses();
                JSONArray arr = new JSONArray();
                for (XxtStudyApi.Course c : courses) {
                    JSONObject o = new JSONObject();
                    o.put("courseId", c.courseId);
                    o.put("clazzId", c.clazzId);
                    o.put("cpi", c.cpi);
                    o.put("title", c.title);
                    o.put("teacher", c.teacher);
                    arr.put(o);
                }
                out.put("ok", true);
                out.put("courses", arr);
                out.put("message", courses.isEmpty() ? "没有读到课程，请先刷新课程" : "");
            } catch (Exception e) {
                try {
                    out.put("ok", false);
                    out.put("message", e.getMessage() == null ? "读取课程失败" : e.getMessage());
                } catch (Exception ignored) {
                }
            }
            return out.toString();
        }

        @JavascriptInterface
        public String studyChapters(String accountId, String courseId,
                                    String clazzId, String cpi) {
            JSONObject out = new JSONObject();
            try {
                XxtStore.Account account = XxtStore.find(activity, accountId);
                if (account == null) {
                    out.put("ok", false);
                    out.put("message", "账号不存在");
                    return out.toString();
                }
                XxtSession session = new XxtSession(account);
                String err = session.ensureLogin();
                XxtStore.saveAccount(activity, account);
                if (!err.isEmpty()) {
                    out.put("ok", false);
                    out.put("message", err);
                    return out.toString();
                }
                XxtStudyApi.Course course = new XxtStudyApi.Course();
                course.courseId = courseId == null ? "" : courseId;
                course.clazzId = clazzId == null ? "" : clazzId;
                course.cpi = cpi == null ? "" : cpi;
                List<XxtStudyApi.Chapter> chapters = new XxtStudyApi(session).chapters(course);
                JSONArray arr = new JSONArray();
                for (XxtStudyApi.Chapter c : chapters) {
                    JSONObject o = new JSONObject();
                    o.put("id", c.id);
                    o.put("title", c.title);
                    o.put("jobCount", c.jobCount);
                    o.put("finished", c.finished);
                    arr.put(o);
                }
                out.put("ok", true);
                out.put("chapters", arr);
                out.put("cpi", course.cpi);
                out.put("message", chapters.isEmpty() ? "没有读到章节" : "");
            } catch (Exception e) {
                try {
                    out.put("ok", false);
                    out.put("message", e.getMessage() == null ? "读取章节失败" : e.getMessage());
                } catch (Exception ignored) {
                }
            }
            return out.toString();
        }

        /** 账号下的全部课程（用于「课堂范围」多选，不局限于有签到活动的课程） */
        @JavascriptInterface
        public String courses(String accountId) {
            JSONObject out = new JSONObject();
            try {
                XxtStore.Account account = XxtStore.find(activity, accountId);
                if (account == null) {
                    out.put("ok", false);
                    out.put("message", "账号不存在");
                    return out.toString();
                }
                XxtSession session = new XxtSession(account);
                String err = session.ensureLogin();
                XxtStore.saveAccount(activity, account);
                if (!err.isEmpty()) {
                    out.put("ok", false);
                    out.put("message", err);
                    return out.toString();
                }
                List<XxtApi.Course> list = XxtApi.courses(session);
                JSONArray arr = new JSONArray();
                for (XxtApi.Course c : list) {
                    JSONObject o = new JSONObject();
                    o.put("courseId", c.courseId);
                    o.put("courseName", c.name);
                    arr.put(o);
                }
                out.put("ok", true);
                out.put("courses", arr);
            } catch (Exception e) {
                try {
                    out.put("ok", false);
                    out.put("message", e.getMessage() == null ? "读取课程失败" : e.getMessage());
                } catch (Exception ignored) {
                }
            }
            return out.toString();
        }

        @JavascriptInterface
        public String studyVideos(String accountId, String courseId, String clazzId,
                                  String cpi, String chapterId) {
            JSONObject out = new JSONObject();
            try {
                XxtStore.Account account = XxtStore.find(activity, accountId);
                if (account == null) {
                    out.put("ok", false);
                    out.put("message", "账号不存在");
                    return out.toString();
                }
                XxtSession session = new XxtSession(account);
                String err = session.ensureLogin();
                XxtStore.saveAccount(activity, account);
                if (!err.isEmpty()) {
                    out.put("ok", false);
                    out.put("message", err);
                    return out.toString();
                }
                XxtStudyApi.Course course = new XxtStudyApi.Course();
                course.courseId = courseId == null ? "" : courseId;
                course.clazzId = clazzId == null ? "" : clazzId;
                course.cpi = cpi == null ? "" : cpi;
                XxtStudyApi.Chapter chapter = new XxtStudyApi.Chapter();
                chapter.id = chapterId == null ? "" : chapterId;
                List<XxtStudyApi.VideoJob> videos = new XxtStudyApi(session).videoJobs(course, chapter);
                JSONArray arr = new JSONArray();
                for (XxtStudyApi.VideoJob v : videos) {
                    JSONObject o = new JSONObject();
                    o.put("name", v.name.isEmpty() ? "视频" : v.name);
                    o.put("duration", v.attDuration);
                    o.put("passed", v.passed);
                    o.put("face", !v.faceEnc.isEmpty());
                    arr.put(o);
                }
                out.put("ok", true);
                out.put("videos", arr);
                out.put("cpi", course.cpi);
            } catch (Exception e) {
                try {
                    out.put("ok", false);
                    out.put("message", e.getMessage() == null ? "读取任务点失败" : e.getMessage());
                } catch (Exception ignored) {
                }
            }
            return out.toString();
        }

        @JavascriptInterface
        public String studyPermState() {
            JSONObject o = new JSONObject();
            try {
                JSONArray miss = XxtPermissionHelper.missing(activity);
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < miss.length(); i++) {
                    if (sb.length() > 0) {
                        sb.append("、");
                    }
                    sb.append(miss.optString(i));
                }
                o.put("ok", miss.length() == 0);
                o.put("missing", miss);
                o.put("text", sb.toString());
            } catch (Exception ignored) {
            }
            return o.toString();
        }

        @JavascriptInterface
        public void openPermissionSettings() {
            activity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    XxtPermissionHelper.showDialog(activity);
                }
            });
        }

        @JavascriptInterface
        public String studyStart(String planJson) {
            JSONObject out = new JSONObject();
            try {
                JSONObject plan = new JSONObject(planJson == null ? "{}" : planJson);
                XxtStore.setStudyAccount(activity, plan.optString("accountId", ""));
                XxtStore.setStudySpeed(activity, (float) plan.optDouble("speed", 2));
            } catch (Exception ignored) {
            }
            String err = XxtStudyEngine.start(activity, planJson);
            if (err.isEmpty()) {
                // 前台服务：退到后台/锁屏后仍继续刷课，进度通知可在设置里关闭
                XxtStudyService.start(activity);
                XxtStudyService.requestIgnoreBattery(activity);
            }
            try {
                out.put("ok", err.isEmpty());
                out.put("message", err);
                out.put("status", studyStatusJson().optJSONObject("status"));
            } catch (Exception ignored) {
            }
            return out.toString();
        }

        @JavascriptInterface
        public String studyStop() {
            if (XxtStudyEngine.isRunning()) {
                // 刷课进行中：让前台服务先发一条「已停止」再撤掉
                XxtStudyService.stop(activity);
            } else {
                // 已结束/未开始：只撤通知，避免凭空拉起服务再闪一条通知
                XxtStudyService.detach(activity);
            }
            XxtStudyEngine.stop();
            return studyStatus();
        }

        @JavascriptInterface
        public String studyStatus() {
            return studyStatusJson().toString();
        }

        @JavascriptInterface
        public String quizStart(String planJson) {
            JSONObject out = new JSONObject();
            try {
                JSONObject plan = new JSONObject(planJson == null ? "{}" : planJson);
                XxtStore.setStudyAccount(activity, plan.optString("accountId", ""));
            } catch (Exception ignored) {
            }
            String err = XxtQuizRunner.start(activity, planJson);
            if (err.isEmpty()) {
                // 刷题同样需要后台保活，复用刷课的前台服务展示刷题进度
                XxtStudyService.startQuiz(activity);
                XxtStudyService.requestIgnoreBattery(activity);
            }
            try {
                out.put("ok", err.isEmpty());
                out.put("message", err);
                out.put("status", quizStatusJson().optJSONObject("status"));
            } catch (Exception ignored) {
            }
            return out.toString();
        }

        @JavascriptInterface
        public String quizSaveSettings(int stay, int wait, boolean saveOnly) {
            XxtStore.setQuizStaySeconds(activity, stay);
            XxtStore.setQuizWaitSeconds(activity, wait);
            XxtStore.setQuizSaveOnly(activity, saveOnly);
            XxtStore.addLog("刷题设置：每题停留 " + XxtStore.quizStaySeconds(activity)
                    + " 秒，提交前等待 " + XxtStore.quizWaitSeconds(activity) + " 秒，"
                    + (saveOnly ? "只暂存不交卷" : "正常交卷"));
            JSONObject out = new JSONObject();
            try {
                out.put("ok", true);
                out.put("settings", settingsJson());
            } catch (Exception ignored) {
            }
            return out.toString();
        }

        @JavascriptInterface
        public String quizStop() {
            XxtQuizRunner.stop();
            if (!XxtQuizRunner.isRunning()) {
                XxtStudyService.detach(activity);
            }
            return quizStatus();
        }

        @JavascriptInterface
        public String quizStatus() {
            return quizStatusJson().toString();
        }

        @JavascriptInterface
        public String studyPickAccount(String id) {
            XxtStore.setStudyAccount(activity, id == null ? "" : id);
            JSONObject out = new JSONObject();
            try {
                out.put("ok", true);
                out.put("accountId", XxtStore.studyAccount(activity));
            } catch (Exception ignored) {
            }
            return out.toString();
        }

        @JavascriptInterface
        public void copyLogs() {
            final String text = XxtStore.logText();
            activity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        ClipboardManager cm = (ClipboardManager) activity
                                .getSystemService(Context.CLIPBOARD_SERVICE);
                        if (cm != null) {
                            cm.setPrimaryClip(ClipData.newPlainText("学习通签到日志", text));
                            Toast.makeText(activity, "日志已复制", Toast.LENGTH_SHORT).show();
                        }
                    } catch (Exception ignored) {
                    }
                }
            });
        }

        @JavascriptInterface
        public void closeDialog() {
            activity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    dismiss();
                }
            });
        }
    }

    // ---------------------------------------------------------------- 数据
    private JSONArray accountsJson() {
        JSONArray arr = new JSONArray();
        for (XxtStore.Account a : XxtStore.accounts(activity)) {
            JSONObject o = new JSONObject();
            try {
                o.put("id", a.id);
                o.put("name", a.display());
                o.put("phone", a.phone);
                o.put("uid", a.uid);
                o.put("error", a.lastError);
                o.put("logged", a.cookies != null && !a.cookies.isEmpty());
                o.put("lastLoginAt", a.lastLoginAt);
            } catch (Exception ignored) {
            }
            arr.put(o);
        }
        return arr;
    }

    private JSONObject settingsJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("auto", XxtStore.autoEnabled(activity));
            o.put("interval", XxtStore.intervalSeconds(activity));
            o.put("lat", XxtStore.latitude(activity));
            o.put("lon", XxtStore.longitude(activity));
            o.put("address", XxtStore.address(activity));
            String photo = XxtStore.photoPath(activity);
            o.put("photo", photo);
            o.put("photoName", photo.isEmpty() ? "" : new File(photo).getName());
            o.put("studyAccount", XxtStore.studyAccount(activity));
            o.put("studySpeed", XxtStore.studySpeed(activity));
            o.put("scope", XxtStore.excludeIds(activity));
            o.put("accountGap", XxtStore.accountGap(activity));
            o.put("ocsConfig", XxtStore.ocsConfig(activity));
            o.put("ocsBanks", OcsBank.names(XxtStore.ocsConfig(activity)));
            o.put("quizStay", XxtStore.quizStaySeconds(activity));
            o.put("quizWait", XxtStore.quizWaitSeconds(activity));
            o.put("quizSaveOnly", XxtStore.quizSaveOnly(activity));
        } catch (Exception ignored) {
        }
        return o;
    }

    private JSONArray logsJson() {
        JSONArray arr = new JSONArray();
        for (String line : XxtStore.logs()) {
            arr.put(line);
        }
        return arr;
    }

    private JSONObject studyStatusJson() {
        JSONObject out = new JSONObject();
        try {
            XxtStudyEngine.Status s = XxtStudyEngine.status();
            JSONObject st = new JSONObject();
            st.put("running", s.running);
            st.put("finished", s.finished);
            st.put("text", s.text);
            st.put("course", s.course);
            st.put("chapter", s.chapter);
            st.put("video", s.video);
            st.put("cur", s.cur);
            st.put("dur", s.dur);
            st.put("percent", s.percent);
            st.put("speed", (double) s.speed);
            st.put("done", s.done);
            st.put("total", s.total);
            st.put("failed", s.failed);
            out.put("ok", true);
            out.put("status", st);
            out.put("logs", logsJson());
        } catch (Exception ignored) {
        }
        return out;
    }

    private JSONObject quizStatusJson() {
        JSONObject out = new JSONObject();
        try {
            XxtQuizRunner.Status s = XxtQuizRunner.status();
            JSONObject st = new JSONObject();
            st.put("running", s.running);
            st.put("finished", s.finished);
            st.put("text", s.text);
            st.put("course", s.course);
            st.put("chapter", s.chapter);
            st.put("paper", s.paper);
            st.put("total", s.total);
            st.put("answered", s.answered);
            st.put("submitted", s.submitted);
            st.put("saved", s.saved);
            st.put("failed", s.failed);
            st.put("skipped", s.skipped);
            out.put("ok", true);
            out.put("status", st);
            out.put("logs", logsJson());
        } catch (Exception ignored) {
        }
        return out;
    }

    private static double parse(String v, double fallback) {
        try {
            return Double.parseDouble(v);
        } catch (Exception e) {
            return fallback;
        }
    }

    private static String escape(String v) {
        if (v == null) {
            return "";
        }
        return v.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", " ").replace("\r", " ");
    }

    // ------------------------------------------------------------ 二维码解析
    private static final class QrPayload {
        final String raw;
        final String aid;
        final String enc;

        QrPayload(String raw, String aid, String enc) {
            this.raw = raw;
            this.aid = aid;
            this.enc = enc;
        }

        boolean usable() {
            return !aid.isEmpty() || !enc.isEmpty();
        }

        static QrPayload parse(String input) {
            String raw = input == null ? "" : input.trim();
            String aid = "";
            String enc = "";
            String lower = raw.toLowerCase(Locale.ROOT);
            Map<String, String> kv = new HashMap<>();
            if (lower.startsWith("signin:")) {
                kv = parseKv(raw.substring(7));
            } else if (raw.contains("=")) {
                int q = raw.indexOf('?');
                kv = parseKv(q >= 0 ? raw.substring(q + 1) : raw);
            }
            aid = first(kv, "aid", "activeid", "activeprimaryid", "id");
            enc = first(kv, "enc");
            String code = first(kv, "code");
            if (aid.isEmpty() && !code.isEmpty() && raw.contains("SIGNIN:")) {
                aid = first(kv, "aid");
            }
            if (enc.isEmpty() && !raw.contains("=") && !raw.contains(":") && raw.length() >= 8) {
                enc = raw;
            }
            return new QrPayload(raw, decode(aid), decode(enc));
        }

        private static Map<String, String> parseKv(String query) {
            Map<String, String> map = new HashMap<>();
            for (String kv : query.split("&")) {
                int i = kv.indexOf('=');
                if (i > 0) {
                    String k = kv.substring(0, i).trim().toLowerCase(Locale.ROOT);
                    String v = kv.substring(i + 1).trim();
                    if (!v.isEmpty()) {
                        map.put(k, v);
                    }
                }
            }
            return map;
        }

        private static String first(Map<String, String> map, String... keys) {
            for (String k : keys) {
                String v = map.get(k);
                if (v != null && !v.isEmpty()) {
                    return v;
                }
            }
            return "";
        }

        private static String decode(String v) {
            if (v == null || v.isEmpty()) {
                return "";
            }
            try {
                return v.contains("%") ? java.net.URLDecoder.decode(v, "UTF-8") : v;
            } catch (Exception e) {
                return v;
            }
        }
    }

    @Override
    public void dismiss() {
        try {
            if (webView != null) {
                webView.removeJavascriptInterface("MimiXxt");
                webView.loadUrl("about:blank");
                webView.destroy();
                webView = null;
            }
        } catch (Exception ignored) {
        }
        activityIndex.clear();
        super.dismiss();
    }
}
