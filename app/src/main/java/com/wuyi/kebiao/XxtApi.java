package com.wuyi.kebiao;

/**
 * 本文件职责：
 * 学习通签到相关接口的纯协议实现（课程、进行中活动、活动详情、预签到、提交签到、
 * 签到码校验、二维码 enc 刷新、拍照签到图片上传）。协议事实来自开源项目 XXTSign
 * 的公开说明与实测结论，此处用 Java 按同一链路独立实现。
 */

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class XxtApi {

    public static final int TYPE_NORMAL = 0;
    public static final int TYPE_QRCODE = 2;
    public static final int TYPE_GESTURE = 3;
    public static final int TYPE_LOCATION = 4;
    public static final int TYPE_CODE = 5;

    public static final int ACT_SIGN = 2;
    public static final int ACT_SIGN_OUT = 74;

    public static final int APP_TYPE_COURSE = 0;
    public static final int APP_TYPE_SCAN = 15;

    private static final String URL_STUSIGN = "https://mobilelearn.chaoxing.com/pptSign/stuSignajax";
    private static final String URL_PRESIGN = "https://mobilelearn.chaoxing.com/newsign/preSign";

    private static final Map<String, CacheItem> COURSE_CACHE = new HashMap<>();

    private XxtApi() {
    }

    public static final class Course {
        public final String courseId;
        public final String classId;
        public final String name;

        Course(String courseId, String classId, String name) {
            this.courseId = courseId;
            this.classId = classId;
            this.name = name;
        }
    }

    private static final class CacheItem {
        final long at;
        final List<Course> list;

        CacheItem(long at, List<Course> list) {
            this.at = at;
            this.list = list;
        }
    }

    // ---------------------------------------------------------------- 课程
    public static List<Course> courses(XxtSession session) {
        String key = session.uid.isEmpty() ? session.account().phone : session.uid;
        synchronized (COURSE_CACHE) {
            CacheItem item = COURSE_CACHE.get(key);
            if (item != null && System.currentTimeMillis() - item.at < 10 * 60 * 1000L) {
                return item.list;
            }
        }
        List<Course> list = loadCourses(session);
        if (!list.isEmpty()) {
            synchronized (COURSE_CACHE) {
                COURSE_CACHE.put(key, new CacheItem(System.currentTimeMillis(), list));
            }
        }
        return list;
    }

    public static void clearCourseCache() {
        synchronized (COURSE_CACHE) {
            COURSE_CACHE.clear();
        }
    }

    private static List<Course> loadCourses(XxtSession session) {
        LinkedHashMap<String, Course> out = new LinkedHashMap<>();
        try {
            XxtSession.Resp r = session.get("https://mooc1-api.chaoxing.com/mycourse/backclazzdata?view=json&rss=1");
            if (r.body.trim().startsWith("{")) {
                parseBackclazz(new JSONObject(r.body), out);
            }
        } catch (Exception ignored) {
        }
        if (out.isEmpty()) {
            try {
                Map<String, String> form = new LinkedHashMap<>();
                form.put("courseType", "1");
                form.put("courseFolderId", "0");
                form.put("courseFolderSize", "0");
                Map<String, String> headers = new LinkedHashMap<>();
                headers.put("Accept", "text/html, */*; q=0.01");
                headers.put("X-Requested-With", "XMLHttpRequest");
                XxtSession.Resp r = session.postForm(
                        "https://mooc1-1.chaoxing.com/visit/courselistdata", form, headers);
                String html = r.body.replace("&amp;", "&");
                java.util.regex.Matcher m1 = java.util.regex.Pattern
                        .compile("course_(\\d+)_(\\d+)").matcher(html);
                while (m1.find()) {
                    out.put(m1.group(1), new Course(m1.group(1), m1.group(2), ""));
                }
                java.util.regex.Matcher m2 = java.util.regex.Pattern
                        .compile("/visit/stucoursemiddle\\?courseid=(\\d+)[^\"']*clazzid=(\\d+)")
                        .matcher(html);
                while (m2.find()) {
                    out.put(m2.group(1), new Course(m2.group(1), m2.group(2), ""));
                }
            } catch (Exception ignored) {
            }
        }
        return new ArrayList<>(out.values());
    }

    private static void parseBackclazz(JSONObject json, LinkedHashMap<String, Course> out) {
        JSONArray channels = json.optJSONArray("channelList");
        if (channels == null) {
            return;
        }
        for (int i = 0; i < channels.length(); i++) {
            JSONObject content = channels.optJSONObject(i) == null
                    ? null : channels.optJSONObject(i).optJSONObject("content");
            if (content == null) {
                continue;
            }
            String classId = content.optString("id");
            if (classId.isEmpty() || !digits(classId)) {
                continue;
            }
            String className = content.optString("name");
            JSONObject course = content.optJSONObject("course");
            JSONArray data = course == null ? null : course.optJSONArray("data");
            if (data == null) {
                continue;
            }
            for (int j = 0; j < data.length(); j++) {
                JSONObject co = data.optJSONObject(j);
                if (co == null) {
                    continue;
                }
                String courseId = co.optString("id");
                if (courseId.isEmpty() || !digits(courseId)) {
                    continue;
                }
                String name = co.optString("name");
                if (name.isEmpty()) {
                    name = className;
                }
                out.put(courseId, new Course(courseId, classId, name));
            }
        }
    }

    private static boolean digits(String v) {
        for (int i = 0; i < v.length(); i++) {
            if (!Character.isDigit(v.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    // -------------------------------------------------------------- 活动查询
    public static List<JSONObject> activeList(XxtSession session, String fid,
                                             String courseId, String classId) {
        List<JSONObject> out = new ArrayList<>();
        try {
            String url = "https://mobilelearn.chaoxing.com/v2/apis/active/student/activelist"
                    + "?fid=" + enc(fid.isEmpty() ? "0" : fid)
                    + "&courseId=" + enc(courseId) + "&classId=" + enc(classId)
                    + "&_=" + System.currentTimeMillis();
            XxtSession.Resp r = session.get(url, mobileHeaders());
            JSONObject j = new JSONObject(r.body);
            JSONObject data = j.optJSONObject("data");
            JSONArray arr = data == null ? null : data.optJSONArray("activeList");
            if (arr == null) {
                return out;
            }
            for (int i = 0; i < arr.length(); i++) {
                JSONObject item = arr.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                if (item.optInt("status", 0) != 1) {
                    continue;
                }
                int type = item.optInt("activeType", ACT_SIGN);
                if (type != ACT_SIGN && type != ACT_SIGN_OUT) {
                    continue;
                }
                out.add(item);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    public static JSONObject signDetail(XxtSession session, String aid) {
        try {
            XxtSession.Resp r = session.get(
                    "https://mobilelearn.chaoxing.com/newsign/signDetail?activePrimaryId="
                            + enc(aid) + "&type=1", mobileHeaders());
            JSONObject j = new JSONObject(r.body);
            JSONObject data = j.optJSONObject("data");
            return data == null ? j : data;
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    public static JSONObject pptInfo(XxtSession session, String aid) {
        try {
            XxtSession.Resp r = session.get(
                    "https://mobilelearn.chaoxing.com/v2/apis/active/getPPTActiveInfo?activeId="
                            + enc(aid), mobileHeaders());
            JSONObject j = new JSONObject(r.body);
            JSONObject data = j.optJSONObject("data");
            return data == null ? j : data;
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    /** 问服务端要一份当前有效的二维码 enc；拿不到返回空串 */
    public static String refreshQr(XxtSession session, String aid) {
        try {
            XxtSession.Resp r = session.get(
                    "https://mobilelearn.chaoxing.com/v2/apis/sign/refreshQRCode?activeId=" + enc(aid),
                    mobileHeaders());
            JSONObject j = new JSONObject(r.body);
            boolean ok = j.optBoolean("result") || j.optInt("result", 0) == 1;
            if (!ok) {
                return "";
            }
            String v = strAny(j, "enc");
            if (v.isEmpty() && j.optJSONObject("data") != null) {
                v = strAny(j.optJSONObject("data"), "enc");
            }
            if (v.isEmpty()) {
                String d = j.optString("data");
                if (!d.isEmpty() && !d.startsWith("{")) {
                    v = d;
                }
            }
            return v;
        } catch (Exception e) {
            return "";
        }
    }

    // ---------------------------------------------------------------- 签到
    /** 预签到；返回空串表示通过，非空为失败/验证提示 */
    public static String preSign(XxtSession session, String courseId, String classId,
                                 JSONObject detail, String rcode, int appType) throws IOException {
        String aid = strAny(detail, "id");
        if (aid.isEmpty()) {
            aid = strAny(detail, "activePrimaryId");
        }
        String url = URL_PRESIGN + "?courseId=" + enc(courseId) + "&classId=" + enc(classId)
                + "&activePrimaryId=" + enc(aid) + "&general=1&sys=1&ls=1&appType=" + appType
                + "&uid=" + enc(session.uid) + "&isTeacherViewOpen=0";
        if (rcode != null && !rcode.isEmpty()) {
            url += "&rcode=" + enc(rcode);
        }
        XxtSession.Resp r = session.get(url, mobileHeaders());
        if (r.code == 401 || r.code == 403) {
            return "会话已失效";
        }
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("id=\"statuscontent\"[^>]*>(.*?)<", java.util.regex.Pattern.DOTALL)
                    .matcher(r.body);
            if (m.find()) {
                String text = m.group(1).replaceAll("<[^>]+>", "").replaceAll("\\s", "");
                if (!text.isEmpty()) {
                    return text;
                }
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    /** 签到码预校验：通过返回空串 */
    public static String checkSignCode(XxtSession session, String aid, String code) {
        try {
            XxtSession.Resp r = session.get(
                    "https://mobilelearn.chaoxing.com/widget/sign/pcStuSignController/checkSignCode"
                            + "?activeId=" + enc(aid) + "&signCode=" + enc(code), mobileHeaders());
            JSONObject j = new JSONObject(r.body);
            int v = j.optInt("result", 0);
            if (v == 1) {
                return "";
            }
            String msg = strAny(j, "errorMsg");
            if (msg.isEmpty()) {
                msg = strAny(j, "msg");
            }
            if (v == -1) {
                return "签到码已被封禁（尝试次数过多）" + (msg.isEmpty() ? "" : "：" + msg);
            }
            return msg.isEmpty() ? "签到码不正确" : msg;
        } catch (Exception e) {
            return "";
        }
    }

    /** 提交签到。params 同时作为 query 与表单字段（与官方页面一致） */
    public static Outcome submit(XxtSession session, LinkedHashMap<String, String> params,
                                 Map<String, String> extraForm) throws IOException {
        StringBuilder query = new StringBuilder();
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (query.length() > 0) {
                query.append('&');
            }
            query.append(e.getKey()).append('=').append(enc(e.getValue()));
        }
        LinkedHashMap<String, String> form = new LinkedHashMap<>(params);
        if (extraForm != null) {
            form.putAll(extraForm);
        }
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Referer", URL_PRESIGN + "?activePrimaryId=0");
        headers.put("X-Requested-With", "XMLHttpRequest");
        XxtSession.Resp r = session.postForm(URL_STUSIGN + "?" + query, form, headers);
        if (r.code == 401 || r.code == 403) {
            return new Outcome("NEED_LOGIN", "会话已失效，请重新登录");
        }
        return classify(r.body);
    }

    public static Outcome classify(String body) {
        String b = body == null ? "" : body.trim();
        String lower = b.toLowerCase(Locale.ROOT);
        // 已经签过再提交时服务端会返回「同一设备不允许重复签到」，这其实就是已完成
        if (b.contains("同一设备不允许重复签到") || b.contains("重复签到")
                || b.contains("请勿重复签到")) {
            return new Outcome("DONE", "签到已完成");
        }
        if (b.contains("您已签到过了") || b.contains("已签到") || b.contains("已经签到")) {
            return new Outcome("DONE", "已签到");
        }
        if (b.contains("success2") || b.contains("已过期") || b.contains("活动已结束")
                || b.contains("签到已结束") || b.contains("已失效")) {
            return new Outcome("EXPIRED", "活动已结束或已过期");
        }
        if (lower.contains("success") || b.contains("\"result\":true")) {
            return new Outcome("DONE", "签到成功");
        }
        if (b.contains("validate") || lower.contains("captcha")) {
            return new Outcome("NEED_CAPTCHA", "需要人机验证，请在官方 App 完成");
        }
        if (b.isEmpty()) {
            return new Outcome("FAILED", "签到无响应，请稍后重试");
        }
        String text = b.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
        if (text.length() > 80) {
            text = text.substring(0, 80);
        }
        return new Outcome("FAILED", text.isEmpty() ? "签到失败" : text);
    }

    public static final class Outcome {
        public final String status;
        public final String message;

        public Outcome(String status, String message) {
            this.status = status;
            this.message = message;
        }

        public boolean ok() {
            return "DONE".equals(status);
        }
    }

    /** 上传图片到超星云盘，返回 objectId；失败返回空串 */
    public static String uploadPhoto(XxtSession session, byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return "";
        }
        try {
            XxtSession.Resp tokenResp = session.get(
                    "https://pan-yz.chaoxing.com/api/token/uservalid", mobileHeaders());
            String raw = tokenResp.body.trim();
            String token = "";
            try {
                token = new JSONObject(raw).optString("_token");
            } catch (Exception ignored) {
            }
            if (token.isEmpty()) {
                token = raw.replace("\"", "");
                if (token.contains("{") || token.contains("<")) {
                    token = "";
                }
            }
            if (token.isEmpty()) {
                return "";
            }
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("puid", session.uid);
            fields.put("_token", token);
            // 上传的是压缩后的 JPEG，文件名与 MIME 要和内容一致，否则服务端会拒收
            XxtSession.Resp r = session.postMultipart(
                    "https://pan-yz.chaoxing.com/upload?_from=mobilelearn&_token=" + enc(token),
                    bytes, "sign.jpg", "image/jpeg", fields);
            JSONObject j = new JSONObject(r.body);
            String objectId = j.optString("objectId");
            if (objectId.isEmpty() && j.optJSONObject("data") != null) {
                objectId = j.optJSONObject("data").optString("objectId");
            }
            return objectId;
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 拍照签到提交：官方拍照页只带固定几个参数，且不带 location / deviceCode，
     * 多带参数会触发照片防代签校验（photoAuthError_PCR007）
     */
    public static Outcome photoSubmit(XxtSession session, String aid, String objectId)
            throws IOException {
        String url = URL_STUSIGN + "?activeId=" + enc(aid)
                + "&uid=" + enc(session.uid)
                + "&clientip=&useragent=&latitude=-1&longitude=-1&appType=" + APP_TYPE_SCAN
                + "&fid=" + enc(session.schoolId)
                + "&objectId=" + enc(objectId)
                + "&name=" + enc(session.realName);
        XxtSession.Resp r = session.get(url, mobileHeaders());
        if (r.code == 401 || r.code == 403) {
            return new Outcome("NEED_LOGIN", "会话已失效，请重新登录");
        }
        return classify(r.body);
    }

    /** 手势签到提交：官方页面依次尝试 gesture / signPath / handPath 三个参数名 */
    public static Outcome gestureSubmit(XxtSession session, String aid, String code)
            throws IOException {
        String[] names = {"gesture", "signPath", "handPath"};
        Outcome last = new Outcome("FAILED", "手势签到失败");
        for (String param : names) {
            String url = URL_STUSIGN + "?activeId=" + enc(aid)
                    + "&uid=" + enc(session.uid)
                    + "&clientip=&latitude=-1&longitude=-1&appType=" + APP_TYPE_SCAN
                    + "&fid=" + enc(session.schoolId)
                    + "&name=" + enc(session.realName)
                    + "&" + param + "=" + enc(code);
            XxtSession.Resp r = session.get(url, mobileHeaders());
            if (r.code == 401 || r.code == 403) {
                return new Outcome("NEED_LOGIN", "会话已失效，请重新登录");
            }
            last = classify(r.body);
            if (last.ok()) {
                return last;
            }
            // 90002 是参数名不匹配时的通用报错，换下一个参数名再试
            if (r.body == null || !r.body.contains("90002")) {
                return last;
            }
        }
        return last;
    }

    /** 从签到页源码里找老师设定的手势序列，找不到返回空串 */
    public static String fetchGesture(XxtSession session, String aid) {
        try {
            String url = "https://mobilelearn.chaoxing.com/sign/produceSignView?activeId=" + enc(aid);
            XxtSession.Resp r = session.get(url, mobileHeaders());
            Pattern p = Pattern.compile("gesture[\"'\\s]*[:=][\"'\\s]*([0-9][0-9,\\s]{2,})");
            Matcher m = p.matcher(r.body == null ? "" : r.body);
            if (m.find()) {
                String digits = m.group(1).replaceAll("[^0-9]", "");
                if (digits.length() >= 4) {
                    return digits;
                }
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    // ---------------------------------------------------------------- 工具
    public static Map<String, String> mobileHeaders() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("Referer", "https://mobilelearn.chaoxing.com/");
        m.put("X-Requested-With", "XMLHttpRequest");
        return m;
    }

    public static String strAny(JSONObject o, String... keys) {
        if (o == null) {
            return "";
        }
        for (String k : keys) {
            if (!o.has(k) || o.isNull(k)) {
                continue;
            }
            String v = o.optString(k, "").trim();
            if (!v.isEmpty() && !"null".equals(v)) {
                return v;
            }
        }
        return "";
    }

    public static int intAny(JSONObject o, String... keys) {
        if (o == null) {
            return 0;
        }
        for (String k : keys) {
            if (o.has(k) && !o.isNull(k)) {
                return o.optInt(k, 0);
            }
        }
        return 0;
    }

    public static String typeName(int otherId, int ifPhoto) {
        if (otherId == TYPE_QRCODE) {
            return "二维码签到";
        }
        if (otherId == TYPE_GESTURE) {
            return "手势签到";
        }
        if (otherId == TYPE_LOCATION) {
            return "位置签到";
        }
        if (otherId == TYPE_CODE) {
            return "签到码签到";
        }
        if (ifPhoto == 1) {
            return "拍照签到";
        }
        return "普通签到";
    }

    public static String enc(String v) {
        try {
            return java.net.URLEncoder.encode(v == null ? "" : v, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }
}
