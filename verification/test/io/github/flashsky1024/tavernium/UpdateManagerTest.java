package io.github.flashsky1024.tavernium;

import java.util.List;

/**
 * UpdateManager 的离线测试：只覆盖纯逻辑（URL 拼接、JSON 解析、排序、文案），
 * 不发真实网络请求。服务端 JSON 契约一变，这里会立刻失败。
 */
public class UpdateManagerTest {
    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        testReleasesUrl();
        testParseReleases();
        testParseSkipsInvalid();
        testFormatSize();
        testReleaseLabel();
        testSha256Of();

        System.out.println();
        System.out.println("========================================");
        System.out.println("PASSED: " + passed + "   FAILED: " + failed);
        System.out.println("========================================");
        System.out.flush();
        System.exit(failed > 0 ? 1 : 0);
    }

    private static void testReleasesUrl() {
        section("更新地址拼接（多应用共用同一域名）");
        eq("默认域名 + appId",
                "https://update.example.com/api/apps/tavernkeep/releases.json",
                UpdateManager.releasesUrl("https://update.example.com", "tavernkeep"));
        eq("尾斜杠不产生双斜杠",
                "https://update.example.com/api/apps/tavernkeep/releases.json",
                UpdateManager.releasesUrl("https://update.example.com/", "tavernkeep"));
        eq("其他 app 只换 appId",
                "https://update.example.com/api/apps/other-app/releases.json",
                UpdateManager.releasesUrl("https://update.example.com", "other-app"));
        eq("保留端口与子路径",
                "http://10.0.0.2:8080/mirror/api/apps/tavernkeep/releases.json",
                UpdateManager.releasesUrl("http://10.0.0.2:8080/mirror", "tavernkeep"));
        eq("APP_ID 为 tavernium", "tavernium", UpdateManager.APP_ID);
    }

    private static void testParseReleases() {
        section("解析 releases.json");
        String json = "{"
                + "\"app\":\"tavernkeep\","
                + "\"updatedAt\":\"2026-10-22T12:00:00Z\","
                + "\"releases\":["
                + "{\"versionName\":\"1.8.15\",\"versionCode\":27,"
                + "\"url\":\"https://update.example.com/apps/tavernkeep/a.apk\","
                + "\"size\":206882,\"date\":\"2026-10-22\",\"channel\":\"stable\","
                + "\"notes\":\"修复帧率弹窗\"},"
                + "{\"versionName\":\"1.8.17\",\"versionCode\":29,"
                + "\"url\":\"https://update.example.com/apps/tavernkeep/b.apk\","
                + "\"size\":208510,\"sha256\":\"ABC123\",\"date\":\"2026-10-22\","
                + "\"notes\":\"挂机黑屏\"},"
                + "{\"versionName\":\"1.8.16\",\"versionCode\":28,"
                + "\"url\":\"https://update.example.com/apps/tavernkeep/c.apk\"}"
                + "]}";
        List<UpdateManager.Release> list = null;
        try {
            list = UpdateManager.parseReleases(json);
        } catch (Exception e) {
            check("解析不抛异常（实际：" + e + "）", false);
            return;
        }
        eq("解析出 3 个版本", 3, list.size());
        expectAll("按 versionCode 降序排列",
                new Object[]{29, 28, 27},
                new Object[]{list.get(0).versionCode, list.get(1).versionCode,
                        list.get(2).versionCode});
        eq("版本名正确", "1.8.17", list.get(0).versionName);
        eq("大小正确", 208510L, list.get(0).size);
        eq("sha256 正确", "ABC123", list.get(0).sha256);
        eq("说明正确", "挂机黑屏", list.get(0).notes);
        eq("缺省 channel 为 stable", "stable", list.get(1).channel);
        eq("缺省 size 为 0", 0L, list.get(1).size);
    }

    private static void testParseSkipsInvalid() {
        section("解析容错：坏条目跳过而非整体失败");
        String json = "{\"releases\":["
                + "{\"versionName\":\"1.0\",\"versionCode\":0,\"url\":\"x\"},"
                + "{\"versionName\":\"1.1\",\"url\":\"x\"},"
                + "{\"versionName\":\"1.2\",\"versionCode\":3},"
                + "{\"versionName\":\"1.3\",\"versionCode\":4,\"url\":\"y\"},"
                + "null"
                + "]}";
        try {
            List<UpdateManager.Release> list = UpdateManager.parseReleases(json);
            eq("只保留有效条目", 1, list.size());
            eq("保留的是 1.3", "1.3", list.get(0).versionName);
        } catch (Exception e) {
            check("坏条目不应导致异常（实际：" + e + "）", false);
        }
        try {
            List<UpdateManager.Release> none = UpdateManager.parseReleases("{}");
            eq("无 releases 字段返回空列表", 0, none.size());
        } catch (Exception e) {
            check("空对象不应抛异常", false);
        }
    }

    private static void testFormatSize() {
        section("大小格式化");
        eq("512B", "512 B", UpdateManager.formatSize(512));
        eq("204800B -> 200 KB", "200 KB", UpdateManager.formatSize(204800));
        eq("1048576B -> 1.0 MB", "1.0 MB", UpdateManager.formatSize(1048576));
    }

    private static void testReleaseLabel() {
        section("版本列表文案");
        UpdateManager.Release r = new UpdateManager.Release();
        r.versionName = "1.8.17";
        r.versionCode = 29;
        r.size = 208510;
        r.date = "2026-10-22";
        String label = r.label(29);
        check("标记当前版本", label.contains("当前版本"));
        check("含版本号与 code", label.contains("1.8.17 (29)"));
        check("含日期", label.contains("2026-10-22"));
        check("含大小", label.contains("KB"));
        check("更高版本标可升级", r.label(28).contains("可升级"));
        check("更低版本标旧版本", r.label(30).contains("旧版本"));
    }

    /** 更新包缓存复用前的 sha256 校验依赖 sha256Of()，这里锁死哈希计算的正确性。 */
    private static void testSha256Of() {
        section("缓存包哈希校验");
        try {
            java.io.File tmp = java.io.File.createTempFile("tk-sha", ".bin");
            tmp.deleteOnExit();
            java.io.FileOutputStream fos = new java.io.FileOutputStream(tmp);
            try {
                fos.write("hello".getBytes("UTF-8"));
            } finally {
                fos.close();
            }
            // "hello" 的 SHA-256 是公开固定值
            String expected = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824";
            String actual = UpdateManager.sha256Of(tmp);
            check("sha256 计算正确", expected.equalsIgnoreCase(actual));
            tmp.delete();
        } catch (Exception e) {
            check("sha256 测试无异常", false);
        }
        check("不存在的文件返回空串",
                UpdateManager.sha256Of(new java.io.File("no-such-file-xyz")).isEmpty());
        check("null 返回空串", UpdateManager.sha256Of(null).isEmpty());
    }

    // ---------------- 断言辅助 ----------------

    private static void section(String name) {
        System.out.println();
        System.out.println("--- " + name + " ---");
    }

    private static void check(String name, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("  [PASS] " + name);
        } else {
            failed++;
            System.out.println("  [FAIL] " + name);
        }
    }

    /** 表驱动断言：同一约束的多组用例压成一条，失败时列出所有不匹配项。 */
    private static void expectAll(String name, Object[] expected, Object[] actual) {
        StringBuilder bad = new StringBuilder();
        int n = Math.min(expected.length, actual.length);
        for (int i = 0; i < n; i++) {
            Object e = expected[i];
            Object a = actual[i];
            if (e == null ? a != null : !e.equals(a)) {
                bad.append("\n      #").append(i).append(" 期望=").append(e)
                        .append(" 实际=").append(a);
            }
        }
        check(name + bad, bad.length() == 0);
    }

    private static void eq(String name, Object expected, Object actual) {
        boolean ok = expected == null ? actual == null : expected.equals(actual);
        if (ok) {
            passed++;
            System.out.println("  [PASS] " + name + " = " + actual);
        } else {
            failed++;
            System.out.println("  [FAIL] " + name + "  expected=" + expected + " actual=" + actual);
        }
    }
}
