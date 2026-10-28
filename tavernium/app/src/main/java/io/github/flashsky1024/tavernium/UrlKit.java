package io.github.flashsky1024.tavernium;

import java.util.Locale;
import java.net.URI;

/**
 * URL 相关的判定工具（纯函数）。
 *
 * <p>这些东西原本散在 MainActivity 里，问题是它们承担的是<b>安全判定</b>
 * （这个页面能不能调桥、这个地址能不能下载），却混在三千行 Activity 里，
 * 既没法单独测，也容易在被复制到第二条链路时走样。</p>
 */
final class UrlKit {

    /** 端口未指定；与 {@link URI#getPort()} 的 -1 对齐。 */
    private static final int PORT_UNSPECIFIED = -1;
    /** 端口非法（非数字、为 0、超出 65535）。 */
    private static final int PORT_INVALID = -2;

    private UrlKit() {
    }

    /**
     * host（小写，不含端口）；解析不出来返回空串。
     *
     * <p>先交给 {@link URI}：历史上能解析的地址走的还是原来那条路，行为不变。
     * 只有 URI 被 path 里的字符卡住时（角色名带 {@code []}、{@code {}}、空格），
     * 才从 authority 段兜底取——"这是哪台主机"本来就只取决于 {@code scheme://authority}。</p>
     */
    static String hostOf(String url) {
        if (url == null) {
            return "";
        }
        try {
            String host = new URI(url).getHost();
            if (host != null && !host.isEmpty()) {
                return host.toLowerCase(Locale.ROOT);
            }
        } catch (Exception ignored) {
            // URI 不接受 path 里的方括号等字符，落到下面的兜底解析
        }
        return hostFromAuthority(url);
    }

    /** 两个 URL 是否指向同一台主机（忽略协议与端口）。 */
    static boolean isSameHost(String a, String b) {
        String ha = hostOf(a);
        return !ha.isEmpty() && ha.equals(hostOf(b));
    }

    /**
     * 主文档必须与配置的服务器同源；原生接口还需配合 BridgeSession 校验本页凭证。
     */
    static boolean isTrustedMainUrl(String mainUrl, String serverUrl) {
        return isSameOrigin(mainUrl, serverUrl);
    }

    /** 浏览器同源边界包含协议和有效端口，不能只比较 host。 */
    static boolean isSameOrigin(String a, String b) {
        String origin = originOf(a);
        return !origin.isEmpty() && origin.equals(originOf(b));
    }

    /**
     * 归一化后的 origin（{@code scheme://host[:port]}）；不可用时返回空串。
     *
     * <p>这里刻意不用 {@link URI}：它把整条地址当 URI 校验，于是
     * {@code /user/images/柏宝绘_[沙盒]FGO/x.jpg} 这种 path 里带方括号的地址会直接
     * 抛异常，被当成"解析不出来"，进而被判成"公网明文"拦掉。而判定 origin 只用得上
     * scheme 和 authority，path/query 里出现什么字符与"这是哪台主机"无关。</p>
     *
     * <p>安全性没有放松：带 userinfo、非法 scheme、含反斜杠/空白/非 ASCII 的 host、
     * 越界端口，一律返回空串，调用方仍按 fail-closed 处理。</p>
     */
    static String originOf(String url) {
        if (url == null) {
            return "";
        }
        int separator = url.indexOf("://");
        if (separator <= 0) {
            return "";
        }
        String scheme = url.substring(0, separator).toLowerCase(Locale.ROOT);
        if (!isScheme(scheme)) {
            return "";
        }
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            return "";
        }
        int start = separator + 3;
        int end = url.length();
        for (int i = start; i < end; i++) {
            char c = url.charAt(i);
            if (c == '/' || c == '?' || c == '#') {
                end = i;
                break;
            }
        }
        if (end <= start) {
            return "";
        }
        for (int i = start; i < end; i++) {
            if (url.charAt(i) == '@') {
                return "";
            }
        }
        String authority = url.substring(start, end);
        String host;
        int port = PORT_UNSPECIFIED;
        if (authority.charAt(0) == '[') {
            int close = authority.indexOf(']');
            if (close < 0) {
                return "";
            }
            host = authority.substring(0, close + 1);
            if (!isIpv6Literal(host)) {
                return "";
            }
            String rest = authority.substring(close + 1);
            if (!rest.isEmpty()) {
                if (rest.charAt(0) != ':') {
                    return "";
                }
                port = parsePort(rest.substring(1));
                if (port == PORT_INVALID) {
                    return "";
                }
            }
        } else {
            int colon = authority.indexOf(':');
            if (colon >= 0) {
                host = authority.substring(0, colon);
                port = parsePort(authority.substring(colon + 1));
                if (port == PORT_INVALID) {
                    return "";
                }
            } else {
                host = authority;
            }
            if (!isHostChars(host)) {
                return "";
            }
        }
        boolean standardPort = port == PORT_UNSPECIFIED
                || ("https".equals(scheme) ? port == 443 : port == 80);
        return scheme + "://" + host.toLowerCase(Locale.ROOT) + (standardPort ? "" : ":" + port);
    }

    /** 服务器入口：公网使用 HTTPS，HTTP 只接受回环/私有 IP 或 localhost。 */
    static boolean isAllowedServerUrl(String url) {
        return !originOf(url).isEmpty()
                && (isHttps(url) || isPrivateHost(hostOf(url)));
    }

    /** 不将域名的 DNS 解析结果作为内网授权；data/blob/about 不在此处拦截。 */
    static boolean isBlockedPublicHttp(String url) {
        return url != null && url.regionMatches(true, 0, "http:", 0, 5)
                && !isAllowedServerUrl(url);
    }

    /**
     * 更新链路是否允许这个地址。
     *
     * <p>规则只有一条：要么走 https，要么是回环/局域网私有地址（自建更新镜像的调试场景）。
     * http 明文意味着链路上的任何人都能替换内容——而更新链最终会装 APK，
     * 明文下载等于把"装什么"交给中间人，即使有签名兜底也不该主动开这个口子。</p>
     */
    static boolean isAllowedUpdateUrl(String url) {
        if (originOf(url).isEmpty()) {
            return false;
        }
        // 带 fragment 的地址不下载：同一个 path 可能被解析成不同资源，无法保证与清单一致。
        // 这里不用 URI.getRawFragment()，否则 path 里的方括号会让整条地址被判非法。
        if (url.indexOf('#') >= 0) {
            return false;
        }
        String lower = url.toLowerCase(Locale.ROOT);
        if (lower.startsWith("https://")) {
            return true;
        }
        if (!lower.startsWith("http://")) {
            return false;
        }
        return isPrivateHost(hostOf(url));
    }

    /**
     * 是否回环/私有网段主机。
     *
     * <p>IPv4 只覆盖 RFC1918 三段 + 回环 + link-local；IPv6 覆盖 ::1 与 fc00::/7（ULA）、
     * fe80::/10（link-local）。这些地址只可能出现在用户自己的网络里，
     * 明文传输不经过运营商链路，可以放行。</p>
     */
    static boolean isPrivateHost(String host) {
        if (host == null || host.isEmpty()) {
            return false;
        }
        String h = host.toLowerCase(Locale.ROOT);
        if (h.startsWith("[") && h.endsWith("]")) {
            h = h.substring(1, h.length() - 1);
        }
        if ("localhost".equals(h) || "::1".equals(h) || "0.0.0.0".equals(h)) {
            return true;
        }
        if (h.indexOf(':') >= 0) {
            try {
                // 仅解析带冒号的 IPv6 字面量，不对任意主机名发起 DNS 查询。
                java.net.InetAddress address = java.net.InetAddress.getByName(h);
                byte[] bytes = address.getAddress();
                if (bytes.length == 4) return isPrivateHost(address.getHostAddress());
                return address.isLoopbackAddress() || (bytes.length == 16 && (((bytes[0] & 0xfe) == 0xfc)
                        || ((bytes[0] & 0xff) == 0xfe && (bytes[1] & 0xc0) == 0x80)));
            } catch (Exception e) {
                return false;
            }
        }
        String[] parts = h.split("\\.");
        if (parts.length != 4) {
            return false;
        }
        int[] v = new int[4];
        for (int i = 0; i < 4; i++) {
            if (!parts[i].matches("0|[1-9][0-9]{0,2}")) {
                return false;
            }
            try {
                v[i] = Integer.parseInt(parts[i]);
            } catch (NumberFormatException e) {
                return false;
            }
            if (v[i] < 0 || v[i] > 255) {
                return false;
            }
        }
        if (v[0] == 127) {
            return true;
        }
        if (v[0] == 10) {
            return true;
        }
        if (v[0] == 192 && v[1] == 168) {
            return true;
        }
        if (v[0] == 169 && v[1] == 254) {
            return true;
        }
        // 172.16.0.0 – 172.31.255.255
        return v[0] == 172 && v[1] >= 16 && v[1] <= 31;
    }

    /** 协议是否 https（用于判断"降级重定向"）。 */
    static boolean isHttps(String url) {
        return url != null && url.toLowerCase(Locale.ROOT).startsWith("https://");
    }

    /**
     * 从 {@code scheme://authority} 段取主机名。
     *
     * <p>只在 {@link #hostOf} 的 URI 快路径失败时使用。不是第二套准入判定：
     * 这里只管取名字，能不能放行由 {@link #originOf} 说了算。</p>
     */
    private static String hostFromAuthority(String url) {
        int separator = url.indexOf("://");
        if (separator <= 0) {
            return "";
        }
        String scheme = url.substring(0, separator).toLowerCase(Locale.ROOT);
        if (!isScheme(scheme)) {
            return "";
        }
        int start = separator + 3;
        int end = url.length();
        for (int i = start; i < end; i++) {
            char c = url.charAt(i);
            if (c == '/' || c == '?' || c == '#') {
                end = i;
                break;
            }
        }
        if (end <= start) {
            return "";
        }
        String authority = url.substring(start, end);
        for (int i = 0; i < authority.length(); i++) {
            char c = authority.charAt(i);
            if (c <= 0x20 || c == 0x7f || c == '\\') {
                return "";
            }
        }
        // userinfo 允许出现，但只作为前缀丢弃：hostOf 的语义是"尽力取 host"，
        // 与 URI.getHost() 对 https://user@host/ 仍返回 host 一致。
        int at = authority.lastIndexOf('@');
        String hostPart = at >= 0 ? authority.substring(at + 1) : authority;
        if (hostPart.isEmpty()) {
            return "";
        }
        if (hostPart.charAt(0) == '[') {
            int close = hostPart.indexOf(']');
            if (close < 0) {
                return "";
            }
            String literal = hostPart.substring(0, close + 1);
            if (!isIpv6Literal(literal)) {
                return "";
            }
            String rest = hostPart.substring(close + 1);
            if (!rest.isEmpty()
                    && (rest.charAt(0) != ':' || parsePort(rest.substring(1)) == PORT_INVALID)) {
                return "";
            }
            return literal.toLowerCase(Locale.ROOT);
        }
        String host = hostPart;
        int colon = hostPart.indexOf(':');
        if (colon >= 0) {
            if (parsePort(hostPart.substring(colon + 1)) == PORT_INVALID) {
                return "";
            }
            host = hostPart.substring(0, colon);
        }
        return isHostChars(host) ? host.toLowerCase(Locale.ROOT) : "";
    }

    /** scheme 只认 RFC 3986 的形式；调用前已转小写。 */
    private static boolean isScheme(String scheme) {
        if (scheme.isEmpty()) {
            return false;
        }
        char first = scheme.charAt(0);
        if (first < 'a' || first > 'z') {
            return false;
        }
        for (int i = 0; i < scheme.length(); i++) {
            char c = scheme.charAt(i);
            boolean allowed = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '+' || c == '-' || c == '.';
            if (!allowed) {
                return false;
            }
        }
        return true;
    }

    /** 主机名字符集，与 {@link URI#getHost()} 的接受范围一致：字母数字、点、连字符。 */
    private static boolean isHostChars(String host) {
        if (host.isEmpty()) {
            return false;
        }
        for (int i = 0; i < host.length(); i++) {
            char c = host.charAt(i);
            boolean allowed = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '.' || c == '-';
            if (!allowed) {
                return false;
            }
        }
        return true;
    }

    /** IPv6 字面量的方括号内部：十六进制、冒号，允许内嵌 IPv4 的点。 */
    private static boolean isIpv6Literal(String literal) {
        if (literal.length() < 3) {
            return false;
        }
        for (int i = 1; i < literal.length() - 1; i++) {
            char c = literal.charAt(i);
            boolean allowed = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f')
                    || (c >= 'A' && c <= 'F') || c == ':' || c == '.';
            if (!allowed) {
                return false;
            }
        }
        return true;
    }

    /**
     * 解析端口。
     *
     * <p>空串视为未指定（{@code https://host:/} 在 URI 里也是 -1，浏览器同样接受）；
     * 前导零按数值处理（{@code :008000} 等价于 {@code :8000}）；
     * 0、非数字、超过 65535 一律非法。</p>
     */
    private static int parsePort(String text) {
        if (text.isEmpty()) {
            return PORT_UNSPECIFIED;
        }
        int i = 0;
        while (i < text.length() && text.charAt(i) == '0') {
            i++;
        }
        if (text.length() - i > 5) {
            return PORT_INVALID;
        }
        int value = 0;
        for (; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < '0' || c > '9') {
                return PORT_INVALID;
            }
            value = value * 10 + (c - '0');
            if (value > 65535) {
                return PORT_INVALID;
            }
        }
        return value == 0 ? PORT_INVALID : value;
    }
}
