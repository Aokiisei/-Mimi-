package com.wuyi.kebiao;

/**
 * 本文件职责：
 * 「液态玻璃效果」开关：把状态写入 app_theme 的 glass_ui，并同步 ThemeH.glass。
 */

import android.content.Context;
import android.content.SharedPreferences;
import android.widget.CompoundButton;
import android.widget.Toast;

public class GlassSwitchListener implements CompoundButton.OnCheckedChangeListener {
    @Override
    public void onCheckedChanged(CompoundButton compoundButton, boolean z) {
        Context context = compoundButton.getContext();
        SharedPreferences.Editor editorEdit = context.getSharedPreferences("app_theme", 0).edit();
        String str = z ? "1" : "0";
        editorEdit.putString("glass_ui", str).apply();
        ThemeH.glass = str;
        Toast.makeText(context, "已保存，切换页面后生效", 0).show();
    }
}
