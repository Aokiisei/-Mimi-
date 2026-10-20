package com.wuyi.kebiao;

/**
 * 本文件职责：
 * 统一字体的系统对话框构造器（android.app AlertDialog）。
 * 用于 MainActivity 与更新提示等系统弹窗。
 */

import android.content.Context;

public class MimiSystemDialogBuilder extends android.app.AlertDialog.Builder {

    public MimiSystemDialogBuilder(Context context) {
        super(context);
    }

    public MimiSystemDialogBuilder(Context context, int themeResId) {
        super(context, themeResId);
    }

    @Override
    public android.app.AlertDialog show() {
        android.app.AlertDialog dialog = super.show();
        if (dialog.getWindow() != null) {
            ThemeH.applySerifFont(dialog.getWindow().getDecorView());
        }
        return dialog;
    }
}
