package wiki.nandflash.tavernkeep;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import com.sun.net.httpserver.HttpServer;
import java.io.File;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** 真实回环 HTTP 请求验证重定向、清单绑定、缓存复用和下载验证。无需外网。 */
public final class UpdateSecurityTest {
    private static int passed;
    private static String manifest;
    private static byte[] apk = "trusted-apk".getBytes(StandardCharsets.UTF_8);
    private static int downloads, foreignHits;
    private static String apkRedirect;

    public static void main(String[] args) throws Exception {
        check("HTTPS 更新允许", UrlKit.isAllowedUpdateUrl("https://updates.example.com/a.apk"));
        check("公网 HTTP 拒绝", !UrlKit.isAllowedUpdateUrl("http://updates.example.com/a.apk"));
        check("局域网 HTTP 允许", UrlKit.isAllowedUpdateUrl("http://192.168.1.8:8000/a.apk"));
        check("IPv6 ULA 允许", UrlKit.isAllowedUpdateUrl("http://[fd12::1]/a.apk"));
            check("展开形式 IPv6 回环允许", UrlKit.isPrivateHost("0:0:0:0:0:0:0:1"));
            check("IPv4 映射私有地址允许", UrlKit.isPrivateHost("::ffff:192.168.1.8"));
        check("IPv6 link-local 全段允许", UrlKit.isPrivateHost("fea0::1"));
        check("fc 前缀域名不能冒充私有地址", !UrlKit.isAllowedUpdateUrl("http://fc-attacker.example/a.apk"));
        check("fd 前缀域名不能冒充私有地址", !UrlKit.isPrivateHost("fd.example.com"));
        check("八进制形式地址被拒绝", !UrlKit.isPrivateHost("010.0.0.1"));
        check("非法 HTTPS 被拒绝", !UrlKit.isAllowedUpdateUrl("https:///a.apk"));
        check("嵌入凭据被拒绝", !UrlKit.isAllowedUpdateUrl("https://user@updates.example.com/a.apk"));
        check("URL 片段被拒绝", !UrlKit.isAllowedUpdateUrl("https://updates.example.com/a.apk#x"));

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/redirect/api/apps/tavernium/releases.json")) {
                exchange.getResponseHeaders().add("Location", "http://localhost:"
                        + server.getAddress().getPort() + "/foreign");
                exchange.sendResponseHeaders(302, -1);
            } else if (path.equals("/loop/api/apps/tavernium/releases.json")) {
                exchange.getResponseHeaders().add("Location", exchange.getRequestURI().toString());
                exchange.sendResponseHeaders(307, -1);
            } else if (path.equals("/foreign")) {
                foreignHits++;
                exchange.sendResponseHeaders(200, -1);
            } else if (path.equals("/move/api/apps/tavernium/releases.json")) {
                exchange.getResponseHeaders().add("Location", "/api/apps/tavernium/releases.json");
                exchange.sendResponseHeaders(308, -1);
            } else if (path.equals("/api/apps/tavernium/releases.json")) {
                byte[] body = manifest.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } else if (path.equals("/apk/app.apk")) {
                downloads++;
                if (apkRedirect != null) {
                    exchange.getResponseHeaders().add("Location", apkRedirect);
                    exchange.sendResponseHeaders(302, -1);
                } else {
                    exchange.sendResponseHeaders(200, apk.length);
                    exchange.getResponseBody().write(apk);
                }
            } else {
                exchange.sendResponseHeaders(404, -1);
            }
            exchange.close();
        });
        server.start();
        try {
            manifest = "{\"releases\":[" + entry("/apk/app.apk", "") + ","
                    + entry("https://evil.example/app.apk", "") + "]}";
            List<UpdateManager.Release> releases = UpdateManager.fetchReleases(base, "tavernium", 50);
            check("跨源 APK 从清单中移除", releases.size() == 1);
            check("相对地址按实际清单解析", releases.get(0).url.equals(base + "/apk/app.apk"));
            check("同源重定向允许", UpdateManager.fetchReleases(base + "/move", "tavernium", 50).size() == 1);
            expectFailure("清单跨源重定向在访问前拦截", () -> UpdateManager.fetchReleases(base + "/redirect", "tavernium", 50));
            check("跨源目标未被访问", foreignHits == 0);
            expectFailure("重定向循环有上限", () -> UpdateManager.fetchReleases(base + "/loop", "tavernium", 50));
            FakeContext context = new FakeContext();
            UpdateManager.Release release = releases.get(0);
            File downloaded = UpdateManager.download(context, release, null, null, false);
            check("合法包下载成功", downloaded.isFile() && downloads == 1);
            UpdateManager.download(context, release, null);
            check("无 SHA 的缓存也先验证签名再复用", downloads == 1 && context.packageChecks >= 4);
            context.archiveName = "other.application";
            check("同证书不同包名被拒绝", !UpdateManager.verifyApkSignature(context, downloaded));
            context.archiveName = context.getPackageName();
            context.ownSignatures = new Signature[]{null};
            context.archiveSignatures = new Signature[]{null};
            check("空签名集合不能互相匹配", !UpdateManager.verifyApkSignature(context, downloaded));
            context.ownSignatures = new Signature[]{new Signature(new byte[0])};
            context.archiveSignatures = context.ownSignatures;
            check("空证书不能互相匹配", !UpdateManager.verifyApkSignature(context, downloaded));
            context.ownSignatures = new Signature[]{new Signature(new byte[]{1, 2, 3})};
            context.archiveSignatures = new Signature[]{new Signature(new byte[]{8, 9})};
            expectFailure("签名错误的下载不会返回成功", () -> UpdateManager.download(context, release, null, null, true));
            check("校验失败不留下完整包和半截包", !downloaded.exists()
                    && !new File(downloaded.getPath() + ".part").exists());
            Files.write(downloaded.toPath(), apk);
            check("直接安装也拒绝错误签名", !UpdateManager.install(context, downloaded));
            context.archiveSignatures = context.ownSignatures;
            context.archiveVersion = 49;
            expectFailure("实际版本必须匹配清单", () -> UpdateManager.download(context, release, null));
            context.archiveVersion = 50;
            release.sha256 = "0".repeat(64);
            expectFailure("SHA 不匹配被拒绝", () -> UpdateManager.download(context, release, null));
            release.sha256 = "invalid";
            int before = downloads;
            expectFailure("强制重新下载也校验清单", () -> UpdateManager.download(context, release, null, null, true));
            check("坏 SHA 在联网前被拒绝", before == downloads);
            release.sha256 = "";
            release.url = "https://evil.example/app.apk";
            expectFailure("下载前重新核对清单来源", () -> UpdateManager.download(context, release, null, null, true));
            release.url = base + "/apk/app.apk";
            apkRedirect = "http://localhost:" + server.getAddress().getPort() + "/foreign";
            expectFailure("APK 重定向也按同源约束", () -> UpdateManager.download(context, release, null));
            check("APK 跨源目标未被访问", foreignHits == 0);
            apkRedirect = null;
            expectFailure("取消状态在联网前检查", () -> UpdateManager.download(context, release, null, new AtomicBoolean(true), true));
            UpdateManager.Release forged = new UpdateManager.Release();
            forged.url = base + "/apk/app.apk";
            expectFailure("未经清单绑定的 Release 被拒绝", () -> UpdateManager.download(context, forged, null));
            System.out.println("PASSED: " + passed + " FAILED: 0");
        } finally {
            server.stop(0);
        }
    }

    private static String entry(String url, String hash) {
        return "{\"versionName\":\"1.8.38\",\"versionCode\":50,\"url\":\"" + url
                + "\",\"sha256\":\"" + hash + "\"}";
    }
    private interface Action { void run() throws Exception; }
    private static void expectFailure(String name, Action action) throws Exception {
        try { action.run(); } catch (SecurityException | IllegalStateException expected) {
            check(name, true); return;
        }
        throw new AssertionError(name);
    }
    private static void check(String name, boolean value) {
        if (!value) throw new AssertionError(name);
        passed++;
        System.out.println("[PASS] " + name);
    }
    private static final class FakeContext extends Context {
        final File cache;
        String archiveName = "wiki.nandflash.tavernkeep";
        int archiveVersion = 50, packageChecks;
        Signature[] ownSignatures = {new Signature(new byte[]{1, 2, 3})};
        Signature[] archiveSignatures = ownSignatures;
        FakeContext() throws Exception { cache = Files.createTempDirectory("tk-update-").toFile(); }
        @Override public File getCacheDir() { return cache; }
        @Override public String getPackageName() { return "wiki.nandflash.tavernkeep"; }
        @Override public PackageManager getPackageManager() { return new PackageManager() {
            @Override public PackageInfo getPackageInfo(String name, int flags) {
                PackageInfo info = new PackageInfo(); info.signatures = ownSignatures; return info;
            }
            @Override public PackageInfo getPackageArchiveInfo(String path, int flags) {
                packageChecks++;
                PackageInfo info = new PackageInfo();
                info.packageName = archiveName;
                info.versionCode = archiveVersion;
                info.signatures = archiveSignatures;
                return info;
            }
        }; }
    }
}
