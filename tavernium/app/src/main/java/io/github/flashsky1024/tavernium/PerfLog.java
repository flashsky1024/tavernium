package io.github.flashsky1024.tavernium;

import android.content.Context;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 性能调试日志系统：异步落盘 + 滚动，默认不写，仅性能监控开启时由 PerfMonitor 驱动。
 *
 * <p>设计约束：</p>
 * <ul>
 *   <li>写盘走独立低优先级线程，绝不阻塞 UI / WebView 网络线程；</li>
 *   <li>待写队列有上限：日志风暴（页面循环报错、每秒数百条 console）时丢弃新日志，
 *       而不是让 Runnable 在无界队列里堆到 OOM；</li>
 *   <li>单文件 1.5MB 滚动，最多保留 3 个，防止 debug 日志吃满存储；</li>
 *   <li>目录用 getExternalFilesDir("perf")：无需存储权限，用户可经 USB / 文件管理器直接取走。</li>
 * </ul>
 */
public final class PerfLog {
    private static final String DIR_NAME = "perf";
    private static final String FILE_NAME = "perf.log";
    private static final long MAX_BYTES = 1_500_000L;
    private static final int MAX_FILES = 3;
    /** 最多允许多少条日志在队列里排队；写盘跟不上时立刻开始丢弃。 */
    private static final int MAX_PENDING = 4000;
    private static final SimpleDateFormat TS =
            new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);

    private static final Object lock = new Object();
    private static final AtomicInteger pending = new AtomicInteger();
    private static final AtomicInteger dropped = new AtomicInteger();
    private static volatile boolean inited = false;
    private static volatile boolean verbose = false;
    private static File dir;
    private static ExecutorService io;

    private PerfLog() {
    }

    /** 进程内只应初始化一次；重复调用安全返回。 */
    public static synchronized void init(Context context) {
        if (inited) {
            return;
        }
        File base = context.getExternalFilesDir(null);
        dir = base != null ? new File(base, DIR_NAME) : new File(context.getFilesDir(), DIR_NAME);
        if (!dir.exists() && !dir.mkdirs()) {
            android.util.Log.w("TavernKeepPerf", "cannot create log dir: " + dir);
        }
        io = Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "tk-perflog");
                t.setDaemon(true);
                t.setPriority(Thread.MIN_PRIORITY);
                return t;
            }
        });
        inited = true;
        i("PerfLog", "init dir=" + dir.getAbsolutePath());
    }

    public static boolean ready() {
        return inited;
    }

    /** 详细日志开关：关闭时 d() 级日志直接丢弃，事件与报告快照不受影响。 */
    public static void setVerbose(boolean on) {
        verbose = on;
        if (inited) {
            i("PerfLog", "verbose=" + on);
        }
    }

    public static boolean isVerbose() {
        return verbose;
    }

    public static File dir() {
        return dir;
    }

    /** 当前日志文件（供导出）。未初始化返回 null。 */
    public static File currentLog() {
        if (!inited || dir == null) {
            return null;
        }
        return new File(dir, FILE_NAME);
    }

    public static void i(String tag, String msg) {
        write("I", tag, msg);
    }

    public static void w(String tag, String msg) {
        write("W", tag, msg);
    }

    /** 详细级：仅 verbose 开启时写盘（逐请求、生命周期等高频事件走这里）。 */
    public static void d(String tag, String msg) {
        if (!verbose) {
            return;
        }
        write("D", tag, msg);
    }

    /** 写入多行文本块（每行带时间戳，一次开合文件），用于性能报告快照。 */
    public static void block(String tag, String text) {
        if (!inited || text == null || text.isEmpty()) {
            return;
        }
        String prefix = ts() + " I/" + tag + ": ";
        StringBuilder sb = new StringBuilder(text.length() + 64);
        for (String line : text.split("\n")) {
            sb.append(prefix).append(line).append('\n');
        }
        final String chunk = sb.toString();
        enqueue(new Runnable() {
            @Override
            public void run() {
                append(chunk);
            }
        });
    }

    /**
     * 入队并维护待写计数。队列满时丢弃并记账——丢几条日志远好过把进程拖死。
     *
     * @return true 表示成功入队
     */
    private static boolean enqueue(Runnable task) {
        if (pending.get() >= MAX_PENDING) {
            dropped.incrementAndGet();
            return false;
        }
        pending.incrementAndGet();
        try {
            io.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        task.run();
                    } finally {
                        pending.decrementAndGet();
                    }
                }
            });
            return true;
        } catch (Exception e) {
            // RejectedExecutionException（已 shutdown）等：计数必须回滚，否则队列永久"满"
            pending.decrementAndGet();
            return false;
        }
    }

    /**
     * SimpleDateFormat 非线程安全：快照来自采样线程、事件来自 UI/网络线程，
     * 必须串行格式化。
     */
    private static String ts() {
        synchronized (TS) {
            return TS.format(new Date());
        }
    }

    /**
     * 写一行日志。线程名一并记录——shouldInterceptRequest 跑在 WebView 网络线程、
     * 快照跑在 tk-perfmon，出问题时能直接看出是谁在说话。
     */
    private static void write(String level, String tag, String msg) {
        if (!inited) {
            return;
        }
        final String line = ts() + " " + level + "/" + tag
                + "[" + Thread.currentThread().getName() + "]: " + msg + "\n";
        enqueue(new Runnable() {
            @Override
            public void run() {
                append(line);
            }
        });
    }

    /** 读取当前日志末尾约 maxBytes 字节（供应用内查看，避免整文件加载）。 */
    public static String tail(int maxBytes) {
        File f = currentLog();
        if (f == null || !f.exists()) {
            return "";
        }
        java.io.RandomAccessFile raf = null;
        try {
            raf = new java.io.RandomAccessFile(f, "r");
            long len = raf.length();
            long from = Math.max(0L, len - Math.max(0, maxBytes));
            raf.seek(from);
            byte[] buf = new byte[(int) (len - from)];
            raf.readFully(buf);
            String text = new String(buf, "UTF-8");
            // 从第一个完整行开始，避免半行乱码
            if (from > 0) {
                int nl = text.indexOf('\n');
                if (nl >= 0 && nl + 1 < text.length()) {
                    text = text.substring(nl + 1);
                }
            }
            return text;
        } catch (Exception e) {
            return "";
        } finally {
            if (raf != null) {
                try {
                    raf.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static void append(String line) {
        appendRaw(line);
        // 队列满时丢弃的条数在这里补记：日志里出现断层时能知道是"丢过"而不是"没发生"
        int d = dropped.getAndSet(0);
        if (d > 0) {
            appendRaw(ts() + " W/PerfLog: " + d + " lines dropped (write queue full)\n");
        }
    }

    private static void appendRaw(String line) {
        synchronized (lock) {
            try {
                File target = currentLog();
                if (target != null && target.length() + line.length() > MAX_BYTES) {
                    rotate();
                    target = currentLog();
                }
                if (target == null) {
                    return;
                }
                FileWriter writer = new FileWriter(target, true);
                try {
                    writer.write(line);
                } finally {
                    writer.close();
                }
            } catch (IOException ignored) {
                // 日志系统自身永不抛异常影响业务
            }
        }
    }

    /** perf.log -> perf.log.1 -> perf.log.2，共保留 MAX_FILES 个，最旧的删除。 */
    private static void rotate() {
        File oldest = new File(dir, FILE_NAME + "." + (MAX_FILES - 1));
        if (oldest.exists()) {
            oldest.delete();
        }
        for (int i = MAX_FILES - 2; i >= 1; i--) {
            File from = new File(dir, FILE_NAME + "." + i);
            if (from.exists()) {
                File to = new File(dir, FILE_NAME + "." + (i + 1));
                if (!from.renameTo(to)) {
                    from.delete();
                }
            }
        }
        File cur = currentLog();
        if (cur != null && cur.exists()) {
            File to = new File(dir, FILE_NAME + ".1");
            if (!cur.renameTo(to)) {
                cur.delete();
            }
        }
    }

    /** 阻塞直到已入队的日志全部落盘（测试与导出前使用）。 */
    public static void flushAndWait() throws InterruptedException {
        if (!inited || io == null) {
            return;
        }
        try {
            Future<?> f = io.submit(new Runnable() {
                @Override
                public void run() {
                }
            });
            f.get();
        } catch (Exception ignored) {
        }
    }

    /** 删除全部日志文件。 */
    public static synchronized void clear() {
        if (!inited || dir == null) {
            return;
        }
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isFile()) {
                    f.delete();
                }
            }
        }
    }
}
