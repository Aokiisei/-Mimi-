package com.wuyi.kebiao;

/**
 * 本文件职责：
 * 学习通章节测验的网络层：从 knowledge/cards 找出 workA 测验任务点、
 * 拉取测验页面、按页面表单原样提交答案。只做请求与结果判定，解析在 XxtWorkParser。
 */

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class XxtWorkApi {

    private static final String URL_CARDS = "https://mooc1.chaoxing.com/mooc-ans/knowledge/cards";
    private static final String URL_WORK = "https://mooc1.chaoxing.com/mooc-ans/api/work";
    private static final String URL_SUBMIT_FALLBACK =
            "https://mooc1.chaoxing.com/mooc-ans/work/addStudentWorkNew";

    public static final class WorkJob {
        public String courseId = "";
        public String clazzId = "";
        public String cpi = "";
        public String chapterId = "";
        public String chapterTitle = "";
        public String jobId = "";
        public String workId = "";
        public String enc = "";
        public String ktoken = "";
        public String name = "";
        public String courseTitle = "";
        public String worktype = "";

        public String key() {
            return courseId + ":" + jobId;
        }

        public boolean isQuiz() {
            return "workA".equalsIgnoreCase(worktype);
        }
    }

    public static final class SubmitResult {
        public boolean ok;
        public String message = "";
        public String raw = "";
    }

    private final XxtSession session;

    public XxtWorkApi(XxtSession session) {
        this.session = session;
    }

    // -------------------------------------------------------------- 任务点
    public List<WorkJob> jobs(XxtStudyApi.Course course, XxtStudyApi.Chapter chapter)
            throws IOException {
        List<WorkJob> out = new ArrayList<>();
        Map<String, WorkJob> seen = new LinkedHashMap<>();
        String cpi = course.cpi == null ? "" : course.cpi;
        String ktoken = "";
        int emptyPages = 0;
        for (int num = 0; num < 24; num++) {
            String url = cardsUrl(course, chapter, num, cpi);
            String referer = URL_CARDS + "?clazzid=" + enc(course.clazzId)
                    + "&courseid=" + enc(course.courseId)
                    + "&knowledgeid=" + enc(chapter.id)
                    + "&ut=s&cpi=" + enc(cpi.isEmpty() ? "0" : cpi)
                    + "&mooc2=1&num=0";
            String html = session.getPc(url, moocHeaders(referer)).body;
            if (html == null || html.contains("章节未开放") || html.contains("暂未开放")) {
                break;
            }
            String raw = extractArg(html);
            if (raw.isEmpty()) {
                break;
            }
            JSONObject root;
            try {
                root = new JSONObject(raw);
            } catch (Exception e) {
                break;
            }
            JSONObject defaults = root.optJSONObject("defaults");
            if (defaults != null) {
                String k = defaults.optString("ktoken", "");
                if (k != null && !k.isEmpty() && !"null".equals(k)) {
                    ktoken = k;
                }
                if (cpi.isEmpty()) {
                    String dc = defaults.optString("cpi", "");
                    if (dc != null && !dc.isEmpty() && !"null".equals(dc)) {
                        cpi = dc;
                    }
                }
            }
            String rootCpi = root.optString("cpi", "");
            if (rootCpi != null && !rootCpi.isEmpty() && !"null".equals(rootCpi)) {
                cpi = rootCpi;
            }
            if (!cpi.isEmpty() && (course.cpi == null || course.cpi.isEmpty())) {
                course.cpi = cpi;
            }
            JSONArray atts = root.optJSONArray("attachments");
            if (atts == null || atts.length() == 0) {
                break;
            }
            int before = seen.size();
            for (int i = 0; i < atts.length(); i++) {
                JSONObject card = atts.optJSONObject(i);
                if (card == null || !"workid".equalsIgnoreCase(card.optString("type", ""))) {
                    continue;
                }
                String jobId = safe(card.optString("jobid", ""));
                String workId = safe(card.optString("workid", ""));
                if (workId.isEmpty() && jobId.startsWith("work-")) {
                    workId = jobId.substring(5);
                }
                if (jobId.isEmpty() && !workId.isEmpty()) {
                    jobId = "work-" + workId;
                }
                if (jobId.isEmpty() || workId.isEmpty()) {
                    continue;
                }
                JSONObject property = card.optJSONObject("property");
                WorkJob job = new WorkJob();
                job.courseId = course.courseId;
                job.clazzId = course.clazzId;
                job.cpi = cpi;
                job.chapterId = chapter.id;
                job.chapterTitle = chapter.title;
                job.courseTitle = course.title;
                job.jobId = jobId;
                job.workId = workId;
                job.enc = safe(card.optString("enc", ""));
                job.ktoken = ktoken;
                if (property != null) {
                    job.name = safe(property.optString("name", ""));
                    if (job.name.isEmpty()) {
                        job.name = safe(property.optString("title", ""));
                    }
                    job.worktype = safe(property.optString("worktype", ""));
                }
                if (job.name.isEmpty()) {
                    job.name = safe(card.optString("name", ""));
                }
                if (job.name.isEmpty()) {
                    job.name = "章节测验";
                }
                if (job.worktype.isEmpty()) {
                    job.worktype = safe(card.optString("worktype", ""));
                }
                seen.put(job.jobId, job);
            }
            if (seen.size() > before) {
                emptyPages = 0;
            } else {
                emptyPages++;
                // 尾部服务端会反复返回同一页，连续两页没有新测验就停
                if (emptyPages >= 2) {
                    break;
                }
            }
            for (WorkJob job : seen.values()) {
                if (job.ktoken.isEmpty()) {
                    job.ktoken = ktoken;
                }
            }
        }
        out.addAll(seen.values());
        return out;
    }

    private String cardsUrl(XxtStudyApi.Course course, XxtStudyApi.Chapter chapter,
                            int num, String cpi) {
        return URL_CARDS + "?clazzid=" + enc(course.clazzId)
                + "&courseid=" + enc(course.courseId)
                + "&knowledgeid=" + enc(chapter.id)
                + "&ut=s&cpi=" + enc(cpi == null || cpi.isEmpty() ? "0" : cpi)
                + "&v=2025-0424-1038-3&mooc2=1&num=" + num;
    }

    /** 从 cards 页里抓出 mArg 的 JSON 对象（按花括号配平，避免正则截断） */
    private static String extractArg(String html) {
        int idx = html.indexOf("mArg=");
        if (idx < 0) {
            return "";
        }
        int start = html.indexOf('{', idx);
        if (start < 0) {
            return "";
        }
        int depth = 0;
        boolean inString = false;
        boolean escape = false;
        for (int i = start; i < html.length(); i++) {
            char c = html.charAt(i);
            if (inString) {
                if (escape) {
                    escape = false;
                } else if (c == '\\') {
                    escape = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return html.substring(start, i + 1);
                }
            }
        }
        return "";
    }

    // -------------------------------------------------------------- 取题
    public String fetch(WorkJob job) throws IOException {
        String referer = referer(job);
        XxtSession.Resp resp = session.getPc(workUrl(job), moocHeaders(referer));
        String body = resp.body == null ? "" : resp.body;
        checkLogin(body);
        return body;
    }

    private String workUrl(WorkJob job) {
        return URL_WORK + "?api=1"
                + "&workId=" + enc(job.workId)
                + "&jobid=" + enc(job.jobId)
                + "&originJobId=" + enc(job.jobId)
                + "&needRedirect=true&skipHeader=true"
                + "&knowledgeid=" + enc(job.chapterId)
                + "&ktoken=" + enc(job.ktoken)
                + "&cpi=" + enc(job.cpi == null || job.cpi.isEmpty() ? "0" : job.cpi)
                + "&ut=s"
                + "&clazzId=" + enc(job.clazzId)
                + "&type="
                + "&enc=" + enc(job.enc)
                + "&mooc2=1"
                + "&courseid=" + enc(job.courseId);
    }

    private String referer(WorkJob job) {
        return "https://mooc1.chaoxing.com/mooc-ans/knowledge/cards"
                + "?clazzid=" + enc(job.clazzId)
                + "&courseid=" + enc(job.courseId)
                + "&knowledgeid=" + enc(job.chapterId)
                + "&ut=s&cpi=" + enc(job.cpi == null || job.cpi.isEmpty() ? "0" : job.cpi)
                + "&mooc2=1";
    }

    // -------------------------------------------------------------- 提交
    public SubmitResult submit(WorkJob job, XxtWorkParser.Page page,
                               Map<String, String> answers, boolean storeOnly)
            throws IOException {
        Map<String, String> form = new LinkedHashMap<>(page.formFields);
        for (XxtWorkParser.Question q : page.questions) {
            String value = answers.get(q.qid);
            if (value == null) {
                continue;
            }
            if (q.richText()) {
                List<String> slots = page.blankFields.get(q.qid);
                if (slots == null || slots.isEmpty()) {
                    if (form.containsKey("answer" + q.qid)) {
                        form.put("answer" + q.qid, value);
                    } else {
                        form.put("answerEditor" + q.qid + "1", value);
                    }
                } else if (slots.size() == 1) {
                    form.put(slots.get(0), value);
                } else {
                    String[] parts = value.split("\\r?\\n");
                    if (parts.length == slots.size()) {
                        for (int i = 0; i < slots.size(); i++) {
                            form.put(slots.get(i), parts[i].trim());
                        }
                    } else {
                        form.put(slots.get(0), value);
                    }
                }
            } else {
                form.put("answer" + q.qid, value);
            }
        }
        StringBuilder qids = new StringBuilder();
        for (XxtWorkParser.Question q : page.questions) {
            if (qids.length() > 0) {
                qids.append(',');
            }
            qids.append(q.qid);
        }
        if (qids.length() > 0) {
            form.put("answerwqbid", qids + ",");
        }
        form.put("pyFlag", storeOnly ? "1" : "");

        String url = resolveAction(page.formAction);
        url = url + (url.contains("?") ? "&" : "?")
                + "ua=pc&formType=post&saveStatus=1&version=1";
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("X-Requested-With", "XMLHttpRequest");
        headers.put("Origin", "https://mooc1.chaoxing.com");
        headers.put("Referer", referer(job));
        XxtSession.Resp resp = session.postFormPc(url, form, headers);
        String body = resp.body == null ? "" : resp.body;
        checkLogin(body);
        SubmitResult result = new SubmitResult();
        result.raw = body.length() > 400 ? body.substring(0, 400) : body;
        try {
            JSONObject json = new JSONObject(body);
            Object status = json.opt("status");
            boolean okFlag = false;
            if (status instanceof Boolean) {
                okFlag = (Boolean) status;
            } else if (status instanceof Number) {
                okFlag = ((Number) status).intValue() != 0;
            } else if (status instanceof String) {
                String s = (String) status;
                okFlag = "true".equalsIgnoreCase(s) || "1".equals(s);
            }
            String msg = json.optString("msg", "");
            if (msg.isEmpty()) {
                msg = okFlag ? "提交成功" : "提交失败";
            }
            result.ok = okFlag && !msg.contains("失败") && !msg.contains("异常");
            result.message = msg;
        } catch (Exception e) {
            result.ok = false;
            result.message = "提交返回异常（HTTP " + resp.code + "）";
        }
        return result;
    }

    private static String resolveAction(String action) {
        String raw = action == null ? "" : action.trim();
        if (raw.isEmpty()) {
            return URL_SUBMIT_FALLBACK;
        }
        if (raw.startsWith("http://") || raw.startsWith("https://")) {
            return raw;
        }
        if (raw.startsWith("/")) {
            return "https://mooc1.chaoxing.com" + raw;
        }
        return "https://mooc1.chaoxing.com/mooc-ans/work/" + raw;
    }

    /** 回查页面里的「第 N 次作答 / 本次成绩 X 分」 */
    public String score(WorkJob job) {
        try {
            String html = fetch(job);
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("class=\"achievement\"[^>]*>[\\s\\S]{0,40}?<i[^>]*>(\\d+(?:\\.\\d+)?)</i>")
                    .matcher(html);
            if (m.find()) {
                return m.group(1);
            }
            m = java.util.regex.Pattern.compile("本次成绩[\\s\\S]{0,80}?(\\d+(?:\\.\\d+)?)\\s*分")
                    .matcher(html);
            return m.find() ? m.group(1) : "";
        } catch (Exception e) {
            return "";
        }
    }

    // -------------------------------------------------------------- 工具
    private static void checkLogin(String body) throws IOException {
        if (body.contains("passport2.chaoxing.com/mlogin")
                || body.contains("passport2.chaoxing.com/login")
                || body.contains("请输入手机号")) {
            throw new IOException("登录状态已失效");
        }
    }

    private static Map<String, String> moocHeaders(String referer) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
        if (referer != null && !referer.isEmpty()) {
            headers.put("Referer", referer);
        }
        return headers;
    }

    private static String safe(String v) {
        return v == null || "null".equals(v) ? "" : v.trim();
    }

    private static String enc(String v) {
        try {
            return URLEncoder.encode(v == null ? "" : v, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }
}
