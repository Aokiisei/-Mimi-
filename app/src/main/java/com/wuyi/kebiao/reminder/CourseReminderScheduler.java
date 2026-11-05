package com.wuyi.kebiao.reminder;

/**
 * 本文件职责：
 * 上课提醒与每日课表提醒的调度与通知展示。
 * - 每节课开始前 10 分钟发送提醒；
 * - 每天晚上 22:00 发送第二天课程汇总；
 * - 课程数据与教学周复用桌面小组件的缓存（mimi_widget）。
 */

import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import androidx.core.app.NotificationCompat;
import com.wuyi.kebiao.R;
import com.wuyi.kebiao.SplashActivity;
import com.wuyi.kebiao.widget.CourseWidgetService;
import java.util.Calendar;
import java.util.List;
import org.json.JSONObject;

public final class CourseReminderScheduler {

    public static final String ACTION_CLASS_REMINDER = "com.mimi.kebiao.action.CLASS_REMINDER";
    public static final String ACTION_DAILY_SUMMARY = "com.mimi.kebiao.action.DAILY_SUMMARY";
    public static final String ACTION_LIVE_UPDATE = "com.mimi.kebiao.action.LIVE_UPDATE";

    public static final String EXTRA_NOTIFY_ID = "notify_id";
    public static final String EXTRA_NAME = "course_name";
    public static final String EXTRA_TIME = "course_time";
    public static final String EXTRA_LOCATION = "course_location";
    public static final String EXTRA_TEACHER = "course_teacher";

    private static final String CHANNEL_CLASS = "mimi_class_reminder";
    private static final String CHANNEL_DAILY = "mimi_daily_summary";
    static final String CHANNEL_LIVE = "mimi_live_status";
    /** 用户开关：课前 10 分钟提醒 / 每晚 22:00 明日课表 / 灵动胶囊（测试） */
    private static final String KEY_CLASS_ENABLED = "remind_class_enabled";
    private static final String KEY_DAILY_ENABLED = "remind_daily_enabled";
    private static final String KEY_LIVE_ENABLED = "remind_live_enabled";

    private static final int DAILY_SUMMARY_REQUEST = 900001;
    private static final int DAILY_SUMMARY_NOTIFY_ID = 900001;
    public static final int DAILY_SUMMARY_HOUR = 22;
    private static final int MINUTES_BEFORE = 10;
    /** 一次调度覆盖未来 7 天，减少长时间不开 App 的漏提醒 */
    private static final int SCHEDULE_DAYS = 7;
    private static final int MAX_COURSES_PER_DAY = 10;
    private static final long DAY_MS = 86400000L;

    static final String[] DOW_NAMES = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};
    static final String[] SLOT_START = {"08:00", "10:00", "14:30", "16:20", "19:00", "20:50"};
    static final String[] SLOT_END = {"09:40", "11:40", "16:10", "18:00", "20:40", "22:30"};

    private CourseReminderScheduler() {
    }

    /** 课前 10 分钟提醒开关，默认开启 */
    public static boolean isClassReminderEnabled(Context context) {
        if (context == null) {
            return true;
        }
        return context.getSharedPreferences(CourseWidgetService.PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_CLASS_ENABLED, true);
    }

    /** 每晚 22:00 明日课表提醒开关，默认开启 */
    public static boolean isDailySummaryEnabled(Context context) {
        if (context == null) {
            return true;
        }
        return context.getSharedPreferences(CourseWidgetService.PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_DAILY_ENABLED, true);
    }

    public static void setClassReminderEnabled(Context context, boolean enabled) {
        if (context == null) {
            return;
        }
        context.getSharedPreferences(CourseWidgetService.PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_CLASS_ENABLED, enabled).apply();
        reschedule(context);
    }

    public static void setDailySummaryEnabled(Context context, boolean enabled) {
        if (context == null) {
            return;
        }
        context.getSharedPreferences(CourseWidgetService.PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_DAILY_ENABLED, enabled).apply();
        reschedule(context);
    }

    /** 灵动胶囊 / 流体云（测试）开关，默认关闭 */
    public static boolean isLiveStatusEnabled(Context context) {
        if (context == null) {
            return false;
        }
        return context.getSharedPreferences(CourseWidgetService.PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_LIVE_ENABLED, false);
    }

    public static void setLiveStatusEnabled(Context context, boolean enabled) {
        if (context == null) {
            return;
        }
        context.getSharedPreferences(CourseWidgetService.PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_LIVE_ENABLED, enabled).apply();
        reschedule(context);
        CourseLiveStatus.refresh(context);
    }

    /** 重新排布未来 7 天的上课提醒与当晚的课表汇总提醒 */
    public static void reschedule(Context context) {
        if (context == null) {
            return;
        }
        Context app = context.getApplicationContext();
        AlarmManager am = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
        if (am == null) {
            return;
        }
        ensureChannels(app);
        cancelClassAlarms(app, am);
        cancelDailySummary(app, am);
        cancelLiveAlarms(app, am);

        boolean classEnabled = isClassReminderEnabled(app);
        boolean dailyEnabled = isDailySummaryEnabled(app);
        boolean liveEnabled = isLiveStatusEnabled(app);
        if (!liveEnabled) {
            CourseLiveStatus.cancel(app);
        }
        if (!classEnabled && !dailyEnabled && !liveEnabled) {
            return;
        }

        int weekNo = CourseWidgetService.currentWeekNo(app);
        String json = app.getSharedPreferences(CourseWidgetService.PREFS_NAME, Context.MODE_PRIVATE)
                .getString(CourseWidgetService.KEY_COURSES, "");
        if (weekNo <= 0 || json == null || json.trim().length() <= 2) {
            if (liveEnabled) {
                CourseLiveStatus.refresh(app);
            }
            return;
        }

        long now = System.currentTimeMillis();
        Calendar today = Calendar.getInstance();
        long baseMonday = mondayOf(today);
        for (int offset = 0; offset < SCHEDULE_DAYS; offset++) {
            Calendar date = (Calendar) today.clone();
            date.add(Calendar.DAY_OF_MONTH, offset);
            int week = weekNo + weekOffset(date, baseMonday);
            List<JSONObject> list = CourseWidgetService.coursesForDay(app, DOW_NAMES[dowIndex(date)], week);
            for (int i = 0; i < list.size() && i < MAX_COURSES_PER_DAY; i++) {
                JSONObject c = list.get(i);
                int slot = CourseWidgetService.slotIndexOfCourse(c);
                if (slot < 0 || slot >= SLOT_START.length) {
                    continue;
                }
                if (classEnabled) {
                    long triggerAt = triggerAt(date, SLOT_START[slot]);
                    if (triggerAt > now) {
                        scheduleClassAlarm(app, am, triggerAt, classRequestCode(offset, slot, i),
                                c, SLOT_START[slot], SLOT_END[slot]);
                    }
                }
                if (liveEnabled) {
                    // 上课开始与下课时各刷新一次胶囊状态，其余时间靠倒计时/进度自行更新
                    long startAt = timeAt(date, SLOT_START[slot]);
                    long endAt = timeAt(date, SLOT_END[slot]);
                    if (startAt > now) {
                        scheduleLiveAlarm(app, am, startAt, liveRequestCode(offset, i, false));
                    }
                    if (endAt > now) {
                        scheduleLiveAlarm(app, am, endAt, liveRequestCode(offset, i, true));
                    }
                }
            }
        }
        if (dailyEnabled) {
            scheduleDailySummary(app, am, now);
        }
        if (liveEnabled) {
            CourseLiveStatus.refresh(app);
        }
    }

    /** 上课前 10 分钟的通知 */
    public static void showClassReminder(Context context, Intent intent) {
        if (context == null || intent == null) {
            return;
        }
        Context app = context.getApplicationContext();
        if (!isClassReminderEnabled(app)) {
            return;
        }
        ensureChannels(app);
        String name = intent.getStringExtra(EXTRA_NAME);
        if (name == null || name.isEmpty()) {
            name = "课程";
        }
        String time = intent.getStringExtra(EXTRA_TIME);
        String location = intent.getStringExtra(EXTRA_LOCATION);
        String teacher = intent.getStringExtra(EXTRA_TEACHER);
        StringBuilder text = new StringBuilder("10 分钟后开始");
        if (time != null && !time.isEmpty()) {
            text.append(" · ").append(time);
        }
        if (location != null && !location.isEmpty()) {
            text.append(" · ").append(location);
        }
        if (teacher != null && !teacher.isEmpty()) {
            text.append(" · ").append(teacher);
        }
        int notifyId = intent.getIntExtra(EXTRA_NOTIFY_ID, 800001);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(app, CHANNEL_CLASS)
                .setSmallIcon(R.drawable.ic_notice)
                .setContentTitle("该上课啦：" + name)
                .setContentText(text.toString())
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text.toString()))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(openAppIntent(app));
        try {
            NotificationManager nm = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.notify(notifyId, builder.build());
            }
        } catch (Throwable th) {
            th.printStackTrace();
        }
        // 课前提醒触发时顺带刷新灵动胶囊的下一节状态
        CourseLiveStatus.refresh(app);
    }

    /** 晚上 22:00 的第二天课程汇总 */
    public static void showDailySummary(Context context) {
        if (context == null) {
            return;
        }
        Context app = context.getApplicationContext();
        if (!isDailySummaryEnabled(app)) {
            return;
        }
        ensureChannels(app);
        SharedPreferences prefs = app.getSharedPreferences(CourseWidgetService.PREFS_NAME, Context.MODE_PRIVATE);
        String json = prefs.getString(CourseWidgetService.KEY_COURSES, "");
        if (json == null || json.trim().length() <= 2) {
            // 还没同步过课表，不打扰用户
            return;
        }
        Calendar tomorrow = Calendar.getInstance();
        tomorrow.add(Calendar.DAY_OF_MONTH, 1);
        int weekNo = weekForDate(app, tomorrow);
        List<JSONObject> list = CourseWidgetService.coursesForDay(app, DOW_NAMES[dowIndex(tomorrow)], weekNo);

        String dateLabel = (tomorrow.get(Calendar.MONTH) + 1) + "月"
                + tomorrow.get(Calendar.DAY_OF_MONTH) + "日 " + DOW_NAMES[dowIndex(tomorrow)];
        StringBuilder text = new StringBuilder();
        if (list.isEmpty()) {
            text.append("明天没有课程安排，安心休息吧。");
        } else {
            text.append("明天共 ").append(list.size()).append(" 节课\n");
            for (int i = 0; i < list.size(); i++) {
                JSONObject c = list.get(i);
                String time = "";
                int slot = CourseWidgetService.slotIndexOfCourse(c);
                if (slot >= 0 && slot < SLOT_START.length) {
                    time = SLOT_START[slot] + "  ";
                }
                text.append(time).append(c.optString("name", "课程"));
                String location = c.optString("location", "");
                if (!location.isEmpty()) {
                    text.append(" · ").append(location);
                }
                if (i < list.size() - 1) {
                    text.append('\n');
                }
            }
        }
        NotificationCompat.Builder builder = new NotificationCompat.Builder(app, CHANNEL_DAILY)
                .setSmallIcon(R.drawable.ic_notice)
                .setContentTitle("明日课表 · " + dateLabel)
                .setContentText(text.toString())
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text.toString()))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setAutoCancel(true)
                .setContentIntent(openAppIntent(app));
        try {
            NotificationManager nm = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.notify(DAILY_SUMMARY_NOTIFY_ID, builder.build());
            }
        } catch (Throwable th) {
            th.printStackTrace();
        }
    }

    // ===== 内部实现 =====

    private static void scheduleClassAlarm(Context app, AlarmManager am, long triggerAt, int requestCode,
                                           JSONObject course, String start, String end) {
        try {
            Intent intent = new Intent(app, CourseReminderReceiver.class);
            intent.setAction(ACTION_CLASS_REMINDER);
            intent.putExtra(EXTRA_NOTIFY_ID, 100000 + requestCode);
            intent.putExtra(EXTRA_NAME, course.optString("name", "课程"));
            intent.putExtra(EXTRA_TIME, start + "-" + end);
            intent.putExtra(EXTRA_LOCATION, course.optString("location", ""));
            intent.putExtra(EXTRA_TEACHER, course.optString("teacher", ""));
            PendingIntent pi = PendingIntent.getBroadcast(app, requestCode, intent, pendingFlags());
            setExact(am, triggerAt, pi);
        } catch (Throwable th) {
            th.printStackTrace();
        }
    }

    private static void scheduleDailySummary(Context app, AlarmManager am, long now) {
        try {
            Calendar next = Calendar.getInstance();
            next.set(Calendar.HOUR_OF_DAY, DAILY_SUMMARY_HOUR);
            next.set(Calendar.MINUTE, 0);
            next.set(Calendar.SECOND, 0);
            next.set(Calendar.MILLISECOND, 0);
            if (next.getTimeInMillis() <= now) {
                next.add(Calendar.DAY_OF_MONTH, 1);
            }
            Intent intent = new Intent(app, CourseReminderReceiver.class);
            intent.setAction(ACTION_DAILY_SUMMARY);
            PendingIntent pi = PendingIntent.getBroadcast(app, DAILY_SUMMARY_REQUEST, intent, pendingFlags());
            setExact(am, next.getTimeInMillis(), pi);
        } catch (Throwable th) {
            th.printStackTrace();
        }
    }

    private static void cancelClassAlarms(Context app, AlarmManager am) {
        try {
            Intent intent = new Intent(app, CourseReminderReceiver.class);
            intent.setAction(ACTION_CLASS_REMINDER);
            for (int offset = 0; offset < SCHEDULE_DAYS; offset++) {
                for (int slot = 0; slot < SLOT_START.length; slot++) {
                    for (int seq = 0; seq < MAX_COURSES_PER_DAY; seq++) {
                        PendingIntent pi = PendingIntent.getBroadcast(
                                app, classRequestCode(offset, slot, seq), intent, pendingFlags());
                        am.cancel(pi);
                    }
                }
            }
        } catch (Throwable th) {
            th.printStackTrace();
        }
    }

    private static void scheduleLiveAlarm(Context app, AlarmManager am, long triggerAt, int requestCode) {
        try {
            Intent intent = new Intent(app, CourseReminderReceiver.class);
            intent.setAction(ACTION_LIVE_UPDATE);
            PendingIntent pi = PendingIntent.getBroadcast(app, requestCode, intent, pendingFlags());
            setExact(am, triggerAt, pi);
        } catch (Throwable th) {
            th.printStackTrace();
        }
    }

    private static void cancelLiveAlarms(Context app, AlarmManager am) {
        try {
            Intent intent = new Intent(app, CourseReminderReceiver.class);
            intent.setAction(ACTION_LIVE_UPDATE);
            for (int offset = 0; offset < SCHEDULE_DAYS; offset++) {
                for (int i = 0; i < MAX_COURSES_PER_DAY; i++) {
                    PendingIntent startPi = PendingIntent.getBroadcast(
                            app, liveRequestCode(offset, i, false), intent, pendingFlags());
                    am.cancel(startPi);
                    PendingIntent endPi = PendingIntent.getBroadcast(
                            app, liveRequestCode(offset, i, true), intent, pendingFlags());
                    am.cancel(endPi);
                }
            }
        } catch (Throwable th) {
            th.printStackTrace();
        }
    }

    private static int liveRequestCode(int dayOffset, int seq, boolean end) {
        return 700000 + dayOffset * 1000 + (end ? 500 : 0) + seq;
    }

    private static void cancelDailySummary(Context app, AlarmManager am) {
        try {
            Intent intent = new Intent(app, CourseReminderReceiver.class);
            intent.setAction(ACTION_DAILY_SUMMARY);
            PendingIntent pi = PendingIntent.getBroadcast(app, DAILY_SUMMARY_REQUEST, intent, pendingFlags());
            am.cancel(pi);
        } catch (Throwable th) {
            th.printStackTrace();
        }
    }

    private static int classRequestCode(int dayOffset, int slot, int seq) {
        return dayOffset * 10000 + slot * 100 + seq;
    }

    /** 课程开始前 10 分钟对应的时间点 */
    private static long triggerAt(Calendar date, String startTime) {
        return timeAt(date, startTime) - MINUTES_BEFORE * 60000L;
    }

    /** 指定日期的 HH:mm 对应时间戳 */
    static long timeAt(Calendar date, String hhmm) {
        Calendar cal = (Calendar) date.clone();
        String[] hm = hhmm.split(":");
        cal.set(Calendar.HOUR_OF_DAY, Integer.parseInt(hm[0]));
        cal.set(Calendar.MINUTE, Integer.parseInt(hm[1]));
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return cal.getTimeInMillis();
    }

    /** 指定日期所属教学周（按当前教学周向前滚动推算） */
    private static int weekForDate(Context context, Calendar date) {
        int base = CourseWidgetService.currentWeekNo(context);
        if (base <= 0) {
            return 0;
        }
        Calendar today = Calendar.getInstance();
        long baseMonday = mondayOf(today);
        int week = base + weekOffset(date, baseMonday);
        return week > 0 ? week : base;
    }

    private static int weekOffset(Calendar date, long baseMonday) {
        Calendar day = (Calendar) date.clone();
        day.set(Calendar.HOUR_OF_DAY, 0);
        day.set(Calendar.MINUTE, 0);
        day.set(Calendar.SECOND, 0);
        day.set(Calendar.MILLISECOND, 0);
        long days = (day.getTimeInMillis() - baseMonday) / DAY_MS;
        return (int) Math.floor(days / 7.0);
    }

    /** 该日期所在周的周一（当天 0 点）的时间戳 */
    private static long mondayOf(Calendar cal) {
        Calendar day = (Calendar) cal.clone();
        day.set(Calendar.HOUR_OF_DAY, 0);
        day.set(Calendar.MINUTE, 0);
        day.set(Calendar.SECOND, 0);
        day.set(Calendar.MILLISECOND, 0);
        int dow = day.get(Calendar.DAY_OF_WEEK);
        int offset = dow == Calendar.SUNDAY ? 6 : dow - 2;
        day.add(Calendar.DAY_OF_MONTH, -offset);
        return day.getTimeInMillis();
    }

    /** 周一为 0，周日为 6 */
    private static int dowIndex(Calendar cal) {
        int dow = cal.get(Calendar.DAY_OF_WEEK);
        return dow == Calendar.SUNDAY ? 6 : dow - 2;
    }

    private static void setExact(AlarmManager am, long triggerAt, PendingIntent pi) {
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                if (Build.VERSION.SDK_INT >= 23) {
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi);
                } else {
                    am.set(AlarmManager.RTC_WAKEUP, triggerAt, pi);
                }
                return;
            }
            if (Build.VERSION.SDK_INT >= 23) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi);
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pi);
            }
        } catch (Throwable th) {
            try {
                am.set(AlarmManager.RTC_WAKEUP, triggerAt, pi);
            } catch (Throwable ignore) {
            }
        }
    }

    private static int pendingFlags() {
        if (Build.VERSION.SDK_INT >= 23) {
            return PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.FLAG_UPDATE_CURRENT;
    }

    private static PendingIntent openAppIntent(Context app) {
        Intent intent = new Intent(app, SplashActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(app, 0, intent, pendingFlags());
    }

    private static void ensureChannels(Context app) {
        if (Build.VERSION.SDK_INT < 26) {
            return;
        }
        try {
            NotificationManager nm = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) {
                return;
            }
            NotificationChannel cls = new NotificationChannel(CHANNEL_CLASS, "上课提醒",
                    NotificationManager.IMPORTANCE_HIGH);
            cls.setDescription("每节课开始前 10 分钟提醒");
            NotificationChannel daily = new NotificationChannel(CHANNEL_DAILY, "每日课表提醒",
                    NotificationManager.IMPORTANCE_DEFAULT);
            daily.setDescription("每天晚上 22:00 提醒第二天的课程");
            NotificationChannel live = new NotificationChannel(CHANNEL_LIVE, "实时课程状态",
                    NotificationManager.IMPORTANCE_LOW);
            live.setDescription("在灵动胶囊 / 流体云等系统区域展示当前或下一节课（测试功能）");
            live.setShowBadge(false);
            nm.createNotificationChannel(cls);
            nm.createNotificationChannel(daily);
            nm.createNotificationChannel(live);
        } catch (Throwable th) {
            th.printStackTrace();
        }
    }
}
