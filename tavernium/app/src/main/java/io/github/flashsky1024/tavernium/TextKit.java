package io.github.flashsky1024.tavernium;

import java.util.Locale;

/**
 * 文件名与短文本工具（纯函数，无 Android 依赖）。
 *
 * <p>从 MainActivity 抽出来的第一批：这些函数只有「输入 → 输出」，不碰 View、不碰
 * 生命周期、也不读写 SharedPreferences，所以搬走是零风险的，还能直接单测。</p>
 *
 * <p>为什么单独成类：下载文件名同时来自两条不可信链路——页面里的 {@code a.download}
 * 和服务端的 {@code Content-Disposition}。两者都必须走同一套消毒逻辑，放在 Activity
 * 里容易被新加的第三条下载路径漏掉。</p>
 */
final class TextKit {

    private TextKit() {
    }

    /**
     * 文件名消毒：只保留基名，去掉路径分隔符、控制字符与 Windows/Android 都不接受的字符，
     * 并防止 "." / ".." 这类名字。
     *
     * <p>超长名截断但保留扩展名，方便用户打开后仍能识别文件类型。</p>
     */
    static String sanitizeFileName(String name) {
        if (name == null) {
            return "download";
        }
        String s = name;
        int slash = Math.max(s.lastIndexOf('/'), s.lastIndexOf('\\'));
        if (slash >= 0) {
            s = s.substring(slash + 1);
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c == 0x7f) {
                continue;
            }
            sb.append("/\\:*?\"<>|".indexOf(c) >= 0 ? '_' : c);
        }
        s = sb.toString().trim();
        while (s.startsWith(".")) {
            s = s.substring(1);
        }
        s = s.trim();
        if (s.isEmpty()) {
            return "download";
        }
        if (s.length() > 100) {
            int dot = s.lastIndexOf('.');
            String ext = dot > 0 && s.length() - dot <= 12 ? s.substring(dot) : "";
            s = s.substring(0, 100 - ext.length()) + ext;
        }
        return s;
    }

    /**
     * 从响应头与 URL 推断落盘文件名。
     *
     * <p>注意返回的名字<b>仍是不可信输入</b>（Content-Disposition 是服务端可控的），
     * 调用方必须再过一遍 {@link #sanitizeFileName(String)} 才能拼路径。</p>
     */
    static String guessFileName(String url, String contentDisposition, String mimeType) {
        String name = null;
        if (contentDisposition != null) {
            int idx = contentDisposition.toLowerCase(Locale.ROOT).indexOf("filename=");
            if (idx >= 0) {
                name = contentDisposition.substring(idx + 9).replace("\"", "")
                        .split(";")[0].trim();
            }
        }
        if (name == null || name.isEmpty()) {
            String path = pathOf(url);
            if (path != null) {
                int slash = path.lastIndexOf('/');
                if (slash >= 0) {
                    path = path.substring(slash + 1);
                }
                name = path.split("[?#]")[0];
            }
        }
        if (name == null || name.isEmpty()) {
            name = "download";
        }
        if (name.lastIndexOf('.') < 0 && mimeType != null) {
            String ext = extensionFromMime(mimeType);
            if (ext != null) {
                name = name + "." + ext;
            }
        }
        if (name.contains(".")) {
            return name;
        }
        return name + "_" + System.currentTimeMillis();
    }

    /**
     * 取 URL 的路径部分（不含 scheme/host/query/fragment）。
     * 手工解析而不是 {@code Uri.parse}：畸形 URL 下 Uri 会抛异常，而这里只需要一个尽力而为的路径。
     */
    static String pathOf(String url) {
        if (url == null) {
            return null;
        }
        String s = url;
        int q = s.indexOf('?');
        if (q >= 0) {
            s = s.substring(0, q);
        }
        int h = s.indexOf('#');
        if (h >= 0) {
            s = s.substring(0, h);
        }
        int scheme = s.indexOf("://");
        if (scheme < 0) {
            return s;
        }
        int slash = s.indexOf('/', scheme + 3);
        return slash < 0 ? "" : s.substring(slash);
    }

    /** MIME → 扩展名；认不出来返回 null（调用方保留原名）。 */
    static String extensionFromMime(String mimeType) {
        if (mimeType == null) {
            return null;
        }
        String lower = mimeType.toLowerCase(Locale.ROOT);
        if (lower.contains("json")) {
            return "json";
        }
        if (lower.contains("javascript")) {
            return "js";
        }
        if (lower.contains("css")) {
            return "css";
        }
        if (lower.contains("png")) {
            return "png";
        }
        if (lower.contains("jpeg")) {
            return "jpg";
        }
        if (lower.contains("gif")) {
            return "gif";
        }
        if (lower.contains("webp")) {
            return "webp";
        }
        if (lower.contains("svg")) {
            return "svg";
        }
        if (lower.contains("pdf")) {
            return "pdf";
        }
        if (lower.contains("zip")) {
            return "zip";
        }
        if (lower.contains("text/plain")) {
            return "txt";
        }
        if (lower.contains("html")) {
            return "html";
        }
        return null;
    }

    /**
     * 日志用：过长时先去掉 query（可能含 token / session 等敏感参数）再截断。
     * 日志经常要发给别人看，避免把访问凭据一起带出去。
     */
    static String shortUrl(String url) {
        if (url == null) {
            return "null";
        }
        String s = url;
        if (s.length() > 100) {
            int q = s.indexOf('?');
            if (q > 0) {
                s = s.substring(0, q) + "?…";
            }
        }
        return s.length() <= 120 ? s : s.substring(0, 117) + "...";
    }
}
