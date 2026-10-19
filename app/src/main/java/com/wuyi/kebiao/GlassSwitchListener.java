package com.wuyi.kebiao;

import android.content.Context;
import android.content.SharedPreferences;
import android.widget.CompoundButton;
import android.widget.Toast;

/* JADX INFO: loaded from: classes.dex */
public class GlassSwitchListener implements CompoundButton.OnCheckedChangeListener {
    @Override // android.widget.CompoundButton.OnCheckedChangeListener
    public void onCheckedChanged(CompoundButton compoundButton, boolean z) {
        Context context = compoundButton.getContext();
        SharedPreferences.Editor editorEdit = context.getSharedPreferences("app_theme", 0).edit();
        String str = z ? "1" : "0";
        editorEdit.putString("glass_ui", str).apply();
        ThemeH.glass = str;
        Toast.makeText(context, "已保存，切换页面后生效", 0).show();
    }
}
