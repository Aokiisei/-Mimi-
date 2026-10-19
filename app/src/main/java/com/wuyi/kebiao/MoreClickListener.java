package com.wuyi.kebiao;

import android.content.Context;
import android.content.DialogInterface;
import android.view.View;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;

/* JADX INFO: loaded from: classes.dex */
public class MoreClickListener implements View.OnClickListener {
    @Override // android.view.View.OnClickListener
    public void onClick(View view) {
        try {
            Context context = view.getContext();
            float f = context.getResources().getDisplayMetrics().density;
            LinearLayout linearLayout = new LinearLayout(context);
            linearLayout.setOrientation(1);
            int i = (int) ((16.0f * f) + 0.5f);
            int i2 = (int) ((8.0f * f) + 0.5f);
            linearLayout.setPadding(i, i2, i, i2);
            LinearLayout linearLayout2 = new LinearLayout(context);
            linearLayout2.setOrientation(0);
            linearLayout2.setGravity(16);
            int i3 = (int) ((12.0f * f) + 0.5f);
            linearLayout2.setPadding(0, i3, 0, i3);
            TextView textView = new TextView(context);
            textView.setText("液态玻璃效果");
            textView.setTextSize(16.0f);
            textView.setTextColor(ThemeH.text);
            textView.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1.0f));
            linearLayout2.addView(textView);
            CheckBox checkBox = new CheckBox(context);
            checkBox.setChecked("1".equals(ThemeH.glass));
            checkBox.setOnCheckedChangeListener(new GlassSwitchListener());
            linearLayout2.addView(checkBox);
            TextView textView2 = new TextView(context);
            textView2.setText("开启后界面展示液态玻璃效果，关闭可提升流畅度");
            textView2.setTextSize(13.0f);
            textView2.setTextColor(-6710887);
            textView2.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
            linearLayout.addView(textView2);
            linearLayout.addView(linearLayout2);
            View view2 = new View(context);
            view2.setLayoutParams(new LinearLayout.LayoutParams(-1, 1));
            view2.setBackgroundColor(335544320);
            linearLayout.addView(view2);
            TextView textView3 = new TextView(context);
            textView3.setText("测试教务系统连接");
            textView3.setTextSize(16.0f);
            textView3.setTextColor(ThemeH.text);
            int i4 = (int) ((12.0f * f) + 0.5f);
            textView3.setPadding(0, i4, 0, i4);
            textView3.setOnClickListener(new ConnectionTestClickListener());
            linearLayout.addView(textView3);
            View view3 = new View(context);
            view3.setLayoutParams(new LinearLayout.LayoutParams(-1, 1));
            view3.setBackgroundColor(335544320);
            linearLayout.addView(view3);
            TextView textView4 = new TextView(context);
            textView4.setText("使用说明");
            textView4.setTextSize(16.0f);
            textView4.setTextColor(ThemeH.text);
            int i5 = (int) ((12.0f * f) + 0.5f);
            textView4.setPadding(0, i5, 0, i5);
            textView4.setOnClickListener(new UsageGuideClickListener());
            linearLayout.addView(textView4);
            new AlertDialog.Builder(context).setTitle("更多").setView(linearLayout).setPositiveButton("完成", (DialogInterface.OnClickListener) null).show();
        } catch (Throwable th) {
            Context context2 = view.getContext();
            CrashLogger.log(context2, "MoreClickListener", th);
            Toast.makeText(context2, th.toString(), 1).show();
        }
    }
}
