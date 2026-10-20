package com.wuyi.kebiao;

/**
 * 本文件职责：
 * 「更多」对话框：分组拼装设置开关、工具入口与帮助支持入口。
 * 内容较多时用 ScrollView 包裹，保证小屏设备可以上下滑动查看全部条目。
 */

import android.content.Context;
import android.content.DialogInterface;
import android.util.TypedValue;
import android.view.View;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;

public class MoreClickListener implements View.OnClickListener {
    private final ProfileFragment fragment;

    public MoreClickListener(ProfileFragment fragment) {
        this.fragment = fragment;
    }

    /** 组标题：灰色小字，如「设置」「工具」 */
    private static TextView groupHeader(Context context, String text, float density) {
        TextView header = new TextView(context);
        header.setText(text);
        header.setTextSize(13.0f);
        header.setTextColor(0xFF87867F);
        int top = (int) (14.0f * density + 0.5f);
        int bottom = (int) (2.0f * density + 0.5f);
        header.setPadding(0, top, 0, bottom);
        return header;
    }

    /** 可点击的文字入口行：带按压反馈 */
    private static TextView entryRow(Context context, String text, float density, View.OnClickListener listener) {
        TextView row = new TextView(context);
        row.setText(text);
        row.setTextSize(16.0f);
        row.setTextColor(ThemeH.text);
        int v = (int) (13.0f * density + 0.5f);
        int h = (int) (4.0f * density + 0.5f);
        row.setPadding(h, v, h, v);
        TypedValue tv = new TypedValue();
        context.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
        row.setBackgroundResource(tv.resourceId);
        row.setOnClickListener(listener);
        return row;
    }

    /** 细分隔线 */
    private static View divider(Context context, float density) {
        View line = new View(context);
        line.setLayoutParams(new LinearLayout.LayoutParams(-1, Math.max(1, (int) (density + 0.5f))));
        line.setBackgroundColor(0x14000000);
        return line;
    }

    /** 开关行（标题 + CheckBox）及其下方说明文字 */
    private static void addSwitch(Context context, LinearLayout parent, String title, String sub,
                                  boolean checked, CompoundButton.OnCheckedChangeListener listener, float density) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(16);
        int v = (int) (10.0f * density + 0.5f);
        row.setPadding(0, v, 0, 0);
        TextView label = new TextView(context);
        label.setText(title);
        label.setTextSize(16.0f);
        label.setTextColor(ThemeH.text);
        label.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1.0f));
        row.addView(label);
        CheckBox checkBox = new CheckBox(context);
        checkBox.setChecked(checked);
        checkBox.setOnCheckedChangeListener(listener);
        row.addView(checkBox);
        parent.addView(row);
        TextView subText = new TextView(context);
        subText.setText(sub);
        subText.setTextSize(13.0f);
        subText.setTextColor(0xFF87867F);
        subText.setPadding(0, 0, 0, (int) (6.0f * density + 0.5f));
        parent.addView(subText);
    }

    @Override
    public void onClick(View view) {
        try {
            final Context context = view.getContext();
            float density = context.getResources().getDisplayMetrics().density;
            LinearLayout content = new LinearLayout(context);
            content.setOrientation(LinearLayout.VERTICAL);
            int padH = (int) (20.0f * density + 0.5f);
            int padV = (int) (4.0f * density + 0.5f);
            content.setPadding(padH, padV, padH, padV);

            // ===== 设置 =====
            content.addView(groupHeader(context, "设置", density));
            addSwitch(context, content, "液态玻璃效果", "开启后界面展示液态玻璃效果，关闭可提升流畅度",
                    "1".equals(ThemeH.glass), new GlassSwitchListener(), density);
            addSwitch(context, content, "课表自动轮播", "开启后同格多张卡片每 5 秒自动切换",
                    "1".equals(ThemeH.autoCarousel), new CarouselSwitchListener(), density);
            content.addView(divider(context, density));

            // ===== 工具 =====
            content.addView(groupHeader(context, "工具", density));
            content.addView(entryRow(context, "测试教务系统连接", density, new ConnectionTestClickListener()));
            content.addView(entryRow(context, "日程自动清理", density, new ScheduleCleanupClickListener()));
            content.addView(divider(context, density));

            // ===== 帮助与支持 =====
            content.addView(groupHeader(context, "帮助与支持", density));
            content.addView(entryRow(context, "使用说明", density, new UsageGuideClickListener()));
            content.addView(entryRow(context, "问题反馈", density, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (fragment != null) {
                        fragment.showFeedbackDialog();
                    }
                }
            }));
            content.addView(entryRow(context, "赞助支持", density, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (fragment != null) {
                        fragment.showSponsorDialog();
                    }
                }
            }));

            // 内容超出弹窗最大高度时允许上下滑动
            ScrollView scrollView = new ScrollView(context);
            scrollView.addView(content);
            scrollView.setVerticalScrollBarEnabled(true);
            new MimiDialogBuilder(context).setTitle("更多").setView(scrollView)
                    .setPositiveButton("完成", (DialogInterface.OnClickListener) null).show();
        } catch (Throwable th) {
            Context context2 = view.getContext();
            CrashLogger.log(context2, "MoreClickListener", th);
            Toast.makeText(context2, th.toString(), 1).show();
        }
    }
}
