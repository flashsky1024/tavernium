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

    private UrlKit() {
    }

    /** host（小写，不含端口）；解析不出来返回空串。 */
    static String hostOf(String url) {
        try {
            String host = new URI(url).getHost();
            return host == null ? "" : host.toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            return "";
        }
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

    static String originOf(String url) {
        try {
            URI uri = new URI(url);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || host == null || host.isEmpty() || uri.getRawUserInfo() != null) {
                return "";
            }
            scheme = scheme.toLowerCase(Locale.ROOT);
            if (!"http".equals(scheme) && !"https".equals(scheme)) {
                return "";
            }
            int port = uri.getPort();
            if (port < -1 || port == 0 || port > 65535) {
                return "";
            }
            boolean standard = port == -1 || ("https".equals(scheme) ? port == 443 : port == 80);
            return scheme + "://" + host.toLowerCase(Locale.ROOT) + (standard ? "" : ":" + port);
        } catch (Exception e) {
            return "";
        }
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
        try {
            if (new URI(url).getRawFragment() != null) return false;
        } catch (Exception e) {
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
}
