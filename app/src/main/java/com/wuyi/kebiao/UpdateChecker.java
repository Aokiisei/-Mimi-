package com.wuyi.kebiao;

import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;
import androidx.core.content.FileProvider;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.json.JSONObject;

/* JADX INFO: loaded from: classes.dex */
public class UpdateChecker {

    public static class CheckCallback implements Callback {
        private final Context a;
        private final boolean b;

        public CheckCallback(Context context, boolean z) {
            this.a = context;
            this.b = z;
        }

        private void a(String str) {
            new Handler(Looper.getMainLooper()).post(new ToastRunnable(this.a, str));
        }

        @Override // okhttp3.Callback
        public void onFailure(Call call, IOException iOException) {
            if (this.b) {
                a("检查更新失败，请检查网络");
            }
        }

        @Override // okhttp3.Callback
        public void onResponse(Call call, Response response) {
            try {
                ResponseBody responseBodyBody = response.body();
                if (responseBodyBody == null) {
                    response.close();
                    if (this.b) {
                        a("检查更新失败，请稍后再试");
                        return;
                    }
                    return;
                }
                String strString = responseBodyBody.string();
                response.close();
                JSONObject jSONObject = new JSONObject(strString);
                int iOptInt = jSONObject.optInt("versionCode", 0);
                String strOptString = jSONObject.optString("versionName", HttpUrl.FRAGMENT_ENCODE_SET);
                String strOptString2 = jSONObject.optString("notes", HttpUrl.FRAGMENT_ENCODE_SET);
                String strOptString3 = jSONObject.optString("apkUrl", "https://aokiisei.vxni.ink/mimi/mimi-kebiao.apk");
                if (strOptString2 == null || strOptString2.length() == 0) {
                    strOptString2 = "暂无更新说明";
                }
                if (iOptInt > this.a.getPackageManager().getPackageInfo(this.a.getPackageName(), 0).versionCode) {
                    new Handler(Looper.getMainLooper()).post(new UpdateDialogRunnable(this.a, strOptString, strOptString2, strOptString3));
                } else if (this.b) {
                    a("当前已是最新版本");
                }
            } catch (Throwable unused) {
            }
        }
    }

    public static class DismissRunnable implements Runnable {
        private final ProgressDialog a;

        public DismissRunnable(ProgressDialog progressDialog) {
            this.a = progressDialog;
        }

        @Override // java.lang.Runnable
        public void run() {
            try {
                this.a.dismiss();
            } catch (Throwable unused) {
            }
        }
    }

    public static class DownloadCallback implements Callback {
        private final Context a;
        private final ProgressDialog b;

        public DownloadCallback(Context context, ProgressDialog progressDialog) {
            this.a = context;
            this.b = progressDialog;
        }

        private void a(Runnable runnable) {
            new Handler(Looper.getMainLooper()).post(runnable);
        }

        @Override // okhttp3.Callback
        public void onFailure(Call call, IOException iOException) {
            a(new DismissRunnable(this.b));
            a(new ToastRunnable(this.a, "下载失败，请检查网络后重试"));
        }

        @Override // okhttp3.Callback
        public void onResponse(Call call, Response response) {
            int i;
            try {
                ResponseBody responseBodyBody = response.body();
                if (responseBodyBody == null) {
                    response.close();
                    a(new DismissRunnable(this.b));
                    a(new ToastRunnable(this.a, "下载失败，请检查网络后重试"));
                    return;
                }
                long jContentLength = responseBodyBody.contentLength();
                InputStream inputStreamByteStream = responseBodyBody.byteStream();
                File file = new File(this.a.getCacheDir(), "mimi_update.apk");
                FileOutputStream fileOutputStream = new FileOutputStream(file);
                byte[] bArr = new byte[8192];
                long j = 0;
                int i2 = -1;
                while (true) {
                    int i3 = inputStreamByteStream.read(bArr);
                    if (i3 == -1) {
                        fileOutputStream.flush();
                        fileOutputStream.close();
                        inputStreamByteStream.close();
                        response.close();
                        a(new InstallRunnable(this.a, this.b, file));
                        return;
                    }
                    fileOutputStream.write(bArr, 0, i3);
                    j += (long) i3;
                    if (jContentLength > 0 && (i = (int) ((j * ((long) 100)) / jContentLength)) != i2) {
                        i2 = i;
                        a(new ProgressRunnable(this.b, i));
                    }
                }
            } catch (Throwable unused) {
            }
        }
    }

    public static class DownloadClickListener implements DialogInterface.OnClickListener {
        private final Context a;
        private final String b;

        public DownloadClickListener(Context context, String str) {
            this.a = context;
            this.b = str;
        }

        @Override // android.content.DialogInterface.OnClickListener
        public void onClick(DialogInterface dialogInterface, int i) {
            try {
                this.a.startActivity(new Intent("android.intent.action.VIEW", Uri.parse(this.b)));
            } catch (Throwable th) {
                Toast.makeText(this.a, "无法打开浏览器，请手动访问网站下载", 1).show();
            }
        }
    }

    public static class InstallRunnable implements Runnable {
        private final Context a;
        private final ProgressDialog b;
        private final File c;

        public InstallRunnable(Context context, ProgressDialog progressDialog, File file) {
            this.a = context;
            this.b = progressDialog;
            this.c = file;
        }

        @Override // java.lang.Runnable
        public void run() {
            try {
                this.b.dismiss();
            } catch (Throwable unused) {
            }
            UpdateChecker.installApk(this.a, this.c);
        }
    }

    public static class ProgressRunnable implements Runnable {
        private final ProgressDialog a;
        private final int b;

        public ProgressRunnable(ProgressDialog progressDialog, int i) {
            this.a = progressDialog;
            this.b = i;
        }

        @Override // java.lang.Runnable
        public void run() {
            this.a.setProgress(this.b);
        }
    }

    public static class ToastRunnable implements Runnable {
        private final Context a;
        private final String b;

        public ToastRunnable(Context context, String str) {
            this.a = context;
            this.b = str;
        }

        @Override // java.lang.Runnable
        public void run() {
            Toast.makeText(this.a, this.b, 1).show();
        }
    }

    public static class UpdateDialogRunnable implements Runnable {
        private final Context a;
        private final String b;
        private final String c;
        private final String d;

        public UpdateDialogRunnable(Context context, String str, String str2, String str3) {
            this.a = context;
            this.b = str;
            this.c = str2;
            this.d = str3;
        }

        @Override // java.lang.Runnable
        public void run() {
            try {
                AlertDialog.Builder builder = new AlertDialog.Builder(this.a);
                builder.setTitle("发现新版本 " + this.b);
                builder.setMessage(this.c);
                builder.setPositiveButton("去浏览器下载", new DownloadClickListener(this.a, this.d));
                builder.setNegativeButton("取消", (DialogInterface.OnClickListener) null);
                builder.show();
            } catch (Throwable unused) {
            }
        }
    }

    private static void check(Context context, boolean z) {
        new OkHttpClient().newCall(new Request.Builder().url("https://aokiisei.vxni.ink/mimi/version.json").build()).enqueue(new CheckCallback(context, z));
    }

    public static void checkAuto(Context context) {
        check(context, false);
    }

    public static void checkManual(Context context) {
        check(context, true);
    }

    public static void installApk(Context context, File file) {
        Uri uriForFile = FileProvider.getUriForFile(context, "com.mimi.kebiao.fileprovider", file);
        Intent intent = new Intent("android.intent.action.VIEW");
        intent.setDataAndType(uriForFile, "application/vnd.android.package-archive");
        intent.addFlags(268435457);
        context.startActivity(intent);
    }

    public static void startDownload(Context context, String str) {
        ProgressDialog progressDialog = new ProgressDialog(context);
        progressDialog.setMessage("正在下载更新…");
        progressDialog.setProgressStyle(1);
        progressDialog.setMax(100);
        progressDialog.setCancelable(false);
        progressDialog.show();
        new OkHttpClient().newCall(new Request.Builder().url(str).build()).enqueue(new DownloadCallback(context, progressDialog));
    }
}
