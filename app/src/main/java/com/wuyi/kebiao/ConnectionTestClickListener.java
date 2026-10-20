package com.wuyi.kebiao;

/**
 * 本文件职责：
 * 「测试教务系统连接」的点击入口，内部转调 ConnectionTester。
 */

import android.view.View;
import android.widget.Toast;

public class ConnectionTestClickListener implements View.OnClickListener {
    @Override
    public void onClick(View view) {
        try {
            ConnectionTester.test(view.getContext());
        } catch (Throwable th) {
            Toast.makeText(view.getContext(), th.toString(), 0).show();
        }
    }
}
