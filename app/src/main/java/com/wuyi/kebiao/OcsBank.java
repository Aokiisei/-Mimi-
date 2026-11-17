package com.wuyi.kebiao;

/**
 * 本文件职责：
 * 解析 OCS（ocsjs）格式的题库脚本配置，并按配置向题库接口发起查题请求。
 * 支持单个或多个题库、GET/POST、JSON/表单提交，以及 ${title}、${question}、
 * ${options}、${type} 等模板变量；返回结果里宽松提取 answer 字段。
 */

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public final class OcsBank {

    private static final MediaType JSON_TYPE =
            MediaType.parse("application/json; charset=utf-8");
    private static final MediaType FORM_TYPE =
            MediaType.parse("application/x-www-form-urlencoded; charset=utf-8");

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .followRedirects(true)
            .build();

    private OcsBank() {
    }

    /** 一个 OCS 题库配置 */
    public static final class Bank {
        public String name = "";
        public String homepage = "";
        public String url = "";
        public String method = "post";
        public String contentType = "";
        public JSONObject data = new JSONObject();
        public JSONObject headers = new JSONObject();
    }

    /** 单个题库的查题结果 */
    public static final class Result {
        public String bank = "";
        public String answer = "";
        public String message = "";
        public String raw = "";
        public long millis = 0L;

        public boolean ok() {
            return answer != null && !answer.isEmpty();
        }

        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("bank", bank);
                o.put("ok", ok());
                o.put("answer", answer);
                o.put("message", message);
                o.put("raw", raw);
                o.put("millis", millis);
            } catch (Exception ignored) {
            }
            return o;
        }
    }

    // -------------------------------------------------------------- 配置解析
    public static List<Bank> parse(String config) {
        List<Bank> out = new ArrayList<>();
        String raw = config == null ? "" : config.trim();
        if (raw.isEmpty()) {
            return out;
        }
        if (raw.startsWith("```")) {
            raw = raw.replaceFirst("^```[a-zA-Z]*", "").replaceFirst("```$", "").trim();
        }
        Object root;
        try {
            root = new JSONTokener(raw).nextValue();
        } catch (Exception e) {
            throw new IllegalArgumentException("不是有效的 JSON 配置");
        }
        collect(root, out);
        if (out.isEmpty()) {
            throw new IllegalArgumentException("配置里没有可用的题库（缺少 url）");
        }
        return out;
    }

    private static void collect(Object node, List<Bank> out) {
        if (node instanceof JSONArray) {
            JSONArray arr = (JSONArray) node;
            for (int i = 0; i < arr.length(); i++) {
                collect(arr.opt(i), out);
            }
            return;
        }
        if (!(node instanceof JSONObject)) {
            return;
        }
        JSONObject o = (JSONObject) node;
        if (!text(o, "url").isEmpty()) {
            out.add(read(o));
            return;
        }
        String[] wrappers = {"banks", "list", "configs", "config", "data", "result"};
        for (String key : wrappers) {
            Object child = o.opt(key);
            if (child instanceof JSONArray || child instanceof JSONObject) {
                collect(child, out);
            }
        }
        if (out.isEmpty()) {
            Iterator<String> it = o.keys();
            while (it.hasNext()) {
                Object child = o.opt(it.next());
                if (child instanceof JSONArray || child instanceof JSONObject) {
                    collect(child, out);
                }
            }
        }
    }

    private static Bank read(JSONObject o) {
        Bank b = new Bank();
        b.name = text(o, "name");
        b.homepage = text(o, "homepage");
        b.url = text(o, "url");
        String method = text(o, "method");
        b.method = method.isEmpty() ? "post" : method.toLowerCase(Locale.ROOT);
        b.contentType = text(o, "contentType");
        if (b.contentType.isEmpty()) {
            b.contentType = text(o, "content_type");
        }
        b.contentType = b.contentType.toLowerCase(Locale.ROOT);
        Object data = o.opt("data");
        if (data instanceof JSONObject) {
            b.data = (JSONObject) data;
        }
        Object headers = o.opt("headers");
        if (headers instanceof JSONObject) {
            b.headers = (JSONObject) headers;
        }
        if (b.name.isEmpty()) {
            try {
                String host = java.net.URI.create(b.url).getHost();
                b.name = host == null || host.isEmpty() ? "题库" : host;
            } catch (Exception e) {
                b.name = "题库";
            }
        }
        return b;
    }

    /** 给页面显示用的题库名称，失败返回空串 */
    public static String names(String config) {
        try {
            StringBuilder sb = new StringBuilder();
            for (Bank b : parse(config)) {
                if (sb.length() > 0) {
                    sb.append('、');
                }
                sb.append(b.name);
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    // -------------------------------------------------------------- 查题
    /**
     * 依次向每个题库查题，任何题库出错都不影响其它题库，全部结果原样返回。
     */
    public static List<Result> search(List<Bank> banks, String title, String options, String type) {
        List<Result> out = new ArrayList<>();
        String q = title == null ? "" : title.trim();
        String opts = options == null ? "" : options;
        String qType = type == null || type.trim().isEmpty() ? "0" : type.trim();
        for (Bank b : banks) {
            Result r = new Result();
            r.bank = b.name;
            long start = System.currentTimeMillis();
            try {
                String body = request(b, q, opts, qType);
                r.raw = trim(body, 600);
                String answer = extractAnswer(body);
                if (answer.isEmpty()) {
                    String msg = extractMessage(body);
                    r.message = msg.isEmpty() ? "题库没有返回答案" : msg;
                } else {
                    r.answer = answer;
                }
            } catch (Exception e) {
                String msg = e.getMessage();
                r.message = "请求失败：" + (msg == null || msg.isEmpty()
                        ? e.getClass().getSimpleName() : msg);
            }
            r.millis = System.currentTimeMillis() - start;
            out.add(r);
        }
        return out;
    }

    /** 命中即停，用于自动答题，避免每道题都把全部题库跑一遍 */
    public static Result searchFirst(List<Bank> banks, String title, String options, String type) {
        String q = title == null ? "" : title.trim();
        String opts = options == null ? "" : options;
        String qType = type == null || type.trim().isEmpty() ? "0" : type.trim();
        Result last = null;
        for (Bank b : banks) {
            Result r = new Result();
            r.bank = b.name;
            long start = System.currentTimeMillis();
            try {
                String body = request(b, q, opts, qType);
                r.raw = trim(body, 600);
                String answer = extractAnswer(body);
                if (answer.isEmpty()) {
                    String msg = extractMessage(body);
                    r.message = msg.isEmpty() ? "题库没有返回答案" : msg;
                } else {
                    r.answer = answer;
                }
            } catch (Exception e) {
                String msg = e.getMessage();
                r.message = "请求失败：" + (msg == null || msg.isEmpty()
                        ? e.getClass().getSimpleName() : msg);
            }
            r.millis = System.currentTimeMillis() - start;
            if (r.ok()) {
                return r;
            }
            last = r;
        }
        return last == null ? new Result() : last;
    }


    private static String request(Bank b, String title, String options, String type) throws Exception {
        String url = fill(b.url, title, options, type);
        Map<String, String> data = new LinkedHashMap<>();
        Iterator<String> keys = b.data.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            data.put(key, fill(valueString(b.data.opt(key)), title, options, type));
        }
        Map<String, String> headers = new LinkedHashMap<>();
        Iterator<String> hKeys = b.headers.keys();
        while (hKeys.hasNext()) {
            String key = hKeys.next();
            headers.put(key, fill(valueString(b.headers.opt(key)), title, options, type));
        }

        boolean get = "get".equals(b.method);
        boolean json = b.contentType.contains("json")
                || containsIgnoreCase(headerValue(headers, "Content-Type"), "json");

        Request.Builder builder;
        if (get) {
            String query = encodeMap(data);
            if (!query.isEmpty()) {
                url = url + (url.contains("?") ? "&" : "?") + query;
            }
            builder = new Request.Builder().url(url).get();
        } else {
            RequestBody body;
            if (json) {
                JSONObject obj = new JSONObject();
                for (Map.Entry<String, String> e : data.entrySet()) {
                    obj.put(e.getKey(), e.getValue());
                }
                body = RequestBody.create(JSON_TYPE, obj.toString());
            } else {
                body = RequestBody.create(FORM_TYPE, encodeMap(data));
            }
            builder = new Request.Builder().url(url).post(body);
        }

        boolean hasUa = false;
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if ("User-Agent".equalsIgnoreCase(e.getKey())) {
                hasUa = true;
            }
            try {
                builder.header(e.getKey(), e.getValue());
            } catch (IllegalArgumentException ignored) {
                // OkHttp 会拒绝 Host / Content-Length 等不可手动设置的请求头
            }
        }
        if (!hasUa) {
            builder.header("User-Agent",
                    "Mozilla/5.0 (Linux; Android 13; Pixel 5) AppleWebKit/537.36 "
                            + "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36");
        }
        if (headerValue(headers, "Content-Type") == null && !get) {
            builder.header("Content-Type", json
                    ? "application/json; charset=utf-8"
                    : "application/x-www-form-urlencoded; charset=utf-8");
        }
        if (headerValue(headers, "Accept") == null) {
            builder.header("Accept", "*/*");
        }

        Response response = CLIENT.newCall(builder.build()).execute();
        try {
            String body = response.body() == null ? "" : response.body().string();
            if (body.trim().isEmpty()) {
                throw new IllegalStateException("HTTP " + response.code() + "，响应为空");
            }
            return body;
        } finally {
            response.close();
        }
    }

    private static String headerValue(Map<String, String> headers, String name) {
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (name.equalsIgnoreCase(e.getKey())) {
                return e.getValue();
            }
        }
        return null;
    }

    private static boolean containsIgnoreCase(String text, String part) {
        return text != null && text.toLowerCase(Locale.ROOT).contains(part.toLowerCase(Locale.ROOT));
    }

    private static String encodeMap(Map<String, String> map) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : map.entrySet()) {
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(URLEncoder.encode(e.getKey(), "UTF-8"))
                    .append('=')
                    .append(URLEncoder.encode(e.getValue() == null ? "" : e.getValue(), "UTF-8"));
        }
        return sb.toString();
    }

    // -------------------------------------------------------------- 模板变量
    private static String fill(String template, String title, String options, String type) {
        if (template == null) {
            return "";
        }
        return template
                .replace("${title}", title)
                .replace("${question}", title)
                .replace("${options}", options)
                .replace("${type}", type)
                .replace("${timestamp}", String.valueOf(System.currentTimeMillis() / 1000L));
    }

    // -------------------------------------------------------------- 结果提取
    /** 从题库响应中宽松提取答案；提取不到返回空串 */
    public static String extractAnswer(String body) {
        String raw = body == null ? "" : body.trim();
        if (raw.isEmpty()) {
            return "";
        }
        if (raw.startsWith("{") || raw.startsWith("[")) {
            try {
                Object root = new JSONTokener(raw).nextValue();
                String found = answerFrom(root);
                if (!found.isEmpty()) {
                    return clean(found);
                }
            } catch (Exception ignored) {
            }
        }
        if (raw.startsWith("<")) {
            return clean(trim(stripHtml(raw), 400));
        }
        if (raw.length() <= 400) {
            return clean(raw);
        }
        return "";
    }

    private static String answerFrom(Object node) {
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            String direct = answerValue(o);
            if (!direct.isEmpty()) {
                return direct;
            }
            Iterator<String> it = o.keys();
            while (it.hasNext()) {
                Object child = o.opt(it.next());
                if (child instanceof JSONObject || child instanceof JSONArray) {
                    String found = answerFrom(child);
                    if (!found.isEmpty()) {
                        return found;
                    }
                }
            }
            // 少数接口把答案直接放在 data / result 字符串里
            for (String key : new String[]{"data", "result", "content"}) {
                String v = valueString(o.opt(key));
                if (plausible(v)) {
                    return v;
                }
            }
            return "";
        }
        if (node instanceof JSONArray) {
            JSONArray arr = (JSONArray) node;
            for (int i = 0; i < arr.length(); i++) {
                Object child = arr.opt(i);
                if (child instanceof JSONObject || child instanceof JSONArray) {
                    String found = answerFrom(child);
                    if (!found.isEmpty()) {
                        return found;
                    }
                }
            }
        }
        return "";
    }

    private static String answerValue(JSONObject o) {
        String[] keys = {"answer", "answers", "答案", "正确答案", "correctAnswer",
                "correct_answer", "resultAnswer"};
        for (String key : keys) {
            if (o.has(key)) {
                String v = valueString(o.opt(key));
                if (!v.isEmpty()) {
                    return v;
                }
            }
        }
        Iterator<String> it = o.keys();
        while (it.hasNext()) {
            String key = it.next();
            String lower = key.toLowerCase(Locale.ROOT);
            if (lower.contains("answer") || key.contains("答案")) {
                String v = valueString(o.opt(key));
                if (!v.isEmpty()) {
                    return v;
                }
            }
        }
        return "";
    }

    /** 题库返回的失败信息，用于界面提示 */
    public static String extractMessage(String body) {
        String raw = body == null ? "" : body.trim();
        if (raw.isEmpty()) {
            return "";
        }
        if (raw.startsWith("{") || raw.startsWith("[")) {
            try {
                Object root = new JSONTokener(raw).nextValue();
                String msg = messageFrom(root);
                if (!msg.isEmpty()) {
                    return trim(msg, 160);
                }
            } catch (Exception ignored) {
            }
        }
        if (raw.startsWith("<")) {
            return trim(stripHtml(raw), 120);
        }
        return raw.length() <= 120 ? raw : "";
    }

    private static String messageFrom(Object node) {
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            String[] keys = {"msg", "message", "err", "error", "info", "tip", "errmsg"};
            for (String key : keys) {
                String v = valueString(o.opt(key));
                if (!v.isEmpty()) {
                    return v;
                }
            }
            Iterator<String> it = o.keys();
            while (it.hasNext()) {
                Object child = o.opt(it.next());
                if (child instanceof JSONObject) {
                    String found = messageFrom(child);
                    if (!found.isEmpty()) {
                        return found;
                    }
                }
            }
        }
        return "";
    }

    private static boolean plausible(String value) {
        if (value == null) {
            return false;
        }
        String v = value.trim();
        if (v.isEmpty() || v.length() > 200) {
            return false;
        }
        String lower = v.toLowerCase(Locale.ROOT);
        String[] bad = {"未找到", "没有找到", "不存在", "失败", "错误", "无法", "请先",
                "余额", "积分不足", "token", "error", "invalid", "not found"};
        for (String b : bad) {
            if (lower.contains(b)) {
                return false;
            }
        }
        return !v.startsWith("{") && !v.startsWith("[");
    }

    private static String clean(String raw) {
        if (raw == null) {
            return "";
        }
        String v = raw.trim();
        v = v.replaceAll("^[\"'“”]+", "").replaceAll("[\"'“”]+$", "").trim();
        v = v.replaceFirst("(?i)^(正确答案|参考答案|答案|answer|correct\\s*answer)\\s*[:：]\\s*", "");
        v = unescape(v);
        v = v.replaceAll("\\s*\\r?\\n\\s*", " ").trim();
        return v;
    }

    private static String valueString(Object value) {
        if (value == null || value == JSONObject.NULL) {
            return "";
        }
        if (value instanceof String) {
            return ((String) value).trim();
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        if (value instanceof JSONArray) {
            StringBuilder sb = new StringBuilder();
            JSONArray arr = (JSONArray) value;
            for (int i = 0; i < arr.length(); i++) {
                Object item = arr.opt(i);
                if (item instanceof String || item instanceof Number || item instanceof Boolean) {
                    if (sb.length() > 0) {
                        sb.append(',');
                    }
                    sb.append(String.valueOf(item).trim());
                }
            }
            return sb.toString().trim();
        }
        return "";
    }

    private static String text(JSONObject o, String key) {
        String v = o.optString(key, "");
        return v == null ? "" : v.trim();
    }

    private static String trim(String v, int max) {
        if (v == null) {
            return "";
        }
        String s = v.trim();
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }

    private static String stripHtml(String html) {
        String s = html.replaceAll("(?is)<script.*?</script>", " ")
                .replaceAll("(?is)<style.*?</style>", " ")
                .replaceAll("(?s)<[^>]*>", " ");
        return unescape(s).replaceAll("\\s+", " ").trim();
    }

    private static String unescape(String s) {
        return s.replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&#x27;", "'");
    }
}
