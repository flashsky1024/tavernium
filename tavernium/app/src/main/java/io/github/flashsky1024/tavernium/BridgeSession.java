package io.github.flashsky1024.tavernium;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

/** 每次顶层导航重建凭证；凭证只保存在注入脚本的闭包中，不暴露到 window。 */
final class BridgeSession {
    private final SecureRandom random = new SecureRandom();
    private String token = "";

    synchronized void beginDocument(String url, String serverUrl) {
        clear();
        if (!UrlKit.isTrustedMainUrl(url, serverUrl)) {
            return;
        }
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        StringBuilder out = new StringBuilder(64);
        for (byte b : bytes) {
            out.append(Character.forDigit((b >>> 4) & 15, 16));
            out.append(Character.forDigit(b & 15, 16));
        }
        token = out.toString();
    }

    synchronized String tokenFor(String url, String serverUrl) {
        return UrlKit.isTrustedMainUrl(url, serverUrl) ? token : "";
    }

    synchronized boolean accepts(String candidate, String url, String serverUrl) {
        return candidate != null && candidate.length() == 64 && !token.isEmpty()
                && UrlKit.isTrustedMainUrl(url, serverUrl)
                && MessageDigest.isEqual(token.getBytes(StandardCharsets.US_ASCII),
                        candidate.getBytes(StandardCharsets.US_ASCII));
    }

    synchronized void clear() {
        token = "";
    }
}
