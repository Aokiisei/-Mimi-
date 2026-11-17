package com.wuyi.kebiao;

/**
 * 本文件职责：
 * 单个学习通账号的会话。负责密码登录（优先明文接口，失败回退官方网页接口）、
 * Cookie 维护与持久化、以及带统一 UA / Cookie 的 GET、表单、multipart 请求。
 * 只与 chaoxing.com 域通信，不影响教务与快递平台登录态。
 */

import android.util.Base64;

import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import okhttp3.FormBody;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public final class XxtSession {

    private static final String UA = "Mozilla/5.0 (Linux; Android 13; Pixel 5) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";

    /** 刷课用的 mooc2-ans 页面按 PC 端渲染，必须带桌面 UA */
    private static final String PC_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    private static final String URL_MLOGIN =
            "https://passport2.chaoxing.com/mlogin?fid=&newversion=true&refer=http%3A%2F%2Fi.chaoxing.com";
    private static final String URL_SELF_HOME = "https://i.chaoxing.com/base";
    private static final String URL_ACCOUNT_MANAGE = "https://passport2.chaoxing.com/mooc/accountManage";
    private static final String URL_PROBE = "https://mooc2-ans.chaoxing.com/mooc2-ans/visit/interaction";
    private static final String REFER_ENCODED = "http%3A%2F%2Fi.chaoxing.com";
    private static final String AES_KEY = "u2oh6Vu^HWe4_AES";

    private final XxtStore.Account account;
    private final LinkedHashMap<String, String> cookies = new LinkedHashMap<>();
    private final OkHttpClient client;

    public String uid;
    public String realName;
    public String schoolId;

    public XxtSession(XxtStore.Account account) {
        this.account = account;
        this.uid = safe(account.uid);
        this.realName = safe(account.name);
        this.schoolId = safe(account.schoolId);
        restoreCookies(account.cookies);
        this.client = new OkHttpClient.Builder()
                .connectTimeout(12, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .writeTimeout(20, TimeUnit.SECONDS)
                .followRedirects(true)
                .build();
    }

    public XxtStore.Account account() {
        return account;
    }

    public String deviceCode() {
        String s = safe(account.schild);
        return s.replace("?", "").replace("&", "");
    }

    public boolean hasCookies() {
        return !cookies.isEmpty();
    }

    // ------------------------------------------------------------ Cookie
    private void restoreCookies(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return;
        }
        try {
            JSONObject o = new JSONObject(raw);
            java.util.Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next();
                String v = o.optString(k, "");
                if (!k.isEmpty() && !v.isEmpty()) {
                    cookies.put(k, v);
                }
            }
        } catch (Exception ignored) {
        }
    }

    private String dumpCookies() {
        JSONObject o = new JSONObject();
        try {
            for (Map.Entry<String, String> e : cookies.entrySet()) {
                o.put(e.getKey(), e.getValue());
            }
        } catch (Exception ignored) {
        }
        return o.toString();
    }

    private String cookieHeader() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : cookies.entrySet()) {
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    private synchronized void absorbCookies(Response response) {
        if (response == null) {
            return;
        }
        for (String header : response.headers("Set-Cookie")) {
            if (header == null) {
                continue;
            }
            String pair = header;
            int semi = pair.indexOf(';');
            if (semi >= 0) {
                pair = pair.substring(0, semi);
            }
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String name = pair.substring(0, eq).trim();
            String value = pair.substring(eq + 1).trim();
            String lower = header.toLowerCase(java.util.Locale.ROOT);
            boolean expired = lower.contains("max-age=0")
                    || lower.contains("expires=thu, 01 jan 1970");
            if (expired || value.isEmpty()) {
                cookies.remove(name);
            } else {
                cookies.put(name, value);
            }
        }
    }

    public synchronized void persist() {
        if (uid.isEmpty()) {
            String v = cookies.get("_uid");
            if (v != null) {
                uid = v;
            }
        }
        if (schoolId.isEmpty()) {
            String v = cookies.get("fid");
            if (v != null) {
                schoolId = v;
            }
        }
        account.uid = safe(uid);
        account.name = safe(realName);
        account.schoolId = safe(schoolId);
        account.cookies = dumpCookies();
        if (account.schild == null || account.schild.trim().isEmpty()) {
            account.schild = "mimi" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        }
    }

    // ---------------------------------------------------------------- 请求
    public Resp get(String url) throws IOException {
        return execute(new Request.Builder().url(url).get().build());
    }

    public Resp get(String url, Map<String, String> headers) throws IOException {
        return execute(build(new Request.Builder().url(url).get(), headers));
    }

    public Resp postForm(String url, Map<String, String> form, Map<String, String> headers) throws IOException {
        FormBody.Builder fb = new FormBody.Builder();
        if (form != null) {
            for (Map.Entry<String, String> e : form.entrySet()) {
                fb.add(e.getKey(), e.getValue() == null ? "" : e.getValue());
            }
        }
        return execute(build(new Request.Builder().url(url).post(fb.build()), headers));
    }

    /** 刷课接口专用：桌面 UA，章节页与任务点页面按 PC 结构返回 */
    public Resp getPc(String url, Map<String, String> headers) throws IOException {
        return execute(build(new Request.Builder().url(url).get(), headers), PC_UA);
    }

    public Resp postFormPc(String url, Map<String, String> form, Map<String, String> headers) throws IOException {
        FormBody.Builder fb = new FormBody.Builder();
        if (form != null) {
            for (Map.Entry<String, String> e : form.entrySet()) {
                fb.add(e.getKey(), e.getValue() == null ? "" : e.getValue());
            }
        }
        return execute(build(new Request.Builder().url(url).post(fb.build()), headers), PC_UA);
    }

    public Resp postMultipart(String url, byte[] data, String fileName, String mime,
                              Map<String, String> fields) throws IOException {
        MediaType type = MediaType.parse(mime == null ? "application/octet-stream" : mime);
        MultipartBody.Builder mb = new MultipartBody.Builder().setType(MultipartBody.FORM);
        mb.addFormDataPart("file", fileName == null ? "image.jpg" : fileName,
                RequestBody.create(type, data));
        if (fields != null) {
            for (Map.Entry<String, String> e : fields.entrySet()) {
                mb.addFormDataPart(e.getKey(), e.getValue() == null ? "" : e.getValue());
            }
        }
        return execute(new Request.Builder().url(url).post(mb.build()).build());
    }

    private Request build(Request.Builder builder, Map<String, String> headers) {
        if (headers != null) {
            for (Map.Entry<String, String> e : headers.entrySet()) {
                builder.header(e.getKey(), e.getValue());
            }
        }
        return builder.build();
    }

    private Resp execute(Request request) throws IOException {
        return execute(request, UA);
    }

    private Resp execute(Request request, String ua) throws IOException {
        Request.Builder b = request.newBuilder()
                .header("User-Agent", ua)
                .header("Accept", "*/*")
                .header("Accept-Language", "zh-CN,zh;q=0.9");
        String cookie = cookieHeader();
        if (!cookie.isEmpty()) {
            b.header("Cookie", cookie);
        }
        Response response = client.newCall(b.build()).execute();
        try {
            absorbCookies(response);
            String body = response.body() == null ? "" : response.body().string();
            return new Resp(response.code(), body);
        } finally {
            response.close();
        }
    }

    public static final class Resp {
        public final int code;
        public final String body;

        Resp(int code, String body) {
            this.code = code;
            this.body = body == null ? "" : body;
        }
    }

    // ---------------------------------------------------------------- 登录
    /** 返回空串表示成功，否则为错误信息 */
    public String login() throws Exception {
        if (safe(account.phone).isEmpty() || safe(account.password).isEmpty()) {
            return "缺少账号或密码";
        }
        try {
            get(URL_MLOGIN);
        } catch (Exception ignored) {
        }
        String err = apiLogin();
        if (!err.isEmpty()) {
            err = fanYaLogin(err);
        }
        if (!err.isEmpty()) {
            account.lastError = err;
            persist();
            return err;
        }
        return finalizeLogin();
    }

    private String apiLogin() {
        try {
            String url = "https://passport2.chaoxing.com/api/login?name=" + url(account.phone)
                    + "&pwd=" + url(account.password) + "&schoolid=&verify=";
            Resp r = get(url, header("X-Requested-With", "XMLHttpRequest"));
            JSONObject j = new JSONObject(r.body);
            if (j.optBoolean("result")) {
                String u = j.optString("uid");
                String n = j.optString("realname");
                String sid = j.optString("schoolid");
                if (!u.isEmpty()) {
                    uid = u;
                }
                if (!n.isEmpty()) {
                    realName = n;
                }
                if (!sid.isEmpty()) {
                    schoolId = sid;
                }
                return "";
            }
            String msg = j.optString("errorMsg");
            return msg.isEmpty() ? "账号或密码错误" : msg;
        } catch (Exception e) {
            return "账号密码登录失败：" + e.getMessage();
        }
    }

    private String fanYaLogin(String prevErr) {
        try {
            Map<String, String> form = new LinkedHashMap<>();
            form.put("uname", aesEncrypt(account.phone));
            form.put("password", aesEncrypt(account.password));
            form.put("fid", "-1");
            form.put("t", "true");
            form.put("refer", REFER_ENCODED);
            form.put("forbidotherlogin", "0");
            form.put("validate", "");
            form.put("doubleFactorLogin", "0");
            form.put("independentId", "0");
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("X-Requested-With", "XMLHttpRequest");
            headers.put("Referer", URL_MLOGIN);
            headers.put("Origin", "https://passport2.chaoxing.com");
            Resp r = postForm("https://passport2.chaoxing.com/fanyalogin", form, headers);
            JSONObject j = new JSONObject(r.body);
            if (j.optBoolean("status")) {
                return "";
            }
            String msg = j.optString("msg");
            if (msg.isEmpty()) {
                msg = j.optString("message");
            }
            return msg.isEmpty() ? prevErr : msg;
        } catch (Exception e) {
            return prevErr;
        }
    }

    private String finalizeLogin() {
        try {
            get(URL_SELF_HOME);
        } catch (Exception ignored) {
        }
        try {
            Resp r = get(URL_ACCOUNT_MANAGE);
            String n = matchName(r.body);
            if (!n.isEmpty()) {
                realName = n;
            }
        } catch (Exception ignored) {
        }
        persist();
        if (uid.isEmpty()) {
            account.lastError = "登录未建立会话，可能需要先过图形验证码";
            persist();
            return account.lastError;
        }
        account.lastError = "";
        account.lastLoginAt = System.currentTimeMillis();
        persist();
        return "";
    }

    private static String matchName(String html) {
        if (html == null) {
            return "";
        }
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("id=\"messageName\"[^>]*>([^<]+)<")
                    .matcher(html);
            if (m.find()) {
                return m.group(1).trim();
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    /** 探测会话是否仍可用：拉一次业务页，看是否被踢回登录页 */
    public boolean probe() {
        try {
            Resp r = get(URL_PROBE);
            if (r.code == 401 || r.code == 403) {
                return false;
            }
            String b = r.body;
            if (b == null || b.trim().isEmpty()) {
                return false;
            }
            return !(b.contains("请输入手机号") || b.contains("忘记密码")
                    || b.contains("fanyalogin") || b.contains("passport2.chaoxing.com/mlogin")
                    || b.contains("passport2.chaoxing.com/login"));
        } catch (Exception e) {
            return false;
        }
    }

    /** 保证有可用会话；返回空串表示正常 */
    public String ensureLogin() {
        try {
            if (cookies.isEmpty()) {
                return login();
            }
            if (!probe()) {
                XxtStore.addLog("学习通会话已失效，重新登录：" + safe(realName));
                return login();
            }
            persist();
            return "";
        } catch (Exception e) {
            return e.getMessage() == null ? "登录异常" : e.getMessage();
        }
    }

    // ---------------------------------------------------------------- 工具
    private static Map<String, String> header(String k, String v) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(k, v);
        return m;
    }

    private static String url(String v) {
        try {
            return java.net.URLEncoder.encode(v == null ? "" : v, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    /** 学习通网页登录的密码加密：AES/CBC/PKCS7，密钥与 IV 均为固定 16 字节 */
    private static String aesEncrypt(String text) throws Exception {
        byte[] keyBytes = AES_KEY.getBytes(StandardCharsets.UTF_8);
        SecretKeySpec key = new SecretKeySpec(keyBytes, "AES");
        IvParameterSpec iv = new IvParameterSpec(keyBytes);
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, key, iv);
        byte[] out = cipher.doFinal(text.getBytes(StandardCharsets.UTF_8));
        return Base64.encodeToString(out, Base64.NO_WRAP);
    }

    private static String safe(String v) {
        return v == null ? "" : v.trim();
    }
}
