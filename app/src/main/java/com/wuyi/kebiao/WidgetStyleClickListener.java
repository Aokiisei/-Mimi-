package com.wuyi.kebiao;

/**
 * 本文件职责：
 * 「更多 → 桌面小组件样式」对话框。
 * 提供 6 套配色与不透明度滑杆，修改后立即刷新桌面小组件。
 */

import android.content.Context;
import android.content.DialogInterface;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import com.wuyi.kebiao.widget.CourseWidgetService;
import java.util.ArrayList;
import java.util.List;

public class WidgetStyleClickListener implements View.OnClickListener {

    @Override
    public void onClick(View view) {
        Context context = view.getContext();
        final Context app = context.getApplicationContext();
        final float density = context.getResources().getDisplayMetrics().density;

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        int padH = (int) (20.0f * density + 0.5f);
        int padV = (int) (4.0f * density + 0.5f);
        content.setPadding(padH, padV, padH, padV);

        content.addView(MoreClickListener.groupHeader(context, "背景颜色", density));

        final List<View> dots = new ArrayList<>();
        LinearLayout row = null;
        for (int i = 0; i < CourseWidgetService.STYLES.length; i++) {
            if (i % 3 == 0) {
                row = new LinearLayout(context);
                row.setOrientation(LinearLayout.HORIZONTAL);
                content.addView(row);
            }
            final int index = i;
            LinearLayout cell = new LinearLayout(context);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
            int v = (int) (7.0f * density + 0.5f);
            cell.setPadding(0, v, 0, v);
            cell.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1.0f));

            View dot = new View(context);
            int size = (int) (38.0f * density + 0.5f);
            dot.setLayoutParams(new LinearLayout.LayoutParams(size, size));
            cell.addView(dot);
            dots.add(dot);

            TextView name = new TextView(context);
            name.setText(CourseWidgetService.STYLES[i].name);
            name.setTextSize(12.0f);
            name.setTextColor(0xFF87867F);
            int top = (int) (4.0f * density + 0.5f);
            name.setPadding(0, top, 0, 0);
            cell.addView(name);

            cell.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    CourseWidgetService.setStyleIndex(app, index);
                    paintSwatches(dots, app, density);
                }
            });
            if (row != null) {
                row.addView(cell);
            }
        }
        paintSwatches(dots, app, density);

        content.addView(MoreClickListener.divider(context, density));
        content.addView(MoreClickListener.groupHeader(context, "不透明度", density));

        final TextView alphaLabel = new TextView(context);
        alphaLabel.setTextSize(13.0f);
        alphaLabel.setTextColor(0xFF87867F);
        content.addView(alphaLabel);

        final SeekBar bar = new SeekBar(context);
        bar.setMax(100);
        int alpha = CourseWidgetService.getAlphaPercent(app);
        bar.setProgress(Math.max(0, Math.min(100, alpha)));
        alphaLabel.setText("当前 " + alpha + "%（0% 为全透明）");
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                alphaLabel.setText("当前 " + progress + "%（0% 为全透明）");
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                CourseWidgetService.setAlphaPercent(app, seekBar.getProgress());
                CourseWidgetService.refreshAll(app);
            }
        });
        content.addView(bar);

        TextView note = new TextView(context);
        note.setText("修改后桌面小组件会自动刷新，部分机型可能需要几秒才生效。");
        note.setTextSize(13.0f);
        note.setTextColor(0xFF87867F);
        int noteTop = (int) (8.0f * density + 0.5f);
        note.setPadding(0, noteTop, 0, 0);
        content.addView(note);

        ScrollView scrollView = new ScrollView(context);
        scrollView.addView(content);
        new MimiDialogBuilder(context).setTitle("桌面小组件样式").setView(scrollView)
                .setPositiveButton("完成", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        CourseWidgetService.setAlphaPercent(app, bar.getProgress());
                        CourseWidgetService.refreshAll(app);
                    }
                }).show();
    }

    /** 按当前选中项重绘色块（选中项加赤陶描边） */
    private static void paintSwatches(List<View> dots, Context context, float density) {
        int selected = CourseWidgetService.getStyleIndex(context);
        for (int i = 0; i < dots.size() && i < CourseWidgetService.STYLES.length; i++) {
            GradientDrawable shape = new GradientDrawable();
            shape.setShape(GradientDrawable.OVAL);
            shape.setColor(CourseWidgetService.STYLES[i].bg);
            int stroke = (int) ((i == selected ? 3.0f : 1.0f) * density + 0.5f);
            shape.setStroke(stroke, i == selected ? 0xFFC96442 : 0x33000000);
            dots.get(i).setBackground(shape);
        }
    }
}
