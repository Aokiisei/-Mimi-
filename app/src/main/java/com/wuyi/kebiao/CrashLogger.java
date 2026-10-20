package com.wuyi.kebiao;

/**
 * 本文件职责：
 * 日志工具：同时写 Logcat 和 mimi_crash.log 文件，
 * 附带机型与 SDK 版本，便于定位线上问题。
 */

import android.content.Context;
import android.os.Build;
import android.util.Log;
import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;

public class CrashLogger {
    public static void event(Context context, String str) {
        try {
            log(context, str, new Throwable("(event)"));
        } catch (Throwable unused) {
        }
    }

    public static void log(Context context, String str, Throwable th) {
        File externalFilesDir;
        try {
            StringWriter stringWriter = new StringWriter();
            PrintWriter printWriter = new PrintWriter(stringWriter);
            if (th != null) {
                th.printStackTrace(printWriter);
            } else {
                printWriter.print("(no stack)");
            }
            printWriter.flush();
            String str2 = "\n==== MIMI-CRASH " + System.currentTimeMillis() + " | " + str + " | " + Build.BRAND + " " + Build.MODEL + " | SDK" + Build.VERSION.SDK_INT + "\n" + stringWriter.toString();
            Log.e("MimiCrash", str2);
            if (context == null || (externalFilesDir = context.getExternalFilesDir(null)) == null) {
                return;
            }
            FileWriter fileWriter = new FileWriter(new File(externalFilesDir, "mimi_crash.log"), true);
            fileWriter.write(str2);
            fileWriter.close();
        } catch (Throwable unused) {
        }
    }
}
