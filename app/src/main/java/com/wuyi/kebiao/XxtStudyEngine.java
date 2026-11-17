package com.wuyi.kebiao;

/**
 * 本文件职责：
 * 学习通「刷课」执行器。处理视频任务点：读取章节任务卡 → 获取视频状态 →
 * 上报播放进度，已完成的视频自动跳过。章节测验由独立的「刷题」执行器处理。
 * 运行在独立线程，进度与日志写入 XxtStore，界面通过轮询读取。
 */

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public final class XxtStudyEngine {

    /** 单次上报间隔（秒），与超星播放器心跳接近 */
    private static final int BEAT_SECONDS = 30;

    public static final class Status {
        public boolean running;
        public boolean finished;
        public String text = "就绪";
        /** 当前正在刷的课程 / 章节 / 视频，界面按章节展示 */
        public String course = "";
        public String chapter = "";
        public String video = "";
        /** 当前视频已播秒数、总秒数与百分比 */
        public int cur;
        public int dur;
        public int percent;
        public float speed = 2f;
        public int done;
        public int total;
        public int failed;
    }

    private static final Status STATUS = new Status();
    private static volatile boolean cancel;
    private static Thread worker;

    private XxtStudyEngine() {
    }

    public static Status status() {
        synchronized (STATUS) {
            Status copy = new Status();
            copy.running = STATUS.running;
            copy.finished = STATUS.finished;
            copy.text = STATUS.text;
            copy.course = STATUS.course;
            copy.chapter = STATUS.chapter;
            copy.video = STATUS.video;
            copy.cur = STATUS.cur;
            copy.dur = STATUS.dur;
            copy.percent = STATUS.percent;
            copy.speed = STATUS.speed;
            copy.done = STATUS.done;
            copy.total = STATUS.total;
            copy.failed = STATUS.failed;
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
            return "正在刷课中";
        }
        Plan plan;
        try {
            plan = Plan.parse(planJson);
        } catch (Exception e) {
            return "刷课参数不正确";
        }
        XxtStore.Account account = XxtStore.find(context, plan.accountId);
        if (account == null) {
            return "账号不存在";
        }
        if (plan.courses.isEmpty()) {
            return "请先选择要刷的章节";
        }
        final Context app = context.getApplicationContext();
        cancel = false;
        synchronized (STATUS) {
            STATUS.running = true;
            STATUS.finished = false;
            STATUS.text = "正在读取任务点…";
            STATUS.course = "";
            STATUS.chapter = "";
            STATUS.video = "";
            STATUS.cur = 0;
            STATUS.dur = 0;
            STATUS.done = 0;
            STATUS.total = 0;
            STATUS.failed = 0;
        }
        worker = new Thread(new Runnable() {
            @Override
            public void run() {
                runPlan(app, account, plan);
            }
        }, "xxt-study");
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
            finish("登录失败：" + err, true);
            return;
        }
        XxtStudyApi api = new XxtStudyApi(session);
        List<Job> jobs = new ArrayList<>();
        for (XxtStudyApi.Course course : plan.courses) {
            if (cancel) {
                finish("已停止", false);
                return;
            }
            setText("读取章节：" + course.title);
            List<XxtStudyApi.Chapter> chapters;
            try {
                chapters = api.chapters(course);
            } catch (Exception e) {
                XxtStore.addLog("[刷课] " + course.title + " 读取章节失败：" + msg(e));
                continue;
            }
            for (String chapterId : plan.chapterIds(course)) {
                if (cancel) {
                    finish("已停止", false);
                    return;
                }
                XxtStudyApi.Chapter chapter = findChapter(chapters, chapterId);
                if (chapter == null) {
                    chapter = new XxtStudyApi.Chapter();
                    chapter.id = chapterId;
                    chapter.title = "章节 " + chapterId;
                }
                setText("读取任务点：" + course.title + " · " + chapter.title);
                List<XxtStudyApi.VideoJob> videos;
                try {
                    videos = api.videoJobs(course, chapter);
                } catch (Exception e) {
                    XxtStore.addLog("[刷课] " + chapter.title + " 读取任务点失败：" + msg(e));
                    continue;
                }
                for (XxtStudyApi.VideoJob video : videos) {
                    Job job = new Job();
                    job.course = course;
                    job.chapter = chapter;
                    job.video = video;
                    jobs.add(job);
                }
            }
        }
        synchronized (STATUS) {
            STATUS.total = jobs.size();
        }
        XxtStore.addLog("[刷课] 任务点读取完成，共 " + jobs.size() + " 个视频");
        if (jobs.isEmpty()) {
            XxtStore.addLog("[刷课] 没有可刷的视频任务点");
        }
        for (Job job : jobs) {
            if (cancel) {
                finish("已停止", false);
                return;
            }
            setText("正在播放：" + job.chapter.title + " · "
                    + (job.video.name.isEmpty() ? "视频" : job.video.name));
            setProgress(job.course.title, job.chapter.title, job.video.name, 0, 0, plan.speed);
            boolean ok = runVideo(api, job, plan.speed);
            synchronized (STATUS) {
                if (ok) {
                    STATUS.done++;
                } else {
                    STATUS.failed++;
                }
            }
            if (!ok) {
                XxtStore.addLog("[刷课] " + job.course.title + " · " + job.video.name + " 未完成");
            }
        }
        if (cancel) {
            finish("已停止", false);
            return;
        }
        if (jobs.isEmpty()) {
            finish("没有可刷的视频任务点", true);
            return;
        }
        finish("刷课结束：完成 " + STATUS.done + " 个，失败 " + STATUS.failed + " 个", true);
    }

    private static boolean runVideo(XxtStudyApi api, Job job, float speed) {
        XxtStudyApi.VideoJob video = job.video;
        if (video.passed) {
            XxtStore.addLog("[刷课] 已完成，跳过：" + video.name);
            return true;
        }
        if (!video.faceEnc.isEmpty()) {
            XxtStore.addLog("[刷课] 需要人脸抓拍，跳过：" + video.name);
            return true;
        }
        XxtStudyApi.VideoStatus status = null;
        try {
            status = api.videoStatus(job.course, video);
        } catch (Exception e) {
            XxtStore.addLog("[刷课] " + video.name + " 获取视频状态失败：" + msg(e));
        }
        if (status == null || !status.ok || status.dtoken.isEmpty()) {
            XxtStore.addLog("[刷课] " + video.name + " 没有拿到播放凭证");
            return false;
        }
        int duration = status.duration > 0 ? status.duration : video.attDuration;
        if (duration <= 0) {
            XxtStore.addLog("[刷课] " + video.name + " 时长为 0，无法上报");
            return false;
        }
        // 心跳上报固定按 2 倍推进，界面上的倍速只在 1.0~2.0 之间生效
        float rate = speed < 1f ? 1f : (speed > 2f ? 2f : speed);
        setProgress(job.course.title, job.chapter.title, video.name, 0, duration, rate);
        // 先尝试一次直接到结尾，服务端放行就不必按心跳慢慢跑
        try {
            if (api.report(job.course, video, status, duration, 4)) {
                setProgress(job.course.title, job.chapter.title, video.name, duration, duration, rate);
                XxtStore.addLog("[刷课] 完成：" + video.name);
                return true;
            }
        } catch (Exception ignored) {
        }
        int step = Math.max(1, Math.round(BEAT_SECONDS * rate));
        int playing = 0;
        boolean passed = false;
        boolean noted = false;
        while (playing < duration) {
            if (cancel) {
                return false;
            }
            int next = Math.min(duration, playing + step);
            long wait = (long) ((next - playing) / rate * 1000L);
            if (wait < 1200) {
                wait = 1200;
            }
            if (wait > BEAT_SECONDS * 1000L) {
                wait = BEAT_SECONDS * 1000L;
            }
            if (!playWait(job, video, playing, next, duration, rate, wait)) {
                return false;
            }
            playing = next;
            setProgress(job.course.title, job.chapter.title, video.name, playing, duration, rate);
            try {
                // 正常播放用 isdrag=3（4 表示拖动进度，连续用会被判定异常）
                if (api.report(job.course, video, status, playing, 3)) {
                    // 服务端常在 90% 左右就判定任务点完成，这里不提前退出，继续播到 100%
                    passed = true;
                    if (!noted) {
                        noted = true;
                        XxtStore.addLog("[刷课] " + video.name + " 任务点已判定完成，继续播到 100%");
                    }
                }
            } catch (Exception e) {
                XxtStore.addLog("[刷课] " + video.name + " 上报失败：" + msg(e));
            }
            // 每 30 秒心跳时把播放进度写进运行日志
            XxtStore.addLog("[刷课] " + job.chapter.title + " · " + video.name
                    + " 已播 " + (playing * 100 / duration) + "%（" + clock(playing)
                    + "/" + clock(duration) + "）");
        }
        // 到结尾后再补报几次，防止单次请求被限流
        for (int i = 0; i < 3 && !passed; i++) {
            if (cancel) {
                return false;
            }
            if (!sleep(3000)) {
                return false;
            }
            try {
                if (api.report(job.course, video, status, duration, 3)) {
                    passed = true;
                }
            } catch (Exception ignored) {
            }
        }
        setProgress(job.course.title, job.chapter.title, video.name, duration, duration, rate);
        if (passed) {
            XxtStore.addLog("[刷课] 完成：" + video.name + "（已播 100%）");
        }
        return passed;
    }

    /** 等待下一次心跳，同时按秒推进界面上的「已播/总时长」 */
    private static boolean playWait(Job job, XxtStudyApi.VideoJob video, int from, int to,
                                    int duration, float rate, long ms) {
        long start = System.currentTimeMillis();
        while (true) {
            if (cancel) {
                return false;
            }
            long elapsed = System.currentTimeMillis() - start;
            if (elapsed >= ms) {
                break;
            }
            int shown = from + (int) Math.round(elapsed / 1000.0 * rate);
            if (shown > to) {
                shown = to;
            }
            setProgress(job.course.title, job.chapter.title, video.name, shown, duration, rate);
            try {
                Thread.sleep(500L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        setProgress(job.course.title, job.chapter.title, video.name, to, duration, rate);
        return !cancel;
    }

    /** 分片等待，停止按钮能在一秒内生效 */
    private static boolean sleep(long ms) {
        long end = System.currentTimeMillis() + ms;
        try {
            while (System.currentTimeMillis() < end) {
                if (cancel) {
                    return false;
                }
                Thread.sleep(Math.min(500L, Math.max(1L, end - System.currentTimeMillis())));
            }
            return !cancel;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static void setText(String text) {
        synchronized (STATUS) {
            STATUS.text = text;
        }
    }

    private static void setProgress(String course, String chapter, String video,
                                    int cur, int dur, float speed) {
        synchronized (STATUS) {
            STATUS.course = course == null ? "" : course;
            STATUS.chapter = chapter == null ? "" : chapter;
            STATUS.video = video == null ? "" : video;
            STATUS.cur = cur;
            STATUS.dur = dur;
            STATUS.percent = dur > 0 ? Math.min(100, cur * 100 / dur) : 0;
            STATUS.speed = speed;
        }
    }

    private static void finish(String text, boolean completed) {
        synchronized (STATUS) {
            STATUS.running = false;
            STATUS.finished = completed;
            STATUS.text = text;
        }
        XxtStore.addLog("[刷课] " + text);
    }

    private static XxtStudyApi.Chapter findChapter(List<XxtStudyApi.Chapter> list, String id) {
        for (XxtStudyApi.Chapter c : list) {
            if (c.id.equals(id)) {
                return c;
            }
        }
        return null;
    }

    private static String msg(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    private static String clock(int sec) {
        int m = sec / 60;
        int s = sec % 60;
        return m + ":" + (s < 10 ? "0" + s : String.valueOf(s));
    }

    private static final class Job {
        XxtStudyApi.Course course;
        XxtStudyApi.Chapter chapter;
        XxtStudyApi.VideoJob video;
    }

    private static final class Plan {
        String accountId = "";
        float speed = 2f;
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
            plan.speed = (float) root.optDouble("speed", 2);
            JSONArray arr = root.optJSONArray("courses");
            if (arr == null) {
                return plan;
            }
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                XxtStudyApi.Course c = new XxtStudyApi.Course();
                c.courseId = o.optString("courseId", "");
                c.clazzId = o.optString("clazzId", "");
                c.cpi = o.optString("cpi", "");
                c.title = o.optString("title", c.courseId);
                List<String> ids = new ArrayList<>();
                JSONArray chapters = o.optJSONArray("chapters");
                if (chapters != null) {
                    for (int j = 0; j < chapters.length(); j++) {
                        JSONObject ch = chapters.optJSONObject(j);
                        if (ch != null) {
                            String id = ch.optString("id", "");
                            if (!id.isEmpty()) {
                                ids.add(id);
                            }
                        }
                    }
                }
                if (!ids.isEmpty() && !c.courseId.isEmpty() && !c.clazzId.isEmpty()) {
                    plan.courses.add(c);
                    plan.chapterIds.add(ids);
                }
            }
            return plan;
        }
    }
}
