package io.github.flashsky1024.tavernium;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * 崩溃落盘与启动自我保护测试。
 *
 * <p>重点是连击计数的边界：只有「启动后很快又崩」才该把应用推进安全模式，
 * 使用中偶发的崩溃不能累积，否则正常用户也会被误降级。</p>
 */
public final class CrashReporterTest {
    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        section("启动崩溃连击");
        File dir = Files.createTempDirectory("crash-test").toFile();
        FakeContext ctx = new FakeContext(dir);

        CrashReporter.markBoot(ctx);
        CrashReporter.noteCrash(ctx);
        check("首次启动崩溃 streak=1", streak(ctx) == 1);
        check("单次崩溃不进入安全模式", !CrashReporter.shouldUseSafeMode(ctx));

        CrashReporter.markBoot(ctx);
        CrashReporter.noteCrash(ctx);
        check("窗口内第二次崩溃 streak=2", streak(ctx) == 2);
        check("连续两次启动崩溃后进入安全模式", CrashReporter.shouldUseSafeMode(ctx));

        CrashReporter.markBootCompleted(ctx);
        check("稳定运行后连击清零", streak(ctx) == 0);
        check("清零后退出安全模式", !CrashReporter.shouldUseSafeMode(ctx));

        section("超出启动窗口的崩溃不累加");
        CrashReporter.markBoot(ctx);
        CrashReporter.noteCrash(ctx);
        check("先记一次", streak(ctx) == 1);
        // 把上次启动时间拨到 60 秒前，模拟「用了一会儿才崩」
        ctx.prefs.edit().putLong("last_boot_at", System.currentTimeMillis() - 60000L).apply();
        CrashReporter.noteCrash(ctx);
        check("超窗口后重新从 1 计", streak(ctx) == 1);
        check("超窗口崩溃不触发安全模式", !CrashReporter.shouldUseSafeMode(ctx));

        section("无启动记录时的崩溃");
        FakeContext fresh = new FakeContext(dir);
        CrashReporter.noteCrash(fresh);
        check("没有 last_boot 也按 1 计", streak(fresh) == 1);

        section("崩溃堆栈落盘");
        FakeContext writer = new FakeContext(dir);
        CrashReporter.writeCrashFile(writer, Thread.currentThread(),
                new IllegalStateException("boom-test"));
        File crash = newestCrashFile(dir);
        check("生成了 crash- 文件", crash != null);
        if (crash != null) {
            String body = new String(Files.readAllBytes(crash.toPath()), StandardCharsets.UTF_8);
            check("包含异常类型", body.contains("IllegalStateException"));
            check("包含异常消息", body.contains("boom-test"));
            check("包含应用版本", body.contains(BuildConfig.VERSION_NAME));
            check("包含线程名", body.contains("thread="));
        }

        section("崩溃记录导出");
        // 用独立目录：上面刚写了一份「现在」的崩溃文件，混在一起会盖过这里的时间排序
        File listDir = Files.createTempDirectory("crash-list").toFile();
        FakeContext lister = new FakeContext(listDir);
        // 直接造文件而不用 writeCrashFile：文件名精确到秒，同一秒内多次调用会互相覆盖
        File older = touch(new File(listDir, "crash-older.txt"), 3000L);
        File newer = touch(new File(listDir, "crash-newer.txt"), 1000L);
        File notCrash = touch(new File(listDir, "perf-20990101.log"), 500L);
        java.util.List<File> recent = CrashReporter.recentCrashFiles(lister, 2);
        check("按上限返回条数", recent.size() == 2);
        check("最新的排在最前", recent.get(0).getName().equals(newer.getName()));
        check("只挑 crash- 文件", !recent.contains(notCrash) && recent.contains(older));
        check("limit=0 返回空", CrashReporter.recentCrashFiles(lister, 0).isEmpty());
        check("context 为空也不崩", CrashReporter.recentCrashFiles(null, 3).isEmpty());

        section("自动修复只清可重建数据");
        File rdir = Files.createTempDirectory("crash-recover").toFile();
        FakeContext recover = new FakeContext(rdir);
        File webviewData = new File(rdir, "data/app_webview/Default/Local Storage");
        File keepSettings = new File(rdir, "data/shared_prefs");
        File cacheFile = new File(rdir, "cache/tavernkeep_webcache/abc.bin");
        Files.createDirectories(webviewData.toPath());
        Files.createDirectories(keepSettings.toPath());
        Files.createDirectories(cacheFile.getParentFile().toPath());
        Files.write(new File(webviewData, "leveldb.ldb").toPath(), "x".getBytes(StandardCharsets.UTF_8));
        Files.write(new File(keepSettings, "tavernium.xml").toPath(), "x".getBytes(StandardCharsets.UTF_8));
        Files.write(cacheFile.toPath(), "x".getBytes(StandardCharsets.UTF_8));
        check("清理前确实有网页数据", webviewData.exists());
        check("执行清理返回 true", CrashReporter.clearTransientData(recover));
        check("网页数据被清掉", !new File(rdir, "data/app_webview").exists());
        check("应用缓存被清掉", !cacheFile.exists());
        check("设置文件保留", new File(keepSettings, "tavernium.xml").exists());

        System.out.println("PASSED: " + passed + "   FAILED: " + failed);
        System.exit(failed > 0 ? 1 : 0);
    }

    private static int streak(Context ctx) {
        return ctx.getSharedPreferences("tavernium_crash", 0).getInt("boot_crash_streak", -1);
    }

    /** 造一个指定「多久以前修改过」的文件，用来验证排序与筛选。 */
    private static File touch(File file, long ageMs) throws Exception {
        Files.write(file.toPath(), "x".getBytes(StandardCharsets.UTF_8));
        file.setLastModified(System.currentTimeMillis() - ageMs);
        return file;
    }

    private static File newestCrashFile(File dir) {
        File[] files = dir.listFiles();
        if (files == null) {
            return null;
        }
        File newest = null;
        for (File f : files) {
            if (f.getName().startsWith("crash-")
                    && (newest == null || f.lastModified() >= newest.lastModified())) {
                newest = f;
            }
        }
        return newest;
    }

    private static final class FakeContext extends Context {
        private final SharedPreferences prefs = new CacheManagerTest.MemoryPrefs();
        private final File dir;

        FakeContext(File dir) {
            this.dir = dir;
        }

        @Override
        public SharedPreferences getSharedPreferences(String name, int mode) {
            return prefs;
        }

        @Override
        public File getExternalFilesDir(String type) {
            return dir;
        }

        @Override
        public File getFilesDir() {
            return dir;
        }

        @Override
        public File getDataDir() {
            return new File(dir, "data");
        }

        @Override
        public File getCacheDir() {
            return new File(dir, "cache");
        }

        @Override
        public Context getApplicationContext() {
            return this;
        }
    }

    private static void section(String name) {
        System.out.println("== " + name);
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
}
