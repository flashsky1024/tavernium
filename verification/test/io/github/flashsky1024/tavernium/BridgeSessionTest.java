package io.github.flashsky1024.tavernium;

public final class BridgeSessionTest {
    private static int passed;
    public static void main(String[] args) {
        String server = "https://tavern.example.com/";
        BridgeSession session = new BridgeSession();
        check("初始未授权", !session.accepts("x".repeat(64), server, server));
        session.beginDocument(server, server);
        String token = session.tokenFor(server, server);
        check("随机凭证", token.matches("[a-f0-9]{64}"));
        check("当前页面可调用", session.accepts(token, server + "chat", server));
        check("缺少凭证被拒绝", !session.accepts(null, server, server));
        check("空凭证被拒绝", !session.accepts("", server, server));
        check("猜测凭证被拒绝", !session.accepts("x".repeat(64), server, server));
        check("跨协议被拒绝", !session.accepts(token, "http://tavern.example.com/", server));
        check("跨端口被拒绝", !session.accepts(token, "https://tavern.example.com:8443/", server));
        check("跨站点被拒绝", !session.accepts(token, "https://other.example.com/", server));
        check("默认端口等价", session.accepts(token, "https://TAVERN.example.com:443/a", server));
        session.beginDocument(server, server);
        check("导航轮换凭证", !token.equals(session.tokenFor(server, server)));
        check("旧页凭证失效", !session.accepts(token, server, server));
        token = session.tokenFor(server, server);
        session.clear();
        check("销毁后失效", !session.accepts(token, server, server));
        session.beginDocument("https://untrusted.example/", server);
        check("外站导航不发凭证", session.tokenFor(server, server).isEmpty());
        check("非网络文档被拒绝", !UrlKit.isSameOrigin("about:blank", server));
        check("含凭据 URL 被拒绝", !UrlKit.isSameOrigin("https://user@tavern.example.com/", server));
        check("无效端口被拒绝", !UrlKit.isSameOrigin("https://tavern.example.com:99999/", server));
        check("IPv6 同源", UrlKit.isSameOrigin("http://[::1]:8000/a", "http://[::1]:8000/"));
        check("空地址安全", !UrlKit.isSameOrigin(null, server));
        System.out.println("PASSED: " + passed + " FAILED: 0");
    }
    private static void check(String name, boolean value) {
        if (!value) throw new AssertionError(name);
        passed++;
        System.out.println("[PASS] " + name);
    }
}
