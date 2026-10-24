package io.github.flashsky1024.tavernium;

import android.content.Context;
import android.net.Uri;
import android.util.Base64;
import android.util.Log;
import android.webkit.WebResourceResponse;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPInputStream;

/**
 * WebView 资源磁盘缓存。
 *
 * <p>CPU/内存优化点（相对旧实现）：</p>
 * <ol>
 *   <li>不再对每个 shouldInterceptRequest 起一个裸 Thread，改为 3 线程有界队列的预取池，
 *       队列满直接丢弃预取，绝不阻塞 WebView 的网络线程；</li>
 *   <li>命中缓存直接返回流式响应，不再把整个文件读进 byte[]；</li>
 *   <li>未命中时只做「后台预取落盘」，当前请求交给 WebKit 自家网络栈（返回 null），
 *       杜绝同一资源被下载两遍、解码两遍；</li>
 *   <li>失败负缓存 60s，避免 404/超时资源被反复重试；</li>
 *   <li>写缓存边下边写盘，超大文件直接放弃，不再整包进内存。</li>
 * </ol>
 *
 * <p>第二轮优化（热路径降载）：</p>
 * <ol>
 *   <li>{@link #intercept} 组合入口：一次子资源请求只做 1 次 URL 解析、1 次 MD5、
 *       1 次 File.length()，同时完成「命中判断 + 预取触发」。旧代码两条路径
 *       （getCached + prefetch）合计要解析 4~5 次 URL、算 3 次 MD5；</li>
 *   <li>URL 解析改为纯字符串实现（不再走 Uri.parse），子资源路径短、
 *       解析频繁（首屏可达上百次），避免每次的对象分配与整串 toLowerCase 拷贝；</li>
 *   <li>命中返回的流包一层 BufferedInputStream，减少 WebKit 读取时的 read 系统调用；</li>
 *   <li>缓存总容量改为增量统计（currentSize），免除每次预取成功后的全盘 listFiles() 扫描。</li>
 * </ol>
 *
 * <p>关于目录扫描：getCached/prefetch/intercept 只用一次 File.length() 判断存在性
 * （文件不存在时返回 0，一并覆盖空文件）；构造时全盘扫描一次用于初始化容量计数，
 * 之后只做增量维护。</p>
 */
public class CacheManager {
    private static final String TAG = "TavernKeepCache";
    private static final String CACHE_DIR = "tavernkeep_webcache";
    private static final int CONNECT_TIMEOUT = 15000;
    private static final int READ_TIMEOUT = 20000;
    private static final long MAX_CACHE_SIZE = 268435456L; // 256 MB
    private static final long MAX_FILE_SIZE = 33554432L;   // 32 MB
    private static final long MIN_FREE_SPACE = 52428800L;  // 50 MB
    private static final long NEGATIVE_TTL_MS = 60000L;
    private static final long NEGATIVE_MAX_ENTRIES = 256L;
    private static final int PREFETCH_THREADS = 3;
    private static final int PREFETCH_QUEUE_CAPACITY = 64;
    private static final int COPY_BUFFER = 16384;
    /** 内容校验时读取的头部字节数（足够覆盖 HTML 前缀与字体魔数）。 */
    private static final int SNIFF_BYTES = 32;

    private static final Set<String> CACHEABLE_EXTENSIONS = new HashSet<>(Arrays.asList(
            "js", "css", "png", "jpg", "jpeg", "gif", "webp", "svg", "ico",
            "woff", "woff2", "ttf", "otf", "eot", "json", "xml", "map", "wasm"));

    private final File cacheDir;
    private final Object lock = new Object();
    private final ExecutorService prefetchExecutor;
    private final android.content.SharedPreferences netPrefs;
    /** 正在预取中的 URL，用于去重。 */
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
    /** URL -> 失败过期时间戳。 */
    private final Map<String, Long> negativeCache = new ConcurrentHashMap<>();
    /**
     * 已判定「连不上 / 超时」的主机 -> 解禁时间戳。
     *
     * <p>国内网络环境下，raw.githubusercontent.com 之类的域名经常被丢包，
     * 一次请求要等到 TCP 超时才失败（实测 235 字节耗了 8.97 秒）。更糟的是页面里
     * 的扩展会 <b>反复</b> 重试，每次都再等一遍，把扩展初始化（activate hook）
     * 拖到 5 秒超时，连带整个 SillyTavern 卡在「Settings not ready」。</p>
     *
     * <p>所以：一旦某主机被判定为不可达，在 TTL 内对它的子资源请求直接返回空响应，
     * 让页面立刻拿到失败（扩展的 catch 分支马上执行），而不是干等。只有我们真的
     * 实测失败过的域名才会进这个集合，不会误伤可用域名。</p>
     */
    private final Map<String, Long> slowHosts = new ConcurrentHashMap<>();
    /**
     * 慢主机的封锁时长。
     *
     * <p>原来是 6 小时，代价太大：跨境链路抖一下（小文件 4 秒没下完就判定不可达），
     * 该 CDN 上所有资源在半天内都拿不到，扩展样式全丢（悬浮球失去定位、图标消失）。
     * 现在缩到 30 分钟，且任意一次预取成功就立刻解除。</p>
     */
    private static final long SLOW_HOST_TTL_MS = 30L * 60L * 1000L;
    private static final String PREFS_NET = "tavernkeep_net";
    private static final String PREF_SLOW_HOSTS = "slow_hosts";
    /** 空响应体，用于对已知不可达主机的快速失败。 */
    private static final byte[] EMPTY_BODY = new byte[0];
    /**
     * 1x1 透明 PNG。
     *
     * <p>用于处理 {@code <img src="">} 这种写法：空 src 会被浏览器解析成当前页面地址，
     * 于是每次重渲染头像/表情都会去请求一次首页（734KB HTML），拿回来当图片解码必然失败。
     * SillyTavern 的头像、表情立绘、裁剪弹窗都是这种写法，实测每 2 秒浪费一次整页请求。</p>
     *
     * <p>这类请求的特征很明确：URL 路径为空（就是站点根），但 Accept 里要的是图片。
     * 命中后直接回一张 1x1 透明图，既不打断页面，也省掉一次整页下载。</p>
     */
    // ---------------- jsDelivr 镜像加速（可选，用户显式开启） ----------------

    /**
     * 镜像根地址，例如 {@code https://jsd.onmicrosoft.cn}；null 表示不启用。
     *
     * <p>只对 {@code cdn.jsdelivr.net} 的静态资源生效：缓存未命中时同步从镜像抓一次，
     * 成功则写缓存并直接返回给 WebView；失败（非 200 / HTML / 超时 / 体积超限）
     * 立刻把镜像标记为不可用 10 分钟，本次请求回退给 WebKit 走官方。</p>
     *
     * <p>缓存 key 始终按<b>原始 URL</b> 计算，切换镜像不会让已缓存的资源失效。</p>
     */
    private volatile String mirrorBase = null;
    private volatile long mirrorDeadUntil = 0L;
    private static final long MIRROR_DEAD_TTL_MS = 10L * 60L * 1000L;
    private static final int MIRROR_MAX_BYTES = 8 * 1024 * 1024;
    private static final int MIRROR_CONNECT_TIMEOUT = 3000;
    private static final int MIRROR_READ_TIMEOUT = 6000;
    /** 官方 jsDelivr 主机名。 */
    static final String JSD_HOST = "cdn.jsdelivr.net";
    /**
     * 一并纳入镜像的 jsDelivr 子域。
     *
     * <p>{@code testingcf.jsdelivr.net} 是 JS-Slash-Runner 在卡片 iframe 的
     * {@code <head>} 里<b>同步</b>加载的脚本（log.js，仅 461 字节）。它卡多久，
     * 后面的 adjust_viewport.js / adjust_iframe_height.js 就等多久——而这两个脚本
     * 负责设置 {@code --TH-viewport-height} 与 iframe 高度，没跑完时卡片里
     * {@code position:fixed} 的悬浮球会被挤进正文。走国内镜像能把 0.6s 压到几十毫秒。</p>
     */
    private static final String JSD_HOST_TESTINGCF = "testingcf.jsdelivr.net";

    private static final byte[] PIXEL_PNG = Base64.decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==",
            0);


    /**
     * 缓存当前总字节数，增量维护：构造时扫描一次，预取成功累加、淘汰扣减、清空归零。
     * 免除 ensureSpace 每次预取都全盘 listFiles() 统计的磁盘与 CPU 开销。
     */
    private final AtomicLong currentSize = new AtomicLong(0L);
    /** HTTP Basic 凭据只可发送给明确配置的服务器来源。 */
    private volatile String credentialOrigin = "";

    public void setCredentialOrigin(String serverUrl) {
        this.credentialOrigin = UrlKit.originOf(serverUrl);
    }

    public static long slowHostTimeoutMinutes() {
        return SLOW_HOST_TTL_MS / 60000L;
    }

    public CacheManager(Context context) {
        File dir = new File(context.getCacheDir(), CACHE_DIR);
        this.cacheDir = dir;
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "cannot create cache dir: " + dir.getAbsolutePath());
        }
        this.currentSize.set(scanSize());
        // 有界线程池：core=1 允许空闲后回收线程，峰值 3；队列满走 DiscardPolicy，
        // 保证预取永远不反向拖慢页面加载。
        this.prefetchExecutor = new ThreadPoolExecutor(
                1, PREFETCH_THREADS, 30L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<Runnable>(PREFETCH_QUEUE_CAPACITY),
                new ThreadFactory() {
                    private final AtomicInteger seq = new AtomicInteger(1);

                    @Override
                    public Thread newThread(Runnable r) {
                        Thread t = new Thread(r, "tk-prefetch-" + seq.getAndIncrement());
                        // 预取是后台杂活，压低优先级，别抢前台渲染和主线程的 CPU
                        t.setPriority(Thread.MIN_PRIORITY + 1);
                        return t;
                    }
                },
                new ThreadPoolExecutor.DiscardPolicy());
        this.netPrefs = context.getSharedPreferences(PREFS_NET, Context.MODE_PRIVATE);
        loadSlowHosts();
    }

    /** 从磁盘恢复「已知不可达主机」，跨进程重启仍然生效（TTL 内）。 */
    private void loadSlowHosts() {
        try {
            String raw = this.netPrefs.getString(PREF_SLOW_HOSTS, "");
            if (raw == null || raw.isEmpty()) {
                return;
            }
            long now = System.currentTimeMillis();
            for (String part : raw.split(";")) {
                int sep = part.lastIndexOf('|');
                if (sep <= 0) {
                    continue;
                }
                long expire = Long.parseLong(part.substring(sep + 1));
                if (expire > now) {
                    this.slowHosts.put(part.substring(0, sep), expire);
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void saveSlowHosts() {
        try {
            StringBuilder sb = new StringBuilder();
            long now = System.currentTimeMillis();
            for (Map.Entry<String, Long> e : this.slowHosts.entrySet()) {
                if (e.getValue() > now) {
                    sb.append(e.getKey()).append('|').append(e.getValue()).append(';');
                }
            }
            this.netPrefs.edit().putString(PREF_SLOW_HOSTS, sb.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    /** 主机是否在「不可达」名单内（且未过期）。 */
    boolean isSlowHost(String host) {
        if (host == null || host.isEmpty()) {
            return false;
        }
        Long expire = this.slowHosts.get(host);
        if (expire == null) {
            return false;
        }
        if (expire <= System.currentTimeMillis()) {
            this.slowHosts.remove(host);
            return false;
        }
        return true;
    }

    /** 把主机标记为不可达；TTL 内后续请求直接快速失败，不再干等超时。 */
    void markSlowHost(String host) {
        if (host == null || host.isEmpty()) {
            return;
        }
        Long prev = this.slowHosts.get(host);
        long expire = System.currentTimeMillis() + SLOW_HOST_TTL_MS;
        if (prev != null && prev > expire - 60_000L) {
            return; // 已经在名单里，避免频繁写盘
        }
        this.slowHosts.put(host, expire);
        saveSlowHosts();
        PerfLog.w(TAG, "host marked unreachable (" + (SLOW_HOST_TTL_MS / 60000L) + "min): " + host);
        PerfMonitor.count("net.slowhost");
    }

    /**
     * 实测成功一次就解除封锁。
     *
     * <p>单次抖动不该让主机被锁半小时——只要有一条资源真的下回来了，就说明链路可用，
     * 立刻恢复，避免"明明已经能访问了，页面还是缺资源"。</p>
     */
    void clearSlowHost(String host) {
        if (host == null || host.isEmpty()) {
            return;
        }
        if (this.slowHosts.remove(host) != null) {
            saveSlowHosts();
            PerfLog.i(TAG, "host recovered, unmark: " + host);
            PerfMonitor.count("net.slowhost.recover");
        }
    }

    /** 清空「不可达主机」名单（换网络环境后手动恢复用）。 */
    public void clearSlowHosts() {
        this.slowHosts.clear();
        saveSlowHosts();
    }

    /** 当前名单里的主机名，供设置界面展示。 */
    public String[] slowHostList() {
        long now = System.currentTimeMillis();
        java.util.List<String> out = new java.util.ArrayList<>();
        for (Map.Entry<String, Long> e : this.slowHosts.entrySet()) {
            if (e.getValue() > now) {
                out.add(e.getKey());
            }
        }
        return out.toArray(new String[0]);
    }

    /** 连接层失败（超时 / 拒连 / DNS 解析不出来）——这类才说明主机本身不可达。 */
    static boolean isConnectivityFailure(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof java.net.SocketTimeoutException
                    || t instanceof java.net.ConnectException
                    || t instanceof java.net.UnknownHostException
                    || t instanceof java.net.NoRouteToHostException) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }

    /** 对已知不可达主机的空响应：让页面立刻拿到失败，而不是等超时。 */
    private static WebResourceResponse emptyResponse() {
        return withCors(new WebResourceResponse("text/plain", "UTF-8",
                new ByteArrayInputStream(EMPTY_BODY)));
    }

    /**
     * 给拦截响应补上 CORS 头。
     *
     * <p>{@code shouldInterceptRequest} 返回的响应，浏览器不会替我们补任何头；
     * 缓存里只有文件内容，源站的 {@code Access-Control-Allow-Origin} 早已丢失。
     * 结果是跨域 {@code <script type="module">}、{@code import()}、{@code fetch()}
     * 被 CORS 拒绝，而普通 {@code <script src>}（非 module）不受影响——
     * 这正是"卡片前端不显示、但页面其余部分正常"的原因。</p>
     */
    static WebResourceResponse withCors(WebResourceResponse resp) {
        if (resp == null) {
            return null;
        }
        try {
            resp.setResponseHeaders(corsHeaders());
        } catch (Exception ignored) {
        }
        return resp;
    }

    private static Map<String, String> corsHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("Access-Control-Allow-Origin", "*");
        // 允许跨域读取少量元信息（部分前端库会读 content-length / content-type）
        headers.put("Access-Control-Expose-Headers", "Content-Length, Content-Type");
        return headers;
    }

    /** 1x1 透明 PNG 响应：给「空 src 被解析成页面地址」的 img 请求兜底。 */
    private static WebResourceResponse pixelResponse() {
        return withCors(new WebResourceResponse("image/png", null,
                new ByteArrayInputStream(PIXEL_PNG)));
    }

    /**
     * URL 是否是「光秃秃的文档地址」——只有 scheme://host（可选端口），没有路径。
     * 这是空 {@code src=""} 被浏览器解析成当前页面地址后的典型形态。
     */
    static boolean isBareDocumentUrl(String url) {
        if (url == null) {
            return false;
        }
        int scheme = url.indexOf("://");
        if (scheme < 0) {
            return false;
        }
        int hostStart = scheme + 3;
        int n = url.length();
        for (int i = hostStart; i < n; i++) {
            char c = url.charAt(i);
            if (c == '/' ) {
                // 「://host/」算裸文档（首页形态）；路径里还有别的字符就否定
                return i + 1 >= n;
            }
            if (c == '?' || c == '#') {
                // 无路径、只带查询/锚片段，同样是裸文档
                return true;
            }
        }
        return true;
    }

    /** 启用/关闭镜像（传 null 关闭）。 */
    public void setMirrorBase(String base) {
        if (base == null || base.trim().isEmpty()) {
            this.mirrorBase = null;
            this.mirrorDeadUntil = 0L;
            return;
        }
        String root = base.trim();
        while (root.endsWith("/")) {
            root = root.substring(0, root.length() - 1);
        }
        this.mirrorBase = root;
    }

    public String mirrorBase() {
        return this.mirrorBase;
    }

    public boolean isMirrorTemporarilyDead() {
        return System.currentTimeMillis() < this.mirrorDeadUntil;
    }

    static boolean isJsdUrl(String url) {
        if (url == null) {
            return false;
        }
        String h = hostOf(url);
        return JSD_HOST.equals(h) || JSD_HOST_TESTINGCF.equals(h);
    }

    /** 把 {@code https://cdn.jsdelivr.net/xxx} 换成 {@code <base>/xxx}。 */
    static String mirrorUrlOf(String base, String url) {
        if (base == null || url == null) {
            return url;
        }
        int scheme = url.indexOf("://");
        if (scheme < 0) {
            return url;
        }
        int hostStart = scheme + 3;
        int pathStart = url.indexOf('/', hostStart);
        String path = pathStart < 0 ? "/" : url.substring(pathStart);
        return base + path;
    }

    /** 当前是否可以用镜像处理这条 URL。 */
    private boolean mirrorUsableFor(String url) {
        return this.mirrorBase != null
                && System.currentTimeMillis() >= this.mirrorDeadUntil
                && isJsdUrl(url);
    }

    private void markMirrorDead(int code) {
        this.mirrorDeadUntil = System.currentTimeMillis() + MIRROR_DEAD_TTL_MS;
        PerfMonitor.count("jsd.mirror.fail");
        PerfLog.w(TAG, "jsd mirror disabled for 10min, code=" + code);
    }

    /**
     * 同步从镜像抓取并写缓存。成功返回可直接交给 WebKit 的响应，任何异常返回 null。
     * 调用发生在 WebView 的网络线程上，因此超时必须短（连接 3s / 读 6s）。
     */
    private WebResourceResponse fetchViaMirror(String url, String ext, String userAgent) {
        String base = this.mirrorBase;
        if (base == null) {
            return null;
        }
        String target = mirrorUrlOf(base, url);
        HttpURLConnection conn = null;
        long t0 = System.nanoTime();
        try {
            conn = openConnection(target, userAgent, null, null);
            conn.setConnectTimeout(MIRROR_CONNECT_TIMEOUT);
            conn.setReadTimeout(MIRROR_READ_TIMEOUT);
            int code = conn.getResponseCode();
            if (code != 200) {
                markMirrorDead(code);
                return null;
            }
            if (isHtmlContentType(conn.getContentType())) {
                // 镜像返回了错误页/登录页：不能当资源交给页面
                markMirrorDead(-1);
                return null;
            }
            byte[] body = readLimited(conn, MIRROR_MAX_BYTES);
            if (body == null || body.length == 0) {
                markMirrorDead(-2);
                return null;
            }
            // 内容与扩展名不符（镜像返回错误页/占位文本/被劫持）：绝不能交给页面，
            // 否则一段错误的 CSS 会让整个扩展样式失效，比"没加速"严重得多。
            if (!contentMatchesExtBytes(body, ext)) {
                PerfMonitor.count("jsd.mirror.badcontent");
                PerfLog.w(TAG, "mirror content mismatch, drop: " + brief(url));
                markMirrorDead(-4);
                return null;
            }
            // 尽力写缓存：失败不影响本次返回
            writeCacheBytes(url, ext, body);
            PerfMonitor.count("jsd.mirror.ok");
            PerfMonitor.mark("jsd.mirror", System.nanoTime() - t0);
            PerfLog.d(TAG, "mirror ok " + body.length + "B "
                    + ((System.nanoTime() - t0) / 1_000_000L) + "ms " + brief(url));
            return withCors(new WebResourceResponse(guessMime(ext), null,
                    new ByteArrayInputStream(body)));
        } catch (Exception e) {
            markMirrorDead(-3);
            PerfLog.w(TAG, "mirror failed " + e + " " + brief(url));
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /** 读满整个响应体；超过上限则放弃（返回 null），避免大文件把内存顶爆。 */
    private static byte[] readLimited(HttpURLConnection conn, int limit) throws IOException {
        InputStream in = null;
        try {
            in = conn.getInputStream();
            String encoding = conn.getContentEncoding();
            if (encoding != null && encoding.toLowerCase(Locale.ROOT).contains("gzip")) {
                in = new GZIPInputStream(new BufferedInputStream(in, COPY_BUFFER), COPY_BUFFER);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream(16384);
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) {
                if (out.size() + n > limit) {
                    return null;
                }
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    /** 把已下载的字节写入缓存；内容与扩展名不符时丢弃。 */
    private void writeCacheBytes(String url, String ext, byte[] body) {
        File target = cacheFile(url, ext);
        File tmp = new File(cacheDir, target.getName() + ".tmp");
        try {
            FileOutputStream fos = new FileOutputStream(tmp);
            try {
                fos.write(body);
                fos.flush();
            } finally {
                fos.close();
            }
            if (!contentMatchesExt(tmp, ext)) {
                tmp.delete();
                PerfMonitor.count("cache.corrupt");
                return;
            }
            ensureSpace(body.length);
            if (!tmp.renameTo(target)) {
                tmp.delete();
                return;
            }
            this.currentSize.addAndGet(body.length);
        } catch (Exception e) {
            tmp.delete();
        }
    }

    /** 从 URL 里取主机名（不含端口）；纯字符串实现，热路径只做一次 substring。 */
    static String hostOf(String url) {
        if (url == null) {
            return "";
        }
        int start = url.indexOf("://");
        if (start < 0) {
            return "";
        }
        start += 3;
        int end = url.length();
        for (int i = start; i < end; i++) {
            char c = url.charAt(i);
            if (c == '/' || c == '?' || c == '#') {
                end = i;
                break;
            }
        }
        for (int i = start; i < end; i++) {
            if (url.charAt(i) == ':') {
                end = i;
                break;
            }
        }
        if (end <= start) {
            return "";
        }
        return url.substring(start, end).toLowerCase(Locale.ROOT);
    }

    public void shutdown() {
        this.prefetchExecutor.shutdownNow();
    }

    /**
     * shouldInterceptRequest 的组合入口：一次 URL 解析、一次 MD5、一次 stat，
     * 同时完成命中判断与（未命中时的）后台预取触发。
     *
     * @param allowPrefetch false 时只查缓存不触发预取（供非 GET 请求保持旧行为）
     * @return 命中返回流式响应；未命中或不可缓存返回 null，当前请求交还 WebKit
     */
    public WebResourceResponse intercept(String url, String userAgent, String user, String pass,
                                        boolean allowPrefetch, boolean wantsImage) {
        // 分段埋点：定位热路径里到底哪一步在吃 CPU（解析 / MD5 / 打开流）
        long t0 = System.nanoTime();
        String ext = cacheableExtension(url);
        long t1 = System.nanoTime();
        PerfMonitor.mark("intercept.parse", t1 - t0);
        if (ext == null) {
            // <img src=""> 被解析成站点根：请求要的是图片，但 URL 是首页本身。
            // 直接给 1x1 透明图，避免把 734KB 的 HTML 当图片下载一遍再解码失败。
            if (wantsImage && isBareDocumentUrl(url)) {
                PerfMonitor.count("img.empty");
                return pixelResponse();
            }
            PerfMonitor.count("intercept.reject");
            return null;
        }
        File file = cacheFile(url, ext);
        long t2 = System.nanoTime();
        PerfMonitor.mark("intercept.md5", t2 - t1);
        if (file.length() > 0L) {
            if (contentMatchesExt(file, ext)) {
                WebResourceResponse resp = openCacheResponse(file, ext, url);
                PerfMonitor.mark("intercept.open", System.nanoTime() - t2);
                PerfMonitor.count("intercept.hit");
                return resp;
            }
            // 内容与扩展名不符（典型：登录页/错误页被预取误存成字体）——
            // 直接删掉自愈，本次交还 WebKit 重新加载，用户无需手动清缓存
            discardCorrupt(file);
        }
        // 已知不可达的主机：立刻给空响应，不发起网络请求。
        // 页面侧的 fetch 会当场进 catch，扩展初始化继续往下走，而不是干等 TCP 超时。
        // 注意必须放在缓存命中判断之后——缓存里可能还有上次成功时存下的副本。
        if (isSlowHost(hostOf(url))) {
            if (isLayoutCriticalExt(ext)) {
                // CSS/JS 缺失不是"少一张图"，而是整个扩展/界面失去样式与逻辑：
                // 悬浮球会掉回普通文档流、扩展初始化失败。这类资源宁可慢，
                // 也绝不能确定性地交回 0 字节——交还 WebKit 让它自己请求。
                PerfMonitor.count("net.fastfail.skipped");
                return null;
            }
            PerfMonitor.count("net.fastfail");
            return emptyResponse();
        }
        // jsDelivr 镜像：缓存未命中时同步走镜像，拿到就直接用（失败自动回退给 WebKit）
        if (mirrorUsableFor(url)) {
            WebResourceResponse mirrored = fetchViaMirror(url, ext, userAgent);
            if (mirrored != null) {
                return mirrored;
            }
        }
        PerfMonitor.count("intercept.miss");
        if (allowPrefetch && !isNegativelyCached(url) && inFlight.add(url)) {
            submitPrefetch(url, ext, userAgent, user, pass);
        }
        return null;
    }

    /** 同 {@link #intercept}，默认允许预取。 */
    public WebResourceResponse intercept(String url, String userAgent, String user, String pass) {
        return intercept(url, userAgent, user, pass, true, false);
    }

    /** 兼容旧签名（测试与内部调用）：不区分图片请求。 */
    public WebResourceResponse intercept(String url, String userAgent, String user, String pass,
                                        boolean allowPrefetch) {
        return intercept(url, userAgent, user, pass, allowPrefetch, false);
    }

    /**
     * 命中磁盘缓存则返回流式响应，未命中返回 null（由 WebKit 自行联网加载）。
     * 只做一次 File.length()：文件不存在或为空时都返回 0，一并覆盖。
     */
    public WebResourceResponse getCached(String url) {
        String ext = cacheableExtension(url);
        if (ext == null) {
            return null;
        }
        File file = cacheFile(url, ext);
        if (file.length() <= 0L) {
            return null;
        }
        if (!contentMatchesExt(file, ext)) {
            discardCorrupt(file);
            return null;
        }
        return openCacheResponse(file, ext, url);
    }

    /**
     * 后台预取：把该资源抓一份落盘，下次请求即可命中磁盘缓存。
     * 本方法运行在 WebView 的网络线程上，必须立刻返回，因此内部只做入队。
     */
    public void prefetch(final String url, final String userAgent,
                         final String user, final String pass) {
        String ext = cacheableExtension(url);
        if (ext == null || isNegativelyCached(url)) {
            return;
        }
        if (cacheFile(url, ext).length() > 0L || !inFlight.add(url)) {
            return;
        }
        submitPrefetch(url, ext, userAgent, user, pass);
    }

    private void submitPrefetch(final String url, final String ext, final String userAgent,
                                final String user, final String pass) {
        try {
            prefetchExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        doPrefetch(url, ext, userAgent, user, pass);
                    } catch (Exception e) {
                        noteFailure(url);
                        if (isConnectivityFailure(e)) {
                            markSlowHost(hostOf(url));
                        }
                    } finally {
                        inFlight.remove(url);
                    }
                }
            });
        } catch (RejectedExecutionException e) {
            // 队列已满：放弃这次预取，不影响页面本身加载
            inFlight.remove(url);
        }
    }

    private void doPrefetch(String url, String ext, String userAgent, String user, String pass)
            throws IOException {
        String host = hostOf(url);
        long t0 = System.nanoTime();
        boolean ok = false;
        long payload = 0L;
        HttpURLConnection conn = null;
        try {
            conn = openConnection(url, userAgent, user, pass);
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                noteFailure(url);
                PerfLog.d(TAG, "prefetch " + code + " " + brief(url));
                return;
            }
            // 重定向到登录页/错误页时服务端仍返回 200，但内容是 HTML。
            // 白名单里没有任何 HTML 类型，落盘只会顶替字体/样式，导致图标全部变缺字符号。
            if (isHtmlContentType(conn.getContentType())) {
                noteFailure(url);
                PerfMonitor.count("cache.corrupt");
                PerfLog.w(TAG, "prefetch rejected text/html " + brief(url));
                return;
            }
            File target = cacheFile(url, ext);
            File tmp = new File(cacheDir, target.getName() + ".tmp");
            payload = streamToFile(conn, tmp);
            if (payload <= 0L) {
                return;
            }
            // 落盘前按内容再校验一次（有些服务器不给 Content-Type）
            if (!contentMatchesExt(tmp, ext)) {
                tmp.delete();
                noteFailure(url);
                PerfMonitor.count("cache.corrupt");
                PerfLog.w(TAG, "prefetch rejected invalid content " + brief(url));
                return;
            }
            ensureSpace(payload);
            if (!tmp.renameTo(target)) {
                tmp.delete();
                return;
            }
            this.currentSize.addAndGet(payload);
            ok = true;
            // 能下回来就说明链路可用：立刻解除可能的误判
            clearSlowHost(host);
            long costMs = (System.nanoTime() - t0) / 1_000_000L;
            PerfMonitor.mark("prefetch.download", System.nanoTime() - t0);
            PerfMonitor.mark("prefetch.bytes", payload);
            PerfLog.d(TAG, "prefetch ok " + payload + "B " + costMs + "ms " + brief(url));
            // 体积很小却耗时数秒 = 这条链路基本不可用（丢包/被墙）。
            // 阈值同时看体积和耗时，避免把「大文件正常下载」误判成慢链路。
            if (payload < 65536L && costMs > 4000L) {
                markSlowHost(host);
                PerfMonitor.count("net.slowhost");
            }
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
            PerfMonitor.markHost(host, System.nanoTime() - t0, ok);
        }
    }

    /** 命中后打开文件流：包 BufferedInputStream 减少 read 系统调用。 */
    private WebResourceResponse openCacheResponse(File file, String ext, String url) {
        String mime = guessMime(ext);
        try {
            WebResourceResponse resp = new WebResourceResponse(mime,
                    isTextual(mime) ? "UTF-8" : null,
                    new BufferedInputStream(new FileInputStream(file), COPY_BUFFER));
            // 必须对所有类型补 CORS：字体（@font-face）、module script、
            // import()、fetch() 都按 CORS 规则加载。源站 jsDelivr 本来就带
            // Access-Control-Allow-Origin: *，但缓存只保存了响应体、丢了响应头，
            // WebView 也不会替我们补——缺了它，卡片前端依赖的库会全部被拦，
            // 而同一页面在手机 Chrome 里（不经拦截）却是正常的。
            return withCors(resp);
        } catch (IOException e) {
            Log.w(TAG, "cache read failed: " + url, e);
            return null;
        }
    }

    /** 白名单里没有 HTML 类型，text/html 响应必然是登录页/错误页。 */
    private static boolean isHtmlContentType(String contentType) {
        return contentType != null
                && contentType.toLowerCase(Locale.ROOT).contains("text/html");
    }

    private static boolean isFontExt(String ext) {
        return "woff".equals(ext) || "woff2".equals(ext)
                || "ttf".equals(ext) || "otf".equals(ext);
    }

    /** 删除被污染的缓存条目，回退容量计数并记录（报告里可见 cache.corrupt 次数）。 */
    private void discardCorrupt(File file) {
        long len = file.length();
        if (file.delete()) {
            this.currentSize.addAndGet(-len);
        }
        PerfMonitor.count("cache.corrupt");
        Log.w(TAG, "discarded corrupt cache entry: " + file.getName());
        PerfLog.w(TAG, "discarded corrupt cache entry " + file.getName() + " (" + len + "B)");
    }

    /**
     * 校验落盘内容与扩展名是否相符。
     * <ul>
     *   <li>白名单里没有 html 类型：内容以 &lt;!doctype / &lt;html 开头一律判为污染；</li>
     *   <li>字体额外校验魔数（wOF2/wOFF/OTTO/0x00010000/true），
     *       防止半截或错位内容被当作字体——那会让整套 icon font 回退成缺字符号。</li>
     * </ul>
     */
    static boolean contentMatchesExt(File file, String ext) {
        // 只对「被 HTML 顶替会造成实际故障」的类型做嗅探：字体与文本资源。
        // 图片等二进制类型跳过，避免热路径上每次命中都多一次磁盘读。
        if (!isTextExt(ext) && !isFontExt(ext)) {
            return true;
        }
        byte[] head = new byte[SNIFF_BYTES];
        int n;
        FileInputStream in = null;
        try {
            in = new FileInputStream(file);
            n = in.read(head);
        } catch (IOException e) {
            return false;
        } finally {
            closeQuietly(in);
        }
        if (n <= 0) {
            return false;
        }
        return headMatchesExt(head, n, ext);
    }

    /** 字节级内容校验（用于镜像响应、写盘前的内存副本）。 */
    static boolean contentMatchesExtBytes(byte[] data, String ext) {
        if (!isTextExt(ext) && !isFontExt(ext)) {
            return true;
        }
        if (data == null || data.length == 0) {
            return false;
        }
        return headMatchesExt(data, Math.min(data.length, SNIFF_BYTES), ext);
    }

    private static boolean headMatchesExt(byte[] head, int n, String ext) {
        if (n <= 0) {
            return false;
        }
        if (startsWithHtml(head, n)) {
            return false;
        }
        return !isFontExt(ext) || fontMagicMatches(head, n, ext);
    }

    /**
     * 缺失后会破坏布局/逻辑的资源类型。
     *
     * <p>图片缺了只是不显示，字体缺了会 fallback，但 CSS 缺了整个扩展失去定位，
     * JS 缺了扩展直接不初始化——这两种不能用"快速失败"处理。</p>
     */
    static boolean isLayoutCriticalExt(String ext) {
        return "css".equals(ext) || "js".equals(ext)
                || "json".equals(ext) || "xml".equals(ext) || "map".equals(ext);
    }

    /** 文本类扩展名：被登录页顶替会导致样式/脚本整体失效。 */
    private static boolean isTextExt(String ext) {
        return "css".equals(ext) || "js".equals(ext) || "json".equals(ext)
                || "xml".equals(ext) || "svg".equals(ext) || "map".equals(ext);
    }

    private static boolean startsWithHtml(byte[] h, int n) {
        int i = 0;
        if (n >= 3 && (h[0] & 0xFF) == 0xEF && (h[1] & 0xFF) == 0xBB
                && (h[2] & 0xFF) == 0xBF) {
            i = 3; // 跳过 UTF-8 BOM
        }
        while (i < n && (h[i] == ' ' || h[i] == '\t' || h[i] == '\r' || h[i] == '\n')) {
            i++;
        }
        return matchesAsciiIgnoreCase(h, n, i, "<!doctype")
                || matchesAsciiIgnoreCase(h, n, i, "<html");
    }

    private static boolean matchesAsciiIgnoreCase(byte[] h, int n, int off, String needle) {
        if (off + needle.length() > n) {
            return false;
        }
        for (int k = 0; k < needle.length(); k++) {
            char c = (char) (h[off + k] & 0xFF);
            if (Character.toLowerCase(c) != needle.charAt(k)) {
                return false;
            }
        }
        return true;
    }

    private static boolean fontMagicMatches(byte[] h, int n, String ext) {
        if (n < 4) {
            return false;
        }
        switch (ext) {
            case "woff2":
                return h[0] == 'w' && h[1] == 'O' && h[2] == 'F' && h[3] == '2';
            case "woff":
                return h[0] == 'w' && h[1] == 'O' && h[2] == 'F' && h[3] == 'F';
            case "otf":
                return h[0] == 'O' && h[1] == 'T' && h[2] == 'T' && h[3] == 'O';
            case "ttf":
                boolean sfnt = h[0] == 0 && h[1] == 1 && h[2] == 0 && h[3] == 0;
                boolean trueType = h[0] == 't' && h[1] == 'r' && h[2] == 'u' && h[3] == 'e';
                boolean collection = h[0] == 't' && h[1] == 't' && h[2] == 'c' && h[3] == 'f';
                return sfnt || trueType || collection;
            default:
                return true;
        }
    }

    /** 直连下载（供"保存到下载目录"使用），不经缓存、不整包进内存。 */
    public InputStream fetchStream(String url, String method, String userAgent,
                                   String user, String pass) throws IOException {
        HttpURLConnection conn = openConnection(url, userAgent, user, pass);
        if (method != null && !method.isEmpty()) {
            conn.setRequestMethod(method);
        }
        int code = conn.getResponseCode();
        if (code < 200 || code >= 300) {
            conn.disconnect();
            throw new IOException("HTTP " + code);
        }
        return openBody(conn);
    }

    /** 兼容旧调用方：整包读取。仅用于体积极小的请求。 */
    public InputStream fetchAndCache(String url, String method, String userAgent,
                                     String user, String pass) throws IOException {
        InputStream in = fetchStream(url, method, userAgent, user, pass);
        try {
            return new ByteArrayInputStream(readAllBytes(in));
        } finally {
            closeQuietly(in);
        }
    }

    private HttpURLConnection openConnection(String url, String userAgent,
                                             String user, String pass) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(CONNECT_TIMEOUT);
        conn.setReadTimeout(READ_TIMEOUT);
        conn.setDoInput(true);
        conn.setUseCaches(false);
        conn.setInstanceFollowRedirects(true);
        // 显式声明 gzip：省流量，且把解压控制权握在自己手里。
        // 注意：一旦应用自己设置了 Accept-Encoding，Android 的 OkHttp 就不再透明解压，
        // 必须在 openBody() 里自己处理——这样 JVM 与真机行为一致，也能被测试覆盖。
        conn.setRequestProperty("Accept-Encoding", "gzip");
        if (userAgent != null && !userAgent.isEmpty()) {
            conn.setRequestProperty("User-Agent", userAgent);
        }
        if (user != null && !user.isEmpty() && Uri.parse(url).getUserInfo() == null
                && UrlKit.isSameOrigin(url, this.credentialOrigin)) {
            // 带凭据时不自动跟随跳转。预取失败可回退 WebView；直连下载会报告失败。
            conn.setInstanceFollowRedirects(false);
            String basic = Base64.encodeToString(
                    (user + ":" + (pass == null ? "" : pass)).getBytes(StandardCharsets.UTF_8), 2);
            conn.setRequestProperty("Authorization", "Basic " + basic);
        }
        return conn;
    }

    /**
     * 取响应体并处理传输编码：落盘/返回给 WebView 的必须是解压后的原始内容，
     * 否则字体、CSS、JS 都会解析失败（缓存命中时我们不带 Content-Encoding 头）。
     * 只解 gzip——服务端若无视 Accept-Encoding 返回 br/deflate，内容校验会拒绝落盘。
     */
    private InputStream openBody(HttpURLConnection conn) throws IOException {
        InputStream in = new BufferedInputStream(conn.getInputStream(), COPY_BUFFER);
        String encoding = conn.getContentEncoding();
        if (encoding != null && encoding.toLowerCase(Locale.ROOT).contains("gzip")) {
            return new GZIPInputStream(in, COPY_BUFFER);
        }
        return in;
    }

    /** 边下边写盘，超过 MAX_FILE_SIZE 立即放弃并删掉临时文件。返回写入字节数，失败返回 -1。 */
    private long streamToFile(HttpURLConnection conn, File out) throws IOException {
        InputStream in = openBody(conn);
        FileOutputStream fos = new FileOutputStream(out);
        long total = 0L;
        try {
            byte[] buf = new byte[COPY_BUFFER];
            int n;
            while ((n = in.read(buf)) != -1) {
                total += n;
                if (total > MAX_FILE_SIZE) {
                    return -1L;
                }
                fos.write(buf, 0, n);
            }
            fos.flush();
            return total;
        } finally {
            closeQuietly(in);
            closeQuietly(fos);
            if (total <= 0L || total > MAX_FILE_SIZE) {
                out.delete();
            }
        }
    }

    private static void closeQuietly(Closeable c) {
        if (c == null) {
            return;
        }
        try {
            c.close();
        } catch (IOException ignored) {
        }
    }

    /** 日志用：过长时去掉 query（可能含 token）再截断。 */
    private static String brief(String url) {
        if (url == null) {
            return "null";
        }
        String s = url;
        if (s.length() > 90) {
            int q = s.indexOf('?');
            if (q > 0) {
                s = s.substring(0, q) + "?…";
            }
        }
        return s.length() <= 110 ? s : s.substring(0, 107) + "...";
    }

    public void clearCache() {
        try {
            prefetchExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    synchronized (lock) {
                        File[] files = cacheDir.listFiles();
                        if (files != null) {
                            for (File file : files) {
                                if (file.isFile()) {
                                    file.delete();
                                }
                            }
                        }
                        currentSize.set(0L);
                    }
                    negativeCache.clear();
                    Log.i(TAG, "cache cleared");
                }
            });
        } catch (RejectedExecutionException ignored) {
        }
    }

    private void ensureSpace(long incoming) {
        synchronized (lock) {
            long usable = cacheDir.getUsableSpace();
            // 测试可能直接往缓存目录写文件而绕过增量计数，这里钳到下界 0，
            // 正常路径上 currentSize 始终 >= 0。
            long current = Math.max(0L, this.currentSize.get());
            if (usable - incoming < MIN_FREE_SPACE) {
                evictOldest((MIN_FREE_SPACE + incoming) - usable);
                current = Math.max(0L, this.currentSize.get());
            }
            if (current + incoming > MAX_CACHE_SIZE) {
                evictOldest((current + incoming) - MAX_CACHE_SIZE);
            }
        }
    }

    /** 构造时一次性全盘扫描，初始化容量计数。 */
    private long scanSize() {
        File[] files = cacheDir.listFiles();
        long total = 0L;
        if (files != null) {
            for (File file : files) {
                if (file.isFile()) {
                    total += file.length();
                }
            }
        }
        return total;
    }

    /** 按写入时间从旧到新淘汰，直到腾出 bytes 字节。仅在预取线程上调用。 */
    private void evictOldest(long bytes) {
        if (bytes <= 0L) {
            return;
        }
        File[] files = cacheDir.listFiles();
        if (files == null) {
            return;
        }
        Arrays.sort(files, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return Long.compare(a.lastModified(), b.lastModified());
            }
        });
        long freed = 0L;
        for (File file : files) {
            if (freed >= bytes) {
                break;
            }
            if (file.isFile()) {
                long len = file.length();
                if (file.delete()) {
                    freed += len;
                    this.currentSize.addAndGet(-len);
                }
            }
        }
    }

    private boolean isNegativelyCached(String url) {
        Long expiry = negativeCache.get(url);
        if (expiry == null) {
            return false;
        }
        if (expiry.longValue() < System.currentTimeMillis()) {
            negativeCache.remove(url);
            return false;
        }
        return true;
    }

    private void noteFailure(String url) {
        negativeCache.put(url, Long.valueOf(System.currentTimeMillis() + NEGATIVE_TTL_MS));
        if (negativeCache.size() > NEGATIVE_MAX_ENTRIES) {
            long now = System.currentTimeMillis();
            for (Map.Entry<String, Long> e : negativeCache.entrySet()) {
                if (e.getValue().longValue() < now) {
                    negativeCache.remove(e.getKey());
                }
            }
        }
    }

    private File cacheFile(String url) {
        return cacheFile(url, extension(url));
    }

    private File cacheFile(String url, String ext) {
        String name = md5(url);
        return new File(cacheDir, ext.isEmpty() ? name : name + "." + ext);
    }

    /**
     * 可缓存则返回小写扩展名，否则返回 null。
     * 一次完成协议检查、特征路径排除与扩展名提取——调用方（热路径）只需解析一遍。
     */
    private static String cacheableExtension(String url) {
        if (url == null || url.isEmpty()) {
            return null;
        }
        int schemeEnd = url.indexOf("://");
        if (schemeEnd < 0) {
            return null;
        }
        String scheme = url.substring(0, schemeEnd);
        if (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https")) {
            return null;
        }
        if (indexOfIgnoreCase(url, "/api/") >= 0
                || indexOfIgnoreCase(url, "/socket") >= 0
                || indexOfIgnoreCase(url, "stream") >= 0) {
            return null;
        }
        String ext = extension(url);
        return CACHEABLE_EXTENSIONS.contains(ext) ? ext : null;
    }

    private boolean shouldCache(String url) {
        return cacheableExtension(url) != null;
    }

    /**
     * 提取 URL 路径的扩展名（小写、不含点）。
     * 纯字符串实现：本方法在每个子资源请求上都会被走到（首屏上百次），
     * 不走 Uri.parse（对象分配）也不整串 toLowerCase（字符串拷贝）。
     */
    private static String extension(String url) {
        if (url == null || url.isEmpty()) {
            return "";
        }
        int end = url.length();
        int q = url.indexOf('?');
        if (q >= 0 && q < end) {
            end = q;
        }
        int h = url.indexOf('#');
        if (h >= 0 && h < end) {
            end = h;
        }
        // 路径起点：协议分隔符之后第一个 '/'
        int schemeEnd = url.indexOf("://");
        int pathStart = schemeEnd >= 0 ? url.indexOf('/', schemeEnd + 3) : url.indexOf('/');
        if (pathStart < 0 || pathStart >= end) {
            return "";
        }
        int lastSlash = url.lastIndexOf('/', end - 1);
        if (lastSlash < pathStart) {
            lastSlash = pathStart;
        }
        int dot = url.lastIndexOf('.', end - 1);
        if (dot <= lastSlash || dot + 1 >= end) {
            return "";
        }
        return url.substring(dot + 1, end).toLowerCase(Locale.ROOT);
    }

    /** 大小写不敏感地查找子串，避免为一次 contains 检查而 toLowerCase 整个 URL。 */
    private static int indexOfIgnoreCase(String src, String sub) {
        if (src == null || sub == null || sub.isEmpty()) {
            return -1;
        }
        int n = src.length();
        int m = sub.length();
        if (m > n) {
            return -1;
        }
        char first = sub.charAt(0);
        for (int i = 0; i <= n - m; i++) {
            if (Character.toLowerCase(src.charAt(i)) != first) {
                continue;
            }
            boolean match = true;
            for (int j = 1; j < m; j++) {
                if (Character.toLowerCase(src.charAt(i + j)) != sub.charAt(j)) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isTextual(String mime) {
        return mime.startsWith("text/")
                || mime.equals("application/javascript")
                || mime.equals("application/json")
                || mime.equals("application/xml")
                || mime.equals("image/svg+xml");
    }

    private static String guessMime(String ext) {
        switch (ext) {
            case "js":
                return "application/javascript";
            case "css":
                return "text/css";
            case "gif":
                return "image/gif";
            case "ico":
                return "image/x-icon";
            case "jpg":
            case "jpeg":
                return "image/jpeg";
            case "otf":
                return "font/otf";
            case "png":
                return "image/png";
            case "svg":
                return "image/svg+xml";
            case "ttf":
                return "font/ttf";
            case "xml":
                return "application/xml";
            case "json":
                return "application/json";
            case "wasm":
                return "application/wasm";
            case "webp":
                return "image/webp";
            case "woff":
                return "font/woff";
            case "woff2":
                return "font/woff2";
            default:
                return "application/octet-stream";
        }
    }

    private byte[] readAllBytes(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[COPY_BUFFER];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    /**
     * 每线程复用一个 MessageDigest：MessageDigest 非线程安全，而
     * getInstance() 在 Android 上要做 Provider 查找，比一次短 URL 的摘要计算还贵。
     */
    private static final ThreadLocal<MessageDigest> MD5_DIGEST =
            new ThreadLocal<MessageDigest>() {
                @Override
                protected MessageDigest initialValue() {
                    try {
                        return MessageDigest.getInstance("MD5");
                    } catch (NoSuchAlgorithmException e) {
                        return null;
                    }
                }
            };

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    /**
     * 真机实测原来这步平均 1.14ms、p99 25ms——两次元凶：每次 getInstance()，
     * 以及 32 次 String.format("%02x")。改为线程内复用实例 + 查表转十六进制后
     * 降到微秒级，热路径 p99 抖动随之消失。
     */
    private String md5(String str) {
        MessageDigest digest = MD5_DIGEST.get();
        if (digest == null) {
            return String.valueOf(str.hashCode());
        }
        digest.reset();
        byte[] bytes = digest.digest(str.getBytes(StandardCharsets.UTF_8));
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int b = bytes[i] & 0xFF;
            out[i * 2] = HEX[b >>> 4];
            out[i * 2 + 1] = HEX[b & 0x0F];
        }
        return new String(out);
    }
}
