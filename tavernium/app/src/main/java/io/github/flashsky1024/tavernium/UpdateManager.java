package io.github.flashsky1024.tavernium;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 联网更新：检查版本列表、下载 APK、校验、交给系统安装器。
 *
 * <p><b>接口约定（服务端只需提供静态 JSON）</b></p>
 * <pre>
 * GET {base}/api/apps/{appId}/releases.json
 * {
 *   "app": "tavernium",
 *   "updatedAt": "2026-10-22T12:00:00Z",
 *   "releases": [
 *     {
 *       "versionName": "1.8.17",
 *       "versionCode": 29,
 *       "url": "https://update.nandflash.wiki/apps/tavernium/tavernium-release-1.8.17.apk",
 *       "size": 208510,
 *       "sha256": "…（可选，有就校验）",
 *       "date": "2026-10-22",
 *       "channel": "stable",
 *       "notes": "更新说明"
 *     }
 *   ]
 * }
 * </pre>
 *
 * <p>路径里带 {@code appId}，因此同一个域名可以给多个应用用：只要各自放一份
 * {@code /api/apps/<appId>/releases.json} 与对应的 APK 目录即可，客户端只换 appId。</p>
 */
public final class UpdateManager {

    /** 更新服务默认根地址；可在设置里覆盖（便于测试/自建镜像）。 */
    public static final String DEFAULT_BASE = "https://update.nandflash.wiki";
    /**
     * 内置候选更新源，按顺序尝试。
     *
     * <p>更新域名可能会换。如果客户端只认一个地址，那个域名一停，所有老版本就永远
     * 收不到更新（只能让用户手动重装）。保留多个候选，任意一个可用就能继续升级。</p>
     */
    public static final String[] DEFAULT_BASES = {
            "https://update.nandflash.wiki",
            "https://update.tavermium.com",
    };
    /** 记住最近一次可用的源，24 小时内不再重复探测，避免每次检查都等超时。 */
    public static final String PREF_RESOLVED_BASE = "resolved_update_base";
    public static final String PREF_RESOLVED_AT = "resolved_update_base_at";
    private static final long RESOLVED_TTL_MS = 24L * 60L * 60L * 1000L;
    /** 本应用的 appId（路径片段）。 */
    private static final String TAG = "TavernKeepUpdate";
    public static final String APP_ID = "tavernium";
    /** 调试包的独立清单（服务端 appId 不同，客户端不会误把调试包当正式版）。 */
    public static final String APP_ID_DEBUG = "tavernium-debug";
    /** 两个发布包使用不同 applicationId，因此可以同时安装，数据互相隔离。 */
    public static final String PACKAGE_STABLE = "io.github.flashsky1024.tavernium";
    public static final String PACKAGE_DEBUG = PACKAGE_STABLE + ".debug";
    /**
     * 正式包与调试包的证书 SHA-256 指纹。
     *
     * <p>更新时如果 APK 的包名是另一个发布包，就不能再拿「当前应用证书」比较。
     * 这里分别固定两个公开证书指纹：既能支持正式版安装调试版、调试版安装正式版，
     * 又不会把「更新服务器被替换成任意签名的 APK」放进来。</p>
     *
     * <p>证书是公开信息，指纹不是私钥。若以后重新生成调试密钥，需要同步更新
     * {@link #SIGNER_SHA256_DEBUG}，并由能覆盖安装的正式渠道发布这次修改。</p>
     */
    static final String SIGNER_SHA256_STABLE =
            "878b8e33dc6cb602d5a6f5980c08c3f48bc72f58942edbbac2ea42873fce1692";
    static final String SIGNER_SHA256_DEBUG =
            "35fdb2af0552ba25622852d33b7e377dd4ad968790377bc7a573d7dd59b1fcf8";
    public static final String CHANNEL_STABLE = "stable";
    public static final String CHANNEL_DEBUG = "debug";
    /** 更新渠道：stable / debug。未设置时跟随当前安装包类型。 */
    public static final String PREF_UPDATE_CHANNEL = "update_channel";
    public static final String PREF_UPDATE_BASE = "update_base_url";
    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int READ_TIMEOUT_MS = 15000;
    private static final int DOWNLOAD_TIMEOUT_MS = 30000;
    /** releases.json 上限：正常几 KB，超过说明拉到的不是清单。 */
    private static final int MAX_MANIFEST_BYTES = 1024 * 1024;
    private static final long MAX_APK_BYTES = 128L * 1024L * 1024L;
    private static final int MAX_REDIRECTS = 5;
    private static final String MIME_APK = "application/vnd.android.package-archive";

    private UpdateManager() {
    }

    /** 单个可用版本。 */
    public static final class Release {
        public String versionName = "";
        public int versionCode = 0;
        public String url = "";
        public long size = 0L;
        public String sha256 = "";
        public String date = "";
        public String channel = "stable";
        public String notes = "";
        // 只由拉取清单时绑定，JSON 无权指定其信任来源。
        private String manifestUrl = "";

        /** 列表里显示的一行文字。 */
        public String label(int currentCode) {
            return label(currentCode, false);
        }

        /**
         * 列表文案。
         *
         * <p>{@code otherChannel} 为 true 时，该 APK 会作为另一个应用安装，
         * 不能标成“当前版本，可重装”或“可升级”。</p>
         */
        public String label(int currentCode, boolean otherChannel) {
            StringBuilder sb = new StringBuilder();
            sb.append(versionName).append(" (").append(versionCode).append(')');
            if (!date.isEmpty()) {
                sb.append(" · ").append(date);
            }
            if (size > 0) {
                sb.append(" · ").append(formatSize(size));
            }
            if (otherChannel) {
                sb.append(" · 独立应用");
            } else if (versionCode == currentCode) {
                sb.append(" · 当前版本，可重装");
            } else if (versionCode > currentCode) {
                sb.append(" · 可升级");
            } else {
                sb.append(" · 旧版本");
            }
            return sb.toString();
        }
    }

    /** 下载进度回调（在调用线程上触发，调用方自行切回 UI 线程）。 */
    public interface Progress {
        void onProgress(int percent, long downloaded, long total);
    }

    public static String formatSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.US, "%.0f KB", bytes / 1024.0);
        }
        return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    public static String releasesUrl(String base, String appId) {
        String root = base == null ? "" : base.trim();
        while (root.endsWith("/")) {
            root = root.substring(0, root.length() - 1);
        }
        return root + "/api/apps/" + appId + "/releases.json";
    }

    /** 一次拉取的结果：实际使用的源 + 版本列表（列表里的 url 已是绝对地址）。 */
    public static final class FetchResult {
        public final String base;
        public final java.util.List<Release> releases;

        FetchResult(String base, java.util.List<Release> releases) {
            this.base = base;
            this.releases = releases;
        }
    }

    /** 渠道对应的 appId。 */
    public static String appIdFor(String channel) {
        return CHANNEL_DEBUG.equals(channel) ? APP_ID_DEBUG : APP_ID;
    }

    /** 渠道对应的 Android 包名。 */
    public static String packageFor(String channel) {
        return CHANNEL_DEBUG.equals(channel) ? PACKAGE_DEBUG : PACKAGE_STABLE;
    }

    /**
     * 目标包是否是本应用的另一发布渠道。
     *
     * <p>只认两个固定包名，不允许更新清单任意指定包名。包名不同意味着 Android
     * 会把它当作另一个应用安装，而不是覆盖当前应用。</p>
     */
    static boolean isSiblingPackage(String currentPackage, String targetPackage) {
        return (PACKAGE_STABLE.equals(currentPackage) && PACKAGE_DEBUG.equals(targetPackage))
                || (PACKAGE_DEBUG.equals(currentPackage) && PACKAGE_STABLE.equals(targetPackage));
    }

    /** 当前安装包与目标渠道是否属于不同应用。 */
    public static boolean isCrossChannel(Context context, String channel) {
        return context != null && !packageFor(channel).equals(context.getPackageName());
    }

    /**
     * 当前更新渠道。
     *
     * <p>没手动设过时跟随安装包类型：debug 包默认查调试清单，正式包查正式清单。
     * 这样装了调试版的人不会再被"更新"回正式包（同 versionCode 无法覆盖安装，
     * 只会让人以为更新失败）。</p>
     */
    public static String channelOf(android.content.SharedPreferences prefs) {
        String c = prefs == null ? null : prefs.getString(PREF_UPDATE_CHANNEL, null);
        if (CHANNEL_DEBUG.equals(c)) {
            return CHANNEL_DEBUG;
        }
        if (CHANNEL_STABLE.equals(c)) {
            return CHANNEL_STABLE;
        }
        return BuildConfig.DEBUG ? CHANNEL_DEBUG : CHANNEL_STABLE;
    }

    /** 渠道的可读名称。 */
    public static String channelLabel(String channel) {
        return CHANNEL_DEBUG.equals(channel) ? "调试版" : "正式版";
    }

    /**
     * 本地缓存文件名：优先用下载地址的末段（服务端已按渠道命名）。
     *
     * <p>不能用固定的 appId —— 正式包与调试包版本号可以相同，同名会互相覆盖，
     * 导致"下到的是上一个渠道的文件"。</p>
     */
    static String apkNameOf(Release release) {
        String name = null;
        if (release != null && release.url != null) {
            String u = release.url;
            int q = u.indexOf('?');
            if (q >= 0) {
                u = u.substring(0, q);
            }
            int slash = u.lastIndexOf('/');
            if (slash >= 0 && slash + 1 < u.length()) {
                name = u.substring(slash + 1);
            }
        }
        if (name == null || name.isEmpty() || !name.endsWith(".apk")) {
            name = "update-" + (release == null ? "unknown" : release.versionName) + ".apk";
        }
        return name;
    }

    static String trimBase(String base) {
        String root = base == null ? "" : base.trim();
        while (root.endsWith("/")) {
            root = root.substring(0, root.length() - 1);
        }
        return root;
    }

    /**
     * 选一个能用的更新源并拉取清单：手动指定 → 24h 内可用的缓存 → 内置候选依次尝试。
     *
     * <p>阻塞调用，必须在后台线程执行。全部失败时抛最后一个异常。</p>
     */
    public static FetchResult fetchFromAnySource(android.content.SharedPreferences prefs,
                                                 String manualBase, String appId, int currentCode)
            throws Exception {
        if (appId == null || appId.trim().isEmpty()) {
            appId = APP_ID;
        }
        if (manualBase != null && !manualBase.trim().isEmpty()) {
            String base = trimBase(manualBase);
            return new FetchResult(base, fetchReleases(base, appId, currentCode));
        }
        Exception last = null;
        String cached = prefs == null ? null : prefs.getString(PREF_RESOLVED_BASE, null);
        long cachedAt = prefs == null ? 0L : prefs.getLong(PREF_RESOLVED_AT, 0L);
        if (cached != null && !cached.isEmpty()
                && System.currentTimeMillis() - cachedAt < RESOLVED_TTL_MS) {
            try {
                return new FetchResult(cached, fetchReleases(cached, appId, currentCode));
            } catch (Exception e) {
                last = e;
                // 缓存的源也挂了：清掉，继续试候选
                prefs.edit().remove(PREF_RESOLVED_BASE).remove(PREF_RESOLVED_AT).apply();
            }
        }
        for (String candidate : DEFAULT_BASES) {
            try {
                java.util.List<Release> list = fetchReleases(candidate, appId, currentCode);
                if (prefs != null) {
                    prefs.edit().putString(PREF_RESOLVED_BASE, candidate)
                            .putLong(PREF_RESOLVED_AT, System.currentTimeMillis()).apply();
                }
                return new FetchResult(candidate, list);
            } catch (Exception e) {
                last = e;
            }
        }
        throw last != null ? last : new IllegalStateException("没有可用的更新源");
    }

    /**
     * 校验待安装 APK 的签名是否与当前应用一致。
     *
     * <p>SHA-256 只能证明"下到的是清单里写的那个文件"，不能证明"清单本身可信"。
     * 签名校验补上这一环：更新服务器即使被攻破，攻击者也签不出同签名的包
     * （除非拿到你的私钥），从而拦住"同包名替换"这一类攻击。</p>
     *
     * @return true 签名一致（或无法读取自身签名时放行）；false 应拒绝安装
     */
    @SuppressWarnings("deprecation")
    public static boolean verifyApkSignature(android.content.Context context, File apk) {
        if (context == null || apk == null || !apk.exists()) {
            return false;
        }
        try {
            android.content.pm.PackageManager pm = context.getPackageManager();
            android.content.pm.PackageInfo self = pm.getPackageInfo(
                    context.getPackageName(), android.content.pm.PackageManager.GET_SIGNATURES);
            if (self == null || self.signatures == null || self.signatures.length == 0) {
                // 拿不到自身签名：fail-closed（返回 false），交调用方走「签名不一致」对话框
                // 让用户知情选择。此前这里 fail-open（放行），等于签名校验形同虚设——
                // 攻击者只要让本机读不到签名就能绕过校验，信任链不能开这种后门。
                return false;
            }
            android.content.pm.PackageInfo target = pm.getPackageArchiveInfo(
                    apk.getAbsolutePath(), android.content.pm.PackageManager.GET_SIGNATURES);
            if (target == null || target.signatures == null || target.signatures.length == 0) {
                return false;
            }
            if (!context.getPackageName().equals(target.packageName)) {
                return false;
            }
            java.util.Set<String> own = signatureHashes(self.signatures);
            java.util.Set<String> other = signatureHashes(target.signatures);
            return !own.isEmpty() && !other.isEmpty() && own.equals(other);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 校验 APK 是否可以交给系统安装器。
     *
     * <p>支持两种情况：</p>
     * <ul>
     *   <li>同一包名的正常更新/重装：证书必须与当前应用完全一致。</li>
     *   <li>另一渠道的独立应用：包名必须是固定的正式版或调试版包名，
     *       并且证书必须匹配该渠道固定的公开证书指纹。</li>
     * </ul>
     *
     * <p>包名不同时 Android 不会覆盖当前应用，而是并行安装另一个应用。
     * 调用方不得把这种情况描述成覆盖升级。</p>
     */
    public static boolean verifyApkForInstall(Context context, File apk) {
        if (verifyApkSignature(context, apk)) {
            return true;
        }
        if (context == null || apk == null || !apk.exists()) {
            return false;
        }
        try {
            android.content.pm.PackageManager pm = context.getPackageManager();
            android.content.pm.PackageInfo target = pm.getPackageArchiveInfo(
                    apk.getAbsolutePath(), android.content.pm.PackageManager.GET_SIGNATURES);
            if (target == null || target.packageName == null || target.signatures == null
                    || target.signatures.length == 0
                    || !isSiblingPackage(context.getPackageName(), target.packageName)) {
                return false;
            }
            String pinned = PACKAGE_DEBUG.equals(target.packageName)
                    ? SIGNER_SHA256_DEBUG : SIGNER_SHA256_STABLE;
            return matchesPinnedSigner(target.signatures, pinned);
        } catch (Exception e) {
            return false;
        }
    }

    /** 签名集合必须恰好是单个固定证书。多签会改变 Android 的签名身份，不能放行。 */
    static boolean matchesPinnedSigner(android.content.pm.Signature[] signatures,
                                       String expectedSha256) {
        if (signatures == null || expectedSha256 == null || expectedSha256.isEmpty()) {
            return false;
        }
        java.util.Set<String> signers = signatureHashes(signatures);
        return signers.size() == 1 && signers.contains(expectedSha256.toLowerCase(Locale.US));
    }

    private static java.util.Set<String> signatureHashes(
            android.content.pm.Signature[] sigs) {
        java.util.Set<String> out = new java.util.HashSet<>();
        for (android.content.pm.Signature s : sigs) {
            if (s == null) {
                return java.util.Collections.emptySet();
            }
            try {
                byte[] encoded = s.toByteArray();
                if (encoded == null || encoded.length == 0) return java.util.Collections.emptySet();
                out.add(toHex(java.security.MessageDigest.getInstance("SHA-256")
                        .digest(encoded)));
            } catch (Exception ignored) {
                return java.util.Collections.emptySet();
            }
        }
        return out;
    }

    /**
     * 拉取版本列表（阻塞调用，必须在后台线程执行）。
     * 结果按 versionCode 从高到低排序；解析失败的条目会被跳过而不是整体报错。
     */
    public static List<Release> fetchReleases(String base, String appId, int currentCode)
            throws Exception {
        String url = releasesUrl(base, appId);
        // 带版本号与时间戳：绕开 CDN/中间层的强缓存，保证刚发布的版本立刻可见
        String sep = url.contains("?") ? "&" : "?";
        url = url + sep + "v=" + currentCode + "&t=" + (System.currentTimeMillis() / 60000L);

        HttpURLConnection conn = null;
        try {
            conn = openUpdateConnection(url, url, READ_TIMEOUT_MS, "application/json");
            StringBuilder body = new StringBuilder();
            try (InputStream in = new BufferedInputStream(conn.getInputStream())) {
                // 先整体收字节再一次性解码：按 8KB 分块 new String 会把跨块的多字节字符
                // 截成两半，中文版本说明直接变问号。
                ByteArrayOutputStream raw = new ByteArrayOutputStream(16384);
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    // 清单只有几 KB。超过 1MB 说明拉到的不是清单（错误页/门户劫持），
                    // 继续读下去只是白占内存。
                    if (raw.size() + n > MAX_MANIFEST_BYTES) {
                        throw new IllegalStateException("manifest too large @ " + url);
                    }
                    raw.write(buf, 0, n);
                }
                body.append(new String(raw.toByteArray(), StandardCharsets.UTF_8));
            }
            List<Release> releases = parseReleases(body.toString());
            java.util.Iterator<Release> iterator = releases.iterator();
            while (iterator.hasNext()) {
                Release release = iterator.next();
                try {
                    release.url = new URL(conn.getURL(), release.url).toString();
                    if (!UrlKit.isAllowedUpdateUrl(release.url)
                            || !UrlKit.isSameOrigin(url, release.url)) {
                        iterator.remove();
                        continue;
                    }
                    release.manifestUrl = conn.getURL().toString();
                } catch (Exception e) {
                    iterator.remove();
                }
            }
            return releases;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /** 每一跳都先校验再联网，自动重定向不能绕过协议、主机和端口的限制。 */
    private static HttpURLConnection openUpdateConnection(String address, String source,
                                                           int timeout, String accept) throws Exception {
        String current = address;
        for (int redirect = 0; redirect <= MAX_REDIRECTS; redirect++) {
            if (!UrlKit.isAllowedUpdateUrl(current) || !UrlKit.isSameOrigin(current, source)) {
                throw new SecurityException("更新地址必须使用 HTTPS（或局域网 HTTP），并与清单同源");
            }
            HttpURLConnection connection = (HttpURLConnection) new URL(current).openConnection();
            boolean retained = false;
            try {
                connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
                connection.setReadTimeout(timeout);
                connection.setRequestProperty("Accept", accept);
                connection.setRequestProperty("User-Agent", "Tavernium/" + BuildConfig.VERSION_NAME);
                connection.setInstanceFollowRedirects(false);
                int code = connection.getResponseCode();
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String location = connection.getHeaderField("Location");
                    if (location == null || redirect == MAX_REDIRECTS) {
                        throw new IllegalStateException("更新地址重定向无效或次数过多");
                    }
                    current = new URL(new URL(current), location).toString();
                } else if (code == 200) {
                    retained = true;
                    return connection;
                } else {
                    throw new IllegalStateException("更新请求失败：HTTP " + code);
                }
            } finally {
                if (!retained) connection.disconnect();
            }
        }
        throw new IllegalStateException("更新地址重定向次数过多");
    }

    private static void requireVerifiedApk(Context context, File apk, Release release) {
        if (!verifyApkForInstall(context, apk)) {
            throw new SecurityException("安装包不是受信任的 Tavernium 版本，已拒绝更新");
        }
        android.content.pm.PackageInfo info = context.getPackageManager().getPackageArchiveInfo(
                apk.getAbsolutePath(), 0);
        if (info == null || info.versionCode != release.versionCode) {
            throw new SecurityException("安装包版本与更新清单不一致，已拒绝更新");
        }
    }

    /** 解析 releases.json；条目缺 versionCode 或下载地址时跳过。 */
    static List<Release> parseReleases(String json) throws Exception {
        List<Release> out = new ArrayList<>();
        JSONObject root = new JSONObject(json);
        JSONArray arr = root.optJSONArray("releases");
        if (arr == null) {
            return out;
        }
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) {
                continue;
            }
            Release r = new Release();
            r.versionName = o.optString("versionName", "");
            r.versionCode = o.optInt("versionCode", 0);
            r.url = o.optString("url", "");
            r.size = o.optLong("size", 0L);
            r.sha256 = o.optString("sha256", "");
            r.date = o.optString("date", "");
            r.channel = o.optString("channel", "stable");
            r.notes = o.optString("notes", "");
            if (r.versionCode <= 0 || r.url.isEmpty() || r.versionName.isEmpty()) {
                continue;
            }
            out.add(r);
        }
        Collections.sort(out, new Comparator<Release>() {
            @Override
            public int compare(Release a, Release b) {
                return Integer.compare(b.versionCode, a.versionCode);
            }
        });
        return out;
    }

    /**
     * 下载 APK 到 {@code cacheDir/updates/}（阻塞调用）。
     * 已存在同版本且大小一致的包会直接复用，避免重复下载。
     */
    public static File download(Context context, Release release, Progress progress)
            throws Exception {
        return download(context, release, progress, null);
    }

    /**
     * 同 {@link #download(Context, Release, Progress)}，额外支持取消。
     * {@code cancel} 非空时，每读一块就检查一次，置位后抛异常并清掉半截文件。
     */
    public static File download(Context context, Release release, Progress progress,
                                final java.util.concurrent.atomic.AtomicBoolean cancel)
            throws Exception {
        return download(context, release, progress, cancel, false);
    }

    /**
     * 同 {@link #download(Context, Release, Progress, java.util.concurrent.atomic.AtomicBoolean)}，
     * 并支持强制重新下载。
     *
     * <p>{@code forceRedownload} 为 true 时忽略本地已有的同名文件——排查问题时
     * 经常需要"重新下一份干净的包"来排除下载损坏的可能。</p>
     */
    public static File download(Context context, Release release, Progress progress,
                                final java.util.concurrent.atomic.AtomicBoolean cancel,
                                boolean forceRedownload)
            throws Exception {
        if (cancel != null && cancel.get()) {
            throw new IllegalStateException("已取消下载");
        }
        if (release == null || release.manifestUrl.isEmpty()
                || !UrlKit.isAllowedUpdateUrl(release.url)
                || !UrlKit.isSameOrigin(release.url, release.manifestUrl)) {
            throw new SecurityException("下载地址未通过更新清单来源校验，请重新检查更新");
        }
        if (release.size > MAX_APK_BYTES || release.size < 0
                || (!release.sha256.trim().isEmpty() && !release.sha256.trim().matches("(?i)[0-9a-f]{64}"))) {
            throw new SecurityException("更新清单中的文件大小或 SHA-256 无效");
        }
        File dir = new File(context.getCacheDir(), ApkProvider.DIR_NAME);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("无法创建更新目录: " + dir);
        }
        File target = new File(dir, apkNameOf(release));
        if (forceRedownload && target.exists() && !target.delete()) {
            throw new IllegalStateException("无法清理更新缓存，请稍后重新下载");
        }
        // 复用本地缓存前必须过一遍信任链：只对长度，等于「攻击者塞进 cacheDir 的同名同长度
        // 文件」就能冒充更新包被安装。release 带 sha256 时必须核对，对不上就删掉重下。
        if (target.exists() && target.length() > 0
                && (release.size <= 0 || target.length() == release.size)) {
            boolean hashMatches = release.sha256.trim().isEmpty()
                    || release.sha256.trim().equalsIgnoreCase(sha256Of(target));
            if (hashMatches) {
                try {
                    requireVerifiedApk(context, target, release);
                } catch (SecurityException e) {
                    if (!target.delete()) throw e;
                    hashMatches = false;
                }
            }
            if (hashMatches) {
                if (progress != null) {
                    progress.onProgress(100, target.length(), target.length());
                }
                return target;
            }
            // 哈希不符：本地文件不可信（可能损坏/被替换），删掉强制重下
            PerfLog.w(TAG, "cached apk sha256 mismatch, redownload: " + target.getName());
            //noinspection ResultOfMethodCallIgnored
            if (target.exists() && !target.delete()) {
                throw new IllegalStateException("无法清理损坏的更新缓存");
            }
        }
        File part = new File(dir, target.getName() + ".part");

        HttpURLConnection conn = null;
        try {
            conn = openUpdateConnection(release.url, release.manifestUrl, DOWNLOAD_TIMEOUT_MS, MIME_APK);
            long total = release.size > 0 ? release.size : conn.getContentLengthLong();
            if (total > MAX_APK_BYTES) {
                throw new IllegalStateException("更新包过大，已停止下载");
            }
            long done = 0L;
            MessageDigest digest = null;
            if (!release.sha256.trim().isEmpty()) {
                digest = MessageDigest.getInstance("SHA-256");
            }
            try (InputStream in = new BufferedInputStream(conn.getInputStream());
                 FileOutputStream out = new FileOutputStream(part)) {
                byte[] buf = new byte[32768];
                int n;
                int lastPercent = -1;
                while ((n = in.read(buf)) > 0) {
                    if (cancel != null && cancel.get()) {
                        throw new IllegalStateException("已取消下载");
                    }
                    out.write(buf, 0, n);
                    if (digest != null) {
                        digest.update(buf, 0, n);
                    }
                    done += n;
                    if (done > MAX_APK_BYTES) {
                        throw new IllegalStateException("更新包过大，已停止下载");
                    }
                    int percent = total > 0 ? (int) (done * 100 / total) : 0;
                    if (progress != null && percent != lastPercent) {
                        lastPercent = percent;
                        progress.onProgress(percent, done, total);
                    }
                }
                out.flush();
            }
            if (total > 0 && done != total) {
                throw new IllegalStateException("下载不完整：" + done + "/" + total + " 字节");
            }
            if (digest != null) {
                String actual = toHex(digest.digest());
                if (!actual.equalsIgnoreCase(release.sha256.trim())) {
                    throw new IllegalStateException("校验失败：sha256 " + actual
                            + " != " + release.sha256);
                }
            }
            if (cancel != null && cancel.get()) {
                throw new IllegalStateException("已取消下载");
            }
            requireVerifiedApk(context, part, release);
            if (target.exists() && !target.delete()) {
                throw new IllegalStateException("无法替换更新缓存");
            }
            if (!part.renameTo(target)) {
                throw new IllegalStateException("无法保存安装包");
            }
            if (progress != null) {
                progress.onProgress(100, done, done);
            }
            return target;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
            // 失败时清掉半截文件，避免下次误判为"已下载"
            if (part.exists()) {
                //noinspection ResultOfMethodCallIgnored
                part.delete();
            }
        }
    }

    /** 系统是否允许本应用安装未知来源的包（API 26+ 需要用户手动授权）。 */
    public static boolean canInstall(Context context) {
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                return context.getPackageManager().canRequestPackageInstalls();
            } catch (Exception e) {
                return false;
            }
        }
        return true;
    }

    /** 打开系统的"允许安装未知应用"设置页。 */
    public static void openInstallSettings(Context context) {
        try {
            Intent intent = new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
            intent.setData(Uri.parse("package:" + context.getPackageName()));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Exception ignored) {
        }
    }

    /**
     * 交给系统安装器。
     *
     * @return false 表示系统里没有能处理安装意图的组件（部分定制 ROM / 被设备策略禁用），
     * 调用方应给出提示，而不是让 ActivityNotFoundException 把进程带走。
     */
    public static boolean install(Context context, File apk) {
        if (!verifyApkForInstall(context, apk)) {
            return false;
        }
        Uri uri = Uri.parse("content://" + context.getPackageName()
                + ApkProvider.AUTHORITY_SUFFIX + "/" + apk.getName());
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, MIME_APK);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(intent);
            return true;
        } catch (Exception e) {
            android.util.Log.w(TAG, "install intent failed: " + e);
            PerfLog.w(TAG, "install intent failed: " + e);
            return false;
        }
    }

    /** 计算本地文件的 SHA-256（流式，常量内存）；异常返回空串，让调用方按「不匹配」处理。 */
    static String sha256Of(File f) {
        if (f == null || !f.exists()) {
            return "";
        }
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (Exception e) {
            return "";
        }
        try (InputStream in = new BufferedInputStream(new java.io.FileInputStream(f))) {
            byte[] buf = new byte[32768];
            int n;
            while ((n = in.read(buf)) > 0) {
                digest.update(buf, 0, n);
            }
            return toHex(digest.digest());
        } catch (Exception e) {
            return "";
        }
    }

    private static String toHex(byte[] bytes) {
        char[] table = "0123456789abcdef".toCharArray();
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            out[i * 2] = table[v >>> 4];
            out[i * 2 + 1] = table[v & 0x0F];
        }
        return new String(out);
    }
}
