package com.wuyi.kebiao.widget;

/**
 * 本文件职责：
 * 桌面小组件的生命周期入口（AppWidgetProvider）。
 * 收到系统更新广播或尺寸变化时，逐个转调 CourseWidgetService 重绘。
 */

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.os.Bundle;

public class CourseWidgetProvider extends AppWidgetProvider {

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        if (appWidgetIds == null) {
            return;
        }
        for (int appWidgetId : appWidgetIds) {
            CourseWidgetService.updateWidget(context, appWidgetManager, appWidgetId);
        }
    }

    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager appWidgetManager,
                                          int appWidgetId, Bundle newOptions) {
        CourseWidgetService.updateWidget(context, appWidgetManager, appWidgetId);
    }
}
