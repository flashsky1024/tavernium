package wiki.nandflash.tavernkeep;

/**
 * 下载文件名消毒测试。
 *
 * <p>名字来自页面里的 {@code a.download}，攻击者可以塞进 {@code ../../} 或绝对路径，
 * 直接拼进 {@code new File(dir, name)} 就会把文件写到下载目录之外。
 * 这里覆盖穿越、非法字符、空名与超长名。</p>
 */
public final class MainActivitySafetyTest {
    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        section("文件名消毒：路径穿越");
        check("/etc/passwd 剥成 basename",
                "passwd".equals(TextKit.sanitizeFileName("/etc/passwd")));
        check("../../x.png 剥成 x.png",
                "x.png".equals(TextKit.sanitizeFileName("../../x.png")));
        check("反斜杠路径同样剥离",
                "a.png".equals(TextKit.sanitizeFileName("..\\..\\a.png")));
        check(".. 本身退化为 download",
                "download".equals(TextKit.sanitizeFileName("..")));
        check("单点退化为 download",
                "download".equals(TextKit.sanitizeFileName(".")));
        check("前导点被去掉（不产生隐藏文件）",
                "hidden.png".equals(TextKit.sanitizeFileName(".hidden.png")));
        check("中间的点保留",
                "my.card.png".equals(TextKit.sanitizeFileName("my.card.png")));

        section("文件名消毒：非法字符与空值");
        check("路径分隔符与冒号替换为下划线",
                "a_b_c.png".equals(TextKit.sanitizeFileName("a:b|c.png")));
        check("引号尖括号替换",
                "a_b_c.txt".equals(TextKit.sanitizeFileName("a\"b<c.txt")));
        check("控制字符被移除",
                "ab.txt".equals(TextKit.sanitizeFileName("a\u0000b.txt")));
        check("空字符串兜底",
                "download".equals(TextKit.sanitizeFileName("")));
        check("null 兜底",
                "download".equals(TextKit.sanitizeFileName(null)));
        check("纯空格兜底",
                "download".equals(TextKit.sanitizeFileName("   ")));

        section("文件名消毒：长度");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            sb.append('a');
        }
        String longName = TextKit.sanitizeFileName(sb + ".png");
        check("超长名被截断到 100 以内（实际 " + longName.length() + "）",
                longName.length() <= 100);
        check("截断后保留扩展名", longName.endsWith(".png"));
        String longNoExt = TextKit.sanitizeFileName(sb.toString());
        check("无扩展名超长名同样截断", longNoExt.length() <= 100);

        // downloadToLocal 路径：文件名来自服务器 Content-Disposition，是攻击者可控的。
        // saveStreamToDownloads 入口统一消毒，这里模拟该链路的最终落盘名。
        section("下载文件名：Content-Disposition 路径穿越");
        check("../../ 恶意 filename 被消毒",
                !TextKit.sanitizeFileName("../../../evil.apk").contains(".."));
        check("绝对路径 filename 被消毒",
                !TextKit.sanitizeFileName("/data/data/x/evil.png").contains("/"));
        check("消毒后仍保留扩展名",
                TextKit.sanitizeFileName("..\\..\\evil.png").endsWith(".png"));
        check("window.name= 空回退 download",
                "download".equals(TextKit.sanitizeFileName("   ")));

        System.out.println();
        System.out.println("========================================");
        System.out.println("PASSED: " + passed + "   FAILED: " + failed);
        System.out.println("========================================");
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static void section(String title) {
        System.out.println();
        System.out.println("--- " + title + " ---");
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
}
