package com.wuyi.kebiao;

/**
 * 本文件职责：
 * 学习通签到编排：发现账号下所有进行中的签到 / 签退活动，按活动类型自动选择
 * 普通、拍照、位置、二维码、手势、签到码的提交方式，并给出可判定的结果状态。
 * 人脸识别、滑块验证不做自动化，只明确提示需要本人处理。
 */

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class XxtSignEngine {

    private XxtSignEngine() {
    }

    public static final class Activity {
        public String accountId = "";
        public String accountName = "";
        public String aid = "";
        public String courseId = "";
        public String classId = "";
        public String courseName = "";
        public int otherId = -1;
        public int ifPhoto = 0;
        public int activeType = XxtApi.ACT_SIGN;
        public boolean needFace = false;
        public String teacherAddress = "";
        public String teacherLat = "";
        public String teacherLon = "";
        public JSONObject detail = new JSONObject();

        public String title() {
            String course = courseName == null || courseName.trim().isEmpty() ? "课程" : courseName.trim();
            String type = XxtApi.typeName(otherId, ifPhoto);
            if (activeType == XxtApi.ACT_SIGN_OUT) {
                type = type.replace("签到", "") + "·签退";
            }
            return course + " · " + type;
        }

        public JSONObject toJson(Context context) {
            JSONObject o = new JSONObject();
            try {
                o.put("accountId", accountId);
                o.put("accountName", accountName);
                o.put("aid", aid);
                o.put("title", title());
                o.put("type", XxtApi.typeName(otherId, ifPhoto));
                o.put("courseId", courseId);
                o.put("courseName", courseName);
                o.put("needFace", needFace);
                o.put("teacherAddress", teacherAddress);
                o.put("teacherLat", teacherLat);
                o.put("teacherLon", teacherLon);
                o.put("auto", autoEligible(context, this));
                o.put("done", XxtStore.isSigned(context, accountId, aid));
            } catch (Exception ignored) {
            }
            return o;
        }
    }

    public static final class Options {
        public String signCode = "";
        /** 手势签到：用户在九宫格画出的序列（如 1478） */
        public String gestureCode = "";
        public String enc = "";
        public String rcode = "";
        public String validate = "";
        public String lat = "";
        public String lon = "";
        public String address = "";
        public String photoPath = "";
        public int appType = XxtApi.APP_TYPE_COURSE;
        /** 用户已在官方 App 完成人脸后重试时置 true：不再被活动的人脸标记拦住，直接尝试提交 */
        public boolean ignoreFace = false;

        public boolean hasLocation() {
            return !lat.isEmpty() && !lon.isEmpty();
        }
    }

    public static final class Result {
        public final String accountId;
        public final String accountName;
        public final String title;
        public final String status;
        public final String message;

        Result(String accountId, String accountName, String title, String status, String message) {
            this.accountId = accountId;
            this.accountName = accountName;
            this.title = title;
            this.status = status;
            this.message = message;
        }

        public boolean ok() {
            return "DONE".equals(status);
        }

        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("accountId", accountId);
                o.put("accountName", accountName);
                o.put("title", title);
                o.put("status", status);
                o.put("message", message);
                o.put("ok", ok());
            } catch (Exception ignored) {
            }
            return o;
        }
    }

    // ------------------------------------------------------------ 活动发现
    public static List<Activity> findActive(Context context, XxtStore.Account account,
                                            XxtSession session) {
        List<Activity> out = new ArrayList<>();
        if (session == null) {
            return out;
        }
        List<XxtApi.Course> courses = XxtApi.courses(session);
        if (courses.isEmpty()) {
            return out;
        }
        Set<String> seen = new LinkedHashSet<>();
        int limit = Math.min(courses.size(), 40);
        for (int i = 0; i < limit; i++) {
            XxtApi.Course course = courses.get(i);
            List<JSONObject> list = XxtApi.activeList(session, session.schoolId,
                    course.courseId, course.classId);
            for (JSONObject item : list) {
                String aid = item.optString("id");
                if (aid.isEmpty() || !seen.add(aid)) {
                    continue;
                }
                JSONObject detail = XxtApi.signDetail(session, aid);
                if (detail.optInt("status", 1) == 2) {
                    continue;
                }
                Activity act = new Activity();
                act.accountId = account.id;
                act.accountName = account.display();
                act.aid = aid;
                act.courseId = course.courseId;
                act.classId = course.classId;
                act.courseName = course.name;
                act.activeType = detail.optInt("activeType",
                        item.optInt("activeType", XxtApi.ACT_SIGN));
                int otherId = detail.optInt("otherId", -1);
                if (otherId < 0) {
                    otherId = item.optInt("otherId", -1);
                }
                act.otherId = otherId;
                int ifPhoto = XxtApi.intAny(detail, "ifPhoto", "ifphoto");
                if (ifPhoto == 0) {
                    ifPhoto = XxtApi.intAny(item, "ifPhoto", "ifphoto");
                }
                act.ifPhoto = ifPhoto;
                act.detail = detail;
                out.add(act);
            }
        }
        return out;
    }

    /** 补全活动详情里的闸门字段（人脸、教师坐标等），进入签到或巡检前调用 */
    public static void prepare(XxtSession session, Activity act) {
        if (act == null || session == null) {
            return;
        }
        JSONObject info = XxtApi.pptInfo(session, act.aid);
        if (act.otherId < 0) {
            act.otherId = XxtApi.intAny(info, "otherId", "otherid");
        }
        if (act.otherId < 0) {
            act.otherId = 0;
        }
        if (act.ifPhoto == 0) {
            act.ifPhoto = XxtApi.intAny(info, "ifphoto", "ifPhoto");
        }
        act.needFace = XxtApi.intAny(info, "openCheckFaceFlag", "opencheckfaceflag") == 1;
        act.teacherAddress = XxtApi.strAny(info, "locationText", "address");
        if (act.teacherAddress.isEmpty()) {
            act.teacherAddress = XxtApi.strAny(act.detail, "locationText", "address");
        }
        act.teacherLat = XxtApi.strAny(info, "locationLatitude", "latitude");
        if (act.teacherLat.isEmpty()) {
            act.teacherLat = XxtApi.strAny(act.detail, "locationLatitude", "latitude");
        }
        act.teacherLon = XxtApi.strAny(info, "locationLongitude", "longitude");
        if (act.teacherLon.isEmpty()) {
            act.teacherLon = XxtApi.strAny(act.detail, "locationLongitude", "longitude");
        }
    }

    // -------------------------------------------------------------- 单个签到
    public static Result sign(Context context, XxtSession session, Activity act, Options opt) {
        if (opt == null) {
            opt = new Options();
        }
        JSONObject info = XxtApi.pptInfo(session, act.aid);
        int otherId = act.otherId;
        if (otherId < 0) {
            otherId = XxtApi.intAny(info, "otherId", "otherid");
        }
        if (otherId < 0) {
            otherId = 0;
        }
        int ifPhoto = act.ifPhoto;
        if (ifPhoto == 0) {
            ifPhoto = XxtApi.intAny(info, "ifphoto", "ifPhoto");
        }
        int needFace = XxtApi.intAny(info, "openCheckFaceFlag", "opencheckfaceflag");
        if (needFace == 1 && !opt.ignoreFace) {
            if (otherId == XxtApi.TYPE_QRCODE) {
                return fail(act, "NEED_FACE",
                        "该扫码签到开启了人脸识别，无法在本应用完成，请到学习通官方 App 签到");
            }
            return fail(act, "NEED_FACE", "要求人脸识别：请到官方 App 完成人脸后返回继续");
        }
        int appType = opt.appType;
        if (ifPhoto == 1 || otherId == XxtApi.TYPE_QRCODE) {
            // 拍照与扫码在官方端都用 appType=15，用课程端参数提交会被服务端拒绝
            appType = XxtApi.APP_TYPE_SCAN;
        }
        boolean needAddress = otherId == XxtApi.TYPE_LOCATION
                || XxtApi.intAny(info, "ifopenAddress") == 1;

        String lat = opt.lat;
        String lon = opt.lon;
        String address = opt.address;
        // 老师发布时设了签到位置，就直接用老师的坐标，避免自己的位置不在范围内
        if (needAddress && validCoord(act.teacherLat, act.teacherLon)) {
            lat = act.teacherLat;
            lon = act.teacherLon;
            address = act.teacherAddress;
        }
        if (lat.isEmpty() || lon.isEmpty()) {
            lat = XxtStore.latitude(context);
            lon = XxtStore.longitude(context);
            address = XxtStore.address(context);
        }
        if (validCoord(lat, lon) && (address == null || address.isEmpty())) {
            address = act.teacherAddress;
        }
        if (needAddress && !validCoord(lat, lon)) {
            return fail(act, "NEED_LOCATION", "需要位置签到：请先在地图上选点");
        }

        // 拍照签到的预签到页会把「请拍照上传」当成提示文字返回，容易被误判为失败，直接走上传+提交
        if (opt.validate.isEmpty() && ifPhoto == 0) {
            try {
                String pre = XxtApi.preSign(session, act.courseId, act.classId,
                        act.detail, opt.rcode, appType);
                if (!pre.isEmpty()) {
                    if (pre.contains("验证码") || pre.contains("滑块") || pre.contains("validate")) {
                        return fail(act, "NEED_CAPTCHA", "需要人机验证，请在官方 App 完成");
                    }
                    if (pre.contains("已过期") || pre.contains("已结束")) {
                        return fail(act, "EXPIRED", pre);
                    }
                    return fail(act, "FAILED", "预签到失败：" + pre);
                }
            } catch (Exception e) {
                return fail(act, "FAILED", "预签到失败：" + e.getMessage());
            }
        }

        String signCode = "";
        LinkedHashMap<String, String> extras = new LinkedHashMap<>();
        if (otherId == XxtApi.TYPE_QRCODE) {
            String enc = opt.enc;
            if (enc.isEmpty()) {
                enc = XxtApi.refreshQr(session, act.aid);
            }
            if (enc.isEmpty()) {
                return fail(act, "NEED_QR", "二维码签到：请按提示拍照或从相册识别大屏二维码");
            }
            extras.put("enc", enc);
        } else if (otherId == XxtApi.TYPE_GESTURE) {
            String gesture = opt.gestureCode;
            if (gesture.isEmpty()) {
                gesture = XxtApi.strAny(act.detail, "gesture", "signGesture", "handGesture", "handSign");
            }
            // 服务端有时会把手势序列下发到签到页，能拿到就不用手画
            if (gesture.isEmpty()) {
                gesture = XxtApi.fetchGesture(session, act.aid);
            }
            if (gesture.isEmpty()) {
                return fail(act, "NEED_CODE", "手势签到：请点「画手势签到」，在九宫格上画一遍");
            }
            try {
                XxtApi.Outcome outcome = XxtApi.gestureSubmit(session, act.aid, gesture);
                if (outcome.ok()) {
                    XxtStore.markSigned(context, act.accountId, act.aid);
                }
                return new Result(act.accountId, act.accountName, act.title(),
                        outcome.status, outcome.message);
            } catch (Exception e) {
                String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                return fail(act, "FAILED", "请求失败：" + msg);
            }
        } else if (otherId == XxtApi.TYPE_CODE) {
            signCode = opt.signCode;
            if (signCode.isEmpty()) {
                signCode = XxtApi.strAny(act.detail, "signCode");
            }
            if (signCode.isEmpty()) {
                return fail(act, "NEED_CODE", "需要签到码，请手动输入");
            }
            String check = XxtApi.checkSignCode(session, act.aid, signCode);
            if (!check.isEmpty()) {
                return fail(act, "FAILED", check);
            }
        } else if (ifPhoto == 1) {
            // 没显式传图片时用设置里保存的那张，单签/一键签/巡检都能复用
            String photoPath = opt.photoPath;
            if (photoPath == null || photoPath.isEmpty()) {
                photoPath = XxtStore.photoPath(context);
            }
            byte[] bytes = readPhoto(context, photoPath);
            if (bytes == null || bytes.length == 0) {
                return fail(act, "NEED_PHOTO", "拍照签到需要图片，请先在设置里选一张");
            }
            String objectId = XxtApi.uploadPhoto(session, bytes);
            if (objectId.isEmpty()) {
                return fail(act, "FAILED", "图片上传失败，请检查网络后重试");
            }
            // 拍照签到按官方页面只带 objectId 提交，不能混入位置/设备参数
            try {
                XxtApi.Outcome outcome = XxtApi.photoSubmit(session, act.aid, objectId);
                String message = outcome.message;
                if (message != null && message.contains("photoAuthError")) {
                    return fail(act, "OFFICIAL_ONLY",
                            "该签到只能在学习通官方 App 完成（平台照片校验未通过），请到官方 App 拍照签到");
                }
                if (outcome.ok()) {
                    XxtStore.markSigned(context, act.accountId, act.aid);
                }
                return new Result(act.accountId, act.accountName, act.title(),
                        outcome.status, message);
            } catch (Exception e) {
                String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                return fail(act, "FAILED", "请求失败：" + msg);
            }
        }

        LinkedHashMap<String, String> params = new LinkedHashMap<>();
        params.put("activeId", act.aid);
        params.put("courseId", act.courseId.isEmpty()
                ? XxtApi.strAny(act.detail, "courseId") : act.courseId);
        params.put("uid", session.uid);
        params.put("clientip", "");
        params.put("latitude", lat);
        params.put("longitude", lon);
        params.put("appType", String.valueOf(appType));
        params.put("fid", session.schoolId);
        params.put("name", session.realName);
        params.put("signCode", signCode);
        params.put("deviceCode", session.deviceCode());
        params.put("location", validCoord(lat, lon)
                ? locationJson(lat, lon, address == null ? "" : address) : "");
        if (!opt.validate.isEmpty()) {
            params.put("validate", opt.validate);
        }
        params.putAll(extras);

        Map<String, String> extraForm = new LinkedHashMap<>();
        if (validCoord(lat, lon)) {
            extraForm.put("locationResult", locationJson(lat, lon, address == null ? "" : address));
        }
        try {
            XxtApi.Outcome outcome = XxtApi.submit(session, params, extraForm);
            String message = outcome.message;
            if (message != null && message.contains("photoAuthError")) {
                // 平台对拍照签到有防代签校验，第三方上传的照片一律会被拒，只能官方 App 现场拍
                return fail(act, "OFFICIAL_ONLY",
                        "该签到只能在学习通官方 App 完成（平台照片校验未通过），请到官方 App 拍照签到");
            }
            if (outcome.ok()) {
                // 记下已完成的活动，刷新列表时直接显示「已完成」，不再重复提交
                XxtStore.markSigned(context, act.accountId, act.aid);
            }
            return new Result(act.accountId, act.accountName, act.title(),
                    outcome.status, message);
        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return fail(act, "FAILED", "请求失败：" + msg);
        }
    }

    /** 一键签到：自动类型直接签，需要码/二维码/图片/位置的活动返回对应状态 */
    public static List<Result> signAll(Context context, XxtStore.Account account,
                                       XxtSession session, List<Activity> acts) {
        List<Result> out = new ArrayList<>();
        if (session == null) {
            out.add(new Result(account.id, account.display(), "账号", "NEED_LOGIN", "未登录"));
            return out;
        }
        for (Activity act : acts) {
            if (!XxtStore.inScope(context, act.courseId)) {
                continue;
            }
            prepare(session, act);
            Options opt = new Options();
            Result r = sign(context, session, act, opt);
            out.add(r);
            XxtStore.addLog(account.display() + " · " + act.title() + " → " + r.message);
            if (r.ok()) {
                XxtStore.markHandled(context, act.aid);
            }
        }
        return out;
    }

    /** 巡检用：只自动处理零门槛活动，其余返回 null 交给通知提醒 */
    public static Result autoSign(Context context, XxtStore.Account account,
                                  XxtSession session, Activity act) {
        if (!XxtStore.inScope(context, act.courseId)) {
            return null;
        }
        prepare(session, act);
        if (!autoEligible(context, act)) {
            return null;
        }
        Options opt = new Options();
        Result r = sign(context, session, act, opt);
        if (r.ok()) {
            // 失败（网络抖动、服务端超时）不记为已处理，下一轮巡检还会再试
            XxtStore.markHandled(context, act.aid);
        }
        XxtStore.addLog("[自动] " + account.display() + " · " + act.title() + " → " + r.message);
        return r;
    }

    public static boolean autoEligible(Context context, Activity act) {
        if (act.needFace) {
            return false;
        }
        // 拍照签到要用户确认照片，不参与自动巡检，列表里标「可立即签到」
        if (act.ifPhoto == 1) {
            return false;
        }
        if (act.otherId == XxtApi.TYPE_NORMAL) {
            return true;
        }
        if (act.otherId == XxtApi.TYPE_LOCATION) {
            return validCoord(act.teacherLat, act.teacherLon)
                    || validCoord(XxtStore.latitude(context), XxtStore.longitude(context));
        }
        return false;
    }

    public static JSONArray activitiesJson(Context context, List<Activity> list) {
        JSONArray arr = new JSONArray();
        for (Activity a : list) {
            arr.put(a.toJson(context));
        }
        return arr;
    }

    // ---------------------------------------------------------------- 工具
    public static boolean validCoord(String lat, String lon) {
        try {
            double la = Double.parseDouble(lat);
            double ln = Double.parseDouble(lon);
            if (la == -1 || ln == -1 || la == 0 || ln == 0) {
                return false;
            }
            return la >= -90 && la <= 90 && ln >= -180 && ln <= 180;
        } catch (Exception e) {
            return false;
        }
    }

    private static String locationJson(String lat, String lon, String address) {
        JSONObject o = new JSONObject();
        try {
            o.put("result", "success");
            o.put("address", address);
            o.put("longitude", lon);
            o.put("latitude", lat);
        } catch (Exception ignored) {
        }
        return o.toString();
    }

    private static Result fail(Activity act, String status, String message) {
        return new Result(act.accountId, act.accountName, act.title(), status, message);
    }

    private static byte[] readPhoto(Context context, String override) {
        String path = override == null || override.isEmpty() ? XxtStore.photoPath(context) : override;
        if (path == null || path.isEmpty()) {
            return null;
        }
        File f = new File(path);
        if (!f.exists()) {
            return null;
        }
        try (InputStream in = new FileInputStream(f)) {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }
}
