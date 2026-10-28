package io.github.flashsky1024.tavernium;

/**
 * UrlKit 的 origin 解析兼容性回归。
 *
 * <p>背景：{@code originOf} 原来用 {@code new URI(url)} 严格解析，角色目录
 * {@code 柏宝绘_[沙盒]FGO} 里的方括号会让 path 解析抛异常，origin 变成空串，
 * {@code isBlockedPublicHttp} 于是把自家服务器当成"公网明文"回了一个本地 403，
 * 表现为"聊天正常、图片全裂"。改成只解析 {@code scheme://authority} 后，
 * 这里把"该修的"和"绝不能变的"两类边界都钉住。</p>
 */
public final class UrlKitCompatTest {

    private static int passed;
    private static final String SERVER = "https://tavern.example.com/";
    private static final String IMAGE_DIR = "[沙盒]";

    public static void main(String[] args) {
        fixes();
        regressions();
        rejections();
        hostLookup();
        updateChain();
        System.out.println("PASSED: " + passed + " FAILED: 0");
    }

    /** 本次要修的场景：path/query 里的字符不再影响 origin。 */
    private static void fixes() {
        String bracket = "https://tavern.example.com/user/images/柏宝绘_" + IMAGE_DIR + "FGO/x.jpg";
        check("方括号目录能取到 origin", "https://tavern.example.com".equals(UrlKit.originOf(bracket)));
        check("方括号目录不再被本地拦成 403", !UrlKit.isBlockedPublicHttp(bracket));
        check("方括号目录与服务器同源", UrlKit.isSameOrigin(bracket, SERVER));

        String loopback = "http://127.0.0.1:8000/user/images/柏宝绘_" + IMAGE_DIR + "FGO/x.jpg";
        check("回环地址的方括号目录不被拦", !UrlKit.isBlockedPublicHttp(loopback));
        check("回环地址的方括号目录同源", UrlKit.isSameOrigin(loopback, "http://127.0.0.1:8000/"));

        // 公网 + 明文仍然要拦，这一条与方括号无关，属于既有安全策略
        check("公网明文即使带方括号也拦",
                UrlKit.isBlockedPublicHttp("http://tavern.example.com/user/images/柏宝绘_" + IMAGE_DIR + "x.jpg"));

        check("path 里的花括号不影响 origin",
                UrlKit.isSameOrigin("https://tavern.example.com/角色/{name}/x.png", SERVER));
        check("query 里的方括号不影响 origin",
                UrlKit.isSameOrigin("https://tavern.example.com/api?filter[]=1", SERVER));
        check("query 里的管道符不影响 origin",
                UrlKit.isSameOrigin("https://tavern.example.com/a?q=1|2", SERVER));
        check("path 里的空格不影响 origin",
                UrlKit.isSameOrigin("https://tavern.example.com/a b/x", SERVER));
        check("已编码方括号仍然正常",
                UrlKit.isSameOrigin("https://tavern.example.com/a%5B1%5D/x.jpg", SERVER));

        String evil = "https://evil.example/user/images/柏宝绘_" + IMAGE_DIR + "x.jpg";
        check("方括号不能绕过跨源判定", !UrlKit.isSameOrigin(evil, SERVER));
    }

    /** 历史行为必须逐条保持。 */
    private static void regressions() {
        check("HTTPS 入口允许", UrlKit.isAllowedServerUrl("https://tavern.nandflash.wiki/"));
        check("origin 去掉默认端口",
                "https://tavern.nandflash.wiki".equals(UrlKit.originOf("https://tavern.nandflash.wiki:443/chat")));
        check("http 的 80 也归并",
                "http://tavern.example.com".equals(UrlKit.originOf("http://tavern.example.com:80/x")));
        check("scheme 与 host 大小写归一",
                "https://host.com".equals(UrlKit.originOf("HTTPS://Host.COM/A")));
        check("非默认端口保留",
                "https://host.com:8443".equals(UrlKit.originOf("https://host.com:8443/a")));
        check("空端口等价于未指定",
                "https://host.com".equals(UrlKit.originOf("https://host.com:/a")));
        check("端口前导零按数值处理",
                "https://host.com:8000".equals(UrlKit.originOf("https://host.com:008000/a")));
        check("末尾点保留（与旧实现一致）",
                "https://host.com.".equals(UrlKit.originOf("https://host.com./a")));
        check("无斜杠的地址可解析",
                "https://tavern.example.com".equals(UrlKit.originOf("https://tavern.example.com")));
        check("无 path 带端口可解析",
                "https://tavern.example.com:8000".equals(UrlKit.originOf("https://tavern.example.com:8000")));

        check("公网明文 HTTP 仍被拦", UrlKit.isBlockedPublicHttp("http://tavern.example.com/"));
        check("同域不同端口不同源", !UrlKit.isSameOrigin("http://127.0.0.1:8001/", "http://127.0.0.1:8000/"));
        check("同域不同协议不同源", !UrlKit.isSameOrigin("http://tavern.example.com/", SERVER));
        check("IPv6 回环同源", UrlKit.isSameOrigin("http://[::1]:8000/a", "http://[::1]:8000/"));
        check("IPv6 默认端口归并", UrlKit.isSameOrigin("https://[::1]:443/a", "https://[::1]/"));
        check("IP 字面量不当域名", UrlKit.isSameOrigin("http://127.0.0.1:8000/", "http://127.0.0.1:8000/"));

        check("about:blank 不同源", !UrlKit.isSameOrigin("about:blank", SERVER));
        check("data: 不同源", !UrlKit.isSameOrigin("data:text/html,x", SERVER));
        check("空地址安全", !UrlKit.isSameOrigin(null, SERVER));
        check("无 scheme 的地址拒绝", UrlKit.originOf("tavern.example.com").isEmpty());
        check("scheme 后无 authority 拒绝", UrlKit.originOf("https:///path").isEmpty());
        check("协议相对地址不参与 origin 判定", UrlKit.originOf("//tavern.example.com/").isEmpty());
        // hostOf 走 URI 快路径，对协议相对地址仍能取到 host —— 这是既有行为，不参与准入
        check("协议相对地址的 host 保持旧行为",
                "tavern.example.com".equals(UrlKit.hostOf("//tavern.example.com/")));
    }

    /** 安全边界不能因为少做 URI 解析而松动。 */
    private static void rejections() {
        check("带凭据的地址拒绝", UrlKit.originOf("https://user:pass@tavern.example.com/").isEmpty());
        check("仅用户名的地址拒绝", UrlKit.originOf("https://user@tavern.example.com/").isEmpty());
        check("空用户名的地址拒绝", UrlKit.originOf("https://@tavern.example.com/").isEmpty());
        check("路径里的 @ 不影响",
                "https://tavern.example.com".equals(UrlKit.originOf("https://tavern.example.com/a@b")));

        check("反斜杠伪装被拒绝", UrlKit.originOf("http://evil\\@127.0.0.1/").isEmpty());
        check("反斜杠伪装仍被拦", UrlKit.isBlockedPublicHttp("http://evil\\@127.0.0.1/"));
        check("反斜杠回环伪装仍被拦", UrlKit.isBlockedPublicHttp("http://127.0.0.1\\@evil.com/"));
        check("反斜杠地址取不到 host", UrlKit.hostOf("http://evil\\@127.0.0.1/").isEmpty());

        check("越界端口拒绝", UrlKit.originOf("https://host.com:99999/").isEmpty());
        check("65536 拒绝", UrlKit.originOf("https://host.com:65536/").isEmpty());
        check("端口 0 拒绝", UrlKit.originOf("https://host.com:0/").isEmpty());
        check("非数字端口拒绝", UrlKit.originOf("https://host.com:abc/").isEmpty());
        check("带符号端口拒绝", UrlKit.originOf("https://host.com:+8000/").isEmpty());
        check("多个冒号拒绝", UrlKit.originOf("http://host:80:90/").isEmpty());
        check("65535 仍允许",
                "https://host.com:65535".equals(UrlKit.originOf("https://host.com:65535/")));

        check("非 ASCII 域名取不到 origin", UrlKit.originOf("https://百度.com/").isEmpty());
        check("host 里的空格拒绝", UrlKit.originOf("https://exa mple.com/").isEmpty());
        check("host 里的下划线拒绝", UrlKit.originOf("https://my_host.com/").isEmpty());

        check("blob 不继承内层 origin", UrlKit.originOf("blob:https://tavern.example.com/abc").isEmpty());
        check("blob 取不到 host", UrlKit.hostOf("blob:https://tavern.example.com/abc").isEmpty());
        check("file 协议拒绝", UrlKit.originOf("file:///sdcard/x.html").isEmpty());
        check("javascript 协议拒绝", UrlKit.originOf("javascript:alert(1)").isEmpty());
        check("明文 http 非内网拒绝", !UrlKit.isAllowedServerUrl("http://tavern.example.com/"));
        check("八进制 IP 不算内网", !UrlKit.isPrivateHost("010.0.0.1"));
        check("整数形式 IP 不算内网", UrlKit.isBlockedPublicHttp("http://2130706433/"));
        check("八进制回环不被放行", UrlKit.isBlockedPublicHttp("http://0177.0.0.1/"));

        check("非法 IPv6 拒绝", UrlKit.originOf("http://[::1/").isEmpty());
        check("空 IPv6 拒绝", UrlKit.originOf("http://[]/").isEmpty());
        check("IPv6 尾随垃圾拒绝", UrlKit.originOf("http://[::1]x/").isEmpty());
        check("IPv6 尾随垃圾取不到 host", UrlKit.hostOf("http://[::1]x/").isEmpty());
    }

    private static void hostLookup() {
        check("普通域名取 host",
                "tavern.nandflash.wiki".equals(UrlKit.hostOf("https://tavern.nandflash.wiki/")));
        check("host 小写归一", "host.com".equals(UrlKit.hostOf("https://Host.COM/A")));
        check("带凭据仍能取 host",
                "tavern.example.com".equals(UrlKit.hostOf("https://user:pass@tavern.example.com/")));
        check("IPv6 host 保留方括号", "[::1]".equals(UrlKit.hostOf("http://[::1]:8000/")));
        check("IPv6 ULA host", "[fd12::1]".equals(UrlKit.hostOf("http://[fd12::1]/")));
        check("非法端口取不到 host", UrlKit.hostOf("https://host.com:abc/").isEmpty());
        check("方括号目录仍能取 host",
                "tavern.example.com".equals(UrlKit.hostOf(
                        "http://tavern.example.com/user/images/柏宝绘_" + IMAGE_DIR + "FGO/x.jpg")));
        check("同主机忽略端口", UrlKit.isSameHost("https://host.com:8443/", "http://host.com/"));
        check("不同主机不相等", !UrlKit.isSameHost("https://a.example/", "https://b.example/"));
    }

    private static void updateChain() {
        check("HTTPS 更新允许", UrlKit.isAllowedUpdateUrl("https://updates.example.com/a.apk"));
        check("公网 HTTP 更新拒绝", !UrlKit.isAllowedUpdateUrl("http://updates.example.com/a.apk"));
        check("局域网 HTTP 更新允许", UrlKit.isAllowedUpdateUrl("http://192.168.1.8:8000/a.apk"));
        check("IPv6 ULA 更新允许", UrlKit.isAllowedUpdateUrl("http://[fd12::1]/a.apk"));
        check("fragment 更新拒绝", !UrlKit.isAllowedUpdateUrl("https://updates.example.com/a.apk#x"));
        check("凭据更新地址拒绝", !UrlKit.isAllowedUpdateUrl("https://user@updates.example.com/a.apk"));
        check("非法 HTTPS 更新拒绝", !UrlKit.isAllowedUpdateUrl("https:///a.apk"));
        check("方括号 APK 名可下载",
                UrlKit.isAllowedUpdateUrl("https://updates.example.com/a[1].apk"));
        check("方括号更新地址与清单同源",
                UrlKit.isSameOrigin("https://updates.example.com/a[1].apk",
                        "https://updates.example.com/releases.json"));
    }

    private static void check(String name, boolean value) {
        if (!value) {
            throw new AssertionError(name);
        }
        passed++;
        System.out.println("[PASS] " + name);
    }
}
