package io.github.flashsky1024.tavernium;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Debug;
import android.os.Process;
import android.os.SystemClock;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 性能监视器（debug 工具，默认关闭，菜单里开启）。
 *
 * <p>三个层面的数据一起看，才能定位"CPU 到底耗在哪"：</p>
 * <ol>
 *   <li><b>进程/线程级 CPU</b>：读 /proc 采样（100Hz jiffies）。注意 WebView 的渲染进程
 *       跑在独立的沙箱进程里（{@code pkg:sandboxed_process0}），本进程 /proc/self 看不到，
 *       因此额外通过 ActivityManager 枚举同包进程一并统计——这往往是 CPU 大头；</li>
 *   <li><b>热路径埋点</b>：{@link #mark} 在 shouldInterceptRequest / 预取 / 注入等关键路径上
 *       打点，输出 count / avg / p50 / p99 / max（对数分桶直方图，O(1) 记录）；</li>
 *   <li><b>JS 侧指标</b>：每秒向页面探针取 FPS、DOM 节点数、long task 计数、JS 堆，
 *       反映 SillyTavern 前端本身的负载（DOM 节点数与布局成本强相关）。</li>
 * </ol>
 *
 * <p>自身开销控制：mark() 约百纳秒级；采样线程 1s 一次、MIN_PRIORITY；
 * JS 探针经 UI 线程 post + 400ms 超时，采样线程阻塞有界。
 * 监控关闭时除 mark 的百纳秒级常数外无任何行为。</p>
 */
public final class PerfMonitor {
    /** 采样周期。真机实测 1s 周期时监控线程自身占到本进程 CPU 的 10%，3s 降到 ~3%。 */
    private static final long INTERVAL_MS = 3000L;
    /** 每采样多少次把完整报告快照落盘一次（30s），导出的日志才有分析价值。 */
    private static final long SNAPSHOT_EVERY_SAMPLES = 10L;
    /** JS 探针最小间隔：每次 evaluateJavascript 都是跨进程往返且在页面主线程执行。 */
    private static final long JS_PROBE_INTERVAL_MS = 3000L;
    private static final long JIFFY_NS = 10_000_000L; // 100Hz
    private static final int HIST_BUCKETS = 16;
    /** 直方图上边界（纳秒），最后一块为溢出（>5s）。 */
    private static final long[] HIST_EDGES = {
            50_000L, 100_000L, 250_000L, 500_000L,
            1_000_000L, 2_500_000L, 5_000_000L, 10_000_000L,
            25_000_000L, 50_000_000L, 100_000_000L, 250_000_000L,
            500_000_000L, 1_000_000_000L, 5_000_000_000L,
    };

    /** JS 侧探针：由 UI 宿主实现，在采样线程阻塞调用（内部自行 post 到 UI 线程并等待）。 */
    public interface JsProbe {
        /** @return 页面返回的 JSON 文本；不可用返回 null */
        String probe();
    }

    /** 埋点统计。字段在 synchronized(this) 下维护。 */
    public static final class Stat {
        public long count;
        public long totalNs;
        public long maxNs;
        public final long[] hist = new long[HIST_BUCKETS];
    }

    /** /proc 解析结果。 */
    static final class ProcStat {
        final String comm;
        final long utime;
        final long stime;

        ProcStat(String comm, long utime, long stime) {
            this.comm = comm;
            this.utime = utime;
            this.stime = stime;
        }
    }

    /** 最近若干个采样窗口的线程增量快照，用于算「近 30s 均值 / 峰值」。 */
    private static final int RECENT_WINDOWS = 10;

    private static final class Window {
        final Map<String, Long> threads;
        final long totalJiffies;

        Window(Map<String, Long> threads, long totalJiffies) {
            this.threads = threads;
            this.totalJiffies = totalJiffies;
        }
    }

    private static final ArrayDeque<Window> RECENT = new ArrayDeque<>();
    private static final Object RECENT_LOCK = new Object();

    private static final Map<String, Stat> STATS = new ConcurrentHashMap<>();
    /**
     * 主机名 -> 网络耗时聚合。用于回答「到底哪个域名在拖时间」——
     * 单个资源慢和整个域名连不上，在总耗时上看起来一样，拆开才看得出来。
     */
    private static final Map<String, HostStat> HOSTS = new ConcurrentHashMap<>();
    /** 主机数量上限，避免恶意/随机子域把内存撑爆。 */
    private static final int MAX_HOSTS = 24;

    /** 单个主机的网络统计（只统计预取路径，即我们真正发出请求的那些）。 */
    static final class HostStat {
        int count;
        int fails;
        long totalNs;
        long maxNs;
        long lastNs;
    }

    /** 线程名 -> 累计 CPU jiffies。 */
    private static final Map<String, Long> THREAD_JIFFIES = new ConcurrentHashMap<>();
    /** 同包其他进程名（如沙箱渲染进程）-> 累计 jiffies。 */
    private static final Map<String, Long> OTHER_PROC_JIFFIES = new ConcurrentHashMap<>();
    private static final Object PROC_LOCK = new Object();
    private static final Map<Integer, ProcStat> PREV_THREADS = new HashMap<>();
    private static final Map<String, long[]> PREV_OTHER_PROCS = new HashMap<>();
    private static long prevProcUt;
    private static long prevProcSt;
    private static long procTotalJiffies;

    private static volatile boolean running = false;
    private static volatile Thread sampler;
    private static volatile JsProbe jsProbe;
    private static volatile Context appCtx;
    private static volatile long samples = 0L;
    private static volatile long startWall = 0L;
    private static volatile long lastJsProbeAt = 0L;

    /** 当前渲染引擎（WebView 包名 + 版本），用于判断内核是否过旧。 */
    private static volatile String webViewInfo = "";

    public static void setWebViewInfo(String info) {
        webViewInfo = info == null ? "" : info;
    }

    private static volatile int jsFps;
    private static volatile int jsDomNodes;
    private static volatile int jsLongTasks;
    private static volatile long jsHeap;
    /** 当前渲染帧率上限，0 = 系统默认。由宿主在应用设置时告知，仅用于报告展示。 */
    private static volatile int frameRateLimit = 0;
    /** 本进程内页面加载/完成次数：两个数字一起涨说明页面在循环重载，而不是进程重启。 */
    private static volatile int pageStarts;
    private static volatile int pageFinishes;

    /** 由宿主在 onPageStarted / onPageFinished 里同步过来。 */
    public static void setPageCounts(int starts, int finishes) {
        pageStarts = starts;
        pageFinishes = finishes;
    }

    private PerfMonitor() {
    }

    // ---------------- 埋点 ----------------

    /** 记录一次耗时（纳秒）或数值（如字节数）。 */
    public static void mark(String key, long value) {
        Stat s = STATS.get(key);
        if (s == null) {
            s = new Stat();
            Stat old = STATS.putIfAbsent(key, s);
            if (old != null) {
                s = old;
            }
        }
        synchronized (s) {
            s.count++;
            s.totalNs += value;
            if (value > s.maxNs) {
                s.maxNs = value;
            }
            s.hist[bucketOf(value)]++;
        }
    }

    /** 只计数（hit/miss 等无数值的维度）。 */
    public static void count(String key) {
        Stat s = STATS.get(key);
        if (s == null) {
            s = new Stat();
            Stat old = STATS.putIfAbsent(key, s);
            if (old != null) {
                s = old;
            }
        }
        synchronized (s) {
            s.count++;
        }
    }

    /**
     * 记录一次预取请求的耗时，按主机聚合并入报告。
     *
     * @param host 目标主机名（不含端口）
     * @param ns   从发起连接到拿到全部响应的纳秒数
     * @param ok   是否成功拿到 2xx
     */
    public static void markHost(String host, long ns, boolean ok) {
        if (host == null || host.isEmpty()) {
            return;
        }
        HostStat h = HOSTS.get(host);
        if (h == null) {
            if (HOSTS.size() >= MAX_HOSTS) {
                return;
            }
            h = new HostStat();
            HostStat old = HOSTS.putIfAbsent(host, h);
            if (old != null) {
                h = old;
            }
        }
        synchronized (h) {
            h.count++;
            if (!ok) {
                h.fails++;
            }
            h.totalNs += ns;
            h.lastNs = ns;
            if (ns > h.maxNs) {
                h.maxNs = ns;
            }
        }
    }

    private static int bucketOf(long ns) {
        for (int i = 0; i < HIST_EDGES.length; i++) {
            if (ns < HIST_EDGES[i]) {
                return i;
            }
        }
        return HIST_BUCKETS - 1;
    }

    private static double edgeMs(int bucket) {
        if (bucket >= HIST_EDGES.length) {
            return 5000.0;
        }
        return HIST_EDGES[bucket] / 1_000_000.0;
    }

    /** 直方图分位数（返回桶上边界，即"不超过该值"）。包可见供测试。 */
    static double percentileMs(Stat s, int pct) {
        synchronized (s) {
            if (s.count <= 0) {
                return -1.0;
            }
            long target = s.count * pct / 100;
            if (target < 1) {
                target = 1;
            }
            long acc = 0L;
            for (int i = 0; i < HIST_BUCKETS; i++) {
                acc += s.hist[i];
                if (acc >= target) {
                    return edgeMs(i);
                }
            }
            return edgeMs(HIST_BUCKETS);
        }
    }

    // ---------------- 生命周期 ----------------

    /** 开启采样。重复调用安全。 */
    public static synchronized void start(Context context) {
        if (running) {
            return;
        }
        appCtx = context.getApplicationContext() != null
                ? context.getApplicationContext() : context;
        synchronized (PROC_LOCK) {
            PREV_THREADS.clear();
            PREV_OTHER_PROCS.clear();
            prevProcUt = 0L;
            prevProcSt = 0L;
        }
        synchronized (RECENT_LOCK) {
            RECENT.clear();
        }
        running = true;
        samples = 0L;
        lastJsProbeAt = 0L;
        startWall = SystemClock.elapsedRealtime();
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                loop();
            }
        }, "tk-perfmon");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY + 1);
        sampler = t;
        t.start();
        PerfLog.i("PerfMonitor", "started");
    }

    public static synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        Thread t = sampler;
        sampler = null;
        if (t != null) {
            t.interrupt();
        }
        jsProbe = null;
        // 关闭前落一份最终快照，短时间运行也能留下完整数据
        try {
            PerfLog.block("PerfSnapshot", report());
        } catch (Exception ignored) {
        }
        PerfLog.i("PerfMonitor", "stopped after " + samples + " samples");
    }

    public static boolean isRunning() {
        return running;
    }

    public static void setJsProbe(JsProbe probe) {
        jsProbe = probe;
    }

    /** 告知当前帧率上限（0 = 系统默认），用于在报告里与实际 fps 读数对照。 */
    /** rAF 节流 hook 是否真的装进页面（false 时报告会标明，便于发现"设了没生效"）。 */
    private static volatile boolean rafHooked = false;

    public static void setRafHook(boolean installed) {
        rafHooked = installed;
    }

    public static void setFrameRateLimit(int fps) {
        frameRateLimit = fps > 0 ? fps : 0;
    }

    // ---------------- 采样循环 ----------------

    private static void loop() {
        while (running) {
            long tick0 = SystemClock.elapsedRealtime();
            sampleSelf();
            sampleOtherProcesses();
            probeJs();
            samples++;
            // 周期性把完整报告写入日志：导出后即可离线分析，不必依赖对话框
            if (samples % SNAPSHOT_EVERY_SAMPLES == 0L) {
                try {
                    PerfLog.block("PerfSnapshot", report());
                } catch (Exception ignored) {
                }
            }
            long rest = INTERVAL_MS - (SystemClock.elapsedRealtime() - tick0);
            if (rest > 0) {
                try {
                    Thread.sleep(rest);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /** 本进程总量 + 逐线程统计。 */
    private static void sampleSelf() {
        long windowTotal = 0L;
        ProcStat self = parseProcStat(readFirstLine(new File("/proc/self/stat")));
        if (self != null) {
            synchronized (PROC_LOCK) {
                if (prevProcUt != 0L || prevProcSt != 0L) {
                    long d = (self.utime - prevProcUt) + (self.stime - prevProcSt);
                    if (d > 0) {
                        procTotalJiffies += d;
                        windowTotal = d;
                    }
                }
                prevProcUt = self.utime;
                prevProcSt = self.stime;
            }
        }
        File[] tasks = new File("/proc/self/task").listFiles();
        if (tasks == null) {
            return;
        }
        Map<Integer, ProcStat> cur = new HashMap<>();
        for (File t : tasks) {
            int tid;
            try {
                tid = Integer.parseInt(t.getName());
            } catch (NumberFormatException e) {
                continue;
            }
            ProcStat ps = parseProcStat(readFirstLine(new File(t, "stat")));
            if (ps != null) {
                cur.put(tid, ps);
            }
        }
        // 本窗口的逐线程增量单独留一份，供「近 30s 均值 / 峰值」使用
        Map<String, Long> window = new HashMap<>();
        synchronized (PROC_LOCK) {
            for (Map.Entry<Integer, ProcStat> e : cur.entrySet()) {
                ProcStat prev = PREV_THREADS.get(e.getKey());
                if (prev != null) {
                    long d = (e.getValue().utime - prev.utime)
                            + (e.getValue().stime - prev.stime);
                    if (d > 0) {
                        accumulate(THREAD_JIFFIES, e.getValue().comm, d);
                        accumulate(window, e.getValue().comm, d);
                    }
                }
                PREV_THREADS.put(e.getKey(), e.getValue());
            }
            // 清理已退出的线程，防止 tid 复用导致误累计
            PREV_THREADS.keySet().retainAll(cur.keySet());
        }
        if (windowTotal > 0L && !window.isEmpty()) {
            synchronized (RECENT_LOCK) {
                RECENT.addLast(new Window(window, windowTotal));
                while (RECENT.size() > RECENT_WINDOWS) {
                    RECENT.removeFirst();
                }
            }
        }
    }

    /** WebView 沙箱渲染进程等本应用的其他进程——CPU 大头常常在这里。 */
    private static void sampleOtherProcesses() {
        // getRunningAppProcesses() 是一次 Binder 往返，且 Android 8+ 上 WebView 渲染进程
        // 是 isolated 进程、不会出现在本应用的进程列表里；30s 查一次足够，避免白付开销。
        if (samples % 10L != 0L) {
            return;
        }
        if (appCtx == null) {
            return;
        }
        ActivityManager am = (ActivityManager) appCtx.getSystemService(
                Context.ACTIVITY_SERVICE);
        if (am == null) {
            return;
        }
        List<ActivityManager.RunningAppProcessInfo> procs = am.getRunningAppProcesses();
        if (procs == null) {
            return;
        }
        String myPkg = appCtx.getPackageName();
        int myPid = Process.myPid();
        for (ActivityManager.RunningAppProcessInfo pi : procs) {
            if (pi.pid == myPid || pi.pkgList == null) {
                continue;
            }
            boolean mine = false;
            for (String pkg : pi.pkgList) {
                if (pkg != null && pkg.equals(myPkg)) {
                    mine = true;
                    break;
                }
            }
            if (!mine) {
                continue;
            }
            ProcStat ps = parseProcStat(readFirstLine(new File("/proc/" + pi.pid + "/stat")));
            if (ps == null) {
                continue;
            }
            synchronized (PROC_LOCK) {
                long[] prev = PREV_OTHER_PROCS.get(pi.processName);
                if (prev != null) {
                    long d = (ps.utime - prev[0]) + (ps.stime - prev[1]);
                    if (d > 0) {
                        accumulate(OTHER_PROC_JIFFIES, pi.processName, d);
                    }
                }
                PREV_OTHER_PROCS.put(pi.processName, new long[]{ps.utime, ps.stime});
            }
        }
    }

    private static void accumulate(Map<String, Long> map, String key, long delta) {
        Long old = map.get(key);
        map.put(key, old == null ? Long.valueOf(delta) : Long.valueOf(old.longValue() + delta));
    }

    private static void probeJs() {
        JsProbe p = jsProbe;
        if (p == null) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (now - lastJsProbeAt < JS_PROBE_INTERVAL_MS) {
            return;
        }
        lastJsProbeAt = now;
        String json = p.probe();
        applyJsMetrics(json);
    }

    /**
     * 解析探针 JSON：{@code {"fps":180,"dom":17298,"lt":3,"heap":123456789,"ms":3000}}。
     * fps 是「距上次探针的帧计数」，用 ms 换算成每秒帧数——探针间隔可调而不影响读数。
     * 手写解析而非 org.json——宿主模块测试环境不带 Android 框架。畸形输入静默忽略。
     */
    static void applyJsMetrics(String json) {
        if (json == null || json.isEmpty()) {
            return;
        }
        try {
            long frames = num(json, "fps");
            long windowMs = num(json, "ms");
            if (windowMs >= 200) {
                jsFps = (int) (frames * 1000L / windowMs);
            }
            jsDomNodes = (int) num(json, "dom");
            jsLongTasks = (int) num(json, "lt");
            long ltMax = num(json, "ltMax");
            jsHeap = num(json, "heap");
            // 卡顿归因：长任务超过 100ms 单独记一条，时间戳能与 CPU 峰值直接对上
            if (ltMax >= 100L) {
                PerfLog.w("PerfMonitor", "js long task " + ltMax + "ms, dom=" + jsDomNodes
                        + ", heap=" + (jsHeap / 1048576L) + "MB, fps~" + jsFps);
            }
        } catch (Exception ignored) {
        }
    }

    private static long num(String json, String key) {
        int i = json.indexOf(key);
        if (i < 0) {
            return 0L;
        }
        i = json.indexOf(':', i) + 1;
        if (i <= 0 || i >= json.length()) {
            return 0L;
        }
        int j = i;
        while (j < json.length() && "0123456789.eE+-".indexOf(json.charAt(j)) >= 0) {
            j++;
        }
        if (j <= i) {
            return 0L;
        }
        return Long.parseLong(json.substring(i, j));
    }

    // ---------------- /proc 解析 ----------------

    /**
     * 解析 /proc/.../stat 的一行。
     * comm 字段可能含空格与括号（如 "(my thread)"、"(a)b)"），用 lastIndexOf(')') 定位；
     * utime/stime 是 ')' 之后的第 12/13 个字段（全局第 14/15）。
     *
     * <p>手写扫描而不是 split("\\s+")：真机上这个函数每秒要为几十个线程各跑一次，
     * 正则编译 + 数组分配是监控线程的主要开销来源之一。</p>
     */
    static ProcStat parseProcStat(String line) {
        if (line == null || line.isEmpty()) {
            return null;
        }
        int lp = line.indexOf('(');
        int rp = line.lastIndexOf(')');
        if (lp < 0 || rp < 0 || rp <= lp) {
            return null;
        }
        String comm = line.substring(lp + 1, rp);
        int field = 2;   // comm 本身是第 2 个字段
        int start = -1;
        long utime = -1L;
        long stime = -1L;
        int len = line.length();
        for (int i = rp + 1; i <= len; i++) {
            boolean sep = i == len || line.charAt(i) == ' ' || line.charAt(i) == '\n';
            if (!sep) {
                if (start < 0) {
                    start = i;
                }
                continue;
            }
            if (start < 0) {
                continue;
            }
            field++;
            if (field == 14) {
                utime = parseLongRange(line, start, i);
            } else if (field == 15) {
                stime = parseLongRange(line, start, i);
                break;
            }
            start = -1;
        }
        if (utime < 0L || stime < 0L) {
            return null;
        }
        return new ProcStat(comm, utime, stime);
    }

    /** 解析 [from, to) 的非负十进制整数，失败返回 -1。 */
    private static long parseLongRange(String s, int from, int to) {
        if (from >= to) {
            return -1L;
        }
        long v = 0L;
        for (int i = from; i < to; i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return -1L;
            }
            v = v * 10L + (c - '0');
        }
        return v;
    }

    private static String readFirstLine(File f) {
        if (f == null || !f.canRead()) {
            return null;
        }
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new FileReader(f));
            return reader.readLine();
        } catch (Exception ignored) {
            return null;
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    // ---------------- 报告 ----------------

    /** 生成文本报告（在 UI 线程调用）。 */
    public static String report() {
        StringBuilder sb = new StringBuilder(1024);
        long wall = SystemClock.elapsedRealtime() - startWall;
        long total = procTotalJiffies;
        sb.append("=== TavernKeep 性能报告 ===\n");
        sb.append("运行 ").append(wall / 1000).append("s / 采样 ").append(samples).append(" 次\n");

        sb.append("[进程 CPU]");
        appendPct(sb, "本进程", total, wall);
        List<Map.Entry<String, Long>> others = new ArrayList<>(OTHER_PROC_JIFFIES.entrySet());
        Collections.sort(others, new Comparator<Map.Entry<String, Long>>() {
            @Override
            public int compare(Map.Entry<String, Long> a, Map.Entry<String, Long> b) {
                return Long.compare(b.getValue(), a.getValue());
            }
        });
        for (Map.Entry<String, Long> e : others) {
            appendPct(sb, e.getKey(), e.getValue(), wall);
        }
        if (others.isEmpty()) {
            sb.append("（渲染进程为 isolated 进程，普通应用无法枚举，未计入）");
        }
        sb.append('\n');

        sb.append("[线程 Top10]（累计 / 近30s 均值 / 峰值，均占本进程）\n");
        sb.append("  渲染相关：RenderThread / Chrome_InProcGp / VizWebView / mali-*\n");
        sb.append("  应用相关：main / tk-prefetch-* / tk-perfmon / HeapTaskDaemon(GC)\n");
        Map<String, double[]> recent = recentStats();
        List<Map.Entry<String, Long>> threads = new ArrayList<>(THREAD_JIFFIES.entrySet());
        Collections.sort(threads, new Comparator<Map.Entry<String, Long>>() {
            @Override
            public int compare(Map.Entry<String, Long> a, Map.Entry<String, Long> b) {
                return Long.compare(b.getValue(), a.getValue());
            }
        });
        int shown = 0;
        for (Map.Entry<String, Long> e : threads) {
            if (shown >= 10) {
                break;
            }
            double pct = total > 0 ? (e.getValue() * 100.0 / total) : 0.0;
            double[] r = recent.get(e.getKey());
            String tail = r == null
                    ? "  近30s   n/a    峰值   n/a"
                    : String.format(Locale.US, "  近30s%5.1f%%   峰值%5.1f%%", r[0], r[1]);
            sb.append(String.format(Locale.US, "  %-18s 累计%5.1f%%%s\n", e.getKey(), pct, tail));
            shown++;
        }
        if (threads.size() > 10) {
            long rest = 0L;
            for (int i = 10; i < threads.size(); i++) {
                rest += threads.get(i).getValue();
            }
            sb.append(String.format(Locale.US, "  ... 其余 %d 个线程合计 %5.1f%%\n",
                    threads.size() - 10, total > 0 ? rest * 100.0 / total : 0.0));
        }

        appendHosts(sb);
        sb.append("[热路径埋点]（p50≤/p99≤ 为直方图桶上边界：不超过该值，非精确分位）\n");
        List<Map.Entry<String, Stat>> stats = new ArrayList<>(STATS.entrySet());
        Collections.sort(stats, new Comparator<Map.Entry<String, Stat>>() {
            @Override
            public int compare(Map.Entry<String, Stat> a, Map.Entry<String, Stat> b) {
                return Long.compare(b.getValue().totalNs, a.getValue().totalNs);
            }
        });
        for (Map.Entry<String, Stat> e : stats) {
            Stat s = e.getValue();
            if (s.count <= 0) {
                continue;
            }
            if (e.getKey().endsWith(".bytes")) {
                // 字节类指标：totalNs 字段承载的是字节总量
                sb.append(String.format(Locale.US, "  %-18s n=%-6d 合计=%8.1fKB avg=%8.1fKB\n",
                        e.getKey(), s.count,
                        s.totalNs / 1024.0, s.totalNs / 1024.0 / s.count));
                continue;
            }
            if (s.totalNs == 0L) {
                // 纯计数类（hit/miss 等），没有耗时分布可谈
                sb.append(String.format(Locale.US, "  %-18s n=%d\n", e.getKey(), s.count));
                continue;
            }
            double avgMs = s.totalNs / 1_000_000.0 / s.count;
            sb.append(String.format(Locale.US,
                    "  %-18s n=%-6d avg=%8.3fms p50≤%8.3f p99≤%9.3f max=%9.3f\n",
                    e.getKey(), s.count, avgMs,
                    percentileMs(s, 50), percentileMs(s, 99), s.maxNs / 1_000_000.0));
        }

        if (pageStarts > 0) {
            sb.append("[页面] 本进程内加载 ").append(pageStarts)
                    .append(" 次 / 完成 ").append(pageFinishes).append(" 次")
                    .append(pageStarts >= 3 ? " ← 在循环重载，先查扩展更新/初始化" : "")
                    .append('\n');
        }
        sb.append(String.format(Locale.US,
                "[JS] fps~%d dom=%d longTask=%d jsHeap=%dMB（仅 JS 对象；DOM 节点在 C++ 堆，不计入）\n",
                jsFps, jsDomNodes, jsLongTasks, jsHeap / 1048576L));
        sb.append("[设置] 帧率上限=")
                .append(frameRateLimit > 0 ? frameRateLimit + "fps" : "系统默认")
                .append("（rAF hook=").append(rafHooked ? "已装" : "未装")
                .append("；未装时限帧不生效）\n");
        sb.append("[WebView] ")
                .append(webViewInfo.isEmpty() ? "未知" : webViewInfo)
                .append("（版本过旧请更新 Android System WebView）\n");

        Runtime rt = Runtime.getRuntime();
        long javaUsed = rt.totalMemory() - rt.freeMemory();
        sb.append(String.format(Locale.US,
                "[内存] javaHeap=%.1f/%.1fMB native=%.1fMB\n",
                javaUsed / 1048576.0, rt.maxMemory() / 1048576.0,
                Debug.getNativeHeapAllocatedSize() / 1048576.0));
        return sb.toString();
    }

    /** 近 N 个窗口内每个线程的占比：返回 {平均, 峰值}。 */
    private static Map<String, double[]> recentStats() {
        Map<String, double[]> out = new HashMap<>();
        synchronized (RECENT_LOCK) {
            if (RECENT.isEmpty()) {
                return out;
            }
            long sumTotal = 0L;
            Map<String, Long> sumThread = new HashMap<>();
            Map<String, Double> peak = new HashMap<>();
            for (Window w : RECENT) {
                sumTotal += w.totalJiffies;
                for (Map.Entry<String, Long> e : w.threads.entrySet()) {
                    accumulate(sumThread, e.getKey(), e.getValue());
                    double pct = w.totalJiffies > 0
                            ? e.getValue() * 100.0 / w.totalJiffies : 0.0;
                    Double old = peak.get(e.getKey());
                    if (old == null || pct > old.doubleValue()) {
                        peak.put(e.getKey(), Double.valueOf(pct));
                    }
                }
            }
            for (Map.Entry<String, Long> e : sumThread.entrySet()) {
                double avg = sumTotal > 0 ? e.getValue() * 100.0 / sumTotal : 0.0;
                Double pk = peak.get(e.getKey());
                out.put(e.getKey(),
                        new double[]{avg, pk == null ? 0.0 : pk.doubleValue()});
            }
        }
        return out;
    }

    /** 按主机汇总的网络耗时：定位"是不是某个域名连不上在拖时间"。 */
    private static void appendHosts(StringBuilder sb) {
        if (HOSTS.isEmpty()) {
            return;
        }
        List<Map.Entry<String, HostStat>> list = new ArrayList<>(HOSTS.entrySet());
        Collections.sort(list, new Comparator<Map.Entry<String, HostStat>>() {
            @Override
            public int compare(Map.Entry<String, HostStat> a, Map.Entry<String, HostStat> b) {
                return Long.compare(b.getValue().totalNs, a.getValue().totalNs);
            }
        });
        sb.append("[网络按主机]（预取路径；失败=超时/连不上/非 2xx）\n");
        for (Map.Entry<String, HostStat> e : list) {
            HostStat h = e.getValue();
            long count;
            long totalNs;
            long maxNs;
            int fails;
            synchronized (h) {
                count = h.count;
                totalNs = h.totalNs;
                maxNs = h.maxNs;
                fails = h.fails;
            }
            if (count <= 0) {
                continue;
            }
            double totalS = totalNs / 1_000_000_000.0;
            double maxS = maxNs / 1_000_000_000.0;
            String host = e.getKey();
            if (host.length() > 26) {
                host = host.substring(0, 25) + "…";
            }
            sb.append(String.format(Locale.US,
                    "  %-26s n=%-3d 合计%7.2fs 最高%6.2fs 失败%d%s\n",
                    host, count, totalS, maxS, fails,
                    (totalS > 3.0 || (count >= 2 && fails >= count))
                            ? "  ← 拖时间" : ""));
        }
    }

    private static void appendPct(StringBuilder sb, String name, long jiffies, long wallMs) {
        double pct = wallMs > 0 ? (jiffies * (JIFFY_NS / 1_000_000.0) / wallMs) * 100.0 : -1.0;
        sb.append(String.format(Locale.US, " %s %.1f%%", name, pct));
    }

    /** 测试用：直接喂埋点值。 */
    static Stat statFor(String key) {
        return STATS.get(key);
    }
}
