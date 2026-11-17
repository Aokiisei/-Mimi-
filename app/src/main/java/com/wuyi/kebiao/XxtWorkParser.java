package com.wuyi.kebiao;

/**
 * 本文件职责：
 * 解析学习通章节测验（work）页面。按公开页面结构提取题目、选项、题型、
 * 提交表单字段、表单 action 与内嵌密文字体；不持有网络与 Android 依赖。
 */

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class XxtWorkParser {

    public static final class Option {
        public String key = "";
        public String text = "";
    }

    public static final class Question {
        public String qid = "";
        /** 0 单选 / 1 多选 / 2 填空 / 3 判断 / 4 简答 */
        public int type = 0;
        public String stem = "";
        public final List<Option> options = new ArrayList<>();

        public boolean richText() {
            return type == 2 || type >= 5;
        }
    }

    public static final class Page {
        public String title = "章节测验";
        public int questionCount;
        public final List<Question> questions = new ArrayList<>();
        public final Map<String, String> formFields = new LinkedHashMap<>();
        public final Map<String, List<String>> blankFields = new LinkedHashMap<>();
        public String formAction = "";
        public String fontBase64 = "";
        public boolean submitted;
    }

    private XxtWorkParser() {
    }

    private static final Pattern QUES_START = Pattern.compile(
            "<div[^>]*\\bsingleQuesId\\b[^>]*>",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern OPTION_SPAN = Pattern.compile(
            "<span[^>]*class=\"[^\"]*\\bnum_option(?:_dx)?\\b[^\"]*\"[^>]*>",
            Pattern.CASE_INSENSITIVE);

    public static Page parse(String html) {
        Page page = new Page();
        if (html == null || html.isEmpty()) {
            page.submitted = true;
            return page;
        }
        page.title = parseTitle(html);
        Matcher m = QUES_START.matcher(html);
        while (m.find()) {
            String startTag = m.group();
            int end = blockEnd(html, m.start(), "div");
            String block = html.substring(m.start(), end);
            Question q = parseQuestion(startTag, block);
            if (q != null) {
                page.questions.add(q);
            }
        }
        page.questionCount = page.questions.size();
        int declared = parseDeclaredCount(html);
        if (declared > 0) {
            page.questionCount = declared;
        }
        page.formAction = parseFormAction(html);
        parseFormFields(html, page.formFields);
        buildBlankFields(page);
        page.fontBase64 = parseFontBase64(html);
        if (page.questions.isEmpty()) {
            page.submitted = containsAny(html, "已交卷", "查看详情", "查看已做答",
                    "本次得分", "成绩详情", "record-list", "次作答");
        }
        return page;
    }

    private static String parseTitle(String html) {
        Matcher m = Pattern.compile(
                "<div[^>]*class=\"[^\"]*ceyan_name[^\"]*\"[^>]*>([\\s\\S]*?)</div>",
                Pattern.CASE_INSENSITIVE).matcher(html);
        if (m.find()) {
            String block = m.group(1);
            Matcher h = Pattern.compile("<h1[^>]*>([\\s\\S]*?)</h1>",
                    Pattern.CASE_INSENSITIVE).matcher(block);
            if (h.find()) {
                String t = text(h.group(1));
                if (!t.isEmpty()) {
                    return t;
                }
            }
            Matcher name = Pattern.compile(
                    "<[^>]*class=\"[^\"]*ceyanName[^\"]*\"[^>]*>([\\s\\S]*?)</",
                    Pattern.CASE_INSENSITIVE).matcher(block);
            if (name.find()) {
                String t = text(name.group(1));
                if (!t.isEmpty()) {
                    return t;
                }
            }
            String t = text(block).replaceAll("题量\\s*[:：]\\s*\\d+", "").trim();
            if (!t.isEmpty()) {
                return t;
            }
        }
        Matcher t = Pattern.compile("<title[^>]*>([\\s\\S]*?)</title>",
                Pattern.CASE_INSENSITIVE).matcher(html);
        if (t.find()) {
            String v = text(t.group(1));
            if (!v.isEmpty()) {
                return v;
            }
        }
        return "章节测验";
    }

    private static int parseDeclaredCount(String html) {
        Matcher m = Pattern.compile("题量\\s*[:：]\\s*(\\d+)").matcher(html);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (Exception ignored) {
            }
        }
        return 0;
    }

    private static Question parseQuestion(String startTag, String block) {
        Question q = new Question();
        q.qid = firstGroup(startTag, "id=\"question([^\"]+)\"");
        if (q.qid.isEmpty()) {
            q.qid = firstGroup(startTag, "data=\"([^\"]+)\"");
        }
        if (q.qid.isEmpty()) {
            q.qid = firstGroup(block, "id=\"question(\\d+)\"");
        }
        if (q.qid.isEmpty()) {
            return null;
        }
        q.type = parseType(block, q.qid);
        q.stem = parseStem(block);
        parseOptions(block, q.options);
        return q;
    }

    private static int parseType(String block, String qid) {
        String v = attrOfTagContaining(block, "answertype" + qid, "value");
        if (v.isEmpty()) {
            v = attrOfTagContaining(block, "answertype", "value");
        }
        if (!v.isEmpty()) {
            try {
                return Integer.parseInt(v.trim());
            } catch (Exception ignored) {
            }
        }
        Matcher m = Pattern.compile("<div[^>]*class=\"[^\"]*\\bTiMu\\b[^\"]*\"[^>]*data=\"(\\d+)\"",
                Pattern.CASE_INSENSITIVE).matcher(block);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (Exception ignored) {
            }
        }
        return 0;
    }

    /** 题干：取到第一个选项标签之前的部分，去掉输入控件与脚本 */
    private static String parseStem(String block) {
        int cut = block.length();
        Matcher m = OPTION_SPAN.matcher(block);
        if (m.find()) {
            cut = m.start();
        }
        String head = block.substring(0, cut)
                .replaceAll("(?is)<script.*?</script>", " ")
                .replaceAll("(?is)<style.*?</style>", " ")
                .replaceAll("(?is)<input[^>]*>", " ")
                .replaceAll("(?is)<textarea.*?</textarea>", " ");
        String stem = text(head);
        if (stem.isEmpty()) {
            stem = text(block);
        }
        return stem;
    }

    private static void parseOptions(String block, List<Option> out) {
        Matcher m = OPTION_SPAN.matcher(block);
        List<int[]> spans = new ArrayList<>();
        List<String> tags = new ArrayList<>();
        while (m.find()) {
            spans.add(new int[]{m.start(), m.end()});
            tags.add(m.group());
        }
        for (int i = 0; i < spans.size(); i++) {
            String tag = tags.get(i);
            String data = attr(tag, "data");
            String key = data.split("\\|")[0].trim();
            if (key.isEmpty()) {
                continue;
            }
            boolean exists = false;
            for (Option o : out) {
                if (o.key.equals(key)) {
                    exists = true;
                    break;
                }
            }
            if (exists) {
                continue;
            }
            Option option = new Option();
            option.key = key;
            option.text = optionText(block, spans.get(i), tags.get(i), key);
            out.add(option);
        }
    }

    private static String optionText(String block, int[] span, String tag, String key) {
        String aria = attr(tag, "aria-label");
        if (!aria.isEmpty()) {
            String cleaned = cleanOption(aria, key);
            if (!cleaned.isEmpty()) {
                return cleaned;
            }
        }
        // 选项正文一般在 span 之后、下一个选项 span 或 </li> 之前
        int end = block.length();
        Matcher next = OPTION_SPAN.matcher(block);
        next.region(span[1], block.length());
        if (next.find()) {
            end = next.start();
        }
        int liEnd = indexOfIgnoreCase(block, "</li>", span[1]);
        if (liEnd >= 0 && liEnd < end) {
            end = liEnd;
        }
        int divEnd = indexOfIgnoreCase(block, "</div>", span[1]);
        if (divEnd >= 0 && divEnd < end && block.substring(span[1], divEnd).indexOf('<') < 0) {
            end = divEnd;
        }
        String raw = text(block.substring(span[1], end));
        String cleaned = cleanOption(raw, key);
        if (!cleaned.isEmpty()) {
            return cleaned;
        }
        return cleanOption(text(tag), key);
    }

    private static String cleanOption(String raw, String key) {
        String t = raw == null ? "" : raw.replaceAll("\\s+", " ").trim();
        String[] prefixes = {key + ".", key + "．", key + "、", key + ")", key + "）", key + " "};
        for (String p : prefixes) {
            if (t.toLowerCase(Locale.ROOT).startsWith(p.toLowerCase(Locale.ROOT))) {
                t = t.substring(p.length()).trim();
                break;
            }
        }
        if (t.endsWith("选择")) {
            t = t.substring(0, t.length() - 2).trim();
        }
        return t;
    }

    // -------------------------------------------------------------- 表单
    private static String parseFormAction(String html) {
        Matcher m = Pattern.compile("<form\\b[^>]*>", Pattern.CASE_INSENSITIVE).matcher(html);
        while (m.find()) {
            String tag = m.group();
            int end = blockEnd(html, m.start(), "form");
            String body = html.substring(m.start(), end);
            String action = attr(tag, "action");
            if (action.contains("addStudentWorkNew") || body.contains("singleQuesId")) {
                return action;
            }
        }
        Matcher first = Pattern.compile("<form\\b[^>]*>", Pattern.CASE_INSENSITIVE).matcher(html);
        return first.find() ? attr(first.group(), "action") : "";
    }

    private static void parseFormFields(String html, Map<String, String> out) {
        Matcher fm = Pattern.compile("<form\\b[^>]*>", Pattern.CASE_INSENSITIVE).matcher(html);
        String scope = "";
        while (fm.find()) {
            int end = blockEnd(html, fm.start(), "form");
            String body = html.substring(fm.start(), end);
            if (body.contains("singleQuesId") || attr(fm.group(), "action").contains("addStudentWorkNew")) {
                scope = body;
                break;
            }
        }
        if (scope.isEmpty()) {
            return;
        }
        Matcher input = Pattern.compile("<input\\b[^>]*>", Pattern.CASE_INSENSITIVE).matcher(scope);
        while (input.find()) {
            String tag = input.group();
            String name = attr(tag, "name");
            if (name.isEmpty()) {
                continue;
            }
            String type = attr(tag, "type").toLowerCase(Locale.ROOT);
            if ("submit".equals(type) || "button".equals(type) || "reset".equals(type)
                    || "file".equals(type) || "image".equals(type)) {
                continue;
            }
            out.put(name, attr(tag, "value"));
        }
        Matcher area = Pattern.compile("<textarea\\b([^>]*)>([\\s\\S]*?)</textarea>",
                Pattern.CASE_INSENSITIVE).matcher(scope);
        while (area.find()) {
            String name = attr("<textarea" + area.group(1) + ">", "name");
            if (!name.isEmpty()) {
                out.put(name, text(area.group(2)));
            }
        }
        Matcher select = Pattern.compile("<select\\b([^>]*)>([\\s\\S]*?)</select>",
                Pattern.CASE_INSENSITIVE).matcher(scope);
        while (select.find()) {
            String name = attr("<select" + select.group(1) + ">", "name");
            if (name.isEmpty()) {
                continue;
            }
            String body = select.group(2);
            Matcher opt = Pattern.compile("<option\\b[^>]*>", Pattern.CASE_INSENSITIVE).matcher(body);
            String picked = "";
            while (opt.find()) {
                String tag = opt.group();
                if (picked.isEmpty() || tag.toLowerCase(Locale.ROOT).contains("selected")) {
                    picked = attr(tag, "value");
                }
                if (tag.toLowerCase(Locale.ROOT).contains("selected")) {
                    break;
                }
            }
            out.put(name, picked);
        }
    }

    private static void buildBlankFields(Page page) {
        for (String name : page.formFields.keySet()) {
            if (!name.startsWith("answerEditor")) {
                continue;
            }
            String best = "";
            for (Question q : page.questions) {
                if (name.startsWith("answerEditor" + q.qid) && q.qid.length() > best.length()) {
                    best = q.qid;
                }
            }
            if (best.isEmpty()) {
                continue;
            }
            List<String> slots = page.blankFields.get(best);
            if (slots == null) {
                slots = new ArrayList<>();
                page.blankFields.put(best, slots);
            }
            slots.add(name);
        }
        for (List<String> slots : page.blankFields.values()) {
            java.util.Collections.sort(slots);
        }
    }

    /** 从 style#cxSecretStyle 里抠出内嵌 TTF 的 base64 */
    private static String parseFontBase64(String html) {
        String style = "";
        Matcher m = Pattern.compile("<style[^>]*id=\"cxSecretStyle\"[^>]*>([\\s\\S]*?)</style>",
                Pattern.CASE_INSENSITIVE).matcher(html);
        if (m.find()) {
            style = m.group(1);
        } else if (html.contains("cxSecretStyle")) {
            style = html;
        }
        if (style.isEmpty()) {
            return "";
        }
        Matcher b = Pattern.compile("base64,([A-Za-z0-9+/=\\s]+)").matcher(style);
        if (!b.find()) {
            return "";
        }
        return b.group(1).replaceAll("\\s", "");
    }

    // -------------------------------------------------------------- 工具
    private static int blockEnd(String html, int start, String tag) {
        String open = "<" + tag;
        String close = "</" + tag + ">";
        String lower = html.toLowerCase(Locale.ROOT);
        int depth = 0;
        int i = start;
        while (i < html.length()) {
            int nextOpen = lower.indexOf(open, i);
            int nextClose = lower.indexOf(close, i);
            if (nextClose < 0) {
                return html.length();
            }
            if (nextOpen >= 0 && nextOpen < nextClose) {
                char after = nextOpen + open.length() < lower.length()
                        ? lower.charAt(nextOpen + open.length()) : ' ';
                if (after == '>' || after == ' ' || after == '\t' || after == '\n' || after == '\r') {
                    depth++;
                }
                i = nextOpen + open.length();
            } else {
                depth--;
                i = nextClose + close.length();
                if (depth <= 0) {
                    return i;
                }
            }
        }
        return html.length();
    }

    private static String attrOfTagContaining(String html, String marker, String attr) {
        int i = html.indexOf(marker);
        if (i < 0) {
            return "";
        }
        int start = html.lastIndexOf('<', i);
        int end = html.indexOf('>', i);
        if (start < 0 || end < 0) {
            return "";
        }
        return attr(html.substring(start, end + 1), attr);
    }

    private static String attr(String tag, String name) {
        if (tag == null) {
            return "";
        }
        String regex = "(?i)\\b" + Pattern.quote(name) + "\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))";
        Matcher m = Pattern.compile(regex).matcher(tag);
        if (!m.find()) {
            return "";
        }
        String v = m.group(1) != null ? m.group(1) : (m.group(2) != null ? m.group(2) : m.group(3));
        return v == null ? "" : decode(v).trim();
    }

    private static String firstGroup(String text, String regex) {
        Matcher m = Pattern.compile(regex, Pattern.CASE_INSENSITIVE).matcher(text);
        return m.find() ? m.group(1).trim() : "";
    }

    private static int indexOfIgnoreCase(String text, String part, int from) {
        int i = text.toLowerCase(Locale.ROOT).indexOf(part, from);
        return i;
    }

    private static boolean containsAny(String text, String... parts) {
        for (String p : parts) {
            if (text.contains(p)) {
                return true;
            }
        }
        return false;
    }

    /** 去标签 + 解实体 + 压空白 */
    public static String text(String html) {
        if (html == null || html.isEmpty()) {
            return "";
        }
        String t = html.replaceAll("(?is)<script.*?</script>", " ")
                .replaceAll("(?is)<style.*?</style>", " ")
                .replaceAll("(?s)<[^>]*>", " ")
                .replace("&nbsp;", " ");
        t = decode(t);
        return t.replaceAll("\\s+", " ").trim();
    }

    public static String decode(String v) {
        if (v == null) {
            return "";
        }
        String s = v.replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&#x27;", "'");
        Matcher m = Pattern.compile("&#(x?)([0-9a-fA-F]+);").matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            try {
                int code = Integer.parseInt(m.group(2), m.group(1).isEmpty() ? 10 : 16);
                m.appendReplacement(sb, Matcher.quoteReplacement(new String(Character.toChars(code))));
            } catch (Exception e) {
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group()));
            }
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
