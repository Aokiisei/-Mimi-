package com.wuyi.kebiao;

/**
 * 本文件职责：
 * 「课表多页卡片自动轮播」开关：把状态写入 app_theme 的 auto_carousel，并同步 ThemeH.autoCarousel。
 * 开启后，同一格子内多张卡片每 5 秒自动切换；关闭则需手动点击翻页。
 */

import android.content.Context;
import android.content.SharedPreferences;
import android.widget.CompoundButton;
import android.widget.Toast;

public class CarouselSwitchListener implements CompoundButton.OnCheckedChangeListener {
    @Override
    public void onCheckedChanged(CompoundButton compoundButton, boolean z) {
        Context context = compoundButton.getContext();
        SharedPreferences.Editor editorEdit = context.getSharedPreferences("app_theme", 0).edit();
        String str = z ? "1" : "0";
        editorEdit.putString("auto_carousel", str).apply();
        ThemeH.autoCarousel = str;
        Toast.makeText(context, z ? "已开启自动轮播，5 秒切换一次" : "已关闭自动轮播", 0).show();
    }
}
