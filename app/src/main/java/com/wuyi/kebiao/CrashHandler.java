package com.wuyi.kebiao;

import android.content.Context;

/* JADX INFO: loaded from: classes.dex */
public class CrashHandler implements Thread.UncaughtExceptionHandler {
    private final Context ctx;
    private final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();

    public CrashHandler(Context context) {
        this.ctx = context;
    }

    public static void install(Context context) {
        try {
            Thread.setDefaultUncaughtExceptionHandler(new CrashHandler(context.getApplicationContext()));
            CrashLogger.event(context, "CrashHandler installed");
        } catch (Throwable unused) {
        }
    }

    @Override // java.lang.Thread.UncaughtExceptionHandler
    public void uncaughtException(Thread thread, Throwable th) {
        try {
            CrashLogger.log(this.ctx, "UNCAUGHT thread=" + thread.getName(), th);
        } catch (Throwable unused) {
        }
        Thread.UncaughtExceptionHandler uncaughtExceptionHandler = this.prev;
        if (uncaughtExceptionHandler != null) {
            uncaughtExceptionHandler.uncaughtException(thread, th);
        }
    }
}
