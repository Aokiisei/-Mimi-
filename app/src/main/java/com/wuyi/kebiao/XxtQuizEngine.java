package com.wuyi.kebiao;

import android.content.Context;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * 本文件职责：
 * 章节测验自动答题执行器。刷课线程按选中的章节找出 workA 测验，
 * 解析题目、反解密文字体、向 OCS 题库查答案并自动交卷。
 * 答不上的题留空；一题都没答上时不交白卷。
 */
public final class XxtQuizEngine {

    public interface Host {
        void status(String text);

        boolean cancelled();

        /** 交卷进度：当前课程/章节/试卷与累计题数、交卷数，供界面实时展示 */
        void progress(String course, String chapter, String paper,
                      int total, int answered, int submitted, int saved, int failed, int skipped);
    }

    public static final class Summary {
        public int total;
        public int answered;
        public int submitted;
        public int saved;
        public int failed;
        public int skipped;
    }

    private static final Random RANDOM = new Random();

    private XxtQuizEngine() {
    }

    public static Summary run(Context context, XxtSession session, XxtStudyApi studyApi,
                              List<XxtStudyApi.Course> courses,
                              Map<String, List<String>> selection, Host host) {
        Summary summary = new Summary();
        List<OcsBank.Bank> banks;
        try {
            banks = OcsBank.parse(XxtStore.ocsConfig(context));
        } catch (Exception e) {
            XxtStore.addLog("[答题] OCS 配置无效，跳过章节测验："
                    + (e.getMessage() == null ? "请重新保存配置" : e.getMessage()));
            return summary;
        }
        if (banks.isEmpty()) {
            XxtStore.addLog("[答题] 未配置 OCS 题库，跳过章节测验");
            return summary;
        }
        XxtWorkApi workApi = new XxtWorkApi(session);
        int staySeconds = XxtStore.quizStaySeconds(context);
        int waitSeconds = XxtStore.quizWaitSeconds(context);
        boolean saveOnly = XxtStore.quizSaveOnly(context);
        for (XxtStudyApi.Course course : courses) {
            if (host.cancelled()) {
                return summary;
            }
            List<String> chapterIds = selection.get(course.key());
            if (chapterIds == null || chapterIds.isEmpty()) {
                continue;
            }
            List<XxtStudyApi.Chapter> chapters;
            host.status("检查测验：" + course.title);
            try {
                chapters = studyApi.chapters(course);
            } catch (Exception e) {
                XxtStore.addLog("[答题] " + course.title + " 读取章节失败：" + msg(e));
                continue;
            }
            for (String chapterId : chapterIds) {
                if (host.cancelled()) {
                    return summary;
                }
                XxtStudyApi.Chapter chapter = findChapter(chapters, chapterId);
                if (chapter == null) {
                    chapter = new XxtStudyApi.Chapter();
                    chapter.id = chapterId;
                    chapter.title = "章节 " + chapterId;
                }
                host.status("检查测验：" + course.title + " · " + chapter.title);
                List<XxtWorkApi.WorkJob> jobs;
                try {
                    jobs = workApi.jobs(course, chapter);
                } catch (Exception e) {
                    XxtStore.addLog("[答题] " + chapter.title + " 读取测验失败：" + msg(e));
                    continue;
                }
                for (XxtWorkApi.WorkJob job : jobs) {
                    if (host.cancelled()) {
                        return summary;
                    }
                    if (!job.isQuiz()) {
                        continue;
                    }
                    if (XxtStore.quizHandled(context, job.key())) {
                        continue;
                    }
                    summary.total++;
                    host.progress(course.title, chapter.title, job.name, summary.total,
                            summary.answered, summary.submitted, summary.saved,
                            summary.failed, summary.skipped);
                    try {
                        runOne(context, workApi, banks, job, summary, host,
                                staySeconds, waitSeconds, saveOnly);
                    } catch (Exception e) {
                        summary.failed++;
                        XxtStore.addLog("[答题] " + job.chapterTitle + " · " + job.name
                                + " 失败：" + msg(e));
                    }
                    host.progress(course.title, chapter.title, job.name, summary.total,
                            summary.answered, summary.submitted, summary.saved,
                            summary.failed, summary.skipped);
                    if (!host.cancelled()) {
                        sleep(35000 + RANDOM.nextInt(15000), host);
                    }
                }
            }
        }
        return summary;
    }

    private static void runOne(Context context, XxtWorkApi workApi, List<OcsBank.Bank> banks,
                               XxtWorkApi.WorkJob job, Summary summary, Host host,
                               int staySeconds, int waitSeconds, boolean saveOnly) throws Exception {
        host.status("读取测验：" + job.chapterTitle + " · " + job.name);
        String html = workApi.fetch(job);
        XxtWorkParser.Page page = XxtWorkParser.parse(html);
        if (page.submitted) {
            XxtStore.markQuizHandled(context, job.key());
            XxtStore.addLog("[答题] 已交卷，跳过：" + job.chapterTitle);
            summary.skipped++;
            return;
        }
        if (page.questions.isEmpty()) {
            XxtStore.addLog("[答题] " + job.chapterTitle + " 没解析到题目，跳过");
            summary.skipped++;
            return;
        }
        XxtStore.addLog("[答题] 开始作答：" + job.courseTitle + " · " + job.chapterTitle
                + "，共 " + page.questions.size() + " 题");

        boolean decodeOk = true;
        if (!page.fontBase64.isEmpty()) {
            host.status("识别密文字体：" + job.chapterTitle);
            XxtFontDecoder decoder = new XxtFontDecoder(context, page.fontBase64);
            if (decoder.ready()) {
                int weak = 0;
                float min = 1f;
                for (XxtWorkParser.Question q : page.questions) {
                    XxtFontDecoder.Decoded stem = decoder.decode(q.stem);
                    q.stem = stem.text;
                    weak += stem.weak;
                    min = Math.min(min, stem.min);
                    for (XxtWorkParser.Option option : q.options) {
                        XxtFontDecoder.Decoded text = decoder.decode(option.text);
                        option.text = text.text;
                        weak += text.weak;
                        min = Math.min(min, text.min);
                    }
                }
                decodeOk = weak == 0 && min >= 0.86f;
                XxtStore.addLog("[答题] 字体识别完成，最低相似度 " + String.format(Locale.ROOT, "%.3f", min)
                        + (weak > 0 ? "，存疑字 " + weak + " 个" : ""));
            } else {
                decodeOk = false;
                XxtStore.addLog("[答题] 字体加载失败，按页面原文查题");
            }
        }
        if (!decodeOk) {
            XxtStore.addLog("[答题] 字体识别置信度偏低，答案可能不准");
        }

        Map<String, String> answers = new LinkedHashMap<>();
        int index = 0;
        for (XxtWorkParser.Question q : page.questions) {
            if (host.cancelled()) {
                return;
            }
            index++;
            if (q.type == 4) {
                continue;
            }
            host.status("作答：" + job.chapterTitle + " · 第 " + index + "/"
                    + page.questions.size() + " 题");
            String type = String.valueOf(q.type);
            OcsBank.Result result = OcsBank.searchFirst(banks, q.stem,
                    optionsText(q.options), type);
            String mapped = result.ok() ? mapAnswer(q, result.answer) : "";
            if (mapped.isEmpty()) {
                XxtStore.addLog("[答题] 第 " + index + " 题未查到可用答案，留空");
                continue;
            }
            answers.put(q.qid, mapped);
            host.progress(job.courseTitle, job.chapterTitle, job.name, summary.total,
                    summary.answered + answers.size(), summary.submitted, summary.saved,
                    summary.failed, summary.skipped);
            XxtStore.addLog("[答题] 第 " + index + " 题 " + typeName(q.type) + " → " + mapped
                    + "（" + result.bank + "）");
            if (index < page.questions.size() && staySeconds > 0) {
                sleep(drift(staySeconds * 1000L), host);
            }
        }
        summary.answered += answers.size();
        if (answers.isEmpty()) {
            XxtStore.addLog("[答题] 一题都没答上，不提交：" + job.chapterTitle);
            summary.skipped++;
            return;
        }
        host.status((saveOnly ? "暂存测验：" : "提交测验：") + job.chapterTitle
                + "（已答 " + answers.size() + " 题）");
        // 交卷前按用户设置等待，避免秒交触发风控
        if (waitSeconds > 0) {
            sleep(drift(waitSeconds * 1000L), host);
        }
        if (host.cancelled()) {
            return;
        }
        XxtWorkApi.SubmitResult submit = workApi.submit(job, page, answers, saveOnly);
        if (submit.ok) {
            if (saveOnly) {
                summary.saved++;
                XxtStore.addLog("[答题] 已暂存，未交卷：" + job.chapterTitle + "（" + answers.size()
                        + "/" + page.questions.size() + " 题）");
            } else {
                summary.submitted++;
                XxtStore.markQuizHandled(context, job.key());
                String score = workApi.score(job);
                XxtStore.addLog("[答题] 已交卷：" + job.chapterTitle + "（" + answers.size() + "/"
                        + page.questions.size() + " 题）" + (score.isEmpty() ? "" : "，本次成绩 " + score + " 分"));
            }
        } else {
            summary.failed++;
            XxtStore.addLog("[答题] " + (saveOnly ? "暂存" : "交卷") + "失败：" + job.chapterTitle + "，"
                    + submit.message + "｜" + submit.raw);
        }
    }

    // -------------------------------------------------------------- 答案映射
    private static String mapAnswer(XxtWorkParser.Question q, String raw) {
        String answer = raw == null ? "" : raw.trim();
        if (answer.isEmpty()) {
            return "";
        }
        if (q.type == 3) {
            return mapJudge(q, answer);
        }
        if (q.type == 0 || q.type == 1) {
            String letters = leadingLetters(answer, q);
            if (!letters.isEmpty()) {
                return q.type == 0 ? letters.substring(0, 1) : letters;
            }
            List<String> keys = matchOptions(q, answer);
            if (keys.isEmpty()) {
                return "";
            }
            if (q.type == 0) {
                return keys.get(0);
            }
            return joinKeys(keys);
        }
        if (q.type == 2 || q.type >= 5) {
            return answer;
        }
        return "";
    }

    private static String mapJudge(XxtWorkParser.Question q, String raw) {
        String a = raw.trim().toLowerCase(Locale.ROOT);
        if (a.isEmpty()) {
            return "";
        }
        if (a.matches("^[a-f]$")) {
            for (XxtWorkParser.Option option : q.options) {
                if (option.key.equalsIgnoreCase(a)) {
                    String text = option.text == null ? "" : option.text;
                    if (text.contains("错") || text.contains("否") || text.contains("不对")) {
                        return "false";
                    }
                    if (!text.isEmpty()) {
                        return "true";
                    }
                }
            }
            return "a".equals(a) ? "true" : "false";
        }
        if (a.contains("不正确") || a.contains("不对") || a.contains("错")
                || a.contains("false") || a.contains("×") || a.contains("否")) {
            return "false";
        }
        if (a.contains("对") || a.contains("正确") || a.contains("true")
                || a.contains("√") || a.contains("是")) {
            return "true";
        }
        return "";
    }

    /** 只接受「整段基本都是选项字母」的答案，避免从英文单词里抠出字母 */
    private static String leadingLetters(String raw, XxtWorkParser.Question q) {
        String a = raw.trim();
        StringBuilder keySet = new StringBuilder();
        for (XxtWorkParser.Option o : q.options) {
            keySet.append(o.key.toUpperCase(Locale.ROOT));
        }
        // 多选常见 "A,C" / "A、C" / "AC"，先按整段字母处理
        String compact = a.replaceAll("[\\s,，、;；/|.]", "");
        if (!compact.isEmpty() && compact.matches("^[A-Fa-f]+$") && compact.length() <= 6) {
            String letters = compact.toUpperCase(Locale.ROOT);
            boolean all = true;
            for (int i = 0; i < letters.length(); i++) {
                if (keySet.indexOf(String.valueOf(letters.charAt(i))) < 0) {
                    all = false;
                    break;
                }
            }
            if (all) {
                return sortUnique(letters);
            }
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^([A-Fa-f]{1,6})(?=[\\s.、,:：)）]|$)").matcher(a);
        if (m.find()) {
            String letters = m.group(1).toUpperCase(Locale.ROOT);
            boolean all = true;
            for (int i = 0; i < letters.length(); i++) {
                if (keySet.indexOf(String.valueOf(letters.charAt(i))) < 0) {
                    all = false;
                    break;
                }
            }
            if (all) {
                return sortUnique(letters);
            }
        }
        return "";
    }

    private static List<String> matchOptions(XxtWorkParser.Question q, String raw) {
        String answer = normalize(raw);
        List<String> keys = new ArrayList<>();
        String bestKey = "";
        int bestLen = 0;
        for (XxtWorkParser.Option option : q.options) {
            String text = normalize(option.text);
            if (text.isEmpty()) {
                continue;
            }
            if (text.equals(answer)) {
                keys.add(option.key);
                if (text.length() > bestLen) {
                    bestLen = text.length();
                    bestKey = option.key;
                }
            }
        }
        if (keys.isEmpty()) {
            for (XxtWorkParser.Option option : q.options) {
                String text = normalize(option.text);
                if (text.length() < 2) {
                    continue;
                }
                if (answer.contains(text) || (text.contains(answer) && answer.length() >= 2)) {
                    keys.add(option.key);
                    if (text.length() > bestLen) {
                        bestLen = text.length();
                        bestKey = option.key;
                    }
                }
            }
        }
        if (keys.isEmpty()) {
            return keys;
        }
        if (q.type == 0) {
            List<String> one = new ArrayList<>();
            one.add(bestKey);
            return one;
        }
        Collections.sort(keys);
        return keys;
    }

    private static String joinKeys(List<String> keys) {
        StringBuilder sb = new StringBuilder();
        for (String key : keys) {
            if (sb.indexOf(key) >= 0) {
                continue;
            }
            sb.append(key);
        }
        return sb.toString();
    }

    private static String sortUnique(String letters) {
        char[] chars = letters.toCharArray();
        java.util.Arrays.sort(chars);
        StringBuilder sb = new StringBuilder();
        for (char c : chars) {
            if (sb.indexOf(String.valueOf(c)) < 0) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("[\\s\\u3000]", "")
                .replaceAll("[，。、“”‘’；;：:！!？?（）()《》\\[\\]【】]", "")
                .toLowerCase(Locale.ROOT);
    }

    private static String optionsText(List<XxtWorkParser.Option> options) {
        StringBuilder sb = new StringBuilder();
        for (XxtWorkParser.Option option : options) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(option.key).append(". ").append(option.text);
        }
        return sb.toString();
    }

    private static String typeName(int type) {
        switch (type) {
            case 0:
                return "单选";
            case 1:
                return "多选";
            case 2:
                return "填空";
            case 3:
                return "判断";
            case 4:
                return "简答";
            default:
                return "题型" + type;
        }
    }

    // -------------------------------------------------------------- 工具
    private static XxtStudyApi.Chapter findChapter(List<XxtStudyApi.Chapter> list, String id) {
        for (XxtStudyApi.Chapter c : list) {
            if (id.equals(c.id)) {
                return c;
            }
        }
        return null;
    }

    /** 在设定秒数上做 ±5 秒随机漂移，避免固定节奏 */
    private static long drift(long baseMillis) {
        long v = baseMillis + RANDOM.nextInt(10001) - 5000L;
        return v < 0 ? 0 : v;
    }

    private static void sleep(long millis, Host host) {
        long left = millis;
        while (left > 0 && !host.cancelled()) {
            long step = Math.min(1000L, left);
            try {
                Thread.sleep(step);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            left -= step;
        }
    }

    private static String msg(Exception e) {
        return e.getMessage() == null || e.getMessage().isEmpty()
                ? e.getClass().getSimpleName() : e.getMessage();
    }
}
