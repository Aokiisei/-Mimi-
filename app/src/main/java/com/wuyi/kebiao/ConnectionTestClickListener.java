package com.wuyi.kebiao;

import android.view.View;
import android.widget.Toast;

/* JADX INFO: loaded from: classes.dex */
public class ConnectionTestClickListener implements View.OnClickListener {
    @Override // android.view.View.OnClickListener
    public void onClick(View view) {
        try {
            ConnectionTester.test(view.getContext());
        } catch (Throwable th) {
            Toast.makeText(view.getContext(), th.toString(), 0).show();
        }
    }
}
