/*
 * Copyright (C) 2026 flashsky1024
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package io.github.flashsky1024.tavernium;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 全局崩溃落盘，以及「连续启动失败就降级」的自我保护。
 *
 * <p>背景：有用户反馈启动时偶尔闪退，而且之后再点图标都打不开，只能清除应用数据。
 * 这类问题最大的障碍不是修，而是<b>没有堆栈</b>——崩溃发生在界面出现之前，
 * 用户进不了菜单，也就导不出日志，只能靠猜。</p>
 *
 * <p>这里做两件事：</p>
 * <ol>
 *   <li>把未捕获异常的完整堆栈写到 {@code files/perf/crash-*.txt}。该目录不需要
 *       存储权限，应用打不开也能用 USB 或文件管理器取走。</li>
 *   <li>记录「启动后 20 秒内崩溃」的连续次数。连续两次说明有持久化状态在反复
 *       触发崩溃，下次启动进入安全模式，跳过非必需的外围功能，让用户至少能进界面
 *       改地址、导日志，而不是只能清数据。</li>
 * </ol>
 *
 * <p>崩溃处理器只写文件和偏好，不做网络、不弹界面，避免在进程将死时引入二次异常。</p>
 */
public final class CrashReporter {
    private static final String TAG = "CrashReporter";
    private static final String PREFS = "tavernium_crash";
    private static final String KEY_STREAK = "boot_crash_streak";
    private static final String KEY_LAST_BOOT = "last_boot_at";
    private static final String KEY_LAST_CRASH = "last_crash_at";
    /** 启动后多久内崩溃算作「启动崩溃」。超过这个窗口的崩溃按普通崩溃处理，不计入连击。 */
    private static final long BOOT_WINDOW_MS = 20000L;
    /** 连续几次启动崩溃后进入安全模式。 */
    private static final int SAFE_MODE_THRESHOLD = 2;
    /** 连续几次后不再只是降级，而是清掉可重建的数据。 */
    private static final int RECOVERY_THRESHOLD = 3;
    /** 崩溃文件位置：应用打不开时也能用文件管理器或电脑取走。 */
    private static String logHint(Context context) {
        // 调试版包名带 .debug，写死正式版包名会让调试版用户照着找却找不到
        String pkg = context == null ? "io.github.flashsky1024.tavernium"
                : context.getPackageName();
        return "Android/data/" + pkg + "/files/perf/\n"
                + "里面的 crash- 开头文件是崩溃原因，perf.log 是运行日志。\n"
                + "能进界面的话：主菜单 → 更新、调试与其他设置 → 调试与诊断 → 导出日志，会打包成一个 zip。";
    }
    private static final int MAX_CRASH_FILES = 5;

    private static boolean installed = false;

    private CrashReporter() {
    }

    /**
     * 安装全局异常处理器。必须在 onCreate 最前面调用，否则启动早期的崩溃抓不到。
     *
     * <p>保留系统原来的处理器：写完日志后继续交给它，系统该弹「已停止运行」还是弹，
     * 不掩盖问题。</p>
     */
    public static synchronized void install(final Context context) {
        if (installed || context == null) {
            return;
        }
        installed = true;
        final Context app = context.getApplicationContext();
        final Thread.UncaughtExceptionHandler previous =
                Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread thread, Throwable throwable) {
                try {
                    writeCrashFile(app, thread, throwable);
                } catch (Throwable ignored) {
                }
                try {
                    noteCrash(app);
                } catch (Throwable ignored) {
                }
                if (previous != null) {
                    previous.uncaughtException(thread, throwable);
                } else {
                    // 没有上游处理器时按 Android 默认行为结束进程
                    android.os.Process.killProcess(android.os.Process.myPid());
                    System.exit(10);
                }
            }
        });
    }

    /** 启动时调用：写入本次启动时间，供崩溃时判断是不是「启动崩溃」。 */
    public static void markBoot(Context context) {
        if (context == null) {
            return;
        }
        try {
            prefs(context).edit().putLong(KEY_LAST_BOOT, System.currentTimeMillis()).apply();
        } catch (Throwable ignored) {
        }
    }

    /**
     * 是否该用安全模式启动。
     *
     * <p>只统计「启动后很快又崩」的连续次数：使用中偶发的崩溃不会把应用推进安全模式。</p>
     */
    public static boolean shouldUseSafeMode(Context context) {
        if (context == null) {
            return false;
        }
        try {
            return prefs(context).getInt(KEY_STREAK, 0) >= SAFE_MODE_THRESHOLD;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 启动稳定运行一段时间后调用，清零连击记录。 */
    public static void markBootCompleted(Context context) {
        if (context == null) {
            return;
        }
        try {
            prefs(context).edit().putInt(KEY_STREAK, 0).apply();
        } catch (Throwable ignored) {
        }
    }

    /**
     * 是否该进入「自动修复」：连续崩溃到安全模式也没救回来，说明问题不在外围功能，
     * 而在某个持久化的坏数据上。
     */
    public static boolean shouldUseRecoveryMode(Context context) {
        if (context == null) {
            return false;
        }
        try {
            return prefs(context).getInt(KEY_STREAK, 0) >= RECOVERY_THRESHOLD;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 清掉可以重新生成的数据，保留设置。
     *
     * <p>用户口中的「重置数据」能救回来，说明坏掉的是磁盘上的持久化状态。但真的清数据
     * 会把服务器地址、账号、各项设置一起抹掉，代价太大。这里只清两类：</p>
     * <ul>
     *   <li>WebView 的 cookie、localStorage、IndexedDB、HTTP 缓存（{@code app_webview/}）</li>
     *   <li>本应用自己的磁盘缓存（{@code cache/}）</li>
     * </ul>
     *
     * <p>代价是登录状态丢失、下次要重新登录酒馆；聊天记录在服务端，不受影响。</p>
     *
     * @return 是否清掉了至少一项
     */
    public static boolean clearTransientData(Context context) {
        if (context == null) {
            return false;
        }
        boolean cleared = false;
        try {
            File webview = new File(context.getDataDir(), "app_webview");
            cleared |= deleteRecursively(webview);
        } catch (Throwable ignored) {
        }
        try {
            File cache = context.getCacheDir();
            if (cache != null && cache.isDirectory()) {
                File[] children = cache.listFiles();
                if (children != null) {
                    for (File child : children) {
                        cleared |= deleteRecursively(child);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        if (cleared) {
            Log.w(TAG, "transient data cleared after repeated boot crashes");
        }
        return cleared;
    }

    private static boolean deleteRecursively(File target) {
        if (target == null || !target.exists()) {
            return false;
        }
        if (target.isDirectory()) {
            File[] children = target.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        return target.delete();
    }

    /** 安全模式的提示文案，集中放这里方便改。 */
    public static String safeModeMessage(Context context) {
        return "检测到连续 " + streak(context) + " 次启动失败，已临时关闭后台服务、缓存加速等外围功能。\n\n"
                + "如果仍然闪退，请用文件管理器或电脑取出日志：\n"
                + logHint(context);
    }

    /** 自动修复后的说明：必须讲清楚丢了什么、没丢什么，否则用户会以为又被清了一次数据。 */
    public static String recoveryMessage(Context context) {
        return "连续 " + streak(context) + " 次启动失败，已清掉网页缓存和登录状态后重试。\n\n"
                + "服务器地址、账号密码等设置都还在；聊天记录在服务端，没有受影响。"
                + "如果酒馆要求重新登录，登录一次即可。\n\n"
                + "如果这次仍然闪退，日志在：\n" + logHint(context);
    }

    private static int streak(Context context) {
        try {
            return prefs(context).getInt(KEY_STREAK, 0);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /**
     * 最近几份崩溃记录，最新的排在最前。
     *
     * <p>供「导出日志」一并带出：崩溃后应用可能根本打不开菜单，但只要还能进一次界面，
     * 导出的压缩包里就该直接有崩溃堆栈，而不是让用户去翻 Android/data 目录。</p>
     */
    public static java.util.List<File> recentCrashFiles(Context context, int limit) {
        java.util.List<File> out = new java.util.ArrayList<File>();
        if (context == null || limit <= 0) {
            return out;
        }
        File base = crashDir(context);
        File[] files = base.listFiles();
        if (files == null) {
            return out;
        }
        java.util.List<File> all = new java.util.ArrayList<File>();
        for (File f : files) {
            if (f != null && f.isFile() && f.getName().startsWith("crash-")) {
                all.add(f);
            }
        }
        java.util.Collections.sort(all, new java.util.Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return Long.compare(b.lastModified(), a.lastModified());
            }
        });
        for (int i = 0; i < all.size() && i < limit; i++) {
            out.add(all.get(i));
        }
        return out;
    }

    /** 崩溃与性能日志共用同一个目录，便于一次性取走。 */
    private static File crashDir(Context context) {
        File base = context.getExternalFilesDir("perf");
        return base == null ? new File(context.getFilesDir(), "perf") : base;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** package-private：单元测试要直接验证连击计数，不必走会结束进程的崩溃入口。 */
    static void noteCrash(Context context) {
        SharedPreferences p = prefs(context);
        long now = System.currentTimeMillis();
        long lastBoot = p.getLong(KEY_LAST_BOOT, 0L);
        // 距上次启动超过窗口，说明不是启动崩溃，连击重新从 1 计
        int streak = (lastBoot > 0 && now - lastBoot <= BOOT_WINDOW_MS)
                ? p.getInt(KEY_STREAK, 0) + 1 : 1;
        p.edit().putInt(KEY_STREAK, streak).putLong(KEY_LAST_CRASH, now).apply();
    }

    /** package-private：测试要验证堆栈确实落盘。 */
    static void writeCrashFile(Context context, Thread thread, Throwable throwable)
            throws Exception {
        File base = crashDir(context);
        if (!base.exists() && !base.mkdirs()) {
            Log.w(TAG, "cannot create crash dir");
            return;
        }
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        File file = new File(base, "crash-" + stamp + ".txt");

        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        pw.println("time=" + new Date());
        pw.println("app=" + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")");
        pw.println("device=" + Build.MANUFACTURER + " " + Build.MODEL);
        pw.println("android=" + Build.VERSION.RELEASE + " (sdk " + Build.VERSION.SDK_INT + ")");
        pw.println("thread=" + (thread == null ? "?" : thread.getName()));
        pw.println();
        throwable.printStackTrace(pw);
        pw.flush();

        FileOutputStream out = new FileOutputStream(file);
        try {
            out.write(sw.toString().getBytes("UTF-8"));
            out.flush();
            // 崩溃时进程随时可能被系统回收，强制落盘，避免只留下一个空文件
            out.getFD().sync();
        } finally {
            out.close();
        }
        Log.e(TAG, "crash written to " + file.getAbsolutePath());
        trimOldFiles(base);
    }

    /** 只保留最近几份，避免反复崩溃把存储写满。 */
    private static void trimOldFiles(File dir) {
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        int count = 0;
        for (File f : files) {
            if (f != null && f.getName().startsWith("crash-")) {
                count++;
            }
        }
        if (count <= MAX_CRASH_FILES) {
            return;
        }
        // listFiles 顺序不保证，按修改时间排序后删最旧的
        java.util.Arrays.sort(files, new java.util.Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return Long.compare(a.lastModified(), b.lastModified());
            }
        });
        int toDelete = count - MAX_CRASH_FILES;
        for (File f : files) {
            if (toDelete <= 0) {
                break;
            }
            if (f != null && f.getName().startsWith("crash-")) {
                if (f.delete()) {
                    toDelete--;
                }
            }
        }
    }
}
