package io.github.flashsky1024.tavernium;

import java.util.Locale;

/**
 * HTML 文档注入（纯函数，无 Android 依赖）。
 *
 * <p>这套逻辑在 {@code shouldInterceptRequest} 的网络线程上跑，做的是"把脚本塞进
 * 同服务器返回的 HTML 头部"。之所以要自己动手而不是用 {@code evaluateJavascript}：
 * 后者要等页面加载完，早期的脚本已经执行过了；而帧率 hook 必须在页面第一帧之前生效。</p>
 *
 * <p>抽取时保持行为完全不变——这个函数的返回值决定"要不要把请求交还 WebKit"，
 * 一旦判断逻辑走样，轻则注入失效，重则把内容改坏导致白屏。</p>
 */
final class HtmlInjector {

    private HtmlInjector() {
    }

    /**
     * 字节级注入：按 Content-Type 里的 charset 解码、插入脚本、再编码回去。
     *
     * <p>返回 null 表示放弃注入——内容不像 HTML、为空、或注入后没有变长。调用方应把
     * 请求交还 WebKit，绝不能把可疑内容交给页面。</p>
     */
    static byte[] injectDocumentBytes(byte[] raw, String contentType, String scriptTag) {
        if (raw == null || raw.length == 0 || scriptTag == null) {
            return null;
        }
        String charset = charsetOf(contentType);
        String html;
        try {
            html = new String(raw, charset);
        } catch (Exception e) {
            return null;
        }
        String head = html.length() > 200 ? html.substring(0, 200) : html;
        String trimmed = head.trim().toLowerCase(Locale.ROOT);
        if (!trimmed.startsWith("<") || html.indexOf('\u0000') >= 0) {
            return null;
        }
        String injected = injectScript(html, scriptTag);
        if (injected == null || injected.length() <= html.length()) {
            return null;
        }
        try {
            return injected.getBytes(charset);
        } catch (Exception e) {
            return null;
        }
    }

    /** 在 {@code <head ...>} 之后插入脚本；找不到 head 就放到文档最前面。 */
    static String injectScript(String html, String scriptTag) {
        if (html == null) {
            return null;
        }
        int head = indexOfIgnoreCase(html, "<head");
        if (head < 0) {
            head = indexOfIgnoreCase(html, "<html");
            if (head < 0) {
                return scriptTag + html;
            }
        }
        int close = html.indexOf('>', head);
        if (close < 0) {
            return scriptTag + html;
        }
        return html.substring(0, close + 1) + scriptTag + html.substring(close + 1);
    }

    /** 从 Content-Type 里取 charset；取不到按 UTF-8。 */
    static String charsetOf(String contentType) {
        if (contentType != null) {
            String lower = contentType.toLowerCase(Locale.ROOT);
            int at = lower.indexOf("charset=");
            if (at >= 0) {
                String cs = contentType.substring(at + 8).trim();
                int semi = cs.indexOf(';');
                if (semi >= 0) {
                    cs = cs.substring(0, semi);
                }
                cs = cs.replace("\"", "").trim();
                if (!cs.isEmpty()) {
                    return cs;
                }
            }
        }
        return "utf-8";
    }

    static int indexOfIgnoreCase(String haystack, String needle) {
        int limit = haystack.length() - needle.length();
        for (int i = 0; i <= limit; i++) {
            if (haystack.regionMatches(true, i, needle, 0, needle.length())) {
                return i;
            }
        }
        return -1;
    }
}
