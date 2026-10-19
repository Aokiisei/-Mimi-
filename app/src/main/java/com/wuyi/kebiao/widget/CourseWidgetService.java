package com.wuyi.kebiao.widget;

import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.widget.RemoteViews;
import android.widget.RemoteViewsService;
import com.wuyi.kebiao.R;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 课表桌面小组件。
 * 数据来源：App 抓取课表成功后，由 HomeFragment.saveWidgetData 把前端解析好的
 * 课程 JSON（含 dayOfWeek 中文星期名、name、sections、location、teacher 等）原样写入 SharedPreferences。
 * 这里只在渲染时按"今天"过滤，因此隔天/定时刷新后内容依然正确。
 */
public class CourseWidgetService extends RemoteViewsService {

    static final String PREFS_NAME = "mimi_widget";
    /** 全量课程 JSON（原样保存前端数据） */
    static final String KEY_COURSES = "all_courses";
    /** 当前教学周 */
    static final String KEY_WEEK_NO = "week_no";
    /** 学期总周数 */
    static final String KEY_TOTAL_WEEK = "total_week";
    public static final int DEFAULT_TOTAL_WEEK = 18;

    private static final String[] DOW_NAMES = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};

    /** 今天中文星期名，如"周一" */
    static String todayDowName() {
        int dow = Calendar.getInstance().get(Calendar.DAY_OF_WEEK);
        int n = dow == Calendar.SUNDAY ? 7 : dow - 1;
        return DOW_NAMES[n - 1];
    }

    /** 保存全量课程 JSON */
    public static void saveCourses(Context context, String coursesJson) {
        if (context == null) {
            return;
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_COURSES, coursesJson).apply();
    }

    /** 保存当前教学周 */
    public static void saveWeekNo(Context context, int weekNo) {
        if (context == null || weekNo <= 0) {
            return;
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putInt(KEY_WEEK_NO, weekNo).apply();
    }

    /** 保存学期总周数 */
    public static void saveTotalWeek(Context context, int totalWeek) {
        if (context == null || totalWeek <= 0) {
            return;
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putInt(KEY_TOTAL_WEEK, totalWeek).apply();
    }

    /** 刷新所有已添加到桌面的小组件实例 */
    public static void refreshAll(Context context) {
        if (context == null) {
            return;
        }
        try {
            AppWidgetManager mgr = AppWidgetManager.getInstance(context);
            int[] ids = mgr.getAppWidgetIds(new ComponentName(context, CourseWidgetProvider.class));
            if (ids == null || ids.length == 0) {
                return;
            }
            for (int id : ids) {
                updateWidget(context, mgr, id);
            }
        } catch (Throwable th) {
            th.printStackTrace();
        }
    }

    public static void updateWidget(Context context, AppWidgetManager mgr, int appWidgetId) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String coursesJson = prefs.getString(KEY_COURSES, "");
        int weekNo = prefs.getInt(KEY_WEEK_NO, 0);
        int totalWeek = prefs.getInt(KEY_TOTAL_WEEK, DEFAULT_TOTAL_WEEK);
        if (totalWeek < weekNo) {
            totalWeek = Math.max(DEFAULT_TOTAL_WEEK, weekNo);
        }
        boolean hasData = coursesJson != null && coursesJson.length() > 2;

        Calendar cal = Calendar.getInstance();
        String dateInfo = (cal.get(Calendar.MONTH) + 1) + "月" + cal.get(Calendar.DAY_OF_MONTH)
                + "日  今天" + todayDowName();
        String weekInfo = weekNo > 0 ? "第" + weekNo + "/" + totalWeek + "周" : "";

        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_course);
        views.setTextViewText(R.id.widget_date_info, dateInfo);
        views.setTextViewText(R.id.widget_week_info, weekInfo);
        views.setTextViewText(R.id.widget_empty,
                hasData ? "今天没有课程" : "请先打开 Mimi课表 同步一次课表");

        Intent intent = new Intent(context, CourseWidgetService.class);
        intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId);
        // setData 让每个实例的 Intent 唯一，避免 RemoteViewsService 实例复用错乱
        intent.setData(Uri.parse(intent.toUri(Intent.URI_INTENT_SCHEME)));
        views.setRemoteAdapter(R.id.widget_course_list, intent);
        views.setEmptyView(R.id.widget_course_list, R.id.widget_empty);

        mgr.updateAppWidget(appWidgetId, views);
        mgr.notifyAppWidgetViewDataChanged(appWidgetId, R.id.widget_course_list);
    }

    @Override
    public RemoteViewsFactory onGetViewFactory(Intent intent) {
        return new CourseRemoteViewsFactory(getApplicationContext());
    }

    static class CourseRemoteViewsFactory implements RemoteViewsFactory {

        private final Context context;
        private final List<JSONObject> todayCourses = new ArrayList<>();

        CourseRemoteViewsFactory(Context context) {
            this.context = context;
        }

        @Override
        public void onCreate() {
            load();
        }

        @Override
        public void onDataSetChanged() {
            load();
        }

        private void load() {
            todayCourses.clear();
            try {
                SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                String today = todayDowName();
                JSONArray arr = new JSONArray(prefs.getString(KEY_COURSES, "[]"));
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject c = arr.optJSONObject(i);
                    if (c == null) {
                        continue;
                    }
                    // 前端课程对象的星期字段为中文名（周一~周日）
                    if (!today.equals(c.optString("dayOfWeek", "").trim())) {
                        continue;
                    }
                    todayCourses.add(c);
                }
                Collections.sort(todayCourses, new Comparator<JSONObject>() {
                    @Override
                    public int compare(JSONObject a, JSONObject b) {
                        return slotOf(a) - slotOf(b);
                    }
                });
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        /** 上课节次序号：取 sections（如 "03~04小节"）里的首个数字，无则退回 timeSlot 映射 */
        private static int slotOf(JSONObject c) {
            String s = c.optString("sections", "");
            if (s.isEmpty()) {
                s = c.optString("timeSlot", "");
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < s.length(); i++) {
                char ch = s.charAt(i);
                if (ch >= '0' && ch <= '9') {
                    sb.append(ch);
                } else if (sb.length() > 0) {
                    break;
                }
            }
            if (sb.length() > 0) {
                try {
                    return Integer.parseInt(sb.toString());
                } catch (Exception ignore) {
                }
            }
            // 中文节次名兜底
            switch (s) {
                case "第一二节": return 1;
                case "第三四节": return 3;
                case "第五六节": return 5;
                case "第七八节": return 7;
                case "第九十节": return 9;
                default: return 99;
            }
        }

        @Override
        public RemoteViews getViewAt(int position) {
            if (position < 0 || position >= todayCourses.size()) {
                return null;
            }
            JSONObject c = todayCourses.get(position);
            RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_course_item);
            String time = c.optString("sections", "");
            if (time.isEmpty()) {
                time = c.optString("timeSlot", "");
            }
            views.setTextViewText(R.id.widget_item_name, c.optString("name", ""));
            views.setTextViewText(R.id.widget_item_time, time);
            views.setTextViewText(R.id.widget_item_location, c.optString("location", ""));
            views.setTextViewText(R.id.widget_item_teacher, c.optString("teacher", ""));
            return views;
        }

        @Override
        public int getCount() {
            return todayCourses.size();
        }

        @Override
        public RemoteViews getLoadingView() {
            return null;
        }

        @Override
        public int getViewTypeCount() {
            return 1;
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public boolean hasStableIds() {
            return true;
        }

        @Override
        public void onDestroy() {
            todayCourses.clear();
        }
    }
}
