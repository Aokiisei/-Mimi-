package com.wuyi.kebiao;

/**
 * 本文件职责：
 * 统一字体的对话框构造器（androidx AlertDialog）。
 * 弹窗显示后把标题/正文/按钮全部换成与页面一致的衬线字体。
 */

import android.content.Context;
import androidx.appcompat.app.AlertDialog;

public class MimiDialogBuilder extends AlertDialog.Builder {

    public MimiDialogBuilder(Context context) {
        super(context);
    }

    public MimiDialogBuilder(Context context, int themeResId) {
        super(context, themeResId);
    }

    @Override
    public AlertDialog show() {
        AlertDialog dialog = super.show();
        if (dialog.getWindow() != null) {
            ThemeH.applySerifFont(dialog.getWindow().getDecorView());
        }
        if (dialog.getButton(AlertDialog.BUTTON_POSITIVE) != null) {
            ThemeH.applySerifFont(dialog.getButton(AlertDialog.BUTTON_POSITIVE));
        }
        if (dialog.getButton(AlertDialog.BUTTON_NEGATIVE) != null) {
            ThemeH.applySerifFont(dialog.getButton(AlertDialog.BUTTON_NEGATIVE));
        }
        if (dialog.getButton(AlertDialog.BUTTON_NEUTRAL) != null) {
            ThemeH.applySerifFont(dialog.getButton(AlertDialog.BUTTON_NEUTRAL));
        }
        return dialog;
    }
}
