package com.wuyi.kebiao;

/**
 * 本文件职责：
 * 「更多 → 通知设置」对话框。
 * 提供「课前 10 分钟提醒」与「每晚 22:00 明日课表」两个开关，
 * 开关状态存入课表提醒配置并立即重排闹钟；Android 13+ 打开时按需申请通知权限。
 */

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.TypedValue;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import com.wuyi.kebiao.reminder.CourseReminderScheduler;

public class NotificationSettingsClickListener implements View.OnClickListener {
    /** 通知权限请求码 */
    private static final int REQUEST_NOTIFICATION = 1002;

    private final ProfileFragment fragment;

    public NotificationSettingsClickListener(ProfileFragment fragment) {
        this.fragment = fragment;
    }

    @Override
    public void onClick(View view) {
        Context context = view.getContext();
        float density = context.getResources().getDisplayMetrics().density;
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        int padH = (int) (20.0f * density + 0.5f);
        int padV = (int) (4.0f * density + 0.5f);
        content.setPadding(padH, padV, padH, padV);

        final Context appContext = context.getApplicationContext();
        MoreClickListener.addSwitch(context, content, "课前 10 分钟提醒",
                "每节课开始前 10 分钟通知你准备去上课",
                CourseReminderScheduler.isClassReminderEnabled(appContext),
                new CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                        CourseReminderScheduler.setClassReminderEnabled(appContext, isChecked);
                        Toast.makeText(appContext, isChecked ? "已开启课前 10 分钟提醒" : "已关闭课前 10 分钟提醒",
                                Toast.LENGTH_SHORT).show();
                        if (isChecked) {
                            requestNotificationPermission();
                        }
                    }
                }, density);

        MoreClickListener.addSwitch(context, content, "每晚 22:00 明日课表",
                "每天晚上 10 点通知第二天的课程安排",
                CourseReminderScheduler.isDailySummaryEnabled(appContext),
                new CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                        CourseReminderScheduler.setDailySummaryEnabled(appContext, isChecked);
                        Toast.makeText(appContext, isChecked ? "已开启每晚 22:00 明日课表提醒" : "已关闭每晚 22:00 明日课表提醒",
                                Toast.LENGTH_SHORT).show();
                        if (isChecked) {
                            requestNotificationPermission();
                        }
                    }
                }, density);

        MoreClickListener.addSwitch(context, content, "灵动胶囊 / 流体云（测试）",
                "在支持的 vivo / OPPO 等机型上把当前或下一节课显示为实时胶囊；实际是否显示由系统决定",
                CourseReminderScheduler.isLiveStatusEnabled(appContext),
                new CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                        CourseReminderScheduler.setLiveStatusEnabled(appContext, isChecked);
                        Toast.makeText(appContext, isChecked ? "已开启灵动胶囊（测试功能）" : "已关闭灵动胶囊",
                                Toast.LENGTH_SHORT).show();
                        if (isChecked) {
                            requestNotificationPermission();
                        }
                    }
                }, density);

        if (!hasNotificationPermission()) {
            TextView note = new TextView(context);
            note.setText("通知权限未开启，需在系统设置中允许通知后才能收到提醒");
            note.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.0f);
            note.setTextColor(0xFF87867F);
            int top = (int) (8.0f * density + 0.5f);
            note.setPadding(0, top, 0, 0);
            content.addView(note);
        }

        ScrollView scrollView = new ScrollView(context);
        scrollView.addView(content);
        new MimiDialogBuilder(context).setTitle("通知设置").setView(scrollView)
                .setPositiveButton("完成", (DialogInterface.OnClickListener) null).show();

        // 打开设置时主动申请一次通知权限，避免开关默认开启却收不到提醒
        requestNotificationPermission();
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33 || fragment == null) {
            return;
        }
        Activity activity = fragment.getActivity();
        if (activity == null) {
            return;
        }
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(activity,
                    new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATION);
        }
    }

    private boolean hasNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) {
            return true;
        }
        Activity activity = fragment == null ? null : fragment.getActivity();
        return activity != null && ContextCompat.checkSelfPermission(activity,
                Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }
}
