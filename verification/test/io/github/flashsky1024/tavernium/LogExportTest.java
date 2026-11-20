package io.github.flashsky1024.tavernium;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 日志导出测试。
 *
 * <p>盯的是「只能导出 crash」这一类故障：运行日志必须真的落盘、轮转文件也要被
 * 收集到，最后打成的 zip 里每个文件都能原样读回来。</p>
 */
public final class LogExportTest {
    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        File base = Files.createTempDirectory("log-export").toFile();

        section("运行日志落盘");
        PerfLog.init(new TempContext(base));
        check("初始化后能拿到日志文件", PerfLog.currentLog() != null);
        PerfLog.i("ExportTest", "hello-export-line");
        PerfLog.w("ExportTest", "warn-export-line");
        PerfLog.flushAndWait();
        File cur = PerfLog.currentLog();
        check("perf.log 已创建", cur != null && cur.isFile());
        String body = cur == null ? "" : readText(cur);
        check("写入的普通日志可见", body.contains("hello-export-line"));
        check("写入的告警日志可见", body.contains("warn-export-line"));

        section("轮转文件也参与导出");
        File rotated = new File(cur.getParentFile(), "perf.log.1");
        writeText(rotated, "older-rotated-line\n");
        List<File> logs = PerfLog.logFiles();
        check("至少包含当前文件与轮转文件（实际 " + logs.size() + "）", logs.size() >= 2);
        check("当前文件排在最前", !logs.isEmpty() && logs.get(0).equals(cur));
        check("轮转文件在列表里", logs.contains(rotated));

        section("打包");
        File crash = new File(base, "crash-20261120-091500.txt");
        writeText(crash, "java.lang.NullPointerException: boom\n\tat X.y(X.java:1)\n");
        List<File> all = new ArrayList<File>(logs);
        all.add(crash);
        File bundleDir = new File(base, "bundle");
        File zip = LogBundle.build(bundleDir, all, "Tavernium 日志包\n应用版本：" + BuildConfig.VERSION_NAME);
        check("压缩包已生成", zip.isFile());
        Map<String, String> entries = unzip(zip);
        check("包含说明文件", entries.containsKey("README.txt"));
        check("说明里带版本号", entries.containsKey("README.txt")
                && entries.get("README.txt").contains(BuildConfig.VERSION_NAME));
        check("包含 perf.log", entries.containsKey("perf.log"));
        check("perf.log 内容完整", entries.containsKey("perf.log")
                && entries.get("perf.log").contains("hello-export-line"));
        check("包含轮转日志", entries.containsKey("perf.log.1"));
        check("包含崩溃记录", entries.containsKey("crash-20261120-091500.txt"));
        check("崩溃堆栈未截断", entries.containsKey("crash-20261120-091500.txt")
                && entries.get("crash-20261120-091500.txt").contains("NullPointerException"));

        section("同名条目去重");
        File a = new File(base, "a");
        File b = new File(base, "b");
        a.mkdirs();
        b.mkdirs();
        File sameA = new File(a, "same.txt");
        File sameB = new File(b, "same.txt");
        writeText(sameA, "from-a");
        writeText(sameB, "from-b");
        List<File> dup = new ArrayList<File>();
        dup.add(sameA);
        dup.add(sameB);
        File dupZip = LogBundle.build(new File(base, "bundle-dup"), dup, null);
        Map<String, String> dupEntries = unzip(dupZip);
        check("两个同名文件都保留（实际 " + dupEntries.size() + " 项）", dupEntries.size() == 2);
        check("第一个保留原名", "from-a".equals(dupEntries.get("same.txt")));
        check("第二个改名不覆盖", "from-b".equals(dupEntries.get("same-1.txt")));
        check("未传说明时不生成 README", !dupEntries.containsKey("README.txt"));

        section("异常输入");
        try {
            LogBundle.build(new File(base, "bundle-empty"), new ArrayList<File>(), null);
            check("空列表应抛异常", false);
        } catch (IOException expected) {
            check("空列表抛 IOException", true);
        }
        List<File> ghost = new ArrayList<File>();
        ghost.add(new File(base, "not-exists.log"));
        try {
            LogBundle.build(new File(base, "bundle-ghost"), ghost, null);
            check("不存在的文件应抛异常", false);
        } catch (IOException expected) {
            check("全部文件不存在时抛 IOException", true);
        }

        System.out.println("PASSED: " + passed + " FAILED: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static String readText(File f) {
        try {
            return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    private static void writeText(File f, String text) throws IOException {
        File parent = f.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        FileOutputStream out = new FileOutputStream(f);
        try {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        } finally {
            out.close();
        }
    }

    private static Map<String, String> unzip(File zip) throws IOException {
        Map<String, String> out = new LinkedHashMap<String, String>();
        ZipInputStream in = new ZipInputStream(Files.newInputStream(zip.toPath()));
        try {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] chunk = new byte[8192];
                int n;
                while ((n = in.read(chunk)) != -1) {
                    buf.write(chunk, 0, n);
                }
                out.put(entry.getName(), new String(buf.toByteArray(), StandardCharsets.UTF_8));
            }
        } finally {
            in.close();
        }
        return out;
    }

    private static void section(String name) {
        System.out.println("\n== " + name);
    }

    private static void check(String name, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("  PASS " + name);
        } else {
            failed++;
            System.out.println("  FAIL " + name);
        }
    }

    private static final class TempContext extends Context {
        private final File dir;

        TempContext(File dir) {
            this.dir = dir;
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
        public File getCacheDir() {
            return new File(dir, "cache");
        }
    }
}
