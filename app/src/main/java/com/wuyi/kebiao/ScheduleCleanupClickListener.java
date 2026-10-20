package com.wuyi.kebiao;

/**
 * 本文件职责：
 * 弹出「日程自动清理」设置对话框。
 * 单选：永不自动清理 / 每 7 天自动清理 / 每 30 天自动清理；
 * 另提供「立即清理过期日程」手动清理入口。
 */

import android.content.Context;
import android.content.DialogInterface;
import android.view.View;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;

public class ScheduleCleanupClickListener implements View.OnClickListener {
    private static final String[] POLICIES = { "never", "7", "30" };
    private static final String[] LABELS = { "永不自动清理", "每 7 天自动清理", "每 30 天自动清理" };

    @Override
    public void onClick(View view) {
        try {
            final Context context = view.getContext();
            String cur = ScheduleStore.getPolicy(context);
            int checked = 0;
            for (int i = 0; i < POLICIES.length; i++) {
                if (POLICIES[i].equals(cur)) {
                    checked = i;
                    break;
                }
            }
            new MimiDialogBuilder(context)
                    .setTitle("日程自动清理")
                    .setSingleChoiceItems(LABELS, checked, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialogInterface, int i) {
                            ScheduleStore.setPolicy(context, POLICIES[i]);
                            Toast.makeText(context, "已设置为：" + LABELS[i], 0).show();
                            dialogInterface.dismiss();
                        }
                    })
                    .setNeutralButton("立即清理过期日程", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialogInterface, int i) {
                            int n = ScheduleStore.cleanupExpired(context);
                            Toast.makeText(context, n > 0 ? "已清理 " + n + " 条过期日程" : "没有过期日程", 0).show();
                        }
                    })
                    .setNegativeButton("取消", (DialogInterface.OnClickListener) null)
                    .show();
        } catch (Throwable th) {
            Context context2 = view.getContext();
            CrashLogger.log(context2, "ScheduleCleanupClickListener", th);
            Toast.makeText(context2, th.toString(), 1).show();
        }
    }
}
