package com.wuyi.kebiao.reminder;

/**
 * 本文件职责：
 * 上课提醒/每日课表提醒的广播接收器。
 * 收到闹钟后展示通知；开机、改时间、改时区、App 更新后重新排布提醒。
 */

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class CourseReminderReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) {
            return;
        }
        String action = intent.getAction();
        if (CourseReminderScheduler.ACTION_CLASS_REMINDER.equals(action)) {
            CourseReminderScheduler.showClassReminder(context, intent);
        } else if (CourseReminderScheduler.ACTION_LIVE_UPDATE.equals(action)) {
            // 上课/下课时刻刷新灵动胶囊状态
            CourseLiveStatus.refresh(context);
        } else if (CourseReminderScheduler.ACTION_DAILY_SUMMARY.equals(action)) {
            CourseReminderScheduler.showDailySummary(context);
            // 汇总提醒触发后重新排布，滚动覆盖后续几天并预约下一次 22:00
            CourseReminderScheduler.reschedule(context);
        } else {
            CourseReminderScheduler.reschedule(context);
        }
    }
}
