package com.wuyi.kebiao;

/**
 * 本文件职责：
 * 反馈对话框「提交」按钮：校验内容非空后启动 FeedbackSender。
 */

import android.content.Context;
import android.content.DialogInterface;
import android.widget.EditText;
import android.widget.Toast;

public class FeedbackSubmitListener implements DialogInterface.OnClickListener {
    private final Context ctx;
    private final EditText et;

    public FeedbackSubmitListener(Context context, EditText editText) {
        this.ctx = context;
        this.et = editText;
    }

    @Override
    public void onClick(DialogInterface dialogInterface, int i) {
        try {
            String strTrim = String.valueOf(this.et.getText()).trim();
            if (strTrim.length() == 0) {
                Toast.makeText(this.ctx, "请先输入反馈内容", 0).show();
            } else {
                new Thread(new FeedbackSender(this.ctx, strTrim)).start();
            }
        } catch (Throwable th) {
            Context context = this.ctx;
            CrashLogger.log(context, "FeedbackSubmit", th);
            Toast.makeText(context, th.toString(), 1).show();
        }
    }
}
