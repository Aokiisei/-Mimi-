package com.wuyi.kebiao;

/**
 * 本文件职责：
 * 学习通签到自动巡检前台服务。按用户设定的间隔轮询所有已登录账号的进行中签到：
 * 普通签到 / 已配好位置的位置签到直接自动完成；
 * 拍照、二维码、手势、签到码、人脸等需要本人操作的活动只发通知提醒，不反复打扰。
 */

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class XxtMonitorService extends Service {

    public static final String ACTION_START = "com.wuyi.kebiao.XXT_MONITOR_START";
    public static final String ACTION_STOP = "com.wuyi.kebiao.XXT_MONITOR_STOP";

    private static final String CHANNEL_RUNNING = "xxt_sign_monitor";
    private static final String CHANNEL_ALERT = "xxt_sign_alert";
    private static final int ID_RUNNING = 9310;
    private static final int ID_ALERT_BASE = 9400;

    private static volatile boolean running = false;

    private HandlerThread thread;
    private Handler handler;
    private volatile boolean loopStarted = false;
    /** 已提醒过、但仍在等待本人处理的活动，避免每轮巡检都弹一次通知 */
    private final Set<String> alerted = new HashSet<>();

    public static boolean isRunning() {
        return running;
    }

    public static void start(Context context) {
        Intent intent = new Intent(context, XxtMonitorService.class);
        intent.setAction(ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    public static void stop(Context context) {
        Intent intent = new Intent(context, XxtMonitorService.class);
        intent.setAction(ACTION_STOP);
        try {
            context.startService(intent);
        } catch (Exception ignored) {
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            XxtStore.setAutoEnabled(this, false);
            XxtStore.addLog("巡检已在通知栏手动停止");
            stopSelf();
            return START_NOT_STICKY;
        }
        startForegroundCompat();
        running = true;
        if (!loopStarted) {
            loopStarted = true;
            thread = new HandlerThread("xxt-sign-monitor");
            thread.start();
            handler = new Handler(thread.getLooper());
            handler.post(tick);
        }
        return START_STICKY;
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) {
                return;
            }
            long started = System.currentTimeMillis();
            try {
                scanOnce();
            } catch (Exception e) {
                XxtStore.addLog("巡检异常：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            }
            if (!running) {
                return;
            }
            long interval = XxtStore.intervalSeconds(XxtMonitorService.this) * 1000L;
            long used = System.currentTimeMillis() - started;
            long delay = Math.max(3000L, interval - used);
            handler.postDelayed(this, delay);
        }
    };

    private void scanOnce() {
        List<XxtStore.Account> accounts = XxtStore.accounts(this);
        if (accounts.isEmpty()) {
            return;
        }
        Set<String> handled = new HashSet<>(XxtStore.handledAids(this));
        for (XxtStore.Account account : accounts) {
            if (!running) {
                return;
            }
            XxtSession session = new XxtSession(account);
            String err = session.ensureLogin();
            XxtStore.saveAccount(this, account);
            if (!err.isEmpty()) {
                XxtStore.addLog("巡检登录失败 " + account.display() + "：" + err);
                continue;
            }
            List<XxtSignEngine.Activity> acts = XxtSignEngine.findActive(this, account, session);
            for (XxtSignEngine.Activity act : acts) {
                if (handled.contains(act.aid) || !XxtStore.inScope(this, act.courseId)) {
                    continue;
                }
                XxtSignEngine.prepare(session, act);
                if (act.needFace) {
                    notifyManual(account, act, "要求人脸识别，需本人到官方 App 完成");
                    XxtStore.markHandled(this, act.aid);
                    handled.add(act.aid);
                    continue;
                }
                if (XxtSignEngine.autoEligible(this, act)) {
                    XxtSignEngine.Result r = XxtSignEngine.autoSign(this, account, session, act);
                    if (r != null && r.ok()) {
                        notifyPlain(account, act, "已自动签到：" + r.message);
                        XxtStore.markHandled(this, act.aid);
                        handled.add(act.aid);
                    } else if (r != null) {
                        // 失败不记为已处理：下一轮继续尝试，提醒只发一次
                        if (alerted.add(act.aid)) {
                            notifyManual(account, act, r.message);
                        }
                    }
                    continue;
                } else {
                    notifyManual(account, act, autoHint(act));
                }
                XxtStore.markHandled(this, act.aid);
                handled.add(act.aid);
            }
            int gap = XxtStore.accountGap(this);
            if (gap > 0 && running) {
                try {
                    Thread.sleep(gap * 1000L);
                } catch (InterruptedException ignored) {
                }
            }
        }
    }

    private String autoHint(XxtSignEngine.Activity act) {
        if (act.ifPhoto == 1) {
            return "拍照签到：平台照片校验只认官方 App，请到学习通官方 App 拍照签到";
        }
        if (act.otherId == XxtApi.TYPE_LOCATION) {
            return "位置签到：请先在签到页地图上选点";
        }
        if (act.otherId == XxtApi.TYPE_QRCODE) {
            return "二维码签到：打开小助手点「立即签到」按提示扫码";
        }
        return "需要签到码，请打开小助手处理";
    }

    // ---------------------------------------------------------------- 通知
    private void startForegroundCompat() {
        ensureChannels();
        Notification n = new NotificationCompat.Builder(this, CHANNEL_RUNNING)
                .setSmallIcon(R.drawable.ic_notice)
                .setContentTitle("学习通签到巡检中")
                .setContentText("每 " + XxtStore.intervalSeconds(this) + " 秒检查一次签到")
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(openAppIntent())
                .addAction(0, "停止巡检", stopPendingIntent())
                .build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(ID_RUNNING, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(ID_RUNNING, n);
        }
    }

    private void notifyManual(XxtStore.Account account, XxtSignEngine.Activity act, String hint) {
        String title = "有新的签到需要处理";
        String text = account.display() + " · " + act.title() + "：" + hint;
        notify(ID_ALERT_BASE + Math.abs(act.aid.hashCode() % 400), title, text, true);
        XxtStore.addLog("[提醒] " + text);
    }

    private void notifyPlain(XxtStore.Account account, XxtSignEngine.Activity act, String text) {
        notify(ID_ALERT_BASE + Math.abs(act.aid.hashCode() % 400),
                "学习通签到", account.display() + " · " + act.title() + "：" + text, false);
    }

    private void notify(int id, String title, String text, boolean highPriority) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) {
            return;
        }
        ensureChannels();
        String channel = highPriority ? CHANNEL_ALERT : CHANNEL_RUNNING;
        Notification n = new NotificationCompat.Builder(this, channel)
                .setSmallIcon(R.drawable.ic_notice)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setPriority(highPriority ? NotificationCompat.PRIORITY_HIGH : NotificationCompat.PRIORITY_DEFAULT)
                .setContentIntent(openAppIntent())
                .build();
        try {
            nm.notify(id, n);
        } catch (Exception ignored) {
        }
    }

    private PendingIntent openAppIntent() {
        Intent intent = new Intent(this, SplashActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getActivity(this, 9311, intent, flags);
    }

    private PendingIntent stopPendingIntent() {
        Intent intent = new Intent(this, XxtMonitorService.class);
        intent.setAction(ACTION_STOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getService(this, 9312, intent, flags);
    }

    private void ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) {
            return;
        }
        NotificationChannel run = new NotificationChannel(CHANNEL_RUNNING, "学习通签到巡检",
                NotificationManager.IMPORTANCE_LOW);
        run.setDescription("自动巡检运行状态与自动签到结果");
        NotificationChannel alert = new NotificationChannel(CHANNEL_ALERT, "学习通签到提醒",
                NotificationManager.IMPORTANCE_HIGH);
        alert.setDescription("有需要本人处理的签到时提醒");
        nm.createNotificationChannel(run);
        nm.createNotificationChannel(alert);
    }

    @Override
    public void onDestroy() {
        running = false;
        loopStarted = false;
        if (handler != null) {
            handler.removeCallbacksAndMessages(null);
        }
        if (thread != null) {
            thread.quitSafely();
        }
        handler = null;
        thread = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
