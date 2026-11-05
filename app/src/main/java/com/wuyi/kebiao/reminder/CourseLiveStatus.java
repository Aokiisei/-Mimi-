package com.wuyi.kebiao.reminder;

/**
 * 本文件职责：
 * 灵动胶囊 / 流体云（测试功能）的实时课程状态通知。
 * 用一条静默的常驻通知展示「正在上课」或「下一节」，并带倒计时/进度；
 * 支持的系统（部分 vivo / OPPO 机型）可把这类通知展示为胶囊形态，具体是否显示由系统决定。
 */

import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import androidx.core.app.NotificationCompat;
import com.wuyi.kebiao.R;
import com.wuyi.kebiao.SplashActivity;
import com.wuyi.kebiao.widget.CourseWidgetService;
import java.util.Calendar;
import java.util.List;
import org.json.JSONObject;

public final class CourseLiveStatus {

    /** 实时状态通知固定 ID，避免和多节课提醒堆叠 */
    private static final int NOTIFY_ID = 700001;
    /** 第一节课开始前 30 分钟才出现，避免全天占用胶囊 */
    private static final long LEAD_MS = 30 * 60 * 1000L;

    private CourseLiveStatus() {
    }

    /** 根据当前时间刷新常驻状态：上课中显示进度，下一节显示倒计时，课后自动收起 */
    public static void refresh(Context context) {
        if (context == null) {
            return;
        }
        Context app = context.getApplicationContext();
        NotificationManager nm = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) {
            return;
        }
        if (!CourseReminderScheduler.isLiveStatusEnabled(app)) {
            nm.cancel(NOTIFY_ID);
            return;
        }
        int weekNo = CourseWidgetService.currentWeekNo(app);
        if (weekNo <= 0) {
            nm.cancel(NOTIFY_ID);
            return;
        }
        Calendar today = Calendar.getInstance();
        int dow = today.get(Calendar.DAY_OF_WEEK);
        int dowIndex = dow == Calendar.SUNDAY ? 6 : dow - 2;
        List<JSONObject> list = CourseWidgetService.coursesForDay(
                app, CourseReminderScheduler.DOW_NAMES[dowIndex], weekNo);
        if (list.isEmpty()) {
            nm.cancel(NOTIFY_ID);
            return;
        }

        long now = System.currentTimeMillis();
        JSONObject current = null;
        long currentStart = 0;
        long currentEnd = 0;
        JSONObject next = null;
        long nextStart = 0;
        long firstStart = 0;
        long lastEnd = 0;
        for (int i = 0; i < list.size(); i++) {
            JSONObject c = list.get(i);
            int slot = CourseWidgetService.slotIndexOfCourse(c);
            if (slot < 0 || slot >= CourseReminderScheduler.SLOT_START.length) {
                continue;
            }
            long start = CourseReminderScheduler.timeAt(today, CourseReminderScheduler.SLOT_START[slot]);
            long end = CourseReminderScheduler.timeAt(today, CourseReminderScheduler.SLOT_END[slot]);
            if (firstStart == 0 || start < firstStart) {
                firstStart = start;
            }
            if (end > lastEnd) {
                lastEnd = end;
            }
            if (now >= start && now < end) {
                if (current == null || start > currentStart) {
                    current = c;
                    currentStart = start;
                    currentEnd = end;
                }
            } else if (start > now) {
                if (next == null || start < nextStart) {
                    next = c;
                    nextStart = start;
                }
            }
        }

        // 首节课前 30 分钟到当天最后一节课结束之间才展示
        if (now < firstStart - LEAD_MS || now >= lastEnd) {
            nm.cancel(NOTIFY_ID);
            return;
        }

        String weekLabel = "第" + weekNo + "周 · " + CourseReminderScheduler.DOW_NAMES[dowIndex];
        NotificationCompat.Builder builder;
        if (current != null) {
            String name = current.optString("name", "课程");
            String time = formatTime(currentStart) + "-" + formatTime(currentEnd);
            String location = current.optString("location", "");
            String text = location.isEmpty() ? time : time + " · " + location;
            int total = (int) Math.max(1, (currentEnd - currentStart) / 60000L);
            int elapsed = (int) Math.max(0, (now - currentStart) / 60000L);
            builder = new NotificationCompat.Builder(app, CourseReminderScheduler.CHANNEL_LIVE)
                    .setSmallIcon(R.drawable.ic_notice)
                    .setContentTitle("正在上课 · " + name)
                    .setContentText(text)
                    .setSubText(weekLabel)
                    .setProgress(total, Math.min(elapsed, total), false)
                    .setWhen(currentStart)
                    .setUsesChronometer(true)
                    .setChronometerCountDown(false);
        } else {
            String name = next.optString("name", "课程");
            int slot = CourseWidgetService.slotIndexOfCourse(next);
            String time = "";
            String location = next.optString("location", "");
            if (slot >= 0 && slot < CourseReminderScheduler.SLOT_START.length) {
                time = CourseReminderScheduler.SLOT_START[slot] + "-" + CourseReminderScheduler.SLOT_END[slot];
            }
            String text = time;
            if (!location.isEmpty()) {
                text = time.isEmpty() ? location : time + " · " + location;
            }
            builder = new NotificationCompat.Builder(app, CourseReminderScheduler.CHANNEL_LIVE)
                    .setSmallIcon(R.drawable.ic_notice)
                    .setContentTitle("下一节 · " + name)
                    .setContentText(text)
                    .setSubText(weekLabel)
                    .setWhen(nextStart)
                    .setUsesChronometer(true)
                    .setChronometerCountDown(true);
        }
        builder.setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setContentIntent(openAppIntent(app));
        try {
            nm.notify(NOTIFY_ID, builder.build());
        } catch (Throwable th) {
            th.printStackTrace();
        }
    }

    /** 关闭开关或离开课程时段时收起常驻状态 */
    public static void cancel(Context context) {
        if (context == null) {
            return;
        }
        try {
            NotificationManager nm = (NotificationManager) context.getApplicationContext()
                    .getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.cancel(NOTIFY_ID);
            }
        } catch (Throwable ignore) {
        }
    }

    private static String formatTime(long millis) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(millis);
        int h = cal.get(Calendar.HOUR_OF_DAY);
        int m = cal.get(Calendar.MINUTE);
        return (h < 10 ? "0" : "") + h + ":" + (m < 10 ? "0" : "") + m;
    }

    private static PendingIntent openAppIntent(Context app) {
        Intent intent = new Intent(app, SplashActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = Build.VERSION.SDK_INT >= 23
                ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                : PendingIntent.FLAG_UPDATE_CURRENT;
        return PendingIntent.getActivity(app, 0, intent, flags);
    }
}
