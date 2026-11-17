package com.wuyi.kebiao;

/**
 * 本文件职责：
 * 学习通签到的本地持久化存储（独立于教务账号与快递登录态）。
 * 保存账号、Cookie、默认签到位置、默认拍照图片、巡检设置与运行日志，
 * 全部写在应用私有 SharedPreferences 中，只能由「退出账号 / 清除数据」删除。
 */

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class XxtStore {

    private static final String PREFS = "mimi_xxt_sign";
    private static final int MAX_LOGS = 300;

    private static final List<String> LOGS = new ArrayList<>();

    private XxtStore() {
    }

    /** 一个学习通账号（密码与 Cookie 都只存在本机） */
    public static final class Account {
        public String id = "";
        public String phone = "";
        public String password = "";
        public String uid = "";
        public String name = "";
        public String schoolId = "";
        public String cookies = "";
        public String schild = "";
        public String lastError = "";
        public long lastLoginAt = 0L;

        public String display() {
            return name == null || name.trim().isEmpty() ? phone : name;
        }

        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("id", id);
                o.put("phone", phone);
                o.put("password", password);
                o.put("uid", uid);
                o.put("name", name);
                o.put("schoolId", schoolId);
                o.put("cookies", cookies);
                o.put("schild", schild);
                o.put("lastError", lastError);
                o.put("lastLoginAt", lastLoginAt);
            } catch (Exception ignored) {
            }
            return o;
        }

        public static Account fromJson(JSONObject o) {
            Account a = new Account();
            a.id = o.optString("id");
            a.phone = o.optString("phone");
            a.password = o.optString("password");
            a.uid = o.optString("uid");
            a.name = o.optString("name");
            a.schoolId = o.optString("schoolId");
            a.cookies = o.optString("cookies");
            a.schild = o.optString("schild");
            a.lastError = o.optString("lastError");
            a.lastLoginAt = o.optLong("lastLoginAt", 0L);
            return a;
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ---------------------------------------------------------------- 账号
    public static synchronized List<Account> accounts(Context context) {
        List<Account> out = new ArrayList<>();
        String raw = prefs(context).getString("accounts", "");
        if (raw == null || raw.trim().isEmpty()) {
            return out;
        }
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null) {
                    out.add(Account.fromJson(o));
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private static synchronized void writeAccounts(Context context, List<Account> list) {
        JSONArray arr = new JSONArray();
        for (Account a : list) {
            arr.put(a.toJson());
        }
        prefs(context).edit().putString("accounts", arr.toString()).apply();
    }

    public static synchronized Account find(Context context, String id) {
        if (id == null) {
            return null;
        }
        for (Account a : accounts(context)) {
            if (id.equals(a.id)) {
                return a;
            }
        }
        return null;
    }

    public static synchronized void saveAccount(Context context, Account account) {
        if (account == null) {
            return;
        }
        List<Account> list = accounts(context);
        boolean replaced = false;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id.equals(account.id)) {
                list.set(i, account);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            list.add(account);
        }
        writeAccounts(context, list);
    }

    public static synchronized void removeAccount(Context context, String id) {
        List<Account> list = accounts(context);
        for (int i = list.size() - 1; i >= 0; i--) {
            if (list.get(i).id.equals(id)) {
                list.remove(i);
            }
        }
        writeAccounts(context, list);
    }

    // -------------------------------------------------------------- 巡检设置
    public static boolean autoEnabled(Context context) {
        return prefs(context).getBoolean("auto_enabled", false);
    }

    public static void setAutoEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean("auto_enabled", enabled).apply();
    }

    public static int intervalSeconds(Context context) {
        int v = prefs(context).getInt("interval_sec", 10);
        if (v < 5) {
            v = 5;
        }
        if (v > 60) {
            v = 60;
        }
        return v;
    }

    public static void setIntervalSeconds(Context context, int seconds) {
        prefs(context).edit().putInt("interval_sec", seconds).apply();
    }

    public static String latitude(Context context) {
        return prefs(context).getString("lat", "");
    }

    public static String longitude(Context context) {
        return prefs(context).getString("lon", "");
    }

    public static String address(Context context) {
        return prefs(context).getString("address", "");
    }

    public static void saveLocation(Context context, String lat, String lon, String address) {
        prefs(context).edit()
                .putString("lat", lat == null ? "" : lat.trim())
                .putString("lon", lon == null ? "" : lon.trim())
                .putString("address", address == null ? "" : address.trim())
                .apply();
    }

    public static String photoPath(Context context) {
        return prefs(context).getString("photo_path", "");
    }

    public static void setPhotoPath(Context context, String path) {
        prefs(context).edit().putString("photo_path", path == null ? "" : path).apply();
    }

    // -------------------------------------------------------------- 刷课设置
    public static String studyAccount(Context context) {
        return prefs(context).getString("study_account", "");
    }

    public static void setStudyAccount(Context context, String id) {
        prefs(context).edit().putString("study_account", id == null ? "" : id).apply();
    }

    public static float studySpeed(Context context) {
        float v = prefs(context).getFloat("study_speed", 2f);
        if (v < 1f) {
            v = 1f;
        }
        if (v > 2f) {
            v = 2f;
        }
        return v;
    }

    public static void setStudySpeed(Context context, float speed) {
        prefs(context).edit().putFloat("study_speed", speed).apply();
    }

    /** 刷课进度通知：在通知栏显示当前章节、视频与百分比 */
    public static boolean studyNotify(Context context) {
        return prefs(context).getBoolean("study_notify", true);
    }

    public static void setStudyNotify(Context context, boolean enabled) {
        prefs(context).edit().putBoolean("study_notify", enabled).apply();
    }

    // ------------------------------------------------------------ 账号间隔
    /** 多账号之间的间隔秒数，0~6 */
    public static int accountGap(Context context) {
        int v = prefs(context).getInt("account_gap", 0);
        return v < 0 ? 0 : (v > 6 ? 6 : v);
    }

    public static void setAccountGap(Context context, int seconds) {
        int v = seconds < 0 ? 0 : (seconds > 6 ? 6 : seconds);
        prefs(context).edit().putInt("account_gap", v).apply();
    }

    // ------------------------------------------------------------ 课堂范围
    /** 逗号分隔的课程 id，表示「不签这个课堂」；为空表示所有课程都参与签到 */
    public static synchronized String excludeIds(Context context) {
        String raw = prefs(context).getString("exclude_ids", "");
        return raw == null ? "" : raw;
    }

    public static synchronized void setExcludeIds(Context context, String ids) {
        prefs(context).edit().putString("exclude_ids", ids == null ? "" : ids).apply();
    }

    /** 该课程是否参与签到：没有被排除的课程一律参与 */
    public static boolean inScope(Context context, String courseId) {
        String raw = excludeIds(context);
        if (raw.trim().isEmpty()) {
            return true;
        }
        if (courseId == null || courseId.trim().isEmpty()) {
            return true;
        }
        for (String id : raw.split(",")) {
            if (id.trim().equals(courseId.trim())) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------ 刷题设置
    /** 每题作答后的停留秒数，0 表示不停留 */
    public static int quizStaySeconds(Context context) {
        int v = prefs(context).getInt("quiz_stay_sec", 5);
        return v < 0 ? 0 : (v > 60 ? 60 : v);
    }

    public static void setQuizStaySeconds(Context context, int seconds) {
        prefs(context).edit().putInt("quiz_stay_sec", seconds < 0 ? 0 : (seconds > 60 ? 60 : seconds)).apply();
    }

    /** 答完后、交卷前的等待秒数 */
    public static int quizWaitSeconds(Context context) {
        int v = prefs(context).getInt("quiz_wait_sec", 15);
        return v < 0 ? 0 : (v > 120 ? 120 : v);
    }

    public static void setQuizWaitSeconds(Context context, int seconds) {
        prefs(context).edit().putInt("quiz_wait_sec", seconds < 0 ? 0 : (seconds > 120 ? 120 : seconds)).apply();
    }

    /** 只暂存不交卷：默认开启，关闭后才会真正提交 */
    public static boolean quizSaveOnly(Context context) {
        return prefs(context).getBoolean("quiz_save_only", true);
    }

    public static void setQuizSaveOnly(Context context, boolean enabled) {
        prefs(context).edit().putBoolean("quiz_save_only", enabled).apply();
    }

    // ------------------------------------------------------------ OCS 题库
    /** OCS（ocsjs）格式的题库脚本配置，空串表示未配置 */
    public static synchronized String ocsConfig(Context context) {
        String raw = prefs(context).getString("ocs_config", "");
        return raw == null ? "" : raw;
    }

    public static synchronized void setOcsConfig(Context context, String config) {
        prefs(context).edit().putString("ocs_config",
                config == null ? "" : config.trim()).apply();
    }

    // ------------------------------------------------------------ 刷题
    public static synchronized boolean quizHandled(Context context, String key) {
        if (key == null || key.isEmpty()) {
            return false;
        }
        return quizHandledSet(context).contains(key);
    }

    public static synchronized void markQuizHandled(Context context, String key) {
        if (key == null || key.isEmpty()) {
            return;
        }
        Set<String> set = quizHandledSet(context);
        set.add(key);
        if (set.size() > 500) {
            java.util.Iterator<String> it = set.iterator();
            if (it.hasNext()) {
                it.next();
                it.remove();
            }
        }
        StringBuilder sb = new StringBuilder();
        for (String s : set) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(s);
        }
        prefs(context).edit().putString("quiz_handled", sb.toString()).apply();
    }

    private static Set<String> quizHandledSet(Context context) {
        Set<String> set = new LinkedHashSet<>();
        String raw = prefs(context).getString("quiz_handled", "");
        if (raw == null || raw.isEmpty()) {
            return set;
        }
        for (String line : raw.split("\\n")) {
            if (!line.trim().isEmpty()) {
                set.add(line.trim());
            }
        }
        return set;
    }

    // ------------------------------------------------------------ 已处理活动
    public static synchronized Set<String> handledAids(Context context) {
        Set<String> set = new LinkedHashSet<>();
        String raw = prefs(context).getString("handled", "");
        if (raw == null || raw.trim().isEmpty()) {
            return set;
        }
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                String aid = arr.optString(i, "");
                if (!aid.isEmpty()) {
                    set.add(aid);
                }
            }
        } catch (Exception ignored) {
        }
        return set;
    }

    public static synchronized boolean isHandled(Context context, String aid) {
        return handledAids(context).contains(aid);
    }

    public static synchronized void markHandled(Context context, String aid) {
        if (aid == null || aid.isEmpty()) {
            return;
        }
        Set<String> set = handledAids(context);
        if (!set.add(aid)) {
            return;
        }
        while (set.size() > 300) {
            String first = set.iterator().next();
            set.remove(first);
        }
        JSONArray arr = new JSONArray();
        for (String v : set) {
            arr.put(v);
        }
        prefs(context).edit().putString("handled", arr.toString()).apply();
    }

    public static void clearHandled(Context context) {
        prefs(context).edit().remove("handled").apply();
    }

    // ------------------------------------------------------ 已签到完成的活动
    /** 签到成功的活动，键为「账号ID|活动ID」。刷新活动列表时直接标成已完成，避免重复提交 */
    public static synchronized Set<String> signedKeys(Context context) {
        Set<String> set = new LinkedHashSet<>();
        String raw = prefs(context).getString("signed", "");
        if (raw == null || raw.trim().isEmpty()) {
            return set;
        }
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                String key = arr.optString(i, "");
                if (!key.isEmpty()) {
                    set.add(key);
                }
            }
        } catch (Exception ignored) {
        }
        return set;
    }

    public static synchronized boolean isSigned(Context context, String accountId, String aid) {
        if (accountId == null || aid == null || aid.isEmpty()) {
            return false;
        }
        return signedKeys(context).contains(accountId + "|" + aid);
    }

    public static synchronized void markSigned(Context context, String accountId, String aid) {
        if (accountId == null || aid == null || aid.isEmpty()) {
            return;
        }
        Set<String> set = signedKeys(context);
        if (!set.add(accountId + "|" + aid)) {
            return;
        }
        while (set.size() > 300) {
            String first = set.iterator().next();
            set.remove(first);
        }
        JSONArray arr = new JSONArray();
        for (String v : set) {
            arr.put(v);
        }
        prefs(context).edit().putString("signed", arr.toString()).apply();
    }

    // ---------------------------------------------------------------- 日志
    public static synchronized void addLog(String text) {
        if (text == null || text.trim().isEmpty()) {
            return;
        }
        String time = new SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(new Date());
        LOGS.add(time + "  " + text);
        while (LOGS.size() > MAX_LOGS) {
            LOGS.remove(0);
        }
    }

    public static synchronized List<String> logs() {
        return new ArrayList<>(LOGS);
    }

    public static synchronized void clearLogs() {
        LOGS.clear();
    }

    public static synchronized String logText() {
        StringBuilder sb = new StringBuilder();
        for (String line : LOGS) {
            sb.append(line).append('\n');
        }
        return sb.toString();
    }
}
