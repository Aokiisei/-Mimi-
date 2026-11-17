package com.wuyi.kebiao;

/**
 * 本文件职责：
 * 「权限与保活」自检。集中检查后台刷课/签到需要的权限（通知、电池优化、相机、定位），
 * 在「我的 → 更多」里展示状态并提供直达系统设置的入口；刷课开始时也用同一份检查结果提醒。
 */

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;

public final class XxtPermissionHelper {

    private XxtPermissionHelper() {
    }

    static boolean notifyEnabled(Context context) {
        try {
            return NotificationManagerCompat.from(context).areNotificationsEnabled();
        } catch (Exception e) {
            return true;
        }
    }

    static boolean batteryIgnored(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true;
        }
        try {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(context.getPackageName());
        } catch (Exception e) {
            return true;
        }
    }

    /** 系统省电模式开启时会限制后台网络与 CPU，刷课容易被掐断 */
    static boolean powerSaveOn(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return false;
        }
        try {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isPowerSaveMode();
        } catch (Exception e) {
            return false;
        }
    }

    static boolean cameraGranted(Context context) {
        try {
            return ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED;
        } catch (Exception e) {
            return true;
        }
    }

    static boolean locationGranted(Context context) {
        try {
            return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED
                    || ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED;
        } catch (Exception e) {
            return true;
        }
    }

    /** 后台刷课缺哪些保活项，空数组表示都齐了（相机、定位只用于签到，不参与刷课检查） */
    static JSONArray missing(Context context) {
        JSONArray arr = new JSONArray();
        try {
            if (!notifyEnabled(context)) {
                arr.put("通知权限");
            }
            if (!batteryIgnored(context) || powerSaveOn(context)) {
                arr.put("应用耗电管理（允许完全后台）");
            }
        } catch (Exception ignored) {
        }
        return arr;
    }

    /** 「我的 → 更多 → 权限与保活」弹窗：逐项显示状态，点一下直接去开启 */
    static void showDialog(final Context context) {
        float density = context.getResources().getDisplayMetrics().density;
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        int padH = (int) (20.0f * density + 0.5f);
        content.setPadding(padH, (int) (4.0f * density + 0.5f), padH, 0);

        TextView head = new TextView(context);
        head.setText("后台刷课、锁屏签到依赖下面这些权限，建议全部开启");
        head.setTextSize(13.0f);
        head.setTextColor(0xFF87867F);
        head.setPadding(0, (int) (6.0f * density + 0.5f), 0, (int) (8.0f * density + 0.5f));
        content.addView(head);

        addRow(context, content, "通知权限",
                notifyEnabled(context) ? "已开启" : "未开启，刷课进度通知无法显示",
                notifyEnabled(context), new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        openNotificationSettings(context);
                    }
                }, density);
        // 合并为「应用耗电管理」：点进去在耗电行为控制里选「无限制 / 允许完全后台」，
        // 不要选「智能优化」或「限制后台运行」。
        boolean powerOk = batteryIgnored(context) && !powerSaveOn(context);
        String powerStatus;
        if (!batteryIgnored(context)) {
            powerStatus = "未完全允许，请在耗电行为控制里选「无限制 / 允许完全后台」";
        } else if (powerSaveOn(context)) {
            powerStatus = "系统省电模式已开启，仍会限制后台，建议关闭";
        } else {
            powerStatus = "已允许完全后台";
        }
        addRow(context, content, "应用耗电管理",
                powerStatus, powerOk, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        openAppBatterySettings(context);
                    }
                }, density);
        addRow(context, content, "后台运行 / 自启动",
                "部分机型需在系统设置里手动允许「后台活动」「自启动」",
                true, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        openAppDetails(context);
                    }
                }, density);
        addRow(context, content, "相机权限",
                cameraGranted(context) ? "已授权" : "未授权，扫码与拍照签到无法使用",
                cameraGranted(context), new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        requestCamera(context);
                    }
                }, density);
        addRow(context, content, "定位权限",
                locationGranted(context) ? "已授权" : "未授权，位置签到无法取点",
                locationGranted(context), new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        requestLocation(context);
                    }
                }, density);

        new MimiDialogBuilder(context).setTitle("权限与保活").setView(content)
                .setPositiveButton("完成", (android.content.DialogInterface.OnClickListener) null).show();
    }

    private static void addRow(Context context, LinearLayout parent, String title, String status,
                               boolean good, View.OnClickListener listener, float density) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.VERTICAL);
        int v = (int) (11.0f * density + 0.5f);
        row.setPadding(0, v, 0, v);
        android.util.TypedValue tv = new android.util.TypedValue();
        context.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
        row.setBackgroundResource(tv.resourceId);
        row.setOnClickListener(listener);

        TextView name = new TextView(context);
        name.setText(title);
        name.setTextSize(16.0f);
        name.setTextColor(ThemeH.text);
        row.addView(name);

        TextView state = new TextView(context);
        state.setText(status);
        state.setTextSize(12.5f);
        state.setTextColor(good ? 0xFF87867F : 0xFFB4553A);
        state.setPadding(0, (int) (2.0f * density + 0.5f), 0, 0);
        row.addView(state);

        parent.addView(row);
        View line = new View(context);
        line.setLayoutParams(new LinearLayout.LayoutParams(-1, Math.max(1, (int) (density + 0.5f))));
        line.setBackgroundColor(0x14000000);
        parent.addView(line);
    }

    static void openNotificationSettings(Context context) {
        try {
            Intent intent;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
                intent.putExtra(Settings.EXTRA_APP_PACKAGE, context.getPackageName());
            } else {
                intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                intent.setData(Uri.parse("package:" + context.getPackageName()));
            }
            launch(context, intent);
        } catch (Exception e) {
            openAppDetails(context);
        }
    }

    /**
     * 打开本应用的耗电管理页：在「耗电行为控制 / 电池」里手动选「无限制 / 允许完全后台行为」。
     * 优先进应用详情（各家 ROM 的耗电管理都在里面），失败再退到系统电池优化列表。
     */
    static void openAppBatterySettings(Context context) {
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + context.getPackageName()));
            launch(context, intent);
        } catch (Exception e) {
            try {
                launch(context, new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Exception e2) {
                try {
                    launch(context, new Intent(Intent.ACTION_POWER_USAGE_SUMMARY));
                } catch (Exception ignored) {
                    Toast.makeText(context, "打不开耗电管理，请到系统设置里手动允许后台", Toast.LENGTH_SHORT).show();
                }
            }
        }
    }

    static void openAppDetails(Context context) {
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + context.getPackageName()));
            launch(context, intent);
        } catch (Exception e) {
            Toast.makeText(context, "打不开系统设置", Toast.LENGTH_SHORT).show();
        }
    }

    private static void requestCamera(Context context) {
        if (cameraGranted(context)) {
            Toast.makeText(context, "相机权限已开启", Toast.LENGTH_SHORT).show();
            return;
        }
        if (context instanceof Activity) {
            ActivityCompat.requestPermissions((Activity) context,
                    new String[]{Manifest.permission.CAMERA}, 7601);
        } else {
            openAppDetails(context);
        }
    }

    private static void requestLocation(Context context) {
        if (locationGranted(context)) {
            Toast.makeText(context, "定位权限已开启", Toast.LENGTH_SHORT).show();
            return;
        }
        if (context instanceof Activity) {
            ActivityCompat.requestPermissions((Activity) context,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION}, 7602);
        } else {
            openAppDetails(context);
        }
    }

    private static void launch(Context context, Intent intent) {
        if (!(context instanceof Activity)) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        context.startActivity(intent);
    }
}
