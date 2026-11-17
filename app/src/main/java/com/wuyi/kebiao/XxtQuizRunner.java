package com.wuyi.kebiao;

/**
 * 本文件职责：
 * 独立的「刷题」执行器。与刷课互不影响，按用户选择的课程/章节检查章节测验，
 * 交给 XxtQuizEngine 解析题目、反解密文字体、查 OCS 答案并交卷；
 * 运行进度写入静态状态，界面通过轮询读取。
 */

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class XxtQuizRunner {

    public static final class Status {
        public boolean running;
        public boolean finished;
        public String text = "就绪";
        public String course = "";
        public String chapter = "";
        public String paper = "";
        /** 本次发现的待做测验份数 */
        public int total;
        public int answered;
        public int submitted;
        public int saved;
        public int failed;
        public int skipped;
    }

    private static final Status STATUS = new Status();
    private static volatile boolean cancel;
    private static Thread worker;

    private XxtQuizRunner() {
    }

    public static Status status() {
        synchronized (STATUS) {
            Status copy = new Status();
            copy.running = STATUS.running;
            copy.finished = STATUS.finished;
            copy.text = STATUS.text;
            copy.course = STATUS.course;
            copy.chapter = STATUS.chapter;
            copy.paper = STATUS.paper;
            copy.total = STATUS.total;
            copy.answered = STATUS.answered;
            copy.submitted = STATUS.submitted;
            copy.saved = STATUS.saved;
            copy.failed = STATUS.failed;
            copy.skipped = STATUS.skipped;
            return copy;
        }
    }

    public static boolean isRunning() {
        synchronized (STATUS) {
            return STATUS.running;
        }
    }

    /** 返回空串表示已开始，否则为错误信息 */
    public static synchronized String start(Context context, String planJson) {
        if (STATUS.running) {
            return "正在刷题中";
        }
        Plan plan;
        try {
            plan = Plan.parse(planJson);
        } catch (Exception e) {
            return "刷题参数不正确";
        }
        XxtStore.Account account = XxtStore.find(context, plan.accountId);
        if (account == null) {
            return "账号不存在";
        }
        if (plan.courses.isEmpty()) {
            return "请先选择要刷题的课程";
        }
        List<OcsBank.Bank> banks;
        try {
            banks = OcsBank.parse(XxtStore.ocsConfig(context));
        } catch (Exception e) {
            return "OCS 题库配置无效，请重新保存";
        }
        if (banks.isEmpty()) {
            return "请先在设置里配置 OCS 题库";
        }
        final Context app = context.getApplicationContext();
        cancel = false;
        synchronized (STATUS) {
            STATUS.running = true;
            STATUS.finished = false;
            STATUS.text = "正在登录…";
            STATUS.course = "";
            STATUS.chapter = "";
            STATUS.paper = "";
            STATUS.total = 0;
            STATUS.answered = 0;
            STATUS.submitted = 0;
            STATUS.saved = 0;
            STATUS.failed = 0;
            STATUS.skipped = 0;
        }
        worker = new Thread(new Runnable() {
            @Override
            public void run() {
                runPlan(app, account, plan);
            }
        }, "xxt-quiz");
        worker.start();
        return "";
    }

    public static void stop() {
        cancel = true;
        synchronized (STATUS) {
            if (STATUS.running) {
                STATUS.text = "正在停止…";
            }
        }
    }

    private static void runPlan(Context context, XxtStore.Account account, Plan plan) {
        XxtSession session = new XxtSession(account);
        String err = session.ensureLogin();
        XxtStore.saveAccount(context, account);
        if (!err.isEmpty()) {
            finish("登录失败：" + err);
            return;
        }
        XxtStudyApi api = new XxtStudyApi(session);
        Map<String, List<String>> selection = new LinkedHashMap<>();
        for (XxtStudyApi.Course course : plan.courses) {
            selection.put(course.key(), plan.chapterIds(course));
        }
        setText("检查章节测验…");
        XxtQuizEngine.Summary summary = XxtQuizEngine.run(context, session, api,
                plan.courses, selection, new XxtQuizEngine.Host() {
                    @Override
                    public void status(String text) {
                        setText(text);
                    }

                    @Override
                    public boolean cancelled() {
                        return cancel;
                    }

                    @Override
                    public void progress(String course, String chapter, String paper,
                                         int total, int answered, int submitted, int saved,
                                         int failed, int skipped) {
                        synchronized (STATUS) {
                            if (course != null && !course.isEmpty()) {
                                STATUS.course = course;
                            }
                            if (chapter != null && !chapter.isEmpty()) {
                                STATUS.chapter = chapter;
                            }
                            STATUS.paper = paper == null ? "" : paper;
                            STATUS.total = total;
                            STATUS.answered = answered;
                            STATUS.submitted = submitted;
                            STATUS.saved = saved;
                            STATUS.failed = failed;
                            STATUS.skipped = skipped;
                        }
                    }
                });
        if (cancel) {
            finish("已停止");
            return;
        }
        if (summary.total == 0) {
            finish("没有需要作答的章节测验");
            return;
        }
        boolean saveOnly = XxtStore.quizSaveOnly(context);
        finish("刷题结束：" + (saveOnly ? "暂存 " + summary.saved + " 份（未交卷）"
                : "交卷 " + summary.submitted + " 份")
                + "，失败 " + summary.failed + " 份"
                + (summary.skipped > 0 ? "，跳过 " + summary.skipped + " 份" : ""));
    }

    private static void setText(String text) {
        synchronized (STATUS) {
            STATUS.text = text;
        }
    }

    private static void finish(String text) {
        synchronized (STATUS) {
            STATUS.running = false;
            STATUS.finished = true;
            STATUS.text = text;
        }
        XxtStore.addLog("[刷题] " + text);
    }

    // ---------------------------------------------------------------- 参数
    private static final class Plan {
        String accountId = "";
        final List<XxtStudyApi.Course> courses = new ArrayList<>();
        final List<List<String>> chapterIds = new ArrayList<>();

        List<String> chapterIds(XxtStudyApi.Course course) {
            for (int i = 0; i < courses.size(); i++) {
                if (courses.get(i) == course) {
                    return chapterIds.get(i);
                }
            }
            return new ArrayList<>();
        }

        static Plan parse(String json) throws Exception {
            Plan plan = new Plan();
            JSONObject root = new JSONObject(json == null ? "{}" : json);
            plan.accountId = root.optString("accountId", "");
            JSONArray arr = root.optJSONArray("courses");
            if (arr == null) {
                return plan;
            }
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                String courseId = o.optString("courseId", "");
                String clazzId = o.optString("clazzId", "");
                if (courseId.isEmpty() || clazzId.isEmpty()) {
                    continue;
                }
                XxtStudyApi.Course course = new XxtStudyApi.Course();
                course.courseId = courseId;
                course.clazzId = clazzId;
                course.cpi = o.optString("cpi", "");
                course.title = o.optString("title", courseId);
                plan.courses.add(course);
                List<String> ids = new ArrayList<>();
                JSONArray chapters = o.optJSONArray("chapters");
                if (chapters != null) {
                    for (int j = 0; j < chapters.length(); j++) {
                        JSONObject ch = chapters.optJSONObject(j);
                        if (ch == null) {
                            continue;
                        }
                        String id = ch.optString("id", "");
                        if (!id.isEmpty()) {
                            ids.add(id);
                        }
                    }
                }
                plan.chapterIds.add(ids);
            }
            return plan;
        }
    }
}
