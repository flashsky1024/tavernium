package io.github.flashsky1024.tavernium;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

/** PerfMonitor / PerfLog 逻辑测试：纯函数 + 真实 /proc 采样 + 日志滚动。 */
public class PerfMonitorTest {
    private static int passed = 0;
    private static int failed = 0;
    private static final boolean HAS_PROC = new File("/proc/self/stat").isFile();

    public static void main(String[] args) throws Exception {
        testParseProcStat();
        testMarkStats();
        testCountOnly();
        testJsMetrics();
        testHostStats();
        testPageCounts();
        if (HAS_PROC) {
            testSamplerLive();
        } else {
            System.out.println("[SKIP] Linux /proc 活采样：当前系统无 /proc");
        }
        testPerfLogRolling();
        testLogLevels();
        testLongTaskLogging();

        System.out.println();
        System.out.println("========================================");
        System.out.println("PASSED: " + passed + "   FAILED: " + failed);
        if (!HAS_PROC) System.out.println("SKIPPED: 2 Linux /proc checks (live parse and sampling)");
        System.out.println("========================================");
        System.out.flush();
        System.exit(failed > 0 ? 1 : 0);
    }

    // ---------------- 用例 ----------------

    private static void testParseProcStat() throws Exception {
        section("/proc stat 解析");
        // 真实文件：当前 JVM 进程
        String real = HAS_PROC ? readFirstLine(new File("/proc/self/stat")) : null;
        PerfMonitor.ProcStat ps = PerfMonitor.parseProcStat(real);
        if (HAS_PROC) check("真实 /proc/self/stat 可解析", ps != null);
        if (ps != null) {
            check("utime/stime 非负", ps.utime >= 0 && ps.stime >= 0);
            check("comm 非空", ps.comm != null && !ps.comm.isEmpty());
        }
        // comm 含空格
        ps = PerfMonitor.parseProcStat(
                "999 (my thread) R 1 0 0 0 -1 4194560 123 0 0 0 42 17 0 0 20 0 1 0 999");
        check("comm 含空格", ps != null && "my thread".equals(ps.comm));
        check("utime/stime 取值正确", ps != null && ps.utime == 42L && ps.stime == 17L);
        // comm 含右括号（注意：')' 之后至少需要 13 个字段才能覆盖 utime/stime，
        // utime/stime 分别是第 12/13 个字段）
        ps = PerfMonitor.parseProcStat("5 (a)b) R 1 0 0 0 -1 0 0 0 0 0 7 9");
        check("comm 含括号", ps != null && "a)b".equals(ps.comm));
        check("括号样本数值正确", ps != null && ps.utime == 7L && ps.stime == 9L);
        // 畸形输入
        check("null 安全", PerfMonitor.parseProcStat(null) == null);
        check("空串安全", PerfMonitor.parseProcStat("") == null);
        check("垃圾输入安全", PerfMonitor.parseProcStat("not a stat line at all") == null);
        check("字段不足安全", PerfMonitor.parseProcStat("1 (x) R 1 2") == null);
    }

    private static void testMarkStats() throws Exception {
        section("埋点统计（count/avg/p50/p99/max）");
        for (int i = 0; i < 100; i++) {
            PerfMonitor.mark("t.dist", 1_000_000L);   // 1ms
        }
        for (int i = 0; i < 100; i++) {
            PerfMonitor.mark("t.dist", 10_000_000L);  // 10ms
        }
        PerfMonitor.Stat s = PerfMonitor.statFor("t.dist");
        check("count=200", s != null && s.count == 200L);
        check("total=1100ms", s != null && s.totalNs == 1_100_000_000L);
        check("max=10ms", s != null && s.maxNs == 10_000_000L);
        // 直方图报告桶上边界：p50 落在 1ms 样本所在桶的上边界 2.5ms
        eq("p50", 2.5, PerfMonitor.percentileMs(s, 50));
        eq("p99", 25.0, PerfMonitor.percentileMs(s, 99));
        eq("avg", 5.5, s.totalNs / 1_000_000.0 / s.count);
        // 边界值：0 与超大值
        PerfMonitor.mark("t.edge", 0L);
        PerfMonitor.mark("t.edge", 9_000_000_000L);
        PerfMonitor.Stat e = PerfMonitor.statFor("t.edge");
        check("0ns 入桶", e != null && e.hist[0] == 1L);
        check(">5s 入溢出桶", e != null && e.hist[15] == 1L);
        // 2 个样本 {0, 9s}：p50/p99 落在第一个非空桶（0.05ms），p100 落在溢出桶
        eq("p99（小样本）", 0.05, PerfMonitor.percentileMs(e, 99));
        eq("p100（溢出桶上边界）", 5000.0, PerfMonitor.percentileMs(e, 100));
    }

    private static void testCountOnly() throws Exception {
        section("纯计数（hit/miss）");
        PerfMonitor.count("t.hit");
        PerfMonitor.count("t.hit");
        PerfMonitor.Stat s = PerfMonitor.statFor("t.hit");
        check("count=2", s != null && s.count == 2L);
        check("无数值累加", s != null && s.totalNs == 0L);
    }

    private static void testPageCounts() throws Exception {
        section("页面加载计数（区分重载与进程重启）");
        PerfMonitor.setPageCounts(0, 0);
        check("未加载过时不输出页面段", !PerfMonitor.report().contains("[页面]"));

        PerfMonitor.setPageCounts(1, 1);
        String once = PerfMonitor.report();
        check("单次加载有页面段", once.contains("[页面] 本进程内加载 1 次 / 完成 1 次"));
        check("单次加载不报警", !once.contains("循环重载"));

        // 反复重载：真机上就是"看起来在频繁重启"，实际是同一进程内页面在 reload
        PerfMonitor.setPageCounts(4, 4);
        String storm = PerfMonitor.report();
        check("多次加载被标记", storm.contains("[页面] 本进程内加载 4 次 / 完成 4 次"));
        check("触发循环重载提示", storm.contains("循环重载"));

        // 加载中未完成也不应崩
        PerfMonitor.setPageCounts(5, 3);
        check("加载/完成不等也能出报告", PerfMonitor.report().contains("加载 5 次 / 完成 3 次"));

        PerfMonitor.setPageCounts(0, 0);
    }

    private static void testHostStats() throws Exception {
        section("网络按主机聚合");
        PerfMonitor.markHost("slow.example.com", 9_000_000_000L, false);
        PerfMonitor.markHost("slow.example.com", 8_000_000_000L, false);
        PerfMonitor.markHost("fast.example.com", 20_000_000L, true);
        String r = PerfMonitor.report();
        check("含网络按主机段", r.contains("[网络按主机]"));
        check("含慢主机名", r.contains("slow.example.com"));
        check("失败计数正确", r.contains("失败2"));
        check("其余为 0 失败", r.contains("失败0"));
        check("慢主机被标记", r.contains("拖时间"));
        // 空主机名不得抛异常，也不应污染统计
        PerfMonitor.markHost(null, 1L, true);
        PerfMonitor.markHost("", 1L, true);
        check("空主机名后仍可出报告", PerfMonitor.report().contains("性能报告"));
    }

    private static void testJsMetrics() throws Exception {
        section("JS 探针指标解析");
        // fps 是窗口内帧计数，配合 ms 换算成每秒帧数
        PerfMonitor.applyJsMetrics(
                "{\"fps\":58,\"dom\":18432,\"lt\":3,\"heap\":123456789,\"ms\":1000}");
        String r = PerfMonitor.report();
        check("fps 进入报告", r.contains("fps~58"));
        check("dom 进入报告", r.contains("dom=18432"));
        check("longTask 进入报告", r.contains("longTask=3"));
        check("jsHeap 换算 MB", r.contains("jsHeap=117MB"));

        // 3 秒窗口（探针降频后的实际间隔）：180 帧 / 3000ms = 60fps
        PerfMonitor.applyJsMetrics(
                "{\"fps\":180,\"dom\":17298,\"lt\":9,\"heap\":167772160,\"ms\":3000}");
        r = PerfMonitor.report();
        check("3s 窗口换算 fps", r.contains("fps~60"));
        check("3s 窗口 dom", r.contains("dom=17298"));
        check("3s 窗口 jsHeap=160MB", r.contains("jsHeap=160MB"));

        // 缺少 ms（旧格式）不更新 fps，但仍更新其他指标
        PerfMonitor.applyJsMetrics("{\"fps\":1,\"dom\":100,\"lt\":0,\"heap\":0}");
        check("无 ms 不覆盖 fps", PerfMonitor.report().contains("fps~60"));
        check("无 ms 仍更新 dom", PerfMonitor.report().contains("dom=100"));

        // 畸形与空输入不得崩溃
        PerfMonitor.applyJsMetrics(null);
        PerfMonitor.applyJsMetrics("");
        PerfMonitor.applyJsMetrics("garbage{not json");
        PerfMonitor.applyJsMetrics("{\"fps\":}");
        check("畸形输入后仍可出报告", PerfMonitor.report().contains("性能报告"));

        // 帧率上限设置行（与 fps 读数对照用）
        PerfMonitor.setFrameRateLimit(30);
        check("报告显示帧率上限", PerfMonitor.report().contains("[设置] 帧率上限=30fps"));
        PerfMonitor.setFrameRateLimit(120);
        check("报告显示 120fps 上限", PerfMonitor.report().contains("帧率上限=120fps"));
        PerfMonitor.setFrameRateLimit(0);
        check("0 显示为系统默认", PerfMonitor.report().contains("帧率上限=系统默认"));
        PerfMonitor.setFrameRateLimit(-5);
        check("负值归为系统默认", PerfMonitor.report().contains("帧率上限=系统默认"));

        // 内核信息（用于判断 WebView 版本是否过旧）
        PerfMonitor.setWebViewInfo("com.google.android.webview 130.0.6723.86");
        check("报告显示 WebView 版本",
                PerfMonitor.report().contains("[WebView] com.google.android.webview 130.0.6723.86"));
        PerfMonitor.setWebViewInfo(null);
        check("未提供时显示未知", PerfMonitor.report().contains("[WebView] 未知"));
    }

    private static void testSamplerLive() throws Exception {
        section("真实采样（/proc 活采样 2.5s）");
        File logDir = new File(System.getProperty("java.io.tmpdir"),
                "tk_perfsnap_" + System.currentTimeMillis());
        logDir.mkdirs();
        System.setProperty("tk.extDir", logDir.getAbsolutePath());
        Context logCtx = new Context() {
        };
        PerfLog.init(logCtx);
        PerfLog.clear();

        Context ctx = new Context() {
        };
        PerfMonitor.start(ctx);
        check("采样器已启动", PerfMonitor.isRunning());
        sleep(2500);
        PerfMonitor.stop();
        check("采样器已停止", !PerfMonitor.isRunning());
        String r = PerfMonitor.report();
        check("报告含进程段", r.contains("[进程 CPU]") && r.contains("本进程"));
        check("报告含线程段", r.contains("[线程 Top10]"));
        check("线程段带近30s/峰值列", r.contains("近30s") && r.contains("峰值"));
        check("线程段标注占本进程", r.contains("均占本进程"));
        check("报告含埋点段", r.contains("[热路径埋点]"));
        check("报告含 JS 段", r.contains("[JS]"));
        check("报告含内存段", r.contains("[内存]"));
        // 当前线程（main）的 tid == 进程 pid，取其真实 comm 验证它出现在线程统计里
        // （不同 JVM 的 comm 不同：HotSpot 为 "main"，本环境 JVM 为 "java"）
        int myTid = (int) ProcessHandle.current().pid();
        PerfMonitor.ProcStat me = PerfMonitor.parseProcStat(
                readFirstLine(new File("/proc/self/task/" + myTid + "/stat")));
        check("统计到当前线程（" + (me == null ? "?" : me.comm) + "）",
                me != null && r.contains(me.comm));
        // 2.5s / 1s 周期 => 至少 2 次采样；线程占比行存在即说明发生了 diff 累计
        check("发生了 CPU 累计", r.indexOf('%') > 0);

        // 停止时必须把完整报告写入日志（导出的 perf.log 才有分析价值）
        PerfLog.flushAndWait();
        String log = readAll(new FileInputStream(PerfLog.currentLog()));
        check("stop 时写入报告快照", log.contains("=== TavernKeep 性能报告 ==="));
        check("快照含线程段", log.contains("[线程 Top10]"));
        check("快照含热路径段", log.contains("[热路径埋点]"));
        check("快照含设置行", log.contains("[设置] 帧率上限="));
        check("快照记录采样次数", log.contains("采样"));
    }

    private static void testPerfLogRolling() throws Exception {
        section("日志滚动（1.5MB / 3 文件）");
        File dir = new File(System.getProperty("java.io.tmpdir"),
                "tk_perflog_" + System.currentTimeMillis());
        dir.mkdirs();
        System.setProperty("tk.extDir", dir.getAbsolutePath());
        Context ctx = new Context() {
        };
        PerfLog.init(ctx);
        check("日志目录就绪", PerfLog.currentLog() != null);
        PerfLog.clear();
        File logDir = PerfLog.dir();
        check("日志目录存在", logDir != null && logDir.isDirectory());
        // 写约 2MB：12000 行 x ~170B，必然触发滚动。
        // PerfLog 的待写队列有上限（写盘跟不上就丢弃），所以分批灌入并等待落盘，
        // 模拟真实使用节奏；一次性灌 12000 行属于人为制造的日志风暴。
        StringBuilder pad = new StringBuilder();
        for (int i = 0; i < 150; i++) {
            pad.append('x');
        }
        for (int i = 0; i < 12000; i++) {
            PerfLog.i("Roll", "line-" + i + "-" + pad);
            if (i % 500 == 0) {
                PerfLog.flushAndWait();
            }
        }
        PerfLog.i("Test", "TAIL-MARKER-778899");
        PerfLog.flushAndWait();

        File[] files = logDir.listFiles();
        int n = files == null ? 0 : files.length;
        check("滚动后不超过 3 个文件（实际 " + n + "）", n > 0 && n <= 3);
        check("当前日志存在", PerfLog.currentLog().exists());
        String tail = readAll(new FileInputStream(PerfLog.currentLog()));
        check("最新日志含尾部标记", tail.contains("TAIL-MARKER-778899"));
        check("单文件不超上限", PerfLog.currentLog().length() <= 1_500_000L + 4096);
        PerfLog.clear();
        check("clear 后为空", logDir.list() == null || logDir.list().length == 0);
    }

    /** 详细日志开关、级别标记、线程名与 tail 读取。 */
    private static void testLogLevels() throws Exception {
        section("日志级别 / 线程名 / 尾部读取");

        PerfLog.clear();
        PerfLog.setVerbose(false);
        PerfLog.d("T", "hidden-line");
        PerfLog.i("T", "info-line");
        PerfLog.flushAndWait();
        String log = readAll(new FileInputStream(PerfLog.currentLog()));
        check("verbose 关闭时 d 级不落盘", !log.contains("hidden-line"));
        check("i 级始终落盘", log.contains("info-line"));
        check("带级别标记 I/T", log.contains("I/T["));
        check("带线程名", log.contains("[main"));

        PerfLog.clear();
        PerfLog.setVerbose(true);
        PerfLog.d("T", "debug-visible");
        PerfLog.flushAndWait();
        String log2 = readAll(new FileInputStream(PerfLog.currentLog()));
        check("verbose 开启时 d 级落盘", log2.contains("debug-visible"));
        check("d 级标记为 D/T", log2.contains("D/T["));
        PerfLog.setVerbose(false);

        String tail = PerfLog.tail(64);
        check("tail 有内容", !tail.isEmpty());
        check("tail 长度受控", tail.length() <= 200);
        check("tail 从完整行开始（首字符是时间戳数字）",
                !tail.isEmpty() && Character.isDigit(tail.charAt(0)));
        check("tail 不含半行乱码", !tail.startsWith("\u0000"));
    }

    /** 长任务超过 100ms 自动落一条日志：把"卡顿时刻"和 CPU 峰值对上。 */
    private static void testLongTaskLogging() throws Exception {
        section("长任务自动记录（卡顿归因）");
        PerfLog.clear();
        PerfMonitor.applyJsMetrics(
                "{\"fps\":180,\"dom\":20000,\"lt\":5,\"ltMax\":420,\"heap\":104857600,\"ms\":3000}");
        PerfLog.flushAndWait();
        String log = readAll(new FileInputStream(PerfLog.currentLog()));
        check("记录 420ms 长任务", log.contains("js long task 420ms"));
        check("带 DOM 规模", log.contains("dom=20000"));
        check("带堆大小", log.contains("heap=100MB"));

        PerfLog.clear();
        PerfMonitor.applyJsMetrics(
                "{\"fps\":180,\"dom\":100,\"lt\":1,\"ltMax\":30,\"heap\":0,\"ms\":3000}");
        PerfLog.flushAndWait();
        String quiet = readLog();
        check("低于 100ms 不记录", !quiet.contains("js long task"));

        // 旧格式（无 ltMax）不应误报
        PerfLog.clear();
        PerfMonitor.applyJsMetrics("{\"fps\":60,\"dom\":100,\"lt\":1,\"heap\":0,\"ms\":1000}");
        PerfLog.flushAndWait();
        check("无 ltMax 字段不误报", !readLog().contains("js long task"));
    }

    /** 读当前日志；文件尚未创建时返回空串（clear 后无写入的情况）。 */
    private static String readLog() {
        File f = PerfLog.currentLog();
        if (f == null || !f.exists()) {
            return "";
        }
        try {
            return readAll(new FileInputStream(f));
        } catch (Exception e) {
            return "";
        }
    }

    // ---------------- 工具 ----------------

    private static String readFirstLine(File f) throws Exception {
        BufferedReader r = new BufferedReader(new FileReader(f));
        try {
            return r.readLine();
        } finally {
            r.close();
        }
    }

    private static String readAll(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
        }
        in.close();
        return new String(out.toByteArray(), "UTF-8");
    }

    private static void sleep(long ms) {
        try {
            TimeUnit.MILLISECONDS.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static long t0 = System.currentTimeMillis();

    private static void section(String name) {
        System.out.println();
        System.out.println("--- " + name + "  (t+" + (System.currentTimeMillis() - t0) + "ms) ---");
        System.out.flush();
    }

    private static void check(String name, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("  [PASS] " + name);
        } else {
            failed++;
            System.out.println("  [FAIL] " + name);
        }
        System.out.flush();
    }

    private static void eq(String name, double expected, double actual) {
        boolean ok = Math.abs(expected - actual) < 1e-6;
        if (ok) {
            passed++;
            System.out.println("  [PASS] " + name + " = " + actual);
        } else {
            failed++;
            System.out.println("  [FAIL] " + name + "  expected=" + expected + " actual=" + actual);
        }
        System.out.flush();
    }
}
