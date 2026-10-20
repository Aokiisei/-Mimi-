package com.wuyi.kebiao;

/**
 * 本文件职责：
 * 日程数据的本地存取与自动清理。
 * 日程以 JSON 数组存于 SharedPreferences，字段：id/title/date(yyyy-MM-dd)/start(HH:mm)/end/location/note。
 * 自动清理策略：never（永不）/ 7（过期 7 天后清除）/ 30（过期 30 天后清除），每次读取时顺带执行。
 */

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class ScheduleStore {
    private static final String PREFS = "mimi_schedules";
    private static final String KEY_LIST = "list";
    private static final String KEY_POLICY = "cleanup_policy";
    private static final String KEY_COUNTDOWNS = "countdowns";

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, 0);
    }

    public static String getPolicy(Context ctx) {
        return prefs(ctx).getString(KEY_POLICY, "never");
    }

    public static void setPolicy(Context ctx, String policy) {
        prefs(ctx).edit().putString(KEY_POLICY, policy).apply();
    }

    // 读取全部日程（JSON 数组字符串），读取前按策略顺带清理过期日程
    public static synchronized String loadJson(Context ctx) {
        JSONArray arr = readArray(ctx);
        arr = applyAutoCleanup(ctx, arr);
        return arr.toString();
    }

    // 新增或更新（json 含 id 则更新）：返回保存后的条目 id
    public static synchronized String save(Context ctx, String json) {
        try {
            JSONObject item = new JSONObject(json);
            String id = item.optString("id", "");
            if (id.isEmpty()) {
                id = "s" + System.currentTimeMillis() + (int) (Math.random() * 900 + 100);
                item.put("id", id);
            }
            JSONArray arr = readArray(ctx);
            boolean updated = false;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null && id.equals(o.optString("id"))) {
                    arr.put(i, item);
                    updated = true;
                    break;
                }
            }
            if (!updated) arr.put(item);
            writeArray(ctx, arr);
            return id;
        } catch (Throwable th) {
            return null;
        }
    }

    public static synchronized void delete(Context ctx, String id) {
        try {
            JSONArray arr = readArray(ctx);
            JSONArray out = new JSONArray();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null || !id.equals(o.optString("id"))) out.put(arr.get(i));
            }
            writeArray(ctx, out);
        } catch (Throwable th) {
        }
    }

    // ===== 倒计时倒计日（考试/自定义目标日期）存取 =====
    // 条目字段：id / title / date(yyyy-MM-dd)

    public static synchronized String loadCountdowns(Context ctx) {
        return readCountdowns(ctx).toString();
    }

    public static synchronized String saveCountdown(Context ctx, String json) {
        try {
            JSONObject item = new JSONObject(json);
            String id = item.optString("id", "");
            if (id.isEmpty()) {
                id = "cd" + System.currentTimeMillis() + (int) (Math.random() * 900 + 100);
                item.put("id", id);
            }
            JSONArray arr = readCountdowns(ctx);
            boolean updated = false;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null && id.equals(o.optString("id"))) {
                    arr.put(i, item);
                    updated = true;
                    break;
                }
            }
            if (!updated) arr.put(item);
            writeCountdowns(ctx, arr);
            return id;
        } catch (Throwable th) {
            return null;
        }
    }

    public static synchronized void deleteCountdown(Context ctx, String id) {
        try {
            JSONArray arr = readCountdowns(ctx);
            JSONArray out = new JSONArray();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null || !id.equals(o.optString("id"))) out.put(arr.get(i));
            }
            writeCountdowns(ctx, out);
        } catch (Throwable th) {
        }
    }

    private static JSONArray readCountdowns(Context ctx) {
        try {
            return new JSONArray(prefs(ctx).getString(KEY_COUNTDOWNS, "[]"));
        } catch (Throwable th) {
            return new JSONArray();
        }
    }

    private static void writeCountdowns(Context ctx, JSONArray arr) {
        prefs(ctx).edit().putString(KEY_COUNTDOWNS, arr.toString()).apply();
    }

    // 手动清理：删除所有日期早于今天的日程，返回清理条数
    public static synchronized int cleanupExpired(Context ctx) {
        try {
            JSONArray arr = readArray(ctx);
            JSONArray out = new JSONArray();
            long today = todayMs();
            int removed = 0;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                long d = dateMs(o != null ? o.optString("date") : null);
                if (d > 0 && d < today) removed++;
                else out.put(arr.get(i));
            }
            if (removed > 0) writeArray(ctx, out);
            return removed;
        } catch (Throwable th) {
            return 0;
        }
    }

    // 按策略自动清理：过期超过 N 天的日程被移除
    private static JSONArray applyAutoCleanup(Context ctx, JSONArray arr) {
        try {
            String policy = getPolicy(ctx);
            if ("never".equals(policy)) return arr;
            int days = Integer.parseInt(policy);
            long limit = todayMs() - days * 86400000L;
            JSONArray out = new JSONArray();
            boolean changed = false;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                long d = dateMs(o != null ? o.optString("date") : null);
                if (d > 0 && d < limit) changed = true;
                else out.put(arr.get(i));
            }
            if (changed) writeArray(ctx, out);
            return out;
        } catch (Throwable th) {
            return arr;
        }
    }

    private static JSONArray readArray(Context ctx) {
        try {
            String raw = prefs(ctx).getString(KEY_LIST, "[]");
            JSONArray arr = new JSONArray(raw);
            return arr;
        } catch (Throwable th) {
            return new JSONArray();
        }
    }

    private static void writeArray(Context ctx, JSONArray arr) {
        prefs(ctx).edit().putString(KEY_LIST, arr.toString()).apply();
    }

    private static long todayMs() {
        try {
            String s = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
            return new SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(s).getTime();
        } catch (Throwable th) {
            return System.currentTimeMillis();
        }
    }

    private static long dateMs(String iso) {
        if (iso == null || iso.isEmpty()) return -1;
        try {
            return new SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(iso).getTime();
        } catch (Throwable th) {
            return -1;
        }
    }
}
