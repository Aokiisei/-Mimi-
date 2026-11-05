package com.wuyi.kebiao.widget;

/**
 * 本文件职责：
 * 桌面小组件的数据与渲染实现。
 * 从 SharedPreferences 读出 App 保存的课表 JSON，
 * 按当天星期过滤后填充 RemoteViews 列表，并计算当前教学周。
 */

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 课表桌面小组件。
 * 数据来源：App 抓取课表成功后，由 HomeFragment.saveWidgetData 把前端解析好的
 * 课程 JSON（含 dayOfWeek 中文星期名、name、sections、location、teacher 等）原样写入 SharedPreferences。
 * 这里只在渲染时按"今天"过滤，因此隔天/定时刷新后内容依然正确。
 */
public class CourseWidgetService extends RemoteViewsService {

    public static final String PREFS_NAME = "mimi_widget";
    /** 全量课程 JSON（原样保存前端数据） */
    public static final String KEY_COURSES = "all_courses";
    /** 当前教学周 */
    public static final String KEY_WEEK_NO = "week_no";
    /** 记录教学周时的系统时间，用于在没有打开 App 时把周次自动往后滚动 */
    public static final String KEY_WEEK_SAVED_AT = "week_saved_at";
    /** 学期总周数 */
    public static final String KEY_TOTAL_WEEK = "total_week";
    public static final int DEFAULT_TOTAL_WEEK = 18;

    private static final String[] DOW_NAMES = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};

    /** 是否已过晚上 9 点：过后小组件自动展示第二天课程 */
    static boolean isAfterNinePm() {
        return Calendar.getInstance().get(Calendar.HOUR_OF_DAY) >= 21;
    }

    /** 小组件展示日的中文星期名：晚 9 点前为今天，之后为明天 */
    static String displayDowName() {
        Calendar cal = Calendar.getInstance();
        if (isAfterNinePm()) {
            cal.add(Calendar.DAY_OF_MONTH, 1);
        }
        int dow = cal.get(Calendar.DAY_OF_WEEK);
        int n = dow == Calendar.SUNDAY ? 7 : dow - 1;
        return DOW_NAMES[n - 1];
    }

    /** 小组件展示日对应的教学周：周日晚上展示明天（下周一）时，周次加一 */
    static int displayWeekNo(int weekNo) {
        if (weekNo <= 0 || !isAfterNinePm()) {
            return weekNo;
        }
        return Calendar.getInstance().get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY ? weekNo + 1 : weekNo;
    }

    /** 周次片段：\\d{1,2}-\\d{1,2}周 / \\d{1,2}周，可带「单/双」标记 */
    private static final Pattern WEEK_SEG_PATTERN = Pattern.compile(
            "(\\d{1,2})\\s*[-~—－至]\\s*(\\d{1,2})\\s*周\\s*[（(]?\\s*([单双])?"
                    + "|(\\d{1,2})\\s*周\\s*[（(]?\\s*([单双])?");

    /** 解析周次为 [起始, 结束, 单双(0 无 / 1 单 / 2 双)]，无法解析返回 null */
    private static List<int[]> parseWeekSegs(String wr) {
        if (wr == null || wr.isEmpty()) {
            return null;
        }
        List<int[]> segs = new ArrayList<>();
        Matcher m = WEEK_SEG_PATTERN.matcher(wr);
        while (m.find()) {
            int a;
            int b;
            String parityText;
            if (m.group(1) != null) {
                a = parseInt(m.group(1));
                b = parseInt(m.group(2));
                parityText = m.group(3);
            } else {
                a = parseInt(m.group(4));
                b = a;
                parityText = m.group(5);
            }
            if (a <= 0 || b <= 0) {
                continue;
            }
            int parity = parityText == null ? 0 : ("单".equals(parityText) ? 1 : 2);
            segs.add(new int[]{Math.min(a, b), Math.max(a, b), parity});
        }
        return segs.isEmpty() ? null : segs;
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (Exception e) {
            return 0;
        }
    }

    /** 课程是否在指定周上课（无周次信息时视为每周都上） */
    static boolean courseInWeek(String wr, int weekNo) {
        List<int[]> segs = parseWeekSegs(wr);
        if (segs == null) {
            return true;
        }
        for (int[] s : segs) {
            if (weekNo < s[0] || weekNo > s[1]) {
                continue;
            }
            if (s[2] == 1 && weekNo % 2 == 0) {
                continue;
            }
            if (s[2] == 2 && weekNo % 2 == 1) {
                continue;
            }
            return true;
        }
        return false;
    }

    /** 一条课程记录覆盖的周数，越小越具体；无周次视为最宽泛 */
    private static int weekSpanLen(String wr) {
        List<int[]> segs = parseWeekSegs(wr);
        if (segs == null) {
            return 99;
        }
        int n = 0;
        for (int[] s : segs) {
            n += s[1] - s[0] + 1;
        }
        return n;
    }

    /** 归并键中的课程名：去掉括号备注与空白，同门课的不同地点/周次视为同一条 */
    private static String nameKey(JSONObject c) {
        String name = c.optString("name", "");
        return name.replaceAll("[（(].*?[）)]", "").replaceAll("\\s+", "");
    }

    /** 上课节次序号（1~12）：取 sections（如 "03~04小节"）里的首个数字，无则按中文节次名兜底 */
    public static int slotNoOfCourse(JSONObject c) {
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
        switch (s) {
            case "第一二节": return 1;
            case "第三四节": return 3;
            case "第五六节": return 5;
            case "第七八节": return 7;
            case "第九十节": return 9;
            case "第十一十二节": return 11;
            case "早八": return 1;
            case "早十": return 3;
            case "午二": return 5;
            case "午四": return 7;
            case "晚七": return 9;
            case "晚九": return 11;
            default: return 99;
        }
    }

    /** 课程所属大节下标（0~5），无法识别返回 99 */
    public static int slotIndexOfCourse(JSONObject c) {
        int n = slotNoOfCourse(c);
        return (n > 0 && n < 99) ? (n - 1) / 2 : 99;
    }

    /**
     * 按星期与教学周取当天课程：
     * 先按周次过滤，再把「同一天 + 同一大节 + 同一门课」合并为一条（优先保留周次最具体的），
     * 最后按节次排序。桌面小组件与上课提醒共用这份数据，避免重复显示/重复通知。
     */
    public static List<JSONObject> coursesForDay(Context context, String dayName, int weekNo) {
        List<JSONObject> out = new ArrayList<>();
        if (context == null) {
            return out;
        }
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            JSONArray arr = new JSONArray(prefs.getString(KEY_COURSES, "[]"));
            Map<String, JSONObject> bestByKey = new LinkedHashMap<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject c = arr.optJSONObject(i);
                if (c == null) {
                    continue;
                }
                // 前端课程对象的星期字段为中文名（周一~周日）
                if (!dayName.equals(c.optString("dayOfWeek", "").trim())) {
                    continue;
                }
                if (weekNo > 0 && !courseInWeek(c.optString("weekRange", ""), weekNo)) {
                    continue;
                }
                String key = nameKey(c) + "#" + slotIndexOfCourse(c);
                JSONObject prev = bestByKey.get(key);
                if (prev == null
                        || weekSpanLen(c.optString("weekRange", ""))
                        < weekSpanLen(prev.optString("weekRange", ""))) {
                    bestByKey.put(key, c);
                }
            }
            out.addAll(bestByKey.values());
            Collections.sort(out, new Comparator<JSONObject>() {
                @Override
                public int compare(JSONObject a, JSONObject b) {
                    return slotNoOfCourse(a) - slotNoOfCourse(b);
                }
            });
        } catch (Exception e) {
            e.printStackTrace();
        }
        return out;
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
                .edit().putInt(KEY_WEEK_NO, weekNo)
                .putLong(KEY_WEEK_SAVED_AT, System.currentTimeMillis()).apply();
    }

    /** 读取当前教学周：按记录时间自动向前滚动，避免长时间不开 App 后周次过期 */
    public static int currentWeekNo(Context context) {
        if (context == null) {
            return 0;
        }
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        int saved = prefs.getInt(KEY_WEEK_NO, 0);
        long savedAt = prefs.getLong(KEY_WEEK_SAVED_AT, 0L);
        if (saved <= 0 || savedAt <= 0) {
            return saved;
        }
        try {
            Calendar savedCal = Calendar.getInstance();
            savedCal.setTimeInMillis(savedAt);
            truncateToDay(savedCal);
            Calendar todayCal = Calendar.getInstance();
            truncateToDay(todayCal);
            long weeks = (mondayOf(todayCal) - mondayOf(savedCal)) / (7L * 86400000L);
            int week = saved + (int) weeks;
            return week > 0 ? week : saved;
        } catch (Throwable th) {
            return saved;
        }
    }

    private static void truncateToDay(Calendar cal) {
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
    }

    /** 该日期所在周的周一（当天 0 点）的时间戳 */
    private static long mondayOf(Calendar cal) {
        int dow = cal.get(Calendar.DAY_OF_WEEK);
        int offset = dow == Calendar.SUNDAY ? 6 : dow - 2;
        Calendar monday = (Calendar) cal.clone();
        monday.add(Calendar.DAY_OF_MONTH, -offset);
        return monday.getTimeInMillis();
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
        int weekNo = displayWeekNo(currentWeekNo(context));
        int totalWeek = prefs.getInt(KEY_TOTAL_WEEK, DEFAULT_TOTAL_WEEK);
        if (totalWeek < weekNo) {
            totalWeek = Math.max(DEFAULT_TOTAL_WEEK, weekNo);
        }
        boolean hasData = coursesJson != null && coursesJson.length() > 2;

        Calendar cal = Calendar.getInstance();
        boolean showTomorrow = isAfterNinePm();
        if (showTomorrow) {
            cal.add(Calendar.DAY_OF_MONTH, 1);
        }
        String dateInfo = (cal.get(Calendar.MONTH) + 1) + "月" + cal.get(Calendar.DAY_OF_MONTH)
                + "日  " + (showTomorrow ? "明天" : "今天") + displayDowName();
        String weekInfo = weekNo > 0 ? "第" + weekNo + "/" + totalWeek + "周" : "";

        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_course);
        views.setTextViewText(R.id.widget_date_info, dateInfo);
        views.setTextViewText(R.id.widget_week_info, weekInfo);
        views.setTextViewText(R.id.widget_empty,
                hasData ? (showTomorrow ? "明天没有课程" : "今天没有课程") : "请先打开 Mimi课表 同步一次课表");

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
                // 晚 9 点后展示日自动切换为明天
                String day = displayDowName();
                int weekNo = displayWeekNo(currentWeekNo(context));
                todayCourses.addAll(coursesForDay(context, day, weekNo));
            } catch (Exception e) {
                e.printStackTrace();
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
