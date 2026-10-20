package com.wuyi.kebiao;

/**
 * 本文件职责：
 * 全局未捕获异常处理器。
 * 崩溃时先写本地日志，再交还给系统默认处理，避免静默吞掉崩溃。
 */

import android.content.Context;

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

    @Override
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
