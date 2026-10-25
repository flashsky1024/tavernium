package io.github.flashsky1024.tavernium;

import android.content.Context;
import android.content.SharedPreferences;
import android.webkit.WebResourceResponse;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;

/** CacheManager 逻辑测试：在可执行的 Android 桩上跑真实行为，不依赖设备。 */
public class CacheManagerTest {
    private static int passed = 0;
    private static int failed = 0;
    private static File cacheDir;

    public static void main(String[] args) throws Exception {
        cacheDir = new File(System.getProperty("java.io.tmpdir"),
                "tk_cache_" + System.currentTimeMillis());
        cacheDir.mkdirs();
        System.setProperty("tk.cacheDir", cacheDir.getAbsolutePath());

        testShouldCache();
        testExtension();
        testGetCachedMissThenHit();
        testMd5Stability();
        testEvictionKeepsNewest();
        testPrefetchEndToEnd();
        testIntercept();
        testIncrementalSize();
        testCachePoisonGuard();
        testHostOf();
        testSlowHostFastFail();
        testEmptySrcInterception();
        testGzipTransparent();
        testPrefetchDedupAndBoundedPool();
        testNegativeCacheStopsRetryStorm();
        testClearCache();
        testFetchStreamIsTrulyStreamed();
        testFetchStreamHttpError();
        testGuessFileName();
        testExtensionFromMime();
        testFrameRateHelpers();
        testBuildVersion();
        testMenuStructure();
        testJsdMirror();
        testFrameRateHookTiming();
        testCorsHeaders();
        testUpdateResilience();
        testOnboarding();
        testLogout();
        testUpdateChannel();
        testDialogListNotSqueezed();

        System.out.println();
        System.out.println("========================================");
        System.out.println("PASSED: " + passed + "   FAILED: " + failed);
        System.out.println("========================================");
        // 预取线程池的核心线程是非守护线程且默认不超时，测试必须显式退出
        System.out.flush();
        System.exit(failed > 0 ? 1 : 0);
    }

    // ---------------- 用例 ----------------

    private static void testShouldCache() throws Exception {
        CacheManager cm = newCacheManager();
        Method m = CacheManager.class.getDeclaredMethod("shouldCache", String.class);
        m.setAccessible(true);
        section("shouldCache 过滤规则");
        check("js 可缓存", call(m, cm, "https://a.com/x/app.js"));
        check("带 query 的 js 可缓存", call(m, cm, "https://a.com/x/app.js?v=12"));
        check("api 不缓存", !call(m, cm, "https://a.com/api/chat"));
        check("socket 不缓存", !call(m, cm, "https://a.com/socket.io/?EIO=4"));
        check("stream 不缓存", !call(m, cm, "https://a.com/media/stream.mp3"));
        check("data: 不缓存", !call(m, cm, "data:image/png;base64,AAAA"));
        check("blob: 不缓存", !call(m, cm, "blob:https://a.com/2f6c"));
        check("html 不在可缓存扩展名内", !call(m, cm, "https://a.com/index.html"));
        check("null 安全", !call(m, cm, (String) null));
        check("空串安全", !call(m, cm, ""));
    }

    private static void testExtension() throws Exception {
        CacheManager cm = newCacheManager();
        Method m = CacheManager.class.getDeclaredMethod("extension", String.class);
        m.setAccessible(true);
        section("extension 提取");
        String[] urls = {
                "https://a.com/b/app.js?v=1", "https://a.com/i/m/a.png",
                "https://a.com/", "https://a.com/api/chat",
                "https://a.com/x/APP.JS", "https://a.com/x/app.js#frag",
                "https://a.com/x/app.js?v=1#f", "https://a.com/x.y/app",
                "https://a.com/x/app?v=1.2", "https://a.com/x/app.tar.gz",
                "https://a.com", null, ""};
        Object[] got = new Object[urls.length];
        for (int i = 0; i < urls.length; i++) {
            got[i] = m.invoke(cm, urls[i]);
        }
        expectAll("扩展名提取（query/fragment/大写/多级/空值）",
                new Object[]{"js", "png", "", "", "js", "js", "js", "",
                        "", "gz", "", "", ""},
                got);
    }

    private static void testGetCachedMissThenHit() throws Exception {
        CacheManager cm = newCacheManager();
        section("getCached 未命中无磁盘 I/O / 命中流式返回");
        check("空缓存返回 null", cm.getCached("https://a.com/x/app.js") == null);

        byte[] payload = "console.log('hi')".getBytes(StandardCharsets.UTF_8);
        String name = fileName(cm, "https://a.com/x/app.js");
        writeFile(new File(realCacheDir(), name), payload);

        WebResourceResponse resp = cm.getCached("https://a.com/x/app.js");
        check("命中返回响应", resp != null);
        if (resp != null) {
            eq("MIME", "application/javascript", resp.mimeType);
            eq("文本类声明 UTF-8", "UTF-8", resp.encoding);
            byte[] got = readAll(resp.data);
            check("内容一致", Arrays.equals(payload, got));
            check("返回的是流而非内存数组", !(resp.data instanceof ByteArrayInputStream));
        }

        byte[] png = new byte[]{1, 2, 3, 4};
        writeFile(new File(realCacheDir(), fileName(cm, "https://a.com/i/a.png")), png);
        WebResourceResponse r2 = cm.getCached("https://a.com/i/a.png");
        check("二进制命中", r2 != null);
        if (r2 != null) {
            eq("二进制不声明 charset", null, r2.encoding);
            check("二进制内容一致", Arrays.equals(png, readAll(r2.data)));
        }
    }

    /** MD5 改为线程内复用 MessageDigest + 查表转十六进制，必须保证键稳定且跨线程一致。 */
    private static void testMd5Stability() throws Exception {
        section("缓存键 MD5（复用摘要实例）");
        CacheManager cm = newCacheManager();
        String a1 = fileName(cm, "https://a.com/x.js");
        String a2 = fileName(cm, "https://a.com/x.js");
        String b = fileName(cm, "https://a.com/y.js");
        eq("同一 URL 键稳定", a1, a2);
        check("不同 URL 键不同", !a1.equals(b));
        check("键为 32 位小写十六进制 + 扩展名", a1.matches("[0-9a-f]{32}\\.js"));

        final String[] other = new String[1];
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                other[0] = fileName(cm, "https://a.com/x.js");
            }
        });
        t.start();
        t.join();
        eq("跨线程键一致", a1, other[0]);
    }

    private static void testEvictionKeepsNewest() throws Exception {
        CacheManager cm = newCacheManager();
        section("LRU 淘汰按修改时间保留最新");
        String a = fileName(cm, "https://a.com/a.js");
        String b = fileName(cm, "https://a.com/b.js");
        String c = fileName(cm, "https://a.com/c.js");
        writeFile(new File(realCacheDir(), a), new byte[100]);
        sleep(30);
        writeFile(new File(realCacheDir(), b), new byte[100]);
        sleep(30);
        writeFile(new File(realCacheDir(), c), new byte[100]);
        Method evict = CacheManager.class.getDeclaredMethod("evictOldest", long.class);
        evict.setAccessible(true);
        evict.invoke(cm, 150L); // 只需腾 150：应删掉最旧的 a(100) 与 b(100)

        check("最旧的 a 被淘汰", !new File(realCacheDir(), a).exists());
        check("次旧的 b 被淘汰", !new File(realCacheDir(), b).exists());
        check("最新的 c 保留", new File(realCacheDir(), c).exists());
    }

    private static void testPrefetchEndToEnd() throws Exception {
        CacheManager cm = newCacheManager();
        section("预取端到端：后台落盘 -> 下次命中磁盘");
        byte[] body = ("var tavern = '" + repeat('x', 400) + "';")
                .getBytes(StandardCharsets.UTF_8);
        TinyServer server = new TinyServer(body, "application/javascript");
        server.start();
        String url = "http://127.0.0.1:" + server.port() + "/app.js";

        cm.prefetch(url, "TavernKeepTest", "", "");
        final File cached = new File(realCacheDir(), fileName(cm, url));
        check("预取写入了缓存文件", waitFor(cached::exists, 8000));

        WebResourceResponse resp = cm.getCached(url);
        check("第二次请求由磁盘缓存命中", resp != null);
        if (resp != null) {
            check("内容与服务器一致", Arrays.equals(body, readAll(resp.data)));
        }
        server.stop();
    }

    private static void testIntercept() throws Exception {
        CacheManager cm = newCacheManager();
        section("intercept 组合入口（热路径单次解析 + 预取触发）");

        // 不可缓存：直接 null，不产生任何缓存文件
        String[] before = realCacheDir().list();
        Arrays.sort(before == null ? new String[0] : before);
        check("不可缓存 URL 返回 null",
                cm.intercept("https://a.com/api/chat", "UA", "", "", true) == null);
        sleep(300);
        String[] after = realCacheDir().list();
        Arrays.sort(after == null ? new String[0] : after);
        check("不可缓存 URL 不产生缓存文件", Arrays.equals(before, after));

        // 未命中 + 允许预取：返回 null（交还 WebKit），后台最终落盘
        byte[] body = "console.log(1)".getBytes(StandardCharsets.UTF_8);
        TinyServer server = new TinyServer(body, "application/javascript");
        server.start();
        String url = "http://127.0.0.1:" + server.port() + "/it.js";
        check("未命中返回 null", cm.intercept(url, "UA", "", "", true) == null);
        check("预取被触发并落盘",
                waitFor(() -> new File(realCacheDir(), fileName(cm, url)).exists(), 8000));

        // 命中：流式返回，内容一致
        WebResourceResponse resp = cm.intercept(url, "UA", "", "", true);
        check("命中返回响应", resp != null);
        if (resp != null) {
            eq("命中 MIME", "application/javascript", resp.mimeType);
            check("命中内容一致", Arrays.equals(body, readAll(resp.data)));
        }

        // 未命中 + 禁止预取（POST 等非 GET 请求）：只查缓存，不触发预取
        String url2 = "http://127.0.0.1:1/noprefetch.js";
        check("禁止预取时返回 null", cm.intercept(url2, "UA", "", "", false) == null);
        check("禁止预取时不落盘",
                waitFor(() -> !new File(realCacheDir(), fileName(cm, url2)).exists(), 500));

        // 负缓存期间：intercept 不再触发预取
        String bad = "http://127.0.0.1:1/neg.js";
        cm.prefetch(bad, "UA", "", "");
        check("失败预取结束", waitFor(() -> inFlightOf(cm).isEmpty(), 15000));
        cm.intercept(bad, "UA", "", "", true);
        eq("负缓存期间不再入队", 0, inFlightOf(cm).size());
        server.stop();
    }

    private static void testIncrementalSize() throws Exception {
        section("缓存容量增量统计（免除每次预取全盘 listFiles 扫描）");
        // 清空共享缓存目录，保证构造时扫描值可控
        File[] existing = realCacheDir().listFiles();
        if (existing != null) {
            for (File f : existing) {
                if (f.isFile()) {
                    f.delete();
                }
            }
        }
        // 先写一个 100 字节文件再构造：currentSize 应被 scanSize 初始化为 100
        writeFile(new File(realCacheDir(), "pre.js"), new byte[100]);
        new File(realCacheDir(), "pre.js")
                .setLastModified(System.currentTimeMillis() - 60000);
        CacheManager cm = newCacheManager();
        java.util.concurrent.atomic.AtomicLong size = currentSizeOf(cm);
        eq("构造时全盘扫描初始化", 100L, size.get());

        // 预取成功后累加写入字节
        byte[] body = new byte[4096];
        TinyServer server = new TinyServer(body, "application/javascript");
        server.start();
        String url = "http://127.0.0.1:" + server.port() + "/size.js";
        cm.prefetch(url, "UA", "", "");
        check("预取完成", waitFor(() -> inFlightOf(cm).isEmpty(), 8000));
        eq("预取后累加写入字节", 100L + body.length, size.get());

        // 淘汰后扣减（pre.js 更旧，先被删；size.js 保留）
        Method evict = CacheManager.class.getDeclaredMethod("evictOldest", long.class);
        evict.setAccessible(true);
        evict.invoke(cm, 50L);
        check("最旧的 pre.js 被淘汰", !new File(realCacheDir(), "pre.js").exists());
        check("较新的 size.js 保留",
                new File(realCacheDir(), fileName(cm, url)).exists());
        eq("淘汰后扣减", 4096L, size.get());

        // 清空后归零
        cm.clearCache();
        check("清空后归零", waitFor(() -> size.get() == 0L, 5000));
        server.stop();
    }

    /**
     * 缓存污染防护：服务端把请求重定向到登录页/错误页时会返回 200 的 HTML，
     * 一旦被当成字体/样式落盘，字体解析失败会让所有图标变成缺字符号（☒）。
     */
    private static void testHostOf() throws Exception {
        section("hostOf 解析");
        expectAll("主机名解析（端口/大写/无路径/无协议/子域）",
                new Object[]{"a.com", "a.com", "a.com", "a.com", "", "",
                        "raw.githubusercontent.com"},
                new Object[]{
                        CacheManager.hostOf("https://a.com/x.js"),
                        CacheManager.hostOf("https://a.com:8443/x.js"),
                        CacheManager.hostOf("HTTPS://A.CoM/x.js"),
                        CacheManager.hostOf("https://a.com?x=1"),
                        CacheManager.hostOf("a.com/x.js"),
                        CacheManager.hostOf(null),
                        CacheManager.hostOf("https://raw.githubusercontent.com/a/b.json")});
    }

    /**
     * 不可达域名：标记后子资源直接空响应（不再等 TCP 超时），
     * 但缓存里已有的副本必须优先返回——不能为了省时间把可用资源也废掉。
     */
    private static void testEmptySrcInterception() throws Exception {
        section("空 src 占位图拦截（避免每 2 秒下载整页 HTML 当图片解码）");

        // 裸文档 URL 判定：只有 scheme://host[/] 才算（空 src 被解析成当前页的形态）
        expectAll("裸文档判定（尾斜杠/纯host/查询/锚点/路径/相对协议/null）",
                new Object[]{true, true, true, true, false, false, false, false},
                new Object[]{
                        CacheManager.isBareDocumentUrl("https://tavern.example.com/"),
                        CacheManager.isBareDocumentUrl("https://tavern.example.com"),
                        CacheManager.isBareDocumentUrl("https://tavern.example.com?x=1"),
                        CacheManager.isBareDocumentUrl("https://tavern.example.com#main"),
                        CacheManager.isBareDocumentUrl("https://tavern.example.com/index.css"),
                        CacheManager.isBareDocumentUrl("https://tavern.example.com/user/login"),
                        CacheManager.isBareDocumentUrl("//tavern.example.com/"),
                        CacheManager.isBareDocumentUrl(null)});

        // 拦截行为：wantsImage + 裸文档 → 1x1 透明 PNG，且不触发预取
        CacheManager cm = newCacheManager();
        String root = "https://tavern.example.com/";
        String[] before = realCacheDir().list();
        WebResourceResponse r = cm.intercept(root, "UA", "", "", true, true);
        byte[] pngBody = r == null || r.data == null ? new byte[0] : readAll(r.data);
        check("返回 1x1 PNG（被拦下，且 mime 与魔数都对）",
                r != null && "image/png".equals(r.mimeType)
                        && pngBody.length > 8
                        && pngBody[0] == (byte) 0x89 && pngBody[1] == 'P'
                        && pngBody[2] == 'N' && pngBody[3] == 'G');
        check("不触发预取", inFlightOf(cm).isEmpty());
        String[] after = realCacheDir().list();
        check("不产生缓存文件", Arrays.equals(before, after));

        // 同样的 URL 但不是图片请求（页面导航本身）：必须放行给 WebKit
        check("页面导航不受拦截",
                cm.intercept(root, "UA", "", "", false, false) == null);
        // 带路径的资源即使 Accept 是图片也不拦截
        check("真实图片路径不拦截",
                cm.intercept("https://tavern.example.com/avatars/a.png",
                        "UA", "", "", false, true) == null);
    }

    private static void testSlowHostFastFail() throws Exception {
        section("不可达域名快速失败");
        MemoryPrefs prefs = new MemoryPrefs();
        CacheManager cm = newCacheManager(prefs);
        String url = "https://raw.githubusercontent.com/a/manifest.json";

        check("初始不在名单", !cm.isSlowHost("raw.githubusercontent.com"));
        check("初始正常返回 null（交给 WebKit）",
                cm.intercept(url, "ua", "", "", false) == null);

        cm.markSlowHost("raw.githubusercontent.com");
        check("标记后进名单", cm.isSlowHost("raw.githubusercontent.com"));
        check("其他主机不受影响", !cm.isSlowHost("tavern.example.com"));

        // 关键资源（CSS/JS/JSON）绝不能变成空文件：缺了会让扩展样式与逻辑整体失效
        expectAll("关键资源（JSON/CSS/JS）交还 WebKit，不给空响应",
                new Object[]{null, null, null},
                new Object[]{
                        cm.intercept(url, "ua", "", "", false),
                        cm.intercept("https://raw.githubusercontent.com/a/x.css",
                                "ua", "", "", false),
                        cm.intercept("https://raw.githubusercontent.com/a/y.js",
                                "ua", "", "", false)});

        // 图片可以快速失败：缺图只是不显示，比干等超时好
        WebResourceResponse img = cm.intercept(
                "https://raw.githubusercontent.com/a/logo.png", "ua", "", "", false);
        check("图片仍快速失败且响应体为空",
                img != null && img.data != null && img.data.read() < 0);
        check("不再发起预取", !inFlightOf(cm).contains(url));

        // 缓存优先：同主机但已有本地副本时，必须返回副本而不是空响应
        String cachedUrl = "https://raw.githubusercontent.com/a/app.js";
        writeFile(new File(realCacheDir(), fileName(cm, cachedUrl)),
                "var ok=1;".getBytes(StandardCharsets.UTF_8));
        WebResourceResponse hit = cm.intercept(cachedUrl, "ua", "", "", true);
        check("慢主机也优先吃缓存", hit != null && hit.data != null && hit.data.read() == 'v');
        if (hit != null) hit.data.close();

        // 持久化：新实例（模拟进程重启）应恢复名单
        CacheManager cm2 = newCacheManager(prefs);
        check("跨实例恢复名单", cm2.isSlowHost("raw.githubusercontent.com"));
        eq("名单可列出", 1, cm2.slowHostList().length);

        cm2.clearSlowHosts();
        check("清空后失效", !cm2.isSlowHost("raw.githubusercontent.com"));
        CacheManager cm3 = newCacheManager(prefs);
        check("清空已持久化", !cm3.isSlowHost("raw.githubusercontent.com"));

        // 关键资源分类：CSS/JS/JSON 缺了会毁样式与逻辑，图片/字体缺了只是降级
        expectAll("关键资源分类",
                new Object[]{true, true, true, false, false},
                new Object[]{
                        CacheManager.isLayoutCriticalExt("css"),
                        CacheManager.isLayoutCriticalExt("js"),
                        CacheManager.isLayoutCriticalExt("json"),
                        CacheManager.isLayoutCriticalExt("png"),
                        CacheManager.isLayoutCriticalExt("woff2")});

        // 镜像内容校验：被 HTML 顶替的 CSS 必须拒绝，图片不做嗅探
        expectAll("镜像内容校验",
                new Object[]{false, true, false, true},
                new Object[]{
                        CacheManager.contentMatchesExtBytes(
                                "<!DOCTYPE html><html>404</html>"
                                        .getBytes(StandardCharsets.UTF_8), "css"),
                        CacheManager.contentMatchesExtBytes(
                                "body{margin:0}".getBytes(StandardCharsets.UTF_8), "css"),
                        CacheManager.contentMatchesExtBytes(new byte[0], "css"),
                        CacheManager.contentMatchesExtBytes(
                                new byte[]{(byte) 0x89, 'P', 'N', 'G'}, "png")});

        // 只有连接层失败才算「不可达」，404 之类不算
        check("SocketTimeout 归为连接失败", CacheManager.isConnectivityFailure(
                new java.io.IOException(new java.net.SocketTimeoutException())));
        check("UnknownHost 归为连接失败", CacheManager.isConnectivityFailure(
                new java.net.UnknownHostException("x")));
        check("普通 IOException 不算", !CacheManager.isConnectivityFailure(
                new java.io.IOException("HTTP 500")));
    }

    private static void testCachePoisonGuard() throws Exception {
        section("防止登录页/错误页污染缓存（图标变方框的根因）");
        CacheManager cm = newCacheManager();
        byte[] html = "<!DOCTYPE html><html><body>please login</body></html>"
                .getBytes(StandardCharsets.UTF_8);

        // 1) 预取时服务端返回 HTML：不得落盘
        TinyServer server = new TinyServer(html, "text/html");
        server.start();
        String url = "http://127.0.0.1:" + server.port() + "/font.woff2";
        cm.prefetch(url, "UA", "", "");
        check("预取结束", waitFor(() -> inFlightOf(cm).isEmpty(), 8000));
        check("HTML 不会被存成字体", !new File(realCacheDir(), fileName(cm, url)).exists());
        check("记为失败负缓存", isNegative(cm, url));
        server.stop();

        // 2) 已被污染的旧缓存：命中时自愈删除，返回 null 交还 WebKit 重新加载
        String url2 = "http://127.0.0.1:1/icon.woff2";
        File f2 = new File(realCacheDir(), fileName(cm, url2));
        writeFile(f2, html);
        check("构造污染缓存", f2.exists());
        check("命中时识别为污染并返回 null",
                cm.intercept(url2, "UA", "", "", false) == null);
        check("污染文件被删除（自愈）", !f2.exists());

        // 3) 合法字体内容仍可正常命中
        String url3 = "http://127.0.0.1:1/ok.woff2";
        File f3 = new File(realCacheDir(), fileName(cm, url3));
        writeFile(f3, new byte[]{'w', 'O', 'F', '2', 0, 0, 0, 0, 0, 0, 0, 0});
        WebResourceResponse ok = cm.intercept(url3, "UA", "", "", false);
        check("合法 woff2 正常命中", ok != null);
        if (ok != null) {
            ok.data.close();
        }

        // 4) HTML 冒充 CSS 同样拒绝
        String url4 = "http://127.0.0.1:1/app.css";
        File f4 = new File(realCacheDir(), fileName(cm, url4));
        writeFile(f4, html);
        check("HTML 不冒充 CSS", cm.intercept(url4, "UA", "", "", false) == null);
        check("污染 CSS 被删除", !f4.exists());

        // 5) 合法 CSS 不受影响
        String url5 = "http://127.0.0.1:1/ok.css";
        File f5 = new File(realCacheDir(), fileName(cm, url5));
        writeFile(f5, ".a{color:red}".getBytes(StandardCharsets.UTF_8));
        WebResourceResponse css = cm.intercept(url5, "UA", "", "", false);
        check("合法 CSS 正常命中", css != null);
        if (css != null) {
            css.data.close();
        }

        // 6) getCached 走同样的自愈路径
        String url6 = "http://127.0.0.1:1/old.js";
        File f6 = new File(realCacheDir(), fileName(cm, url6));
        writeFile(f6, html);
        check("getCached 识别污染", cm.getCached(url6) == null);
        check("getCached 删除污染文件", !f6.exists());
    }

    /**
     * gzip：请求显式声明 Accept-Encoding，落盘与返回给 WebView 的必须是解压后的原文，
     * 否则字体/CSS/JS 都会解析失败（缓存命中时我们不带 Content-Encoding 头）。
     */
    private static void testGzipTransparent() throws Exception {
        section("gzip 响应（显式声明 + 自行解压）");
        CacheManager cm = newCacheManager();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            sb.append("console.log('gzip line ").append(i).append("');\n");
        }
        byte[] raw = sb.toString().getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        GZIPOutputStream gz = new GZIPOutputStream(bos);
        gz.write(raw);
        gz.close();
        byte[] gzBytes = bos.toByteArray();
        check("压缩后体积确实变小（" + gzBytes.length + " < " + raw.length + "）",
                gzBytes.length < raw.length);

        TinyServer server = new TinyServer(gzBytes, "application/javascript")
                .contentEncoding("gzip");
        server.start();
        String url = "http://127.0.0.1:" + server.port() + "/app.js";
        cm.prefetch(url, "UA", "", "");
        check("预取结束", waitFor(() -> inFlightOf(cm).isEmpty(), 8000));

        check("请求头声明 gzip", server.lastAcceptEncoding()
                .toLowerCase(Locale.ROOT).contains("gzip"));

        File cached = new File(realCacheDir(), fileName(cm, url));
        check("预取落盘", cached.exists());
        if (cached.exists()) {
            check("落盘为解压后的原文（非压缩字节）", Arrays.equals(raw, readFile(cached)));
        }
        WebResourceResponse resp = cm.getCached(url);
        check("缓存命中", resp != null);
        if (resp != null) {
            check("命中内容为原文", Arrays.equals(raw, readAll(resp.data)));
            resp.data.close();
        }

        InputStream in = cm.fetchStream(url, "GET", "UA", "", "");
        check("下载路径同样已解压", Arrays.equals(raw, readAll(in)));
        in.close();
        server.stop();
    }

    private static byte[] readFile(File f) throws IOException {
        InputStream in = new java.io.FileInputStream(f);
        try {
            return readAll(in);
        } finally {
            in.close();
        }
    }

    private static boolean isNegative(CacheManager cm, String url) {
        try {
            Method m = CacheManager.class.getDeclaredMethod("isNegativelyCached", String.class);
            m.setAccessible(true);
            return (Boolean) m.invoke(cm, url);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void testPrefetchDedupAndBoundedPool() throws Exception {
        CacheManager cm = newCacheManager();
        section("预取去重 + 线程池有界（CPU 修复核心）");
        final java.util.concurrent.atomic.AtomicInteger hits =
                new java.util.concurrent.atomic.AtomicInteger();
        TinyServer server = new TinyServer(new byte[]{1, 2, 3}, "application/javascript");
        server.onRequest(hits::incrementAndGet);
        server.start();
        String url = "http://127.0.0.1:" + server.port() + "/dup.js";

        // 同一 URL 连续提交 5 次：去重后只应产生 1 次真实网络请求
        for (int i = 0; i < 5; i++) {
            cm.prefetch(url, "UA", "", "");
        }
        Field inFlightField = CacheManager.class.getDeclaredField("inFlight");
        inFlightField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Set<String> inFlight = (Set<String>) inFlightField.get(cm);
        check("任务执行完毕", waitFor(inFlight::isEmpty, 15000));
        eq("5 次提交只发起 1 次请求", 1, hits.get());

        Field execField = CacheManager.class.getDeclaredField("prefetchExecutor");
        execField.setAccessible(true);
        ThreadPoolExecutor exec = (ThreadPoolExecutor) execField.get(cm);
        eq("预取线程峰值上限", 3, exec.getMaximumPoolSize());
        eq("核心线程数", 1, exec.getCorePoolSize());
        Field queueField = ThreadPoolExecutor.class.getDeclaredField("workQueue");
        queueField.setAccessible(true);
        LinkedBlockingQueue<Runnable> q = (LinkedBlockingQueue<Runnable>) queueField.get(exec);
        eq("队列容量上限", 64, q.remainingCapacity() + q.size());
        check("拒绝策略为丢弃（不阻塞网络线程）",
                exec.getRejectedExecutionHandler() instanceof ThreadPoolExecutor.DiscardPolicy);
        server.stop();
    }

    private static void testNegativeCacheStopsRetryStorm() throws Exception {
        CacheManager cm = newCacheManager();
        section("失败负缓存：404/超时不再反复重试");
        String url = "http://127.0.0.1:1/unreachable.js"; // 1 号端口必然拒连
        cm.prefetch(url, "UA", "", "");
        Field inFlightField = CacheManager.class.getDeclaredField("inFlight");
        inFlightField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Set<String> inFlight = (Set<String>) inFlightField.get(cm);
        check("预取任务结束", waitFor(inFlight::isEmpty, 15000));

        Method neg = CacheManager.class.getDeclaredMethod("isNegativelyCached", String.class);
        neg.setAccessible(true);
        check("失败已被负缓存记录", call(neg, cm, url));

        cm.prefetch(url, "UA", "", ""); // 负缓存生效，应立即短路
        eq("负缓存期间不再入队", 0, inFlight.size());
    }

    private static void testClearCache() throws Exception {
        CacheManager cm = newCacheManager();
        section("清空缓存");
        String n = fileName(cm, "https://a.com/z.js");
        writeFile(new File(realCacheDir(), n), new byte[]{9});
        WebResourceResponse open = cm.getCached("https://a.com/z.js");
        if (open != null) open.data.close();
        check("写入成功", new File(realCacheDir(), n).exists());

        final File cacheRoot = realCacheDir();
        cm.clearCache();
        check("异步清空生效", waitFor(() -> !new File(cacheRoot, n).exists(), 5000));
        check("缓存目录已空", waitFor(() -> {
            String[] left = cacheRoot.list();
            return left == null || left.length == 0;
        }, 5000));
    }

    private static void testFetchStreamIsTrulyStreamed() throws Exception {
        CacheManager cm = newCacheManager();
        section("fetchStream 流式下载（不整包进内存）");
        byte[] body = new byte[3 * 1024 * 1024]; // 3MB，远超 16KB 缓冲区
        for (int i = 0; i < body.length; i++) {
            body[i] = (byte) (i * 31);
        }
        TinyServer server = new TinyServer(body, "application/octet-stream");
        server.start();
        String url = "http://127.0.0.1:" + server.port() + "/big.bin";

        InputStream in = cm.fetchStream(url, "GET", "UA", "", "");
        check("返回的不是内存数组流", !(in instanceof ByteArrayInputStream));
        byte[] got = readAll(in);
        in.close();
        check("3MB 内容逐字节一致", Arrays.equals(body, got));
        server.stop();
    }

    private static void testFetchStreamHttpError() throws Exception {
        CacheManager cm = newCacheManager();
        section("fetchStream 错误码抛异常");
        TinyServer server = new TinyServer(new byte[0], "text/plain", 404, "Not Found");
        server.start();
        String url = "http://127.0.0.1:" + server.port() + "/missing.js";
        boolean threw = false;
        try {
            cm.fetchStream(url, "GET", "UA", "", "");
        } catch (IOException e) {
            threw = true;
            check("异常信息含状态码", e.getMessage() != null && e.getMessage().contains("404"));
        }
        check("HTTP 404 抛出 IOException", threw);
        server.stop();
    }

    private static void testGuessFileName() {
        section("下载文件名推导");
        expectAll("文件名推导（Disposition / URL / MIME 回退）",
                new Object[]{"chat.jsonl", "my file.txt", "avatar.png", "download.json"},
                new Object[]{
                        TextKit.guessFileName("https://a.com/dl",
                                "attachment; filename=\"chat.jsonl\"", "application/json"),
                        TextKit.guessFileName("https://a.com/dl",
                                "inline; filename=\"my file.txt\"; charset=utf-8", null),
                        TextKit.guessFileName("https://a.com/img/avatar.png", null, null),
                        TextKit.guessFileName("https://a.com/download", null, "application/json")});
        String ts = TextKit.guessFileName("https://a.com/download", null, null);
        check("无扩展名时追加时间戳保证唯一", ts.startsWith("download_"));
    }

    private static void testExtensionFromMime() {
        section("MIME -> 扩展名");
        expectAll("MIME 映射（含大小写与未知类型）",
                new Object[]{"png", "json", "js", null},
                new Object[]{TextKit.extensionFromMime("IMAGE/PNG"),
                        TextKit.extensionFromMime("application/json"),
                        TextKit.extensionFromMime("application/javascript"),
                        TextKit.extensionFromMime("application/x-foo-bar")});
    }

    private static void testFrameRateHelpers() throws Exception {
        section("渲染帧率档位与换算");

        // ---- 档位表：数量、内容、自定义区间 ----
        int[] options = (int[]) field("FRAME_RATE_OPTIONS").get(null);
        String[] labels = (String[]) field("FRAME_RATE_LABELS").get(null);
        Object[] opts = new Object[options.length];
        for (int i = 0; i < options.length; i++) {
            opts[i] = options[i];
        }
        expectAll("预设档位表", new Object[]{0, 20, 30, 45, 60, 90, 120}, opts);
        eq("标签数量与档位一致", options.length, labels.length);
        expectAll("自定义区间", new Object[]{5, 240},
                new Object[]{field("FRAME_RATE_MIN").get(null), field("FRAME_RATE_MAX").get(null)});

        // ---- 帧率 -> 帧间隔（含上下界）----
        Method interval = MainActivity.class.getDeclaredMethod("frameIntervalMs", Integer.TYPE);
        interval.setAccessible(true);
        int[] fpsCases = {0, 20, 30, 45, 60, 90, 120, 5, 240};
        int[] msWant = {0, 50, 33, 22, 16, 11, 8, 200, 4};
        Object[] msGot = new Object[fpsCases.length];
        for (int i = 0; i < fpsCases.length; i++) {
            msGot[i] = interval.invoke(null, fpsCases[i]);
        }
        Object[] msExp = new Object[msWant.length];
        for (int i = 0; i < msWant.length; i++) {
            msExp[i] = msWant[i];
        }
        expectAll("帧率换算（0/20/30/45/60/90/120/5/240）", msExp, msGot);

        // ---- 档位标签（含自定义与非法值回落）----
        Method label = MainActivity.class.getDeclaredMethod("frameRateLabel", Integer.TYPE);
        label.setAccessible(true);
        expectAll("档位标签文案",
                new Object[]{"系统默认（不限制）", "20 fps（挂机最省电）",
                        "120 fps（高刷屏）", "75 fps（自定义）", "系统默认（不限制）"},
                new Object[]{label.invoke(null, 0), label.invoke(null, 20),
                        label.invoke(null, 120), label.invoke(null, 75), label.invoke(null, -1)});

        // ---- 按屏幕刷新率动态标注 ----
        Method dyn = MainActivity.class.getDeclaredMethod("frameRateLabelsFor", Float.TYPE);
        dyn.setAccessible(true);
        String[] at60 = (String[]) dyn.invoke(null, 60f);
        check("60Hz 屏：60 不标注、90/120 标注超屏",
                at60[4].equals("60 fps（流畅）")
                        && at60[5].contains("屏幕仅 60Hz") && at60[6].contains("屏幕仅 60Hz"));
        String[] at120 = (String[]) dyn.invoke(null, 120f);
        String[] at90 = (String[]) dyn.invoke(null, 90f);
        String[] at5994 = (String[]) dyn.invoke(null, 59.94f);
        check("足够高的屏幕不误标、59.94 留容差",
                !at120[5].contains("屏幕仅") && !at120[6].contains("屏幕仅")
                        && !at90[5].contains("屏幕仅") && !at5994[4].contains("屏幕仅"));

        // ---- 挂机模式对帧率的压制 ----
        Method eff = MainActivity.class.getDeclaredMethod("effectiveFrameRate",
                Integer.TYPE, Boolean.TYPE);
        eff.setAccessible(true);
        expectAll("挂机压制帧率",
                new Object[]{60, 0, 1, 1, 1},
                new Object[]{eff.invoke(null, 60, false), eff.invoke(null, 0, false),
                        eff.invoke(null, 0, true), eff.invoke(null, 120, true),
                        eff.invoke(null, 10, true)});

        // ---- 自定义判定 ----
        Method custom = MainActivity.class.getDeclaredMethod("isCustomFrameRate", Integer.TYPE);
        custom.setAccessible(true);
        expectAll("自定义档判定",
                new Object[]{false, false, false, true, true},
                new Object[]{custom.invoke(null, 0), custom.invoke(null, 30),
                        custom.invoke(null, 120), custom.invoke(null, 75),
                        custom.invoke(null, 3)});

        // ---- 挂机黑屏的关键契约（源码约束，防回退）----
        String src = readSource("MainActivity.java") + readSource("IdleController.java");
        check("挂机=纯黑遮罩 + 进入即零发光",
                src.contains("buildIdleOverlay")
                        && src.contains("overlay.setBackgroundColor(Color.BLACK)")
                        && src.contains("this.idleBox.setAlpha(0f)"));
        check("只有防烧屏模式才隐藏 WebView（停止合成）",
                src.contains("wv.setVisibility(burnIn ? View.INVISIBLE : View.VISIBLE)"));
        check("上滑解锁 + 提示 5 秒熄灭 + 每次换位置",
                src.contains("if (this.idleProgress >= 1f)")
                        && src.contains("IDLE_HINT_TIMEOUT_MS = 5000L")
                        && src.contains("Math.random() * 2 - 1")
                        && src.contains("heightPixels * IDLE_UNLOCK_RATIO"));
        check("三态挂机：关/普通/防烧屏，均 1fps",
                src.contains("IDLE_FPS = 1") && src.contains("IDLE_NORMAL = 1")
                        && src.contains("IDLE_BURNIN = 2"));
        check("旧 boolean 设置可迁移", src.contains("PREF_IDLE_MODE_LEGACY"));
        check("普通挂机首次选择时提示烧屏风险",
                src.contains("showNormalIdleWarning")
                        && src.contains("PREF_NORMAL_IDLE_WARNED"));
        check("普通挂机选项标注不防烧屏",
                src.contains("锁 1fps（不防烧屏）"));
    }

    /** jsDelivr 镜像：URL 重写、host 判定、命中与失败回退。 */
    private static void testJsdMirror() throws Exception {
        section("jsDelivr 镜像加速");
        eq("重写保留路径",
                "https://jsd.onmicrosoft.cn/npm/a@1/b.js",
                CacheManager.mirrorUrlOf("https://jsd.onmicrosoft.cn",
                        "https://cdn.jsdelivr.net/npm/a@1/b.js"));
        eq("重写保留查询串",
                "https://m.example/x.js?v=1",
                CacheManager.mirrorUrlOf("https://m.example",
                        "https://cdn.jsdelivr.net/x.js?v=1"));
        eq("无路径时补根",
                "https://m.example/",
                CacheManager.mirrorUrlOf("https://m.example", "https://cdn.jsdelivr.net"));
        check("只认官方 host", CacheManager.isJsdUrl("https://cdn.jsdelivr.net/npm/x.js"));
        check("gcore 不算（换个域名不换 IP）",
                !CacheManager.isJsdUrl("https://gcore.jsdelivr.net/npm/x.js"));
        // JS-Slash-Runner 在卡片 iframe 里同步加载 testingcf 子域的 log.js，
        // 卡住会连带 viewport/height 脚本不执行，必须一起加速
        check("testingcf 子域也走镜像",
                CacheManager.isJsdUrl("https://testingcf.jsdelivr.net/gh/a/b/log.js"));
        check("自家站点不算", !CacheManager.isJsdUrl("https://tavern.example.com/x.js"));

        CacheManager cm = newCacheManager();
        cm.setMirrorBase("https://jsd.onmicrosoft.cn/");
        eq("base 尾斜杠被归一", "https://jsd.onmicrosoft.cn", cm.mirrorBase());

        // 端到端：镜像返回正常内容 -> 直接命中，并写入缓存
        byte[] js = "var mirrored=1;".getBytes(StandardCharsets.UTF_8);
        TinyServer mirror = new TinyServer(js, "application/javascript");
        mirror.start();
        String base = "http://127.0.0.1:" + mirror.port();
        cm.setMirrorBase(base);
        String url = "https://cdn.jsdelivr.net/npm/demo@1.0.0/m.js";
        WebResourceResponse resp = cm.intercept(url, "UA", "", "", false);
        check("镜像命中返回响应", resp != null);
        if (resp != null) {
            eq("镜像命中 MIME", "application/javascript", resp.mimeType);
            check("镜像命中内容一致", Arrays.equals(js, readAll(resp.data)));
        }
        check("镜像内容已落盘", waitFor(
                () -> new File(realCacheDir(), fileName(cm, url)).exists(), 3000));
        check("缓存 key 用原始地址（切线路不失效）",
                new File(realCacheDir(), fileName(cm, url)).exists());
        eq("镜像请求计数", 1, mirror.hits.get());

        // 镜像返回 404 -> 回退（返回 null），并标记 10 分钟不可用
        TinyServer bad = new TinyServer(new byte[0], "text/html", 404, "Not Found");
        bad.start();
        CacheManager cm2 = newCacheManager();
        cm2.setMirrorBase("http://127.0.0.1:" + bad.port());
        String url2 = "https://cdn.jsdelivr.net/npm/demo@1.0.0/miss.js";
        WebResourceResponse r2 = cm2.intercept(url2, "UA", "", "", false);
        check("镜像 404 时回退（交还 WebKit）", r2 == null);
        check("镜像被标记为暂时不可用", cm2.isMirrorTemporarilyDead());
        mirror.stop();
        bad.stop();
    }

    /** 帧率节流：hook 必须在文档开始时安装，否则页面缓存的 rAF 引用会让节流失效。 */
    private static void testFrameRateHookTiming() throws Exception {
        section("帧率 hook 的安装时机与主文档注入");

        // 安装时机是"改帧率有没有效"的唯一决定因素：必须早于页面所有脚本
        String src = readSource("MainActivity.java");
        check("主文档注入（仅主框架、不压缩、明文交还）",
                src.contains("fetchInjectedDocument")
                        && src.contains("request.isForMainFrame()")
                        && src.contains("Accept-Encoding\", \"identity")
                        && src.contains("injectDocumentBytes(raw, contentType")
                        && !src.contains("\"Content-Encoding\""));
        check("注入失败熔断（连续 3 次就放弃，避免白屏）",
                src.contains("INJECT_FAIL_LIMIT") && src.contains("injectDisabled"));
        check("hook 始终安装（不再按帧率条件跳过）",
                src.contains("js.append(FRAME_RATE_HOOK_JS)"));
        check("报告能看出 hook 是否装上",
                src.contains("setRafHook")
                        && readSource("PerfMonitor.java").contains("rAF hook="));

        // charset 解析
        expectAll("Content-Type 的 charset 解析",
                new Object[]{"utf-8", "GBK", "utf-8", "utf-8", "utf-8"},
                new Object[]{
                        HtmlInjector.charsetOf("text/html; charset=utf-8"),
                        HtmlInjector.charsetOf("text/html; charset=\"GBK\""),
                        HtmlInjector.charsetOf("text/html"),
                        HtmlInjector.charsetOf(null),
                        HtmlInjector.charsetOf("text/html; charset=utf-8; boundary=x")});

        // 字节级注入：中文往返不能损坏（1.8.23 乱码的根因所在）
        String cnHtml = "<!doctype html><html><head><meta charset=\"utf-8\">"
                + "<title>酒馆 — 聊天</title></head><body>角色卡：小夜</body></html>";
        byte[] injected = HtmlInjector.injectDocumentBytes(
                cnHtml.getBytes(StandardCharsets.UTF_8),
                "text/html; charset=utf-8", "<script>HOOK</script>");
        check("UTF-8 中文往返无损且脚本插在 head 后", injected != null
                && new String(injected, StandardCharsets.UTF_8).contains("酒馆 — 聊天")
                && new String(injected, StandardCharsets.UTF_8).contains("角色卡：小夜")
                && new String(injected, StandardCharsets.UTF_8)
                .contains("<head><script>HOOK</script>"));
        check("GBK 文档按 GBK 编解码",
                new String(HtmlInjector.injectDocumentBytes(
                        "<html><head></head><body>中文</body></html>".getBytes("GBK"),
                        "text/html; charset=GBK", "<script>H</script>"), "GBK")
                        .contains("中文"));
        check("空内容/非 HTML/含 NUL 一律拒绝注入",
                HtmlInjector.injectDocumentBytes(new byte[0], "text/html", "<script>H</script>") == null
                        && HtmlInjector.injectDocumentBytes(
                        "not html at all".getBytes(StandardCharsets.UTF_8),
                        "text/html; charset=utf-8", "<script>H</script>") == null
                        && HtmlInjector.injectDocumentBytes(
                        new byte[]{'<', 'h', 't', 'm', 'l', '>', 0, 1, 2},
                        "text/html; charset=utf-8", "<script>H</script>") == null);

        // 插入位置：head 之后 / 无 head 时的降级
        String out = HtmlInjector.injectScript(
                "<!doctype html><html><head><script src=\"a.js\"></script></head><body></body></html>",
                "<script>HOOK</script>");
        check("插在 head 之后且原文档完整",
                out.contains("<head><script>HOOK</script><script src=\"a.js\">")
                        && out.contains("<body></body>") && out.startsWith("<!doctype html>"));
        check("无 head 退到 html 后、无标签则前置",
                HtmlInjector.injectScript("<html><body>x</body></html>", "<script>HOOK</script>")
                        .contains("<html><script>HOOK</script><body>")
                        && HtmlInjector.injectScript("plain", "<script>HOOK</script>")
                        .startsWith("<script>HOOK</script>"));
    }

    /** 拦截响应必须带 CORS：module script / import() / fetch 都按 CORS 规则加载。 */
    private static void testCorsHeaders() throws Exception {
        section("拦截响应的 CORS 头");
        CacheManager cm = newCacheManager();

        // 缓存命中（JS）
        String jsUrl = "https://cdn.jsdelivr.net/gh/a/b/mvu.js";
        writeFile(new File(realCacheDir(), fileName(cm, jsUrl)),
                "export const x=1;".getBytes(StandardCharsets.UTF_8));
        WebResourceResponse js = cm.intercept(jsUrl, "ua", "", "", false);
        check("JS 缓存命中返回响应", js != null);
        eq("JS 响应带 ACAO", "*", headerOf(js, "Access-Control-Allow-Origin"));

        // 缓存命中（CSS）
        String cssUrl = "https://cdn.jsdelivr.net/gh/a/b/theme.css";
        writeFile(new File(realCacheDir(), fileName(cm, cssUrl)),
                "body{color:red}".getBytes(StandardCharsets.UTF_8));
        WebResourceResponse css = cm.intercept(cssUrl, "ua", "", "", false);
        eq("CSS 响应带 ACAO", "*", headerOf(css, "Access-Control-Allow-Origin"));

        // 空 src 兜底图片
        WebResourceResponse px = cm.intercept("https://tavern.example.com/",
                "ua", "", "", false, true);
        eq("占位图带 ACAO", "*", headerOf(px, "Access-Control-Allow-Origin"));

        // 镜像命中
        byte[] body = "var mirrored=1;".getBytes(StandardCharsets.UTF_8);
        TinyServer mirror = new TinyServer(body, "application/javascript");
        mirror.start();
        CacheManager cm2 = newCacheManager();
        cm2.setMirrorBase("http://127.0.0.1:" + mirror.port());
        WebResourceResponse m = cm2.intercept("https://cdn.jsdelivr.net/npm/demo@1.0.0/c.js",
                "ua", "", "", false);
        check("镜像命中返回响应", m != null);
        eq("镜像响应带 ACAO", "*", headerOf(m, "Access-Control-Allow-Origin"));
        mirror.stop();

        // null 安全
        check("withCors(null) 不炸", CacheManager.withCors(null) == null);
    }

    private static String headerOf(WebResourceResponse resp, String name) {
        if (resp == null) {
            return null;
        }
        java.util.Map<String, String> h = resp.getResponseHeaders();
        return h == null ? null : h.get(name);
    }

    /** 首次启动引导：一页填完地址与可选凭据，替代原来的连环裸对话框。 */
    private static void testOnboarding() {
        section("首次启动引导");
        String src = readSource("MainActivity.java");
        check("首次进入走引导页", src.contains("showOnboardingDialog()"));
        check("引导页含服务器地址输入", src.contains("inputServer"));
        check("账号密码为可选项", src.contains("用户名（可选）") && src.contains("密码（可选）"));
        check("密码框做了遮蔽", src.contains("TYPE_TEXT_VARIATION_PASSWORD"));
        check("说明了这是什么应用",
                src.contains("这是 Tavernium —— SillyTavern 的 Android 客户端"));
        check("地址前缀有校验", src.contains("地址要以 http:// 或 https:// 开头"));
        check("保存后同时写入地址与凭据",
                src.contains("putString(PREF_SERVER_URL, url)")
                        && src.contains("putString(PREF_USER")
                        && src.contains("putString(PREF_PASS"));
    }

    /** 更新渠道：正式版与调试版各自独立的清单。 */
    private static void testUpdateChannel() throws Exception {
        section("更新渠道（正式 / 调试）");
        eq("调试渠道用独立 appId", "tavernium-debug",
                UpdateManager.appIdFor(UpdateManager.CHANNEL_DEBUG));
        eq("正式渠道用正式 appId", "tavernium",
                UpdateManager.appIdFor(UpdateManager.CHANNEL_STABLE));
        eq("渠道名称：调试版", "调试版",
                UpdateManager.channelLabel(UpdateManager.CHANNEL_DEBUG));
        eq("渠道名称：正式版", "正式版",
                UpdateManager.channelLabel(UpdateManager.CHANNEL_STABLE));

        // 未设置时跟随安装包类型（验证桩里 DEBUG=false，即正式版）
        MemoryPrefs p = new MemoryPrefs();
        eq("未设置时默认正式版", UpdateManager.CHANNEL_STABLE, UpdateManager.channelOf(p));
        p.edit().putString(UpdateManager.PREF_UPDATE_CHANNEL,
                UpdateManager.CHANNEL_DEBUG).apply();
        eq("手动选调试版生效", UpdateManager.CHANNEL_DEBUG, UpdateManager.channelOf(p));
        p.edit().putString(UpdateManager.PREF_UPDATE_CHANNEL, "garbage").apply();
        eq("非法值回落默认", UpdateManager.CHANNEL_STABLE, UpdateManager.channelOf(p));

        // 本地文件名按渠道区分：同版本号的正式包与调试包不能互相覆盖
        UpdateManager.Release r = new UpdateManager.Release();
        r.versionName = "1.8.32";
        r.url = "https://x/apps/tavernkeep-debug/tavernkeep-debug-1.8.32.apk";
        eq("文件名取下载地址末段", "tavernkeep-debug-1.8.32.apk",
                UpdateManager.apkNameOf(r));
        r.url = "https://x/a.apk?v=1";
        eq("忽略 query", "a.apk", UpdateManager.apkNameOf(r));
        r.url = "https://x/download";
        check("无 .apk 后缀时兜底", UpdateManager.apkNameOf(r).endsWith(".apk"));
        check("null 安全", UpdateManager.apkNameOf(null).endsWith(".apk"));

        // 同版本重装：标签明确提示，且下载支持丢弃本地副本重来
        UpdateManager.Release rel = new UpdateManager.Release();
        rel.versionName = "1.8.33";
        rel.versionCode = 45;
        check("同版本标注可重装", rel.label(45).contains("可重装"));
        check("更高版本标可升级", rel.label(44).contains("可升级"));
        check("更低版本标旧版本", rel.label(46).contains("旧版本"));

        String src = readSource("MainActivity.java");
        check("同版本走重装分支", src.contains("sameVersion"));
        check("重装提供重新下载", src.contains("\"重新下载\""));
        check("强制重下参数接入", src.contains("cancel, forceRedownload"));
        check("菜单有更新渠道项", src.contains("更新渠道："));
        check("检查更新按渠道取 appId", src.contains("UpdateManager.appIdFor(channel)"));
        check("列表标题标明渠道", src.contains("UpdateManager.channelLabel(channel)"));
    }

    /**
     * 列表型对话框的结构约束。
     *
     * <p>部分 ROM 的 AlertDialog 在 {@code setMessage} 内容较长时，会把
     * {@code setItems} 的列表区压成 0 高度——用户只看到一个"取消"按钮，
     * 以为功能坏了。1.8.14 的帧率对话框、1.8.34 的渠道对话框都踩过。</p>
     */
    private static void testDialogListNotSqueezed() {
        section("列表型对话框不被说明文字挤压");
        String src = readSource("MainActivity.java");
        String[] lines = src.split("\n");
        int badLine = -1;
        for (int i = 0; i < lines.length; i++) {
            String t = lines[i].trim();
            if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) {
                continue;
            }
            if (t.contains(".setMessage(")) {
                for (int j = i + 1; j < Math.min(i + 14, lines.length); j++) {
                    String u = lines[j].trim();
                    if (u.startsWith("//") || u.startsWith("*")) {
                        continue;
                    }
                    if (u.contains(".setItems(")) {
                        badLine = i + 1;
                        break;
                    }
                }
            }
        }
        check("不存在 setMessage + setItems 组合（会把列表挤成 0 高）"
                + (badLine > 0 ? "，见第 " + badLine + " 行" : ""), badLine < 0);
        check("渠道对话框用 ListView 承载选项",
                src.contains("private void showUpdateChannelDialog()")
                        && src.contains("listView.setOnItemClickListener"));
        check("渠道对话框不再用 setItems",
                !src.substring(src.indexOf("private void showUpdateChannelDialog()"),
                        src.indexOf("private void checkUpdates()")).contains(".setItems("));
    }

    /** 登录：一个「退出登录」同时覆盖 Cookie 与 Basic 两种认证。 */
    private static void testLogout() {
        section("退出登录与登录态识别");
        String src = readSource("MainActivity.java");
        check("菜单有退出登录项", src.contains("\"退出登录\""));
        check("退出时清 Cookie",
                src.contains("removeAllCookies") && src.contains("flush()"));
        check("退出时清 Basic 凭据",
                src.contains("remove(PREF_USER)") && src.contains("remove(PREF_PASS)"));
        check("退出时清 WebView 认证缓存", src.contains("clearHttpAuthUsernamePassword"));
        check("退出后重新加载页面", src.contains("doLogout()"));
        check("退出前有确认", src.contains("confirmLogout"));
        check("识别原生登录（只影响文案）",
                src.contains("login mode detected")
                        && src.contains("input[type=password]"));
        check("引导页用用户视角说明何时填账号",
                src.contains("浏览器先弹系统认证框"));
        check("引导页说明原生登录可留空",
                src.contains("上面两栏留空即可"));
    }

    /** 更新源多候选回退 + 安装包签名校验。 */
    private static void testUpdateResilience() {
        section("更新链路加固（多源回退 / 签名校验）");
        eq("trimBase 去尾斜杠", "https://a.com",
                UpdateManager.trimBase("https://a.com///"));
        eq("trimBase 去首尾空格", "https://a.com",
                UpdateManager.trimBase("  https://a.com  "));
        eq("trimBase 容忍 null", "", UpdateManager.trimBase(null));

        check("内置至少两个候选更新源（换域名不失联）",
                UpdateManager.DEFAULT_BASES.length >= 2);
        boolean allHttps = true;
        boolean hasCurrent = false;
        for (String b : UpdateManager.DEFAULT_BASES) {
            if (!b.startsWith("https://")) {
                allHttps = false;
            }
            if (UpdateManager.DEFAULT_BASE.equals(b)) {
                hasCurrent = true;
            }
        }
        check("候选源全部走 https", allHttps);
        check("候选中包含当前生效的源", hasCurrent);

        check("签名校验：null 上下文不放行",
                !UpdateManager.verifyApkSignature(null, null));
        check("签名校验：文件不存在不放行",
                !UpdateManager.verifyApkSignature(null, new File("/nonexistent/x.apk")));

        String src = readSource("MainActivity.java");
        check("检查更新走多源解析", src.contains("fetchFromAnySource"));
        check("安装前校验签名", src.contains("verifyApkForInstall"));
        check("签名不一致有明确警告", src.contains("showSignatureMismatchDialog"));
        String up = readSource("UpdateManager.java");
        check("候选源列表存在", up.contains("DEFAULT_BASES"));
        check("可用源缓存 24h", up.contains("RESOLVED_TTL_MS"));
    }

    /** 菜单分层：主菜单只留日常项，诊断类统一收进二级菜单。 */
    private static void testMenuStructure() {
        section("菜单分层");
        String src = readSource("MainActivity.java");
        check("主菜单有二级入口", src.contains("\"调试与诊断…\""));
        check("二级菜单存在", src.contains("private void showDebugMenu()"));
        check("二级菜单有独立分发", src.contains("private void onDebugMenuItemSelected(int i)"));
        int a = src.indexOf("private void showMenuDialog()");
        int b = src.indexOf("private void onMenuItemSelected(int i)");
        String mainMenu = a >= 0 && b > a ? src.substring(a, b) : "";
        check("主菜单不含诊断/日志/网络项",
                !mainMenu.contains("性能监控") && !mainMenu.contains("导出性能日志")
                        && !mainMenu.contains("清空不可达域名") && !mainMenu.contains("查看性能报告"));
        check("取消改为按钮而非列表项", src.contains(".setNegativeButton(\"取消\", null)"));
        check("导出日志文件名使用 Tavernium 前缀",
                src.contains("\"tavernium-perf-\"") && !src.contains("\"tavernkeep-perf-\""));

        // 返回键固定作为菜单入口，不再跟随网页历史；网页后退只能从菜单第一项走。
        int hb = src.indexOf("private void handleBackKey()");
        int hbEnd = src.indexOf("屏幕配置变化", hb);
        String back = hb >= 0 && hbEnd > hb ? src.substring(hb, hbEnd) : "";
        check("返回键固定弹菜单", back.contains("showMenuDialog()"));
        check("返回键不再跟随网页历史", !back.contains("canGoBack") && !back.contains("goBack()"));
        check("全屏视频仍可退出", back.contains("hideCustomView()"));
        check("菜单保留网页后退入口", src.contains("\"返回上一级\""));

        check("主菜单有使用指引入口", src.contains("\"使用指引\""));
        check("指引走内置页而非外链", src.contains("showGuideDialog()")
                && src.contains("file:///android_asset/guide.html"));
        check("内置指引不启用 JS", src.contains("ws.setJavaScriptEnabled(false)"));
        check("升级说明按 versionCode 判断且只弹一次",
                src.contains("PREF_LAST_SEEN_VERSION") && src.contains("last == 0 || last >= current"));

        java.io.File guide = new java.io.File(
                System.getProperty("tk.appSrc", ""), "app/src/main/assets/guide.html");
        String guideHtml = null;
        try {
            guideHtml = new String(java.nio.file.Files.readAllBytes(guide.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
        check("内置指引已打包", guideHtml != null && guideHtml.length() > 3000);
        check("内置指引不依赖外部资源", guideHtml != null
                && !guideHtml.contains("href=\"http") && !guideHtml.contains("src=\"http"));
        check("内置指引不含脚本", guideHtml != null && !guideHtml.contains("<script"));
    }

    /**
     * 版本信息一致性：桩里的 BuildConfig 由 gen_stubs.py 从 app/build.gradle 解析生成，
     * 因此这组断言同时校验「桩与真实构建保持同步」——若 build.gradle 改了版本而解析失效，
     * 这里会退化成 0.0.0 而失败。
     */
    private static void testBuildVersion() {
        section("构建版本（桩应与 build.gradle 同步）");
        check("VERSION_NAME 非空", BuildConfig.VERSION_NAME != null
                && !BuildConfig.VERSION_NAME.isEmpty());
        check("VERSION_NAME 形如 x.y.z（实际 " + BuildConfig.VERSION_NAME + "）",
                BuildConfig.VERSION_NAME.matches("\\d+\\.\\d+\\.\\d+.*"));
        check("VERSION_CODE 已递增（实际 " + BuildConfig.VERSION_CODE + "）",
                BuildConfig.VERSION_CODE >= 12);
        eq("APPLICATION_ID", "io.github.flashsky1024.tavernium", BuildConfig.APPLICATION_ID);

        // User-Agent 用了 BuildConfig.VERSION_NAME，避免版本号写死漂移
        String src = readSource("MainActivity.java");
        check("UA 引用 BuildConfig.VERSION_NAME（不写死版本号）",
                src != null && src.contains("Tavernium/\" + BuildConfig.VERSION_NAME"));
    }

    private static String readSource(String name) {
        java.io.File f = new java.io.File(
                System.getProperty("tk.appSrc", ""), "app/src/main/java/io/github/flashsky1024/tavernium/" + name);
        if (!f.exists()) {
            return null;
        }
        try {
            byte[] buf = new byte[(int) f.length()];
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            try {
                int off = 0;
                while (off < buf.length) {
                    int n = in.read(buf, off, buf.length - off);
                    if (n <= 0) {
                        break;
                    }
                    off += n;
                }
            } finally {
                in.close();
            }
            return new String(buf, java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    private static boolean contains(int[] arr, int v) {
        for (int x : arr) {
            if (x == v) {
                return true;
            }
        }
        return false;
    }

    // ---------------- 工具 ----------------

    private static CacheManager newCacheManager() {
        return newCacheManager(new MemoryPrefs());
    }

    private static CacheManager newCacheManager(final SharedPreferences prefs) {
        Context ctx = new Context() {
            @Override
            public File getCacheDir() {
                return cacheDir;
            }

            @Override
            public SharedPreferences getSharedPreferences(String name, int mode) {
                return prefs;
            }
        };
        return new CacheManager(ctx);
    }

    /** 测试用内存 SharedPreferences：让「不可达域名」的持久化与跨实例恢复可验证。 */
    static final class MemoryPrefs implements SharedPreferences {
        final java.util.Map<String, Object> map = new java.util.HashMap<>();

        @Override
        public String getString(String key, String defValue) {
            Object v = map.get(key);
            return v instanceof String ? (String) v : defValue;
        }

        @Override
        public boolean getBoolean(String key, boolean defValue) {
            Object v = map.get(key);
            return v instanceof Boolean ? (Boolean) v : defValue;
        }

        @Override
        public long getLong(String key, long defValue) {
            Object v = map.get(key);
            return v instanceof Long ? (Long) v : defValue;
        }

        @Override
        public int getInt(String key, int defValue) {
            Object v = map.get(key);
            return v instanceof Integer ? (Integer) v : defValue;
        }

        @Override
        public Editor edit() {
            return new Editor() {
                private final java.util.Map<String, Object> pending = new java.util.HashMap<>();

                @Override
                public Editor putString(String key, String value) {
                    pending.put(key, value);
                    return this;
                }

                @Override
                public Editor putBoolean(String key, boolean value) {
                    pending.put(key, value);
                    return this;
                }

                @Override
                public Editor putInt(String key, int value) {
                    pending.put(key, value);
                    return this;
                }

                @Override
                public Editor putLong(String key, long value) {
                    pending.put(key, value);
                    return this;
                }

                @Override
                public Editor remove(String key) {
                    pending.put(key, null);
                    return this;
                }

                @Override
                public void apply() {
                    map.putAll(pending);
                }
            };
        }
    }


    /** CacheManager 实际使用的缓存目录 = getCacheDir()/CACHE_DIR，测试必须写到这一层。 */
    private static File realCacheDir() {
        try {
            Field f = CacheManager.class.getDeclaredField("CACHE_DIR");
            f.setAccessible(true);
            return new File(cacheDir, (String) f.get(null));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Set<String> inFlightOf(CacheManager cm) {
        try {
            Field f = CacheManager.class.getDeclaredField("inFlight");
            f.setAccessible(true);
            @SuppressWarnings("unchecked")
            Set<String> s = (Set<String>) f.get(cm);
            return s;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static java.util.concurrent.atomic.AtomicLong currentSizeOf(CacheManager cm) {
        try {
            Field f = CacheManager.class.getDeclaredField("currentSize");
            f.setAccessible(true);
            return (java.util.concurrent.atomic.AtomicLong) f.get(cm);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String fileName(CacheManager cm, String url) {
        try {
            Method m = CacheManager.class.getDeclaredMethod("cacheFile", String.class);
            m.setAccessible(true);
            return ((File) m.invoke(cm, url)).getName();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean call(Method m, Object target, String arg) throws Exception {
        return (Boolean) m.invoke(target, arg);
    }

    private static void writeFile(File f, byte[] data) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(f)) {
            fos.write(data);
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        try (InputStream owned = in) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = owned.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    private static String repeat(char c, int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            sb.append(c);
        }
        return sb.toString();
    }

    private static void sleep(long ms) {
        try {
            TimeUnit.MILLISECONDS.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean waitFor(java.util.function.BooleanSupplier cond, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) {
                return true;
            }
            sleep(25);
        }
        return cond.getAsBoolean();
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

    /** 取 MainActivity 的私有静态字段（已 setAccessible）。 */
    private static java.lang.reflect.Field field(String name) throws Exception {
        java.lang.reflect.Field f = MainActivity.class.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    /**
     * 表驱动断言：同一约束的多组用例压成一条。
     *
     * <p>以前每个档位/每种类型各写一条 eq，PASS 列表被大量同质条目淹没；
     * 合并后失败信息反而更完整（一次列出所有不匹配的用例编号）。</p>
     */
    private static void expectAll(String name, Object[] expected, Object[] actual) {
        StringBuilder bad = new StringBuilder();
        int n = Math.min(expected.length, actual.length);
        for (int i = 0; i < n; i++) {
            Object e = expected[i];
            Object a = actual[i];
            boolean same = (e == null) ? a == null : e.equals(a);
            if (!same) {
                bad.append("\n      #").append(i).append(" 期望=").append(e)
                        .append(" 实际=").append(a);
            }
        }
        if (expected.length != actual.length) {
            bad.append("\n      长度不一致：期望 ").append(expected.length)
                    .append(" 实际 ").append(actual.length);
        }
        check(name + bad, bad.length() == 0);
    }

    private static void eq(String name, Object expected, Object actual) {
        boolean ok = (expected == null) ? actual == null : expected.equals(actual);
        if (ok) {
            passed++;
            System.out.println("  [PASS] " + name + " = " + actual);
        } else {
            failed++;
            System.out.println("  [FAIL] " + name + "  expected=" + expected + " actual=" + actual);
        }
        System.out.flush();
    }

    /** 极简单连接 HTTP 服务器，用于本地验证下载/预取链路。 */
    private static final class TinyServer {
        private final byte[] body;
        private final String contentType;
        private final int status;
        private final String reason;
        private final java.util.concurrent.atomic.AtomicInteger hits =
                new java.util.concurrent.atomic.AtomicInteger();
        private volatile Runnable onRequest;
        private volatile String contentEncoding;
        private volatile String acceptEncoding = "";
        private ServerSocket socket;

        TinyServer(byte[] body, String contentType) {
            this(body, contentType, 200, "OK");
        }

        TinyServer(byte[] body, String contentType, int status, String reason) {
            this.body = body;
            this.contentType = contentType;
            this.status = status;
            this.reason = reason;
        }

        void onRequest(Runnable r) {
            this.onRequest = r;
        }

        /** 让响应带上 Content-Encoding（用于验证 gzip 解压）。 */
        TinyServer contentEncoding(String enc) {
            this.contentEncoding = enc;
            return this;
        }

        /** 最近一次请求里的 Accept-Encoding 值。 */
        String lastAcceptEncoding() {
            return acceptEncoding;
        }

        int hits() {
            return hits.get();
        }

        void start() throws IOException {
            socket = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        while (!socket.isClosed()) {
                            Socket s = socket.accept();
                            try {
                                byte[] req = new byte[8192];
                                int rn = s.getInputStream().read(req);
                                if (rn > 0) {
                                    String reqText = new String(req, 0, rn,
                                            StandardCharsets.ISO_8859_1);
                                    for (String line : reqText.split("\r\n")) {
                                        String low = line.toLowerCase(java.util.Locale.ROOT);
                                        if (low.startsWith("accept-encoding:")) {
                                            acceptEncoding = line.substring(
                                                    "accept-encoding:".length()).trim();
                                        }
                                    }
                                }
                                hits.incrementAndGet();
                                Runnable cb = onRequest;
                                if (cb != null) {
                                    cb.run();
                                }
                                String head = "HTTP/1.1 " + status + " " + reason
                                        + "\r\nContent-Type: " + contentType
                                        + (contentEncoding != null
                                        ? "\r\nContent-Encoding: " + contentEncoding : "")
                                        + "\r\nContent-Length: " + body.length
                                        + "\r\nConnection: close\r\n\r\n";
                                s.getOutputStream().write(head.getBytes(StandardCharsets.US_ASCII));
                                s.getOutputStream().write(body);
                                s.getOutputStream().flush();
                            } finally {
                                s.close();
                            }
                        }
                    } catch (IOException ignored) {
                    }
                }
            });
            t.setDaemon(true);
            t.start();
        }

        int port() {
            return socket.getLocalPort();
        }

        /** 不显式关闭监听套接字：服务线程是 daemon，配合 main 末尾的 System.exit 即可回收。
         *  (ServerSocket.close() 与阻塞中的 accept() 竞争会拖住 JVM，属测试脚手架问题。) */
        void stop() {
        }
    }
}
