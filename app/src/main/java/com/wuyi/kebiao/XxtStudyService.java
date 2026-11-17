package com.wuyi.kebiao;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

import androidx.core.app.NotificationCompat;

/**
 * 本文件职责：
 * 刷课 / 刷题的前台保活服务。让任务在退到后台后继续运行，并把当前
 * 课程、章节、视频或试卷进度实时显示在通知栏；任务结束后停留几秒自动消失。
 * 检查更新里可关闭「刷课进度通知」，但服务本身仍会运行（后台保活必需）。
 */
public final class XxtStudyService extends Service {

    public static final String ACTION_START = "com.wuyi.kebiao.XXT_STUDY_START";
    public static final String ACTION_START_QUIZ = "com.wuyi.kebiao.XXT_QUIZ_START";
    public static final String ACTION_STOP = "com.wuyi.kebiao.XXT_STUDY_STOP";

    private static final String CHANNEL = "xxt_study";
    private static final int NOTIFY_ID = 9320;
    private static final long FINISH_KEEP_MS = 10000L;

    /** true 表示当前通知展示的是「刷题」进度 */
    private static volatile boolean quizMode;

    private Handler handler;
    private long finishedAt;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (quizMode) {
                XxtQuizRunner.Status q = XxtQuizRunner.status();
                // 刷题已结束但刷课还在跑时，切回刷课进度
                if (!q.running && XxtStudyEngine.isRunning()) {
                    quizMode = false;
                } else {
                    notifyQuizStatus(q);
                    if (q.running) {
                        handler.postDelayed(this, 2000);
                        return;
                    }
                    if (!q.finished) {
                        stopSelf();
                        return;
                    }
                    if (finishedAt == 0) {
                        finishedAt = SystemClock.elapsedRealtime();
                    }
                    if (SystemClock.elapsedRealtime() - finishedAt < FINISH_KEEP_MS) {
                        handler.postDelayed(this, 2000);
                    } else {
                        stopSelf();
                    }
                    return;
                }
            }
            XxtStudyEngine.Status st = XxtStudyEngine.status();
            notifyStatus(st);
            if (st.running) {
                handler.postDelayed(this, 2000);
                return;
            }
            if (!st.finished) {
                stopSelf();
                return;
            }
            if (finishedAt == 0) {
                finishedAt = SystemClock.elapsedRealtime();
            }
            if (SystemClock.elapsedRealtime() - finishedAt < FINISH_KEEP_MS) {
                handler.postDelayed(this, 2000);
            } else {
                stopSelf();
            }
        }
    };

    public static void start(Context context) {
        quizMode = false;
        Intent intent = new Intent(context, XxtStudyService.class);
        intent.setAction(ACTION_START);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (Exception ignored) {
        }
    }

    /** 启动刷题的后台保活通知 */
    public static void startQuiz(Context context) {
        quizMode = true;
        Intent intent = new Intent(context, XxtStudyService.class);
        intent.setAction(ACTION_START_QUIZ);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (Exception ignored) {
        }
    }

    /** 停止刷课并撤掉通知 */
    public static void stop(Context context) {
        Intent intent = new Intent(context, XxtStudyService.class);
        intent.setAction(ACTION_STOP);
        try {
            context.startService(intent);
        } catch (Exception ignored) {
        }
    }

    /** 只撤掉通知服务，不打断正在进行的刷课 */
    public static void detach(Context context) {
        try {
            context.stopService(new Intent(context, XxtStudyService.class));
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        handler = new Handler(Looper.getMainLooper());
        ensureChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? "" : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            XxtStudyEngine.stop();
            XxtQuizRunner.stop();
            notifyCurrent();
            handler.removeCallbacks(tick);
            handler.postDelayed(tick, 1500);
            return START_NOT_STICKY;
        }
        if (ACTION_START_QUIZ.equals(action)) {
            quizMode = true;
        } else if (ACTION_START.equals(action)) {
            quizMode = false;
        }
        finishedAt = 0;
        startForegroundCompat();
        acquireLocks();
        handler.removeCallbacks(tick);
        handler.post(tick);
        // 被系统杀掉后尽量由系统重建服务，tick 发现刷课已停会自行退出
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (handler != null) {
            handler.removeCallbacks(tick);
        }
        releaseLocks();
        super.onDestroy();
    }

    // 退到后台、锁屏后系统会休眠 CPU 并断开 Wi-Fi，刷课线程随之停摆；
    // 用部分唤醒锁 + Wi-Fi 高性能锁维持网络请求持续进行。
    private void acquireLocks() {
        try {
            if (wakeLock == null) {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                if (pm != null) {
                    wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MimiKebiao:study");
                    wakeLock.setReferenceCounted(false);
                }
            }
            if (wakeLock != null && !wakeLock.isHeld()) {
                wakeLock.acquire(3 * 60 * 60 * 1000L);
            }
        } catch (Exception ignored) {
        }
        try {
            if (wifiLock == null) {
                WifiManager wm = (WifiManager) getApplicationContext()
                        .getSystemService(Context.WIFI_SERVICE);
                if (wm != null) {
                    wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "MimiKebiao:study");
                    wifiLock.setReferenceCounted(false);
                }
            }
            if (wifiLock != null && !wifiLock.isHeld()) {
                wifiLock.acquire();
            }
        } catch (Exception ignored) {
        }
    }

    private void releaseLocks() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
        } catch (Exception ignored) {
        }
        try {
            if (wifiLock != null && wifiLock.isHeld()) {
                wifiLock.release();
            }
        } catch (Exception ignored) {
        }
        wakeLock = null;
        wifiLock = null;
    }

    /** 申请加入电池优化白名单，否则部分机型锁屏后仍会冻结应用 */
    public static void requestIgnoreBattery(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return;
        }
        try {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (pm == null || pm.isIgnoringBatteryOptimizations(context.getPackageName())) {
                return;
            }
            Intent intent = new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.parse("package:" + context.getPackageName()));
            if (!(context instanceof android.app.Activity)) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            context.startActivity(intent);
        } catch (Exception ignored) {
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ---------------------------------------------------------------- 通知
    private void startForegroundCompat() {
        ensureChannel();
        Notification n = quizMode
                ? buildQuiz(XxtQuizRunner.status(), true)
                : build(XxtStudyEngine.status(), true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFY_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFY_ID, n);
        }
    }

    private void notifyCurrent() {
        if (quizMode) {
            notifyQuizStatus(XxtQuizRunner.status());
        } else {
            notifyStatus(XxtStudyEngine.status());
        }
    }

    private void notifyQuizStatus(XxtQuizRunner.Status st) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) {
            return;
        }
        ensureChannel();
        try {
            nm.notify(NOTIFY_ID, buildQuiz(st, st.running));
        } catch (Exception ignored) {
        }
    }

    private void notifyStatus(XxtStudyEngine.Status st) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) {
            return;
        }
        ensureChannel();
        try {
            nm.notify(NOTIFY_ID, build(st, st.running));
        } catch (Exception ignored) {
        }
    }

    private Notification build(XxtStudyEngine.Status st, boolean ongoing) {
        String title;
        String text;
        if (st.running && !XxtStore.studyNotify(this)) {
            // 用户关掉了进度通知：保活通知仍然保留，只显示最简信息
            title = "刷课中（后台运行）";
            text = "已关闭进度通知，刷课仍在继续，点按查看";
        } else if (st.running) {
            title = "刷课中 · " + (st.chapter == null || st.chapter.isEmpty()
                    ? (st.course == null || st.course.isEmpty() ? "准备中" : st.course)
                    : st.chapter);
            if (st.video != null && !st.video.isEmpty() && st.dur > 0) {
                text = st.video + "  " + st.percent + "%（" + clock(st.cur) + "/" + clock(st.dur) + "）";
            } else {
                text = st.text == null || st.text.isEmpty() ? "正在读取任务点…" : st.text;
            }
        } else if (st.finished) {
            title = "刷课结束";
            text = (st.text == null ? "" : st.text) + "（完成 " + st.done + " 个，失败 " + st.failed + " 个）";
        } else {
            title = "刷课已停止";
            text = st.text == null || st.text.isEmpty() ? "已停止" : st.text;
        }
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_notice)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setOngoing(ongoing)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(openAppIntent());
        if (ongoing) {
            b.addAction(0, "停止刷课", stopPendingIntent());
        } else {
            b.setAutoCancel(true);
        }
        if (st.running && st.dur > 0 && XxtStore.studyNotify(this)) {
            b.setProgress(100, Math.max(0, Math.min(100, st.percent)), false);
        }
        return b.build();
    }

    private Notification buildQuiz(XxtQuizRunner.Status st, boolean ongoing) {
        String title;
        String text;
        if (st.running) {
            title = "刷题中 · " + (st.chapter == null || st.chapter.isEmpty()
                    ? (st.course == null || st.course.isEmpty() ? "准备中" : st.course)
                    : st.chapter);
            String paper = st.paper == null || st.paper.isEmpty() ? st.text : st.paper;
            text = paper + " · 已答 " + st.answered + " 题 · 交卷 " + st.submitted
                    + (st.failed > 0 ? " · 失败 " + st.failed : "");
        } else if (st.finished) {
            title = "刷题结束";
            text = st.text == null || st.text.isEmpty() ? "已结束" : st.text;
        } else {
            title = "刷题已停止";
            text = st.text == null || st.text.isEmpty() ? "已停止" : st.text;
        }
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_notice)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setOngoing(ongoing)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(openAppIntent());
        if (ongoing) {
            b.addAction(0, "停止刷题", stopPendingIntent());
        } else {
            b.setAutoCancel(true);
        }
        return b.build();
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) {
            return;
        }
        NotificationChannel ch = new NotificationChannel(CHANNEL, "刷课进度",
                NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("刷课时在通知栏显示当前章节、视频和播放百分比");
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
    }

    private PendingIntent openAppIntent() {
        Intent intent = new Intent(this, SplashActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getActivity(this, 9321, intent, flags);
    }

    private PendingIntent stopPendingIntent() {
        Intent intent = new Intent(this, XxtStudyService.class);
        intent.setAction(ACTION_STOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getService(this, 9322, intent, flags);
    }

    private static String clock(int sec) {
        if (sec < 0) {
            sec = 0;
        }
        int m = sec / 60;
        int s = sec % 60;
        return m + ":" + (s < 10 ? "0" + s : String.valueOf(s));
    }
}
