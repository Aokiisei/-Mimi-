package com.wuyi.kebiao;

/**
 * 本文件职责：
 * 学习通「刷课」只读视频任务点所需的网页接口：课程列表、章节目录、任务点卡片、
 * 视频状态与进度心跳上报。全部按公开协议独立实现，只处理 video 类型，
 * 不解析章节测验与作业。
 */

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class XxtStudyApi {

    private static final String URL_COURSE_LIST =
            "https://mooc2-ans.chaoxing.com/mooc2-ans/visit/courselistdata"
                    + "?courseType=1&courseFolderId=0&query=&pageHeader=-1&single=0&superstarClass=0";
    private static final String URL_CHAPTER_LIST =
            "https://mooc2-ans.chaoxing.com/mooc2-ans/mycourse/studentcourse";
    private static final String URL_CHAPTER_LIST_FALLBACK =
            "https://mooc1.chaoxing.com/mycourse/studentcourse";
    private static final String URL_CARDS = "https://mooc1.chaoxing.com/mooc-ans/knowledge/cards";
    private static final String URL_VIDEO_STATUS = "https://mooc1.chaoxing.com/ananas/status/";
    private static final String URL_VIDEO_LOG = "https://mooc1.chaoxing.com/mooc-ans/multimedia/log/a/";

    /** 超星播放器硬编码的 enc 盐值 */
    private static final String SALT = "d_yHJ!$pdA~5";

    private final XxtSession session;

    public XxtStudyApi(XxtSession session) {
        this.session = session;
    }

    // ---------------------------------------------------------------- 模型
    public static final class Course {
        public String courseId = "";
        public String clazzId = "";
        public String cpi = "";
        public String title = "";
        public String teacher = "";

        public String key() {
            return courseId + ":" + clazzId;
        }
    }

    public static final class Chapter {
        public String id = "";
        public String title = "";
        public int jobCount;
        public boolean finished;
    }

    public static final class VideoJob {
        public String jobId = "";
        public String objectId = "";
        public String name = "";
        public String otherInfo = "";
        public String rt = "0.9";
        public String faceEnc = "";
        public int attDuration;
        public boolean passed;
    }

    public static final class VideoStatus {
        public boolean ok;
        public String dtoken = "";
        public int duration;
    }

    // ---------------------------------------------------------------- 课程
    public List<Course> courses() throws IOException {
        LinkedHashMap<String, Course> out = new LinkedHashMap<>();
        String html = "";
        try {
            html = session.getPc(URL_COURSE_LIST, moocHeaders(null)).body.replace("&amp;", "&");
            parseCourses(html, out);
        } catch (IOException ignored) {
        }
        // 课程可能被放进「课程文件夹」，无论主列表是否有内容都要逐个文件夹补拉
        List<String> folders = parseFolders(html);
        if (folders.isEmpty()) {
            folders = foldersFromCoursePage();
        }
        for (String folderId : folders) {
            String url = URL_COURSE_LIST.replace("courseFolderId=0", "courseFolderId=" + folderId);
            try {
                parseCourses(session.getPc(url, moocHeaders(null)).body.replace("&amp;", "&"), out);
            } catch (IOException ignored) {
            }
        }
        // 兜底补全：移动端课程接口能拿到跨文件夹 / 历史学期的课程，只补缺不覆盖
        mergeFallback(out);
        return new ArrayList<>(out.values());
    }

    private List<String> foldersFromCoursePage() {
        List<String> ids = new ArrayList<>();
        String[] pages = {
                "https://mooc2-ans.chaoxing.com/mooc2-ans/visit/interaction",
                "https://mooc2-ans.chaoxing.com/visit/interaction"};
        for (String page : pages) {
            try {
                String html = session.getPc(page, moocHeaders(null)).body.replace("&amp;", "&");
                for (String id : parseFolders(html)) {
                    if (!ids.contains(id)) {
                        ids.add(id);
                    }
                }
            } catch (IOException ignored) {
            }
            if (!ids.isEmpty()) {
                break;
            }
        }
        return ids;
    }

    private void mergeFallback(Map<String, Course> out) {
        for (XxtApi.Course c : XxtApi.courses(session)) {
            if (c.courseId == null || c.courseId.trim().isEmpty()
                    || c.classId == null || c.classId.trim().isEmpty()) {
                continue;
            }
            Course item = new Course();
            item.courseId = c.courseId;
            item.clazzId = c.classId;
            item.title = c.name == null ? "" : c.name.trim();
            Course old = out.get(item.key());
            if (old == null) {
                if (item.title.isEmpty()) {
                    item.title = item.courseId;
                }
                out.put(item.key(), item);
            } else if (old.title.trim().isEmpty() && !item.title.isEmpty()) {
                old.title = item.title;
            }
        }
    }

    private void parseCourses(String html, Map<String, Course> out) {
        if (html == null || html.isEmpty()) {
            return;
        }
        Matcher divider = Pattern.compile(
                "<div[^>]*class=\"[^\"]*(?<![-\\w])course(?![-\\w])[^\"]*\"[^>]*>").matcher(html);
        List<Integer> starts = new ArrayList<>();
        while (divider.find()) {
            starts.add(divider.start());
        }
        for (int i = 0; i < starts.size(); i++) {
            int end = i + 1 < starts.size() ? starts.get(i + 1) : html.length();
            String block = html.substring(starts.get(i), end);
            if (block.contains("not-open-tip")) {
                continue;
            }
            Course c = new Course();
            c.clazzId = inputValue(block, "clazzId");
            c.courseId = inputValue(block, "courseId");
            if (c.clazzId.isEmpty() || c.courseId.isEmpty()) {
                continue;
            }
            Matcher cpi = Pattern.compile("cpi=([^&\"'\\s]+)").matcher(block);
            if (cpi.find()) {
                c.cpi = cpi.group(1);
            }
            c.title = attrOrText(block, "span", "course-name");
            if (c.title.isEmpty()) {
                c.title = c.courseId;
            }
            c.teacher = firstAttr(block, "<p[^>]*class=\"[^\"]*color3[^\"]*\"[^>]*>", "title");
            c.title = decode(c.title);
            c.teacher = decode(c.teacher);
            Course old = out.get(c.key());
            if (old == null) {
                out.put(c.key(), c);
            } else {
                if (old.cpi.isEmpty()) {
                    old.cpi = c.cpi;
                }
                if (old.title.isEmpty() || old.title.equals(old.courseId)) {
                    old.title = c.title;
                }
                if (old.teacher.isEmpty()) {
                    old.teacher = c.teacher;
                }
            }
        }
    }

    private static List<String> parseFolders(String html) {
        List<String> ids = new ArrayList<>();
        if (html == null) {
            return ids;
        }
        Matcher m = Pattern.compile("<li[^>]*(?:fileid|courseFolderId)=\"(\\d+)\"").matcher(html);
        while (m.find() && ids.size() < 30) {
            String id = m.group(1);
            if (!"0".equals(id) && !ids.contains(id)) {
                ids.add(id);
            }
        }
        return ids;
    }

    // ---------------------------------------------------------------- 章节
    public List<Chapter> chapters(Course course) throws IOException {
        List<Chapter> out = new ArrayList<>();
        String url = URL_CHAPTER_LIST + "?courseid=" + enc(course.courseId)
                + "&clazzid=" + enc(course.clazzId)
                + (course.cpi.isEmpty() ? "" : "&cpi=" + enc(course.cpi))
                + "&ut=s";
        String html = session.getPc(url, moocHeaders(URL_CHAPTER_LIST)).body;
        parseChapters(html, out);
        if (course.cpi.isEmpty()) {
            String cpi = attrValue(html, "id=\"curCpi\"", "value");
            if (cpi.isEmpty()) {
                cpi = attrValue(html, "name=\"curCpi\"", "value");
            }
            course.cpi = cpi;
        }
        if (out.isEmpty()) {
            String fallback = URL_CHAPTER_LIST_FALLBACK + "?courseId=" + enc(course.courseId)
                    + "&clazzid=" + enc(course.clazzId) + "&v=2";
            html = session.getPc(fallback, moocHeaders(URL_CHAPTER_LIST_FALLBACK)).body;
            parseChapters(html, out);
        }
        return out;
    }

    private void parseChapters(String html, List<Chapter> out) {
        if (html == null || html.isEmpty()) {
            return;
        }
        Set<String> seen = new LinkedHashSet<>();
        for (Chapter c : out) {
            seen.add(c.id);
        }
        List<Integer> starts = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        Matcher m = Pattern.compile("id=\"cur(\\d+)\"").matcher(html);
        while (m.find()) {
            starts.add(m.start());
            ids.add(m.group(1));
        }
        for (int i = 0; i < starts.size(); i++) {
            if (!seen.add(ids.get(i))) {
                continue;
            }
            int end = i + 1 < starts.size() ? starts.get(i + 1) : html.length();
            String block = html.substring(starts.get(i), end);
            Chapter c = new Chapter();
            c.id = ids.get(i);
            c.title = chapterTitle(block, c.id);
            String count = firstGroup(block, "class=\"[^\"]*knowledgeJobCount[^\"]*\"[^>]*value=\"(\\d+)\"");
            if (count.isEmpty()) {
                count = firstGroup(block, "value=\"(\\d+)\"[^>]*class=\"[^\"]*knowledgeJobCount");
            }
            c.jobCount = parseInt(count, 0);
            c.finished = block.contains("icon_yiwanc");
            out.add(c);
        }
    }

    /**
     * 章节标题。优先读 clicktitle 的 title 属性 / 文本（形如「3.7 视频名称」），
     * 都读不到时退回整块文本里带编号的那一段，最后才用「章节 id」。
     */
    private static String chapterTitle(String block, String id) {
        String t = firstAttr(block, "<[a-zA-Z][^>]*class=\"[^\"]*clicktitle[^\"]*\"[^>]*>", "title");
        if (t.trim().isEmpty()) {
            t = firstAttr(block, "<[a-zA-Z][^>]*class=\"[^\"]*chapterText[^\"]*\"[^>]*>", "title");
        }
        if (t.trim().isEmpty()) {
            Matcher m = Pattern.compile("class=\"[^\"]*clicktitle[^\"]*\"[^>]*>([\\s\\S]*?)</a>").matcher(block);
            if (m.find()) {
                t = m.group(1).replaceAll("<[^>]+>", " ");
            }
        }
        t = t.replaceAll("<[^>]+>", " ").replace("&nbsp;", " ").replaceAll("\\s+", " ").trim();
        if (t.isEmpty() || t.matches("[\\d\\s.]+")) {
            String text = block.replaceAll("<script[\\s\\S]*?</script>", " ")
                    .replaceAll("<style[\\s\\S]*?</style>", " ")
                    .replaceAll("<[^>]+>", " ").replace("&nbsp;", " ")
                    .replaceAll("\\s+", " ").trim();
            Matcher m = Pattern.compile("(\\d+(?:\\.\\d+)+)\\s*([^\\d]{2,60})").matcher(text);
            if (m.find()) {
                t = (m.group(1) + " " + m.group(2)).trim();
            } else if (!text.isEmpty()) {
                t = text.length() > 60 ? text.substring(0, 60) : text;
            }
        }
        t = decode(t);
        return t.isEmpty() ? ("章节 " + id) : t;
    }

    // ---------------------------------------------------------------- 任务点
    public List<VideoJob> videoJobs(Course course, Chapter chapter) throws IOException {
        List<VideoJob> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        String referer = cardsUrl(course, chapter, 0);
        for (int num = 0; num < 5; num++) {
            String url = cardsUrl(course, chapter, num);
            String html = session.getPc(url, moocHeaders(referer)).body;
            if (html.contains("章节未开放") || html.contains("暂未开放")) {
                break;
            }
            String mArg = extractObject(html);
            if (mArg.isEmpty()) {
                break;
            }
            JSONObject root;
            try {
                root = new JSONObject(mArg);
            } catch (Exception e) {
                break;
            }
            JSONObject defaults = root.optJSONObject("defaults");
            if (defaults != null && course.cpi.isEmpty()) {
                course.cpi = defaults.optString("cpi", "");
            }
            JSONArray atts = root.optJSONArray("attachments");
            if (atts == null || atts.length() == 0) {
                break;
            }
            boolean added = false;
            for (int i = 0; i < atts.length(); i++) {
                JSONObject a = atts.optJSONObject(i);
                if (a == null || !"video".equalsIgnoreCase(a.optString("type", ""))) {
                    continue;
                }
                VideoJob job = new VideoJob();
                job.jobId = a.optString("jobid", a.optString("jobId", ""));
                job.objectId = a.optString("objectId", a.optString("objectid", ""));
                if (job.jobId.isEmpty() || job.objectId.isEmpty()) {
                    continue;
                }
                job.otherInfo = a.optString("otherInfo", "");
                int amp = job.otherInfo.indexOf('&');
                if (amp > 0) {
                    job.otherInfo = job.otherInfo.substring(0, amp);
                }
                JSONObject property = a.optJSONObject("property");
                if (property != null) {
                    job.name = property.optString("name", "");
                    job.rt = property.optString("rt", "0.9");
                    job.attDuration = (int) numOf(property.opt("duration"));
                    job.faceEnc = property.optString("videoFaceCaptureEnc", "");
                }
                if (job.name.isEmpty()) {
                    job.name = a.optString("name", "");
                }
                if (job.rt.trim().isEmpty()) {
                    job.rt = "0.9";
                }
                job.passed = a.optBoolean("isPassed", false);
                if (seen.add(job.jobId)) {
                    out.add(job);
                }
                added = true;
            }
            if (!added && num > 0) {
                break;
            }
        }
        return out;
    }

    private String cardsUrl(Course course, Chapter chapter, int num) {
        return URL_CARDS + "?clazzid=" + enc(course.clazzId)
                + "&courseid=" + enc(course.courseId)
                + "&knowledgeid=" + enc(chapter.id)
                + "&num=" + num
                + "&ut=s"
                + (course.cpi.isEmpty() ? "" : "&cpi=" + enc(course.cpi))
                + "&v=20160407-3&mooc2=1";
    }

    // ---------------------------------------------------------------- 视频状态
    public VideoStatus videoStatus(Course course, VideoJob job) throws IOException {
        String k = safe(session.schoolId);
        if (k.isEmpty()) {
            k = safe(course.cpi);
        }
        VideoStatus st = requestStatus(course, job, k);
        if (!st.ok && !k.equals(safe(course.cpi))) {
            st = requestStatus(course, job, safe(course.cpi));
        }
        return st;
    }

    private VideoStatus requestStatus(Course course, VideoJob job, String k) throws IOException {
        VideoStatus st = new VideoStatus();
        String url = URL_VIDEO_STATUS + enc(job.objectId) + "?k=" + enc(k) + "&flag=normal";
        Map<String, String> headers = moocHeaders("https://mooc1.chaoxing.com/ananas/modules/video/index.html");
        XxtSession.Resp r = session.getPc(url, headers);
        if (r.body == null || !r.body.trim().startsWith("{")) {
            return st;
        }
        try {
            JSONObject o = new JSONObject(r.body);
            st.dtoken = o.optString("dtoken", "");
            st.duration = (int) numOf(o.opt("duration"));
            st.ok = !st.dtoken.isEmpty() || o.optBoolean("status", false);
        } catch (Exception ignored) {
        }
        return st;
    }

    // ---------------------------------------------------------------- 进度上报
    /** 返回服务端是否确认完成 */
    public boolean report(Course course, VideoJob job, VideoStatus st,
                          int playingTime, int isdrag) throws IOException {
        String uid = safe(session.uid);
        if (uid.isEmpty()) {
            uid = safe(session.account().uid);
        }
        int duration = st.duration;
        if (duration <= 0) {
            duration = Math.max(job.attDuration, playingTime);
        }
        String clipTime = "0_" + duration;
        String raw = "[" + course.clazzId + "][" + uid + "][" + job.jobId + "]["
                + job.objectId + "][" + (playingTime * 1000L) + "][" + SALT + "]["
                + (duration * 1000L) + "][" + clipTime + "]";
        String enc = md5(raw);
        String url = URL_VIDEO_LOG + enc(course.cpi) + "/" + enc(st.dtoken)
                + "?clazzId=" + enc(course.clazzId)
                + "&playingTime=" + playingTime
                + "&duration=" + duration
                + "&clipTime=" + enc(clipTime)
                + "&objectId=" + enc(job.objectId)
                + "&otherInfo=" + enc(job.otherInfo)
                + "&jobid=" + enc(job.jobId)
                + "&userid=" + enc(uid)
                + "&isdrag=" + isdrag
                + "&view=pc"
                + "&enc=" + enc
                + "&rt=" + enc(job.rt)
                + "&dtype=Video"
                + "&_t=" + System.currentTimeMillis();
        Map<String, String> headers = moocHeaders("https://mooc1.chaoxing.com/mooc-ans/knowledge/cards");
        headers.put("Accept", "application/json, text/javascript, */*; q=0.01");
        headers.put("X-Requested-With", "XMLHttpRequest");
        XxtSession.Resp r = session.getPc(url, headers);
        if (r.body == null) {
            return false;
        }
        String body = r.body.trim();
        if (body.startsWith("{")) {
            try {
                JSONObject o = new JSONObject(body);
                return o.optBoolean("isPassed", false);
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- 工具
    private static Map<String, String> moocHeaders(String referer) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
        if (referer != null && !referer.isEmpty()) {
            headers.put("Referer", referer);
        }
        return headers;
    }

    private static String inputValue(String block, String className) {
        Matcher m = Pattern.compile("<input[^>]*class=\"[^\"]*\\b" + className + "\\b[^\"]*\"[^>]*>")
                .matcher(block);
        while (m.find()) {
            Matcher v = Pattern.compile("value=\"([^\"]*)\"").matcher(m.group());
            if (v.find()) {
                return v.group(1);
            }
        }
        Matcher m2 = Pattern.compile("<input[^>]*value=\"([^\"]*)\"[^>]*class=\"[^\"]*\\b"
                + className + "\\b[^\"]*\"[^>]*>").matcher(block);
        if (m2.find()) {
            return m2.group(1);
        }
        return "";
    }

    private static String attrValue(String html, String marker, String attr) {
        int i = html.indexOf(marker);
        if (i < 0) {
            return "";
        }
        int end = html.indexOf('>', i);
        String tag = end < 0 ? html.substring(i) : html.substring(i, end);
        Matcher m = Pattern.compile(attr + "=\"([^\"]*)\"").matcher(tag);
        return m.find() ? m.group(1) : "";
    }

    private static String attrOrText(String block, String tag, String className) {
        Matcher m = Pattern.compile("<" + tag + "[^>]*class=\"[^\"]*" + className
                + "[^\"]*\"[^>]*>([\\s\\S]*?)</" + tag + ">").matcher(block);
        if (!m.find()) {
            return "";
        }
        String full = m.group();
        Matcher t = Pattern.compile("title=\"([^\"]*)\"").matcher(full);
        if (t.find() && !t.group(1).trim().isEmpty()) {
            return t.group(1).trim();
        }
        return m.group(1).replaceAll("<[^>]+>", "").trim();
    }

    private static String firstAttr(String block, String tagPattern, String attr) {
        Matcher m = Pattern.compile(tagPattern).matcher(block);
        if (!m.find()) {
            return "";
        }
        Matcher v = Pattern.compile(attr + "=\"([^\"]*)\"").matcher(m.group());
        return v.find() ? v.group(1) : "";
    }

    private static String firstGroup(String text, String pattern) {
        Matcher m = Pattern.compile(pattern, Pattern.DOTALL).matcher(text);
        return m.find() ? m.group(1).trim() : "";
    }

    /** 从 html 里找出 mArg = {...}; 的 JSON 对象，按括号配对截取 */
    private static String extractObject(String html) {
        Matcher m = Pattern.compile("mArg\\s*=\\s*\\{").matcher(html);
        if (!m.find()) {
            return "";
        }
        int start = html.indexOf('{', m.start());
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
            } else if (c == '"') {
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

    private static double numOf(Object v) {
        if (v instanceof Number) {
            return ((Number) v).doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(v));
        } catch (Exception e) {
            return 0;
        }
    }

    private static int parseInt(String v, int fallback) {
        try {
            return Integer.parseInt(v.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static String decode(String v) {
        if (v == null) {
            return "";
        }
        return v.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ").trim();
    }

    private static String md5(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] bytes = md.digest(text.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                sb.append(String.format(Locale.ROOT, "%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static String enc(String v) {
        try {
            return java.net.URLEncoder.encode(v == null ? "" : v, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    private static String safe(String v) {
        return v == null ? "" : v.trim();
    }
}
