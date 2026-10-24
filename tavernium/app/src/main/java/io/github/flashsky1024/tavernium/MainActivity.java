package io.github.flashsky1024.tavernium;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.ConsoleMessage;
import android.webkit.DownloadListener;
import android.webkit.HttpAuthHandler;
import android.webkit.JavascriptInterface;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebViewDatabase;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {
    private static final String TAG = "TavernKeep";

    /** 心跳注入脚本。开头加了 __tkHb 短路守卫：已注入过就立刻返回，重复执行几乎零成本。 */
    private static final String HEARTBEAT_JS = "(function(){try{if(window.__tkHb)return;window.__tkHb=1;Object.defineProperty(document,'visibilityState',{get:function(){return 'visible';},configurable:true});Object.defineProperty(document,'hidden',{get:function(){return false;},configurable:true});Object.defineProperty(document,'hasFocus',{value:function(){return true;},configurable:true});window.hasFocus=function(){return true;};var block=function(e){e.stopImmediatePropagation();};window.addEventListener('visibilitychange',block,true);document.addEventListener('visibilitychange',block,true);window.addEventListener('webkitvisibilitychange',block,true);document.addEventListener('webkitvisibilitychange',block,true);window.addEventListener('blur',block,true);window.addEventListener('pagehide',block,true);if(document.webkitHidden!==undefined){Object.defineProperty(document,'webkitHidden',{get:function(){return false;},configurable:true});Object.defineProperty(document,'webkitVisibilityState',{get:function(){return 'visible';},configurable:true});}try{if(window.screen&&window.screen.orientation&&!window.__tkOrientHooked){window.__tkOrientHooked=true;screen.orientation.lock=function(){return Promise.resolve();};screen.orientation.unlock=function(){};}}catch(e2){}try{if(!window.__tkNetHooked){window.__tkNetHooked=true;var note=function(){try{if(window.TavernKeep&&window.TavernKeep.noteNetActivity){bridge.noteNetActivity();}}catch(en){}};if(window.fetch){var of=window.fetch;window.fetch=function(){note();return of.apply(this||window,arguments);};}if(window.XMLHttpRequest&&XMLHttpRequest.prototype&&XMLHttpRequest.prototype.open){var oxo=XMLHttpRequest.prototype.open;XMLHttpRequest.prototype.open=function(){note();return oxo.apply(this,arguments);};}}}catch(e3){}console.log('[TavernKeep] heartbeat js injected');}catch(e){console.log('[TavernKeep] inject error',e);}})();";

    private static final String BLOB_DOWNLOAD_JS = "(function(){try{if(window.__tkBlobHooked)return;window.__tkBlobHooked=true;var blobMap={};var origCreate=window.URL.createObjectURL;window.URL.createObjectURL=function(obj){var url=origCreate.call(this,obj);if(obj instanceof Blob)blobMap[url]=obj;return url;};var origRevoke=window.URL.revokeObjectURL;window.URL.revokeObjectURL=function(url){delete blobMap[url];origRevoke.call(this,url);};function saveBase64(name,mime,base64){if(window.TavernKeep&&window.TavernKeep.saveBlob){bridge.saveBlob(name,mime,base64);}}document.addEventListener('click',function(e){var a=e.target.closest('a');if(!a||!a.download)return;e.preventDefault();e.stopPropagation();var name=a.download||'download';var href=a.href;if(href.startsWith('blob:')){var blob=blobMap[href];if(blob){var r=new FileReader();r.onload=function(){saveBase64(name,blob.type||'application/octet-stream',r.result.split(',')[1]);};r.readAsDataURL(blob);}else{fetch(href).then(function(r){return r.blob();}).then(function(b){var fr=new FileReader();fr.onload=function(){saveBase64(name,b.type||'application/octet-stream',fr.result.split(',')[1]);};fr.readAsDataURL(b);});}}},true);console.log('[TavernKeep] blob download hook injected');}catch(e){console.log('[TavernKeep] blob hook error',e);}})();";

    /** 两段脚本合并成一次 evaluateJavascript，把跨进程 IPC 次数减半。 */
    /**
     * JS 错误捕获：页面里未处理的异常、Promise rejection 以及**资源加载失败**转发到
     * console.error，由 onConsoleMessage 收进日志——SillyTavern 前端报错时不用连电脑看控制台。
     * capture=true 会同时收到 &lt;script&gt;/&lt;link&gt;/&lt;img&gt; 的加载失败事件，这类事件没有
     * message，必须从 e.target 取 src/href 才能知道是哪个资源挂了。
     * 每秒最多 5 条，避免错误风暴刷爆日志。
     */
    private static final String JS_ERROR_HOOK = "(function(){try{if(window.__tkErrHooked)return;window.__tkErrHooked=1;var n=0,t=0;function r(m){var now=Date.now();if(now-t>1000){n=0;t=now;}if(++n>5)return;console.error('[tk-js] '+m);}function dg(el){try{var s=el.tagName||'?';if(el.id)s+='#'+el.id;var c=el.className;if(typeof c==='string'&&c.trim())s+='.'+c.trim().split(' ').slice(0,3).join('.');var p=el.parentElement;if(!p)return s+'{detached}';var ps=p.tagName;if(p.id)ps+='#'+p.id;var pc=p.className;if(typeof pc==='string'&&pc.trim())ps+='.'+pc.trim().split(' ').slice(0,2).join('.');return s+' in '+ps}catch(e){return '?'}}window.addEventListener('error',function(e){var x=e.target;if(x&&(x.src||x.href)){try{if(x.tagName==='IMG'){if(x.naturalWidth===1){return;}if(x.hasAttribute&&x.hasAttribute('src')){var _s=x.getAttribute('src');if(_s!==null&&String(_s).trim()===''){return;}}}}catch(_e0){}var u=String(x.src||x.href);if(u.length>110)u=u.slice(0,107)+'...';var h=String(x.outerHTML||'');if(h.length>130)h=h.slice(0,130)+'...';r('resource failed: '+(x.tagName||'?')+' '+u+' | el='+dg(x)+' | connected='+(x.isConnected===false?'no':'yes')+' | html='+h);return;}r((e.message||'error')+' @ '+(e.filename||'?')+':'+(e.lineno||0));},true);window.addEventListener('unhandledrejection',function(e){var y=e.reason;r('unhandled rejection: '+(y&&y.message?y.message:String(y)));});}catch(e){}})();";

    private static final String INJECT_JS = HEARTBEAT_JS + BLOB_DOWNLOAD_JS + JS_ERROR_HOOK;

    private static final long HEARTBEAT_INTERVAL_MS = 30000L;
    private static final long NET_WAKELOCK_MS = 60000L;
    /** 唤醒锁剩余时间还超过该值时不重复 acquire/release，减少抖动。 */
    private static final long NET_WAKELOCK_RENEW_MS = 30000L;

    private static final String EMPTY = "";
    private static final String DEFAULT_SERVER_URL = "";
    private static final String SERVER_URL_HELP =
            "公网服务器请使用 https://；http:// 仅用于局域网 IP 或 localhost。";
    private static final int FILE_CHOOSER_REQUEST = 1001;
    private static final int REPLY_PERMISSION_REQUEST = 1002;
    private static final String PREFS_NAME = "tavernkeep_prefs";
    private static final String PREF_KEEP_SCREEN_ON = "keep_screen_on";
    private static final String PREF_PASS = "basic_auth_pass";
    private static final String PREF_PAUSE_IN_BACKGROUND = "pause_in_background";
    private static final String PREF_SERVER_URL = "server_url";
    private static final String PREF_USER = "basic_auth_user";
    private static final String PREF_PERF_MONITOR = "perf_monitor";
    private static final String PREF_VERBOSE_LOG = "verbose_log";
    private static final String PREF_FRAME_RATE = "frame_rate_limit";
    private static final String PREF_REPLY_NOTIFICATIONS = "reply_notifications";
    /** jsDelivr 加速：开启后用第三方镜像替换 cdn.jsdelivr.net（仅静态资源）。 */
    private static final String PREF_JSD_ACCEL = "jsd_accel";
    private static final String JSD_MIRROR_BASE = "https://jsd.onmicrosoft.cn";
    private static final String JSD_PROBE_PATH = "/npm/jquery@3.7.1/package.json";

    /** 新版三态：0 关闭 / 1 普通挂机 / 2 防烧屏挂机。 */
    private static final String PREF_IDLE_MODE = "idle_mode_v2";
    /** 是否已提示过"普通挂机不防烧屏"（只提示一次，避免每次切换都弹）。 */
    private static final String PREF_NORMAL_IDLE_WARNED = "idle_normal_warned";
    /** 电池优化白名单只在首次自动引导一次，之后从「调试与诊断」手动进入。 */
    private static final String PREF_BATTERY_PROMPTED = "battery_opt_prompted";
    /** blob 下载的 base64 上限：64MB 文本约 48MB 文件，再大基本是异常调用，宁可拒绝也别 OOM。 */
    private static final int MAX_BLOB_BASE64_CHARS = 64 * 1024 * 1024;
    private static final int IDLE_OFF = 0;
    private static final int IDLE_NORMAL = 1;
    private static final int IDLE_BURNIN = 2;

    /**
     * 挂机模式锁定的帧率上限。取 1fps：页面几乎是静止的（一秒钟只重绘一次），
     * 聊天生成仍由 JS 定时器与网络驱动，不依赖帧率。若用户原本设得更低则沿用更低值。
     */
    private static final int IDLE_FPS = 1;

    /** 帧率上限候选值：0 表示系统默认（不节流），其余从省电到流畅排列。 */
    private static final int[] FRAME_RATE_OPTIONS = {0, 20, 30, 45, 60, 90, 120};
    private static final String[] FRAME_RATE_LABELS = {
            "系统默认（不限制）", "20 fps（挂机最省电）", "30 fps（省电）", "45 fps（平衡）",
            "60 fps（流畅）", "90 fps（高刷屏）", "120 fps（高刷屏）"};
    /** 自定义帧率的合法区间。 */
    private static final int FRAME_RATE_MIN = 5;
    private static final int FRAME_RATE_MAX = 240;

    /**
     * 帧率上限的 JS 侧实现：包装 requestAnimationFrame，按 window.__tkMinFrameInterval
     * （毫秒）限制最小帧间隔。值为 0 时原样透传，几乎零开销。
     * 之所以在页面里做节流而不是只调系统刷新率：系统级 API 只是"首选刷新率"提示，
     * 真正吃掉 CPU 的是每帧的 JS 回调 + 布局 + 绘制，必须在源头减少帧数。
     * 用单引号书写，避免 Java 字符串转义（该常量会被验证脚本原样提取给 node 测试）。
     */
    private static final String FRAME_RATE_HOOK_JS = "(function(){try{if(window.__tkRafHooked)return;window.__tkRafHooked=1;var O=window.requestAnimationFrame;var C=window.cancelAnimationFrame;var T={};var N=1;var L=0;function now(){return (window.performance&&performance.now)?performance.now():Date.now();}window.requestAnimationFrame=function(cb){var iv=window.__tkMinFrameInterval||0;if(iv<=0){return O.call(window,cb);}var id=N++;var d=Math.max(0,iv-(now()-L));T[id]=setTimeout(function(){delete T[id];L=now();cb(L);},d);return id;};window.cancelAnimationFrame=function(id){if(T[id]!==undefined){clearTimeout(T[id]);delete T[id];return;}if(C){C.call(window,id);}};}catch(e){}})();";

    /**
     * 文档开始时安装的 rAF hook。
     *
     * <p>必须在页面脚本执行<b>之前</b>装：SillyTavern 与多数扩展会把
     * {@code window.requestAnimationFrame} 缓存成局部引用（{@code const raf = ...}），
     * 若等 onPageFinished 才替换，页面早就拿着原始引用了，节流等于没装——
     * 这正是之前"改帧率没效果"的根因。</p>
     *
     * <p>hook 永远安装（即使当前不限帧），变量为 0 时原样透传，开销可忽略；
     * 之后改设置只改变量，立即对所有调用方生效。</p>
     */
    /** rAF hook 是否确实装进了当前页面（供报告展示，防止"设了没生效"却无感知）。 */
    private volatile boolean rafHookInstalled = false;
    /**
     * 主文档注入的熔断：连续失败 3 次就本次会话不再尝试，直接交还 WebKit。
     *
     * <p>注入是把主文档自己取回来再交回去，一旦某个环节出问题（服务端编码异常、
     * 超大文档、非 HTML），最坏结果是整页白屏或乱码。宁可退化成"帧率限制不生效"，
     * 也不能让页面打不开——所以加了这道闸。</p>
     */
    /**
     * 当前站点是否使用 SillyTavern 自己的账号系统（页面出现登录表单）。
     * 仅用于菜单文案，不参与任何认证决策——用户不该被迫先判断自己是哪种登录。
     */
    private volatile boolean loginModeNative = false;
    /** 首次检测完成后置位，避免页面每次刷新都刷新日志。 */
    private boolean loginModeProbed = false;

    private int injectFailStreak = 0;
    private volatile boolean injectDisabled = false;
    private static final int INJECT_FAIL_LIMIT = 3;

    /** JS 性能探针：rAF 计数出 FPS + PerformanceObserver 统计 long task。随注入脚本播种。 */
    private static final String PERF_PROBE_SEED = "(function(){try{if(window.__tkPerfSeeded)return;window.__tkPerfSeeded=1;window.__tkFps=0;window.__tkLongTasks=0;window.__tkLtMax=0;try{new PerformanceObserver(function(es){var a=es.getEntries();window.__tkLongTasks+=a.length;for(var i=0;i<a.length;i++){if(a[i].duration>(window.__tkLtMax||0)){window.__tkLtMax=a[i].duration;}}}).observe({entryTypes:['longtask']});}catch(e2){}function burst(){var f=0,t0=window.performance.now();(function loop(){f++;var el=window.performance.now()-t0;if(el<500){window.requestAnimationFrame(loop);}else{window.__tkFps=Math.round(f*1000/el);}})();}burst();window.setInterval(burst,3000);}catch(e){}})();";

    /**
     * 采集一次：帧计数、DOM 节点数、long task 累计、窗口内最长 long task、JS 堆、窗口毫秒数。
     * ltMax 用于定位"卡顿发生在哪一刻"——Java 侧超过 100ms 会单独写一条 W 日志。
     */
    private static final String PERF_PROBE_JS = "(function(){try{var f=window.__tkFps||0;var d=document.getElementsByTagName('*').length;var l=window.__tkLongTasks||0;var m=window.__tkLtMax||0;var h=(window.performance&&performance.memory)?performance.memory.usedJSHeapSize:0;window.__tkLtMax=0;return {fps:f,dom:d,lt:l,ltMax:Math.round(m),heap:h,ms:1000};}catch(e){return null}})()";

    private CacheManager cacheManager;
    private IdleController idleController;
    private final BridgeSession bridgeSession = new BridgeSession();
    private ReplyNotifier replyNotifier;
    private String replyScript = EMPTY;
    private PowerManager.WakeLock netWakeLock;
    private ValueCallback<Uri[]> filePathCallback;
    private Handler handler;
    private SharedPreferences prefs;
    private WebView webView;

    /** 以下字段在 UI 线程写、在 WebView 网络线程读，用 volatile 保证可见性。 */
    private volatile String userAgent = EMPTY;
    private volatile String authUser = EMPTY;
    private volatile String authPass = EMPTY;
    private volatile String serverUrl = EMPTY;

    private boolean authRetried = false;
    private boolean authDialogShowing = false;
    private boolean webViewPaused = false;
    /** 当前是否处于 resumed 状态：决定渲染进程死亡时当场重建还是等回前台。 */
    private boolean activityResumed = false;
    /** 后台期间渲染进程被回收/崩溃，回到前台后需要重建 WebView。 */
    private boolean pendingWebViewRecovery = false;
    private long netWakeLockExpiry = 0L;
    /** 页面加载计时（onPageStarted -> onPageFinished）。 */
    private long pageStartMs = 0L;
    private String pageUrl = EMPTY;
    /** 主文档最近一次地址（导航开始与结束各更新一次），供 JS 接口做来源校验。 */
    private volatile String lastMainUrl = EMPTY;
    /** 本进程内累计的页面加载/完成次数。两者持续增长 = 页面在反复重载（不是进程重启）。 */
    private int pageStarts = 0;
    private int pageFinishes = 0;

    /** 重载风暴检测：60 秒窗口内的加载次数。 */
    private long reloadWindowStart = 0L;
    private int reloadsInWindow = 0;
    /** 冷启动只报一次，避免每次重载都打一条单调上涨的假"冷启动"。 */
    private boolean firstFinishLogged = false;
    /** 进程启动时刻（elapsedRealtime），用于算"点击图标到页面可见"的真实冷启动耗时。 */
    private long processStartMs = 0L;

    /** jsDelivr 加速探测结果（毫秒，-1 表示失败），供菜单显示。 */
    private volatile long jsdOfficialMs = -1;
    private volatile long jsdMirrorMs = -1;
    /** 当前生效线路：关 / 探测中 / 官方 / 镜像。 */
    private volatile String jsdActiveLine = "关";

    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;

    private final Runnable heartbeatRunnable = new Runnable() {
        @Override
        public void run() {
            long t0 = System.nanoTime();
            injectScripts(MainActivity.this.webView);
            PerfMonitor.mark("heartbeat", System.nanoTime() - t0);
            if (MainActivity.this.handler != null) {
                MainActivity.this.handler.postDelayed(this, HEARTBEAT_INTERVAL_MS);
            }
        }
    };

    @Override
    protected void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        this.prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        reloadPrefs();
        PerfLog.init(this);
        PerfLog.setVerbose(this.prefs.getBoolean(PREF_VERBOSE_LOG, false));
        this.replyNotifier = new ReplyNotifier(this);
        try (InputStream in = getAssets().open("reply-notifications.js")) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int n;
            while ((n = in.read(buffer)) != -1) {
                out.write(buffer, 0, n);
            }
            this.replyScript = new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            PerfLog.w(TAG, "reply notification hook unavailable");
        }
        try {
            // 进程 fork 时刻：与 onPageFinished 相减得到真实冷启动耗时
            this.processStartMs = Process.getStartElapsedRealtime();
        } catch (Throwable ignored) {
        }
        PerfLog.i(TAG, "onCreate sdk=" + Build.VERSION.SDK_INT
                + " server=" + TextKit.shortUrl(this.serverUrl));
        // 与 WebView 初始化并行的 DNS 预热，省掉首个请求的解析等待
        warmUpDns(this.serverUrl);
        this.cacheManager = new CacheManager(this);
        this.cacheManager.setCredentialOrigin(this.serverUrl);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        applyKeepScreenOn(this.prefs.getBoolean(PREF_KEEP_SCREEN_ON, true));
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        }
        this.handler = new Handler();
        this.idleController = new IdleController(this, this.handler, new IdleController.Listener() {
            @Override public void onApplied(int mode) {
                applyFrameRateLimit(effectiveFrameRate());
            }
            @Override public void onUnlocked() {
                setIdleMode(IDLE_OFF);
                Toast.makeText(MainActivity.this, "已解除挂机模式", Toast.LENGTH_SHORT).show();
            }
        });
        createWebView();
        handleReplyIntent(getIntent());
        // jsDelivr 探测放到后台：未完成前镜像为 null，请求照常走官方，不阻塞首屏
        probeJsdAccel();
        this.handler.post(this.heartbeatRunnable);
        if (this.prefs.getBoolean(PREF_PERF_MONITOR, false)) {
            startPerfMonitor();
        }

        // 只在首次、且已经配好服务器时提示一次白名单；每次启动都跳系统设置页会打断使用，
        // 用户拒绝后反复跳更是纯骚扰。之后可从「调试与诊断」手动进入。
        if (!this.serverUrl.isEmpty()) {
            maybePromptBatteryOptimization();
        }
        startKeepAliveService();
    }

    /**
     * 电池优化白名单：把本应用排除出 Doze/后台限制，否则息屏后 WebView 的定时器会被冻结。
     * 只自动跳一次，跳转失败（部分 ROM 没有该页面）退回应用详情页。
     */
    private void maybePromptBatteryOptimization() {
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        if (powerManager == null || powerManager.isIgnoringBatteryOptimizations(getPackageName())) {
            return;
        }
        if (this.prefs.getBoolean(PREF_BATTERY_PROMPTED, false)) {
            return;
        }
        this.prefs.edit().putBoolean(PREF_BATTERY_PROMPTED, true).apply();
        this.handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!isFinishing() && !isDestroyed()) {
                    openBatteryOptimizationSettings();
                }
            }
        }, 1500L);
    }

    /** 打开系统的电池优化设置；失败时退回本应用详情页。 */
    private void openBatteryOptimizationSettings() {
        try {
            Intent intent = new Intent("android.settings.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS");
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception e) {
            try {
                Intent fallback = new Intent(
                        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                fallback.setData(Uri.parse("package:" + getPackageName()));
                startActivity(fallback);
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * 创建（或重建）WebView 并加载服务器地址。
     * 首次进入由 onCreate 调用；渲染进程被系统回收/崩溃后，
     * 由 onRenderProcessGone 的恢复路径再次调用重建（旧实例已销毁，不能复用）。
     */
    private void createWebView() {
        long t0 = System.nanoTime();
        WebView webView = new WebView(this);
        this.webView = webView;
        webView.setBackgroundColor(Color.parseColor("#18191c"));
        // 用 FrameLayout 承载 WebView：挂机黑屏遮罩要盖在它上面。
        // 重建 WebView 时容器一并重建（旧容器随 Activity 内容替换被丢弃）。
        FrameLayout container = new FrameLayout(this);
        container.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        buildIdleOverlay(container);
        setContentView(container);

        WebSettings settings = this.webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        // 配合 addJavascriptInterface 的防御：页面 JS 能拿到原生接口，
        // 就不能再让它顺手读到本地文件系统。默认值在新 WebView 上已关，这里显式钉死。
        try {
            settings.setAllowFileAccess(false);
            settings.setAllowContentAccess(false);
            settings.setAllowFileAccessFromFileURLs(false);
            settings.setAllowUniversalAccessFromFileURLs(false);
        } catch (Exception ignored) {
        }
        try {
            settings.setGeolocationEnabled(false);
        } catch (Exception ignored) {
        }
        // UA 只取一次存字段：后台网络线程不允许再碰 WebView
        // 版本号取自 BuildConfig，与 build.gradle 单一来源，避免写死漂移
        this.userAgent = settings.getUserAgentString()
                + " Tavernium/" + BuildConfig.VERSION_NAME;
        settings.setUserAgentString(this.userAgent);

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(this.webView, true);
        this.webView.addJavascriptInterface(new WebAppInterface(this), "TavernKeep");
        this.webView.setWebViewClient(new TavernWebViewClient());
        this.webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView webView2, ValueCallback<Uri[]> valueCallback,
                                             WebChromeClient.FileChooserParams fileChooserParams) {
                if (MainActivity.this.filePathCallback != null) {
                    MainActivity.this.filePathCallback.onReceiveValue(null);
                }
                MainActivity.this.filePathCallback = valueCallback;
                Intent intent = new Intent("android.intent.action.OPEN_DOCUMENT");
                intent.addCategory("android.intent.category.OPENABLE");
                intent.setType("*/*");
                try {
                    if (fileChooserParams.getMode() == WebChromeClient.FileChooserParams.MODE_OPEN) {
                        intent.putExtra("android.intent.extra.ALLOW_MULTIPLE", true);
                    }
                } catch (Exception unused) {
                }
                try {
                    MainActivity.this.startActivityForResult(intent, FILE_CHOOSER_REQUEST);
                    return true;
                } catch (Exception unused2) {
                    MainActivity.this.filePathCallback = null;
                    return false;
                }
            }

            @Override
            public void onShowCustomView(View view, WebChromeClient.CustomViewCallback callback) {
                MainActivity.this.showCustomView(view, callback);
            }

            @Override
            public void onHideCustomView() {
                MainActivity.this.hideCustomView();
            }

            /**
             * 页面脚本报错一律记录：SillyTavern 的"卡片空白""图标变方框"通常只在这里留下痕迹，
             * 之前挂在性能监控开关后面，等于默认把最关键的线索丢了。
             *
             * <p>噪音控制交给两处：warn 级仅在性能监控开启时记，error 级无条件记；
             * 数量上限由 {@link PerfLog} 的有界队列兜底，日志风暴不会拖垮进程。</p>
             */
            @Override
            public boolean onConsoleMessage(ConsoleMessage message) {
                if (message == null) {
                    return false;
                }
                ConsoleMessage.MessageLevel level = message.messageLevel();
                if (level == ConsoleMessage.MessageLevel.ERROR) {
                    PerfLog.w("WebConsole", message.message()
                            + " @" + message.sourceId() + ":" + message.lineNumber());
                } else if (level == ConsoleMessage.MessageLevel.WARNING
                        && MainActivity.this.prefs.getBoolean(PREF_PERF_MONITOR, false)) {
                    PerfLog.d("WebConsole", message.message()
                            + " @" + message.sourceId() + ":" + message.lineNumber());
                }
                return false;
            }
        });
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                WebView.class.getMethod("setRendererPriorityPolicy", Integer.TYPE, Boolean.TYPE)
                        .invoke(this.webView, 2, true);
            } catch (Exception unused) {
            }
        }
        this.webView.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent2, String contentDisposition,
                                        String mimeType, long contentLength) {
                MainActivity.this.downloadToLocal(url, contentDisposition, mimeType);
            }
        });

        // 重建后常亮标志要重新贴到新 WebView 上（窗口级标志跨重建仍然有效）
        applyKeepScreenOn(this.prefs.getBoolean(PREF_KEEP_SCREEN_ON, true));
        // 帧率上限：窗口首选刷新率 + 页面侧 rAF 节流（挂机模式会进一步压低）
        applyFrameRateLimit(effectiveFrameRate());
        // 挂机黑屏属于内容视图级状态，重建 WebView 后要重新贴一遍
        applyIdleMode(idleMode());
        // 内核信息（包名 + 版本）写进报告，便于判断 WebView 是否过旧
        PerfMonitor.setWebViewInfo(describeWebViewEngine());

        if (this.serverUrl.isEmpty()) {
            this.webView.loadUrl("about:blank");
            this.handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    MainActivity.this.showOnboardingDialog();
                }
            }, 400L);
        } else {
            // 预设 HTTP Basic Auth 凭据：让 WebView 第一次请求就带上 Authorization 头，
            // 不等服务器返回 401 再 proceed()。跨境链路下省一次完整 RTT（实测 ~2.3s）。
            // realm 传 null 匹配任意 realm（SillyTavern 用 "SillyTavern"，但写死会随版本漂移）。
            preSeedHttpAuth();
            loadServerPage(this.webView, this.serverUrl);
        }
        long cost = System.nanoTime() - t0;
        PerfMonitor.mark("createWebView", cost);
        PerfLog.i(TAG, "createWebView done in " + (cost / 1_000_000L) + "ms url="
                + TextKit.shortUrl(this.serverUrl));
    }

    /**
     * 预设 HTTP Basic Auth 凭据到 WebView。
     *
     * <p>不做这一步时，WebView 的第一次请求不带 Authorization 头，服务器返回 401 才触发
     * {@link WebViewClient#onReceivedHttpAuthRequest}，然后 proceed() 再发一次带凭据的请求
     * ——跨境链路下要等两次完整 RTT（实测 ~2.3s，20:20:21 那次冷启动慢的主因）。</p>
     *
     * <p>{@code setHttpAuthUsernamePassword(host, null, user, pass)} 把凭据按 host 维度
     * 预存到 WebView 的 auth cache，realm 传 null 表示"匹配任意 realm"，这样后续同一 host
     * 的请求第一次就带上 Authorization 头，省掉 401-重试的往返。不持久化，只在当前进程
     * 有效，与 {@link #authUser} 等 SharedPreferences 里的源数据互不干扰。</p>
     */
    private void preSeedHttpAuth() {
        String user = this.authUser;
        String pass = this.authPass;
        if (user == null || user.isEmpty() || this.serverUrl == null || this.serverUrl.isEmpty()) {
            return;
        }
        try {
            String host = CacheManager.hostOf(this.serverUrl);
            if (host.isEmpty()) {
                return;
            }
            this.webView.setHttpAuthUsernamePassword(host, null, user, pass == null ? "" : pass);
            // 只记 host，绝不记录凭据
            PerfLog.i(TAG, "preseed http auth for " + host);
            PerfMonitor.count("auth.preseed");
        } catch (Exception e) {
            PerfLog.w(TAG, "preseed http auth failed: " + e);
        }
    }

    /**
     * 拉取主文档并在 {@code <head>} 后插入 rAF hook 脚本。
     *
     * <p>为什么必须这么做：页面（SillyTavern 与多数扩展）会把
     * {@code requestAnimationFrame} 缓存成局部引用，只有让 hook 在<b>页面任何脚本之前</b>
     * 执行，节流才真正生效。原生 WebView 没有 document-start 注入 API
     * （{@code addDocumentStartJavaScript} 属于 androidx.webkit），
     * 所以这里自己取回 HTML、插入脚本，再以<b>明文</b>交还给 WebView
     * （WebView 不会对拦截响应应用 Content-Encoding，交回压缩字节会渲染成乱码）。</p>
     *
     * <p>只在帧率限制生效时才走这条路径；否则返回 null，交还 WebKit 走它自己的
     * 缓存与流式解析，零影响。</p>
     */
    private WebResourceResponse fetchInjectedDocument(String url) {
        if (!UrlKit.isSameOrigin(url, this.serverUrl)) {
            return null;
        }
        HttpURLConnection conn = null;
        long t0 = System.nanoTime();
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(20000);
            conn.setInstanceFollowRedirects(false);
            // 关键：不要压缩。WebView 对 shouldInterceptRequest 返回的响应
            // 不会应用 Content-Encoding 头，若交回 gzip 字节会被当成文本渲染成乱码。
            conn.setRequestProperty("Accept-Encoding", "identity");
            conn.setRequestProperty("User-Agent", this.userAgent);
            String user = this.authUser;
            if (user != null && !user.isEmpty()) {
                String basic = Base64.encodeToString(
                        (user + ":" + (this.authPass == null ? "" : this.authPass))
                                .getBytes("UTF-8"), 2);
                conn.setRequestProperty("Authorization", "Basic " + basic);
            }
            int code = conn.getResponseCode();
            if (code != 200) {
                PerfLog.d(TAG, "inject doc: HTTP " + code);
                return null;
            }
            String contentType = conn.getContentType();
            if (contentType == null || !contentType.toLowerCase(Locale.ROOT).contains("text/html")) {
                return null;
            }
            InputStream in = conn.getInputStream();
            String encoding = conn.getContentEncoding();
            if (encoding != null && encoding.toLowerCase(Locale.ROOT).contains("gzip")) {
                in = new java.util.zip.GZIPInputStream(in, 16384);
            }
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream(1 << 20);
            try {
                byte[] chunk = new byte[16384];
                int n;
                while ((n = in.read(chunk)) > 0) {
                    buf.write(chunk, 0, n);
                    if (buf.size() > 8 * 1024 * 1024) {
                        // 异常大的文档：放弃注入，交还 WebKit
                        return null;
                    }
                }
            } finally {
                try {
                    in.close();
                } catch (Exception ignored) {
                }
            }
            byte[] raw = buf.toByteArray();
            byte[] body = HtmlInjector.injectDocumentBytes(raw, contentType, frameRateScriptTag());
            if (body == null) {
                PerfLog.w(TAG, "inject doc: skipped (not html / empty)");
                return null;
            }
            String charset = HtmlInjector.charsetOf(contentType);
            PerfMonitor.count("html.inject");
            PerfMonitor.mark("html.inject", System.nanoTime() - t0);
            PerfLog.d(TAG, "inject doc ok " + raw.length + "B -> " + body.length + "B");
            // 明文交还：不加 Content-Encoding，WebView 会按 mimeType/charset 直接解析
            return CacheManager.withCors(new WebResourceResponse("text/html", charset,
                    new java.io.ByteArrayInputStream(body)));
        } catch (Exception e) {
            PerfLog.w(TAG, "inject doc failed: " + e);
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /** 请求是否与当前 SillyTavern 服务器同源（只对它做注入）。 */
    private boolean sameServerOrigin(String url) {
        return UrlKit.isSameOrigin(url, this.serverUrl);
    }

    /** 当前帧率对应的 &lt;script&gt; 标签（含 rAF hook 与节流间隔）。 */
    private String frameRateScriptTag() {
        return "<script>" + FRAME_RATE_HOOK_JS
                + "window.__tkMinFrameInterval=" + frameIntervalMs(effectiveFrameRate())
                + ";</script>";
    }

    /** 当前帧率对应的注入串（hook + 变量），低版本与兜底注入共用。 */
    private String frameRateJs() {
        int intervalMs = frameIntervalMs(effectiveFrameRate());
        if (intervalMs <= 0) {
            return "window.__tkMinFrameInterval=0;";
        }
        return FRAME_RATE_HOOK_JS + "window.__tkMinFrameInterval=" + intervalMs + ";";
    }

    /** 把两段注入脚本合并为一次 evaluateJavascript，减少跨进程 IPC。 */
    private void injectScripts(WebView target) {
        if (target == null) {
            return;
        }
        long t0 = System.nanoTime();
        try {
            String token = this.bridgeSession.tokenFor(target.getUrl(), this.serverUrl);
            if (token.isEmpty()) {
                return;
            }
            // 原生桥存在于所有 frame；只有同源顶层文档取得闭包内的本页凭证。
            StringBuilder js = new StringBuilder("(function(raw,token){")
                    .append("if(window.top!==window||location.origin!=='")
                    .append(UrlKit.originOf(this.serverUrl)).append("')return;")
                    .append("var bridge={noteNetActivity:function(){raw.noteNetActivity(token);},")
                    .append("saveBlob:function(n,m,b){raw.saveBlob(token,n,m,b);},")
                    .append("replyReady:function(e){raw.replyReady(token,e);}};")
                    .append(INJECT_JS);
            if (this.prefs.getBoolean(PREF_REPLY_NOTIFICATIONS, false)) {
                js.append(this.replyScript);
            }
            // 始终装 hook（幂等）：变量为 0 时原样透传。这样改帧率只需改变量，
            // 对页面已缓存的 rAF 引用同样生效。
            js.append(FRAME_RATE_HOOK_JS)
                    .append("window.__tkMinFrameInterval=")
                    .append(frameIntervalMs(effectiveFrameRate())).append(';');
            // 性能监控开启时把 JS 探针种子拼进同一次 IPC，不额外增加跨进程调用
            if (this.prefs.getBoolean(PREF_PERF_MONITOR, false)) {
                js.append(PERF_PROBE_SEED);
            }
            js.append("})(window.TavernKeep,'").append(token).append("');");
            target.evaluateJavascript(js.toString(), null);
        } catch (Exception e) {
            Log.w(TAG, "inject failed", e);
        }
        PerfMonitor.mark("injectScripts", System.nanoTime() - t0);
    }

    private void reloadPrefs() {
        if (this.prefs == null) {
            return;
        }
        this.authUser = this.prefs.getString(PREF_USER, EMPTY);
        this.authPass = this.prefs.getString(PREF_PASS, EMPTY);
        this.serverUrl = this.prefs.getString(PREF_SERVER_URL, DEFAULT_SERVER_URL);
        if (this.cacheManager != null) {
            this.cacheManager.setCredentialOrigin(this.serverUrl);
        }
    }

    /** 同时检查持久化的旧地址和新输入，避免只有设置界面做了限制。 */
    private void loadServerPage(WebView target, String url) {
        if (target == null) return;
        if (UrlKit.isAllowedServerUrl(url)) {
            target.loadUrl(url);
            return;
        }
        target.loadUrl("about:blank");
        Toast.makeText(this, SERVER_URL_HELP, Toast.LENGTH_LONG).show();
        if (this.handler != null) {
            this.handler.postDelayed(new Runnable() {
                @Override public void run() {
                    if (!isFinishing() && !isDestroyed()) showServerDialog();
                }
            }, 400L);
        }
    }

    /**
     * 屏幕常亮开关。旧代码只在 WebView 上生效，窗口的 FLAG_KEEP_SCREEN_ON 从未被清除，
     * 导致菜单里关掉"屏幕常亮"后屏幕依然不灭 —— 这是最直接的一处电量/CPU 浪费。
     */
    private void applyKeepScreenOn(boolean keepOn) {
        WebView wv = this.webView;
        if (wv != null) {
            wv.setKeepScreenOn(keepOn);
        }
        Window window = getWindow();
        if (window == null) {
            return;
        }
        if (keepOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    /** 当前 WebView 内核的包名与版本（API 26+ 提供查询）。 */
    private String describeWebViewEngine() {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                android.content.pm.PackageInfo pi = WebView.getCurrentWebViewPackage();
                if (pi != null) {
                    return pi.packageName + " " + pi.versionName;
                }
            }
        } catch (Exception ignored) {
        }
        return "unknown";
    }

    /** 帧率上限 -> rAF 最小帧间隔（毫秒）；0 表示不限制。 */
    private static int frameIntervalMs(int fps) {
        return fps > 0 ? 1000 / fps : 0;
    }

    /**
     * 当前屏幕刷新率（Hz）。取不到时按 60 兜底。
     *
     * <p>用于给帧率档位做"是否超过屏幕"的标注：60Hz 屏选 120fps 是无效设置，
     * 系统会把实际帧率钳回 60，但旧 UI 不提示，容易让人以为设了没用或设错了。</p>
     */
    private float refreshRateHz() {
        try {
            android.view.Display display = getWindowManager().getDefaultDisplay();
            float hz = display == null ? 0f : display.getRefreshRate();
            return hz > 1f ? hz : 60f;
        } catch (Exception e) {
            return 60f;
        }
    }

    /**
     * 生成档位标签：给超过屏幕刷新率的档位追加"不会生效"提示。
     * 保留该档位（不隐藏），否则跨设备迁移过来的旧设置会找不到当前值。
     */
    static String[] frameRateLabelsFor(float screenHz) {
        String[] out = new String[FRAME_RATE_LABELS.length];
        int screen = Math.round(screenHz > 1f ? screenHz : 60f);
        for (int i = 0; i < FRAME_RATE_LABELS.length; i++) {
            int fps = FRAME_RATE_OPTIONS[i];
            // 留 1Hz 容差：59.94Hz 之类的实测值不应把 60fps 档判成超屏
            if (fps > 0 && fps > screen + 1) {
                out[i] = FRAME_RATE_LABELS[i] + " · 屏幕仅 " + screen + "Hz";
            } else {
                out[i] = FRAME_RATE_LABELS[i];
            }
        }
        return out;
    }

    private static String frameRateLabel(int fps) {
        for (int i = 0; i < FRAME_RATE_OPTIONS.length; i++) {
            if (FRAME_RATE_OPTIONS[i] == fps) {
                return FRAME_RATE_LABELS[i];
            }
        }
        return fps <= 0 ? FRAME_RATE_LABELS[0] : fps + " fps（自定义）";
    }

    /**
     * 应用帧率上限，两边一起做：
     * <ol>
     *   <li>窗口首选刷新率（API 23+）：让系统挑匹配的显示模式，在 90/120Hz 屏上降低显示与合成功耗。
     *       注意系统帧率 API（Surface.setFrameRate / View.setRequestedFrameRate）只是"意向提示"，
     *       官方明确说明<b>不会产生帧率节流、不影响渲染管线</b>，因此不能指望它省 CPU；
     *       且它们分别是 API 30+/35+ 且需要 Surface，这里不用。</li>
     *   <li>页面侧 rAF 节流：真正减少每帧的 JS 回调、布局与绘制——这才是 CPU 大头，全版本生效。</li>
     * </ol>
     * 0 表示恢复系统默认（不节流）。改设置即时生效，无需重载页面。
     */
    private void applyFrameRateLimit(int fps) {
        PerfMonitor.setFrameRateLimit(fps);
        Window window = getWindow();
        if (window != null) {
            try {
                WindowManager.LayoutParams lp = window.getAttributes();
                lp.preferredRefreshRate = fps > 0 ? fps : 0.0f;
                window.setAttributes(lp);
            } catch (Exception ignored) {
            }
        }
        WebView wv = this.webView;
        if (wv == null) {
            return;
        }
        int intervalMs = frameIntervalMs(fps);
        try {
            if (intervalMs > 0) {
                // hook 是幂等的，已装过则只更新间隔变量，当场生效
                wv.evaluateJavascript(FRAME_RATE_HOOK_JS
                        + "window.__tkMinFrameInterval=" + intervalMs + ";", null);
            } else {
                wv.evaluateJavascript("window.__tkMinFrameInterval=0;", null);
            }
        } catch (Exception ignored) {
        }
    }

    // ---------------- 挂机模式（黑屏遮罩） ----------------

    /** 当前挂机模式：IDLE_OFF / IDLE_NORMAL / IDLE_BURNIN。 */
    private int idleMode() {
        return IdleController.readMode(this.prefs);
    }

    private boolean isIdleMode() {
        return idleMode() != IDLE_OFF;
    }

    /** 只有"防烧屏挂机"才用黑屏遮罩；普通挂机保持画面可见，仅锁帧。 */
    private boolean isBurnInIdle() {
        return idleMode() == IDLE_BURNIN;
    }

    /** 切换到指定挂机模式并立即生效。 */
    private void setIdleMode(int mode) {
        if (this.prefs != null) {
            this.prefs.edit().putInt(PREF_IDLE_MODE, mode).apply();
        }
        applyIdleMode(mode);
    }

    /** 实际生效的帧率上限：挂机时压到 {@link #IDLE_FPS}，但不会抬高用户原本更低的设置。 */
    int effectiveFrameRate() {
        return effectiveFrameRate(this.prefs == null ? 0 : this.prefs.getInt(PREF_FRAME_RATE, 0),
                isIdleMode());
    }

    /** 纯函数版本（便于单测）：base=用户设置，idle=挂机模式是否开启。 */
    static int effectiveFrameRate(int base, boolean idle) {
        if (!idle) {
            return base;
        }
        return base <= 0 ? IDLE_FPS : Math.min(base, IDLE_FPS);
    }

    /**
     * 构建黑屏遮罩，覆盖在 WebView 之上。
     *
     * <p>为什么用"黑屏"而不是降亮度/微移：OLED 屏上纯黑像素不发光，等于物理断电——
     * 省电与防烧屏一次到位，且不依赖系统亮度权限。挂机时同时把 WebView 设为
     * INVISIBLE，渲染进程不再合成帧（画面没人看），CPU/GPU 才真正降下来。</p>
     *
     * <p>提示计时和上滑手势由 IdleController 处理，Activity 负责生命周期转发。</p>
     */
    /**
     * 构建黑屏遮罩（挂在 WebView 之上）。
     *
     * <p>进入后<b>立即全黑、零像素发光</b>：OLED 上黑像素不发光，等于物理断电，
     * 既省电又彻底不烧屏。提示文字只在触摸时亮起，5 秒后自动熄灭——
     * 长期挂机时屏幕上没有任何一帧画面，也不会有固定位置长期点亮。</p>
     *
     * <p>解锁参考 iQOO 游戏空间：按住屏幕<b>向上滑</b>过阈值即解除，无需先点击。
     * 阈值取屏高 22%（不低于 200dp），单点或小幅滑动不会误触。</p>
     */
    private void buildIdleOverlay(FrameLayout container) {
        if (this.idleController != null) this.idleController.buildIdleOverlay(container);
    }

    private void recalcIdleUnlockDistance() {
        if (this.idleController != null) this.idleController.recalcIdleUnlockDistance();
    }

    private void applyIdleMode(int mode) {
        if (this.idleController != null) this.idleController.applyIdleMode(mode, this.webView);
    }

    private void showSlowHostDialog() {
        CacheManager cm = this.cacheManager;
        String[] hosts = cm == null ? new String[0] : cm.slowHostList();
        StringBuilder msg = new StringBuilder();
        if (hosts.length == 0) {
            msg.append("当前没有记录。\n\n规则：某个域名预取时超时/连不上，")
                    .append("或者「文件很小却耗时超过 4 秒」，就会被记入名单；")
                    .append("此后 ").append(CacheManager.slowHostTimeoutMinutes())
                    .append(" 分钟内对它的请求直接快速失败，不再让页面干等 TCP 超时。");
        } else {
            msg.append("以下域名被判定为不可达，其子资源请求会被立刻置空：\n\n");
            for (String h : hosts) {
                msg.append("  • ").append(h).append('\n');
            }
            msg.append("\n换了网络环境（切换 Wi-Fi / 开关代理）后建议清空。");
        }
        new AlertDialog.Builder(this)
                .setTitle("不可达域名（" + hosts.length + "）")
                .setMessage(msg.toString())
                .setPositiveButton(hosts.length > 0 ? "清空名单" : "知道了",
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                if (MainActivity.this.cacheManager != null) {
                                    MainActivity.this.cacheManager.clearSlowHosts();
                                }
                                Toast.makeText(MainActivity.this, "已清空不可达域名名单",
                                        Toast.LENGTH_SHORT).show();
                            }
                        })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showFrameRateDialog() {
        final int current = this.prefs.getInt(PREF_FRAME_RATE, 0);
        final String customTag = "自定义…";
        final float screenHz = refreshRateHz();
        int screen = Math.round(screenHz);
        // 动态标签：超过屏幕刷新率的档位标注"屏幕仅 xxHz"，避免误以为设置无效
        String[] base = frameRateLabelsFor(screenHz);
        String[] labels = new String[base.length + 1];
        for (int i = 0; i < base.length; i++) {
            labels[i] = base[i] + (FRAME_RATE_OPTIONS[i] == current ? "  ✓当前" : "");
        }
        // 当前值若高于屏幕刷新率，额外提示一次：实际被系统钳到屏幕上限
        boolean overScreen = current > screen + 1;
        boolean customActive = isCustomFrameRate(current);
        labels[labels.length - 1] = customActive
                ? customTag + "（当前 " + current + " fps）" : customTag;

        // 修复：setMessage 太长会把 setItems 的列表区域挤没，只剩"取消"按钮。
        // 改用自定义 View：标题 + 说明 + 选项列表放在一个垂直 LinearLayout 里，
        // 选项用 ListView 承载，确保所有档位都可见可点。
        float density = getResources().getDisplayMetrics().density;
        int pad = (int) (20 * density);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, 0, pad, 0);

        TextView desc = new TextView(this);
        String descText = "降低帧率可减少每帧的 JS/布局/绘制开销，是最直接的省电手段。"
                + "20-30 fps 适合挂机，60 fps 以上适合阅读动画较多的场景。";
        if (overScreen) {
            descText += "\n\n注意：当前设置 " + current + " fps 高于本机屏幕 " + screen
                    + "Hz，实际会被系统钳制到 " + screen + " fps。";
        }
        desc.setText(descText);
        desc.setTextSize(14);
        desc.setLineSpacing(4 * density, 1.0f);
        root.addView(desc);

        final ListView listView = new ListView(this);
        listView.setAdapter(new ArrayAdapter<String>(this,
                android.R.layout.simple_list_item_1, labels));
        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int which, long id) {
                if (which == labels.length - 1) {
                    showFrameRateCustomDialog(current);
                    return;
                }
                applyFrameRateChoice(FRAME_RATE_OPTIONS[which]);
            }
        });
        // 限制 ListView 高度，避免撑爆对话框；8 个选项 × 48dp ≈ 384dp
        int maxListHeight = (int) (380 * density);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                Math.min(maxListHeight, (int) (380 * density)));
        listView.setLayoutParams(lp);
        root.addView(listView);

        new AlertDialog.Builder(this)
                .setTitle("渲染帧率上限")
                .setView(root)
                .setNegativeButton("取消", null)
                .show();
    }

    /** 当前值是否落在预设档位之外（即自定义）。 */
    private static boolean isCustomFrameRate(int fps) {
        if (fps <= 0) {
            return false;
        }
        for (int option : FRAME_RATE_OPTIONS) {
            if (option == fps) {
                return false;
            }
        }
        return true;
    }

    private void showFrameRateCustomDialog(int current) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (getResources().getDisplayMetrics().density * 20.0f);
        layout.setPadding(pad, pad / 2, pad, 0);

        final EditText input = new EditText(this);
        input.setHint(FRAME_RATE_MIN + " - " + FRAME_RATE_MAX);
        input.setSingleLine(true);
        input.setInputType(2); // TYPE_CLASS_NUMBER
        if (current > 0) {
            input.setText(String.valueOf(current));
        }
        layout.addView(input);

        new AlertDialog.Builder(this)
                .setTitle("自定义帧率上限")
                .setMessage("输入 " + FRAME_RATE_MIN + " - " + FRAME_RATE_MAX
                        + " 之间的数值（fps）。数值越低越省电。")
                .setView(layout)
                .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        int fps;
                        try {
                            fps = Integer.parseInt(input.getText().toString().trim());
                        } catch (NumberFormatException e) {
                            fps = -1;
                        }
                        if (fps < FRAME_RATE_MIN || fps > FRAME_RATE_MAX) {
                            Toast.makeText(MainActivity.this,
                                    "请输入 " + FRAME_RATE_MIN + " - " + FRAME_RATE_MAX
                                            + " 之间的数值", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        applyFrameRateChoice(fps);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 保存并即时应用帧率上限（0 = 系统默认）。挂机模式下实际值会被再压一档。 */
    private void applyFrameRateChoice(int fps) {
        this.prefs.edit().putInt(PREF_FRAME_RATE, fps).apply();
        int effective = effectiveFrameRate();
        applyFrameRateLimit(effective);
        String extra = effective != fps ? "（挂机模式限制中）" : "";
        if (effective > 0 && !this.rafHookInstalled) {
            // 页面加载时没走注入，hook 还没装：改变量不会生效，需要重新加载一次
            extra += "；请点「重新加载页面」后生效";
        }
        Toast.makeText(this, "渲染帧率：" + frameRateLabel(effective) + extra,
                Toast.LENGTH_LONG).show();
    }

    private class TavernWebViewClient extends WebViewClient {
        /**
         * 站内导航留在 WebView，站外链接交给系统浏览器。
         *
         * <p>此前一律 return false：等同于任何页面（广告跳转、被投毒的扩展外链、
         * 聊天里点到的恶意地址）都能在这个挂着 JS 接口的 WebView 里执行脚本。
         * 现在只放行配置的服务器来源，其余一律外抛，非 http(s) 协议同样交给系统。</p>
         */
        @Override
        public boolean shouldOverrideUrlLoading(WebView webView, WebResourceRequest request) {
            // 只拦主框架导航：跨域 iframe（视频嵌入、扩展面板）必须留在 WebView 里，
            // 一律外抛会把这些功能直接废掉。
            if (request == null || !request.isForMainFrame()) {
                return false;
            }
            Uri uri = request.getUrl();
            if (uri == null || uri.getScheme() == null) {
                return false;
            }
            String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
            if (("http".equals(scheme) || "https".equals(scheme))
                    && sameServerOrigin(uri.toString())) {
                return false;
            }
            return openExternally(uri);
        }

        /** 用系统浏览器/对应应用打开链接，返回 true 表示这次导航已被消费。 */
        private boolean openExternally(Uri uri) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, uri));
            } catch (Exception e) {
                PerfLog.w(TAG, "cannot open external url: " + TextKit.shortUrl(uri.toString()));
                Toast.makeText(MainActivity.this, "没有可以打开该链接的应用",
                        Toast.LENGTH_SHORT).show();
            }
            return true;
        }

        @Override
        public void onPageStarted(WebView webView, String str, Bitmap bitmap) {
            MainActivity.this.authRetried = false;
            MainActivity.this.bridgeSession.beginDocument(str, MainActivity.this.serverUrl);
            if (MainActivity.this.replyNotifier != null) {
                MainActivity.this.replyNotifier.resetDocument();
            }
            // 低版本没有文档开始注入：在导航刚开始时补一次，尽量抢在页面脚本之前
            if (Build.VERSION.SDK_INT < 33) {
                try {
                    webView.evaluateJavascript(MainActivity.this.frameRateJs(), null);
                } catch (Exception ignored) {
                }
            }
            MainActivity.this.pageStartMs = SystemClock.elapsedRealtime();
            MainActivity.this.pageUrl = str == null ? EMPTY : str;
            MainActivity.this.lastMainUrl = MainActivity.this.pageUrl;
            MainActivity.this.pageStarts++;
            // 60 秒窗口内统计加载次数：正常使用是 1，循环重载会持续增长
            long now = MainActivity.this.pageStartMs;
            if (MainActivity.this.reloadWindowStart == 0L
                    || now - MainActivity.this.reloadWindowStart > 60000L) {
                MainActivity.this.reloadWindowStart = now;
                MainActivity.this.reloadsInWindow = 1;
            } else {
                MainActivity.this.reloadsInWindow++;
                int n = MainActivity.this.reloadsInWindow;
                if (n == 3 || n == 5 || n % 10 == 0) {
                    PerfLog.w(TAG, "page reload storm: " + n + " loads in "
                            + ((now - MainActivity.this.reloadWindowStart) / 1000L)
                            + "s (process up "
                            + ((MainActivity.this.processStartMs > 0L
                                    ? now - MainActivity.this.processStartMs : 0L) / 1000L)
                            + "s), url=" + TextKit.shortUrl(str));
                }
            }
            PerfMonitor.setPageCounts(MainActivity.this.pageStarts, MainActivity.this.pageFinishes);
            PerfLog.d(TAG, "page start: " + TextKit.shortUrl(str));
            injectScripts(webView);
        }

        @Override
        public void onPageFinished(WebView webView, String str) {
            // 重定向后 onPageStarted 的地址可能已过期，用"页面实际停在哪"覆盖一次
            try {
                String actual = webView.getUrl();
                MainActivity.this.lastMainUrl = actual == null ? EMPTY : actual;
            } catch (Exception ignored) {
            }
            // 确认 hook 真的进了页面：读一次标志位，结果进性能报告
            try {
                webView.evaluateJavascript(
                        "(function(){try{return !!window.__tkRafHooked;}catch(e){return false;}})()",
                        new ValueCallback<String>() {
                            @Override
                            public void onReceiveValue(String value) {
                                boolean on = "true".equals(value);
                                MainActivity.this.rafHookInstalled = on;
                                PerfMonitor.setRafHook(on);
                            }
                        });
            } catch (Exception ignored) {
            }
            // 顺带判断站点用的是哪种登录：只影响菜单文案，不参与认证决策
            try {
                webView.evaluateJavascript(
                        "(function(){try{return !!(document.querySelector('input[type=password]')"
                                + "||document.querySelector('#login_button,#login_form')"
                                + "||/\\/login\\b/.test(location.pathname));}catch(e){return false;}})()",
                        new ValueCallback<String>() {
                            @Override
                            public void onReceiveValue(String value) {
                                boolean native_ = "true".equals(value);
                                MainActivity.this.loginModeNative = native_;
                                if (!MainActivity.this.loginModeProbed) {
                                    MainActivity.this.loginModeProbed = true;
                                    PerfLog.i(TAG, "login mode detected: "
                                            + (native_ ? "sillytavern-account" : "basic-or-none"));
                                }
                            }
                        });
            } catch (Exception ignored) {
            }
            long start = MainActivity.this.pageStartMs;
            long cost = start > 0L ? SystemClock.elapsedRealtime() - start : -1L;
            MainActivity.this.pageStartMs = 0L;
            MainActivity.this.pageFinishes++;
            long ps = MainActivity.this.processStartMs;
            long upMs = ps > 0L ? SystemClock.elapsedRealtime() - ps : -1L;
            if (!MainActivity.this.firstFinishLogged) {
                // 真正的冷启动：只有本进程第一次加载完成才算
                MainActivity.this.firstFinishLogged = true;
                PerfLog.i(TAG, "page finish: " + TextKit.shortUrl(str) + " in " + cost + "ms");
                if (upMs > 0L) {
                    PerfLog.i(TAG, "cold start: " + upMs
                            + "ms (process start -> first page finish)");
                }
            } else {
                // 之后的每次完成都是重载：报单次耗时 + 本次是第几次 + 进程已运行多久，
                // 不再打印单调上涨的"cold start"，那会让人误判成反复冷启动。
                PerfLog.i(TAG, "page finish #" + MainActivity.this.pageFinishes
                        + " in " + cost + "ms (reload, process up " + upMs + "ms)");
            }
            PerfMonitor.setPageCounts(MainActivity.this.pageStarts, MainActivity.this.pageFinishes);
            injectScripts(webView);
        }

        @Override
        public void onReceivedError(WebView webView, WebResourceRequest request,
                                    WebResourceError error) {
            boolean main = request != null && request.isForMainFrame();
            String desc = error == null ? "?" : (error.getErrorCode() + " " + error.getDescription());
            String url = request == null ? "?" : String.valueOf(request.getUrl());
            if (main) {
                PerfLog.w(TAG, "page error: " + desc + " url=" + TextKit.shortUrl(url));
            } else {
                PerfLog.d(TAG, "resource error: " + desc + " url=" + TextKit.shortUrl(url));
            }
        }

        @Override
        public void onReceivedHttpError(WebView webView, WebResourceRequest request,
                                        WebResourceResponse errorResponse) {
            int code = errorResponse == null ? -1 : errorResponse.getStatusCode();
            String url = request == null ? "?" : String.valueOf(request.getUrl());
            if (code >= 400) {
                PerfLog.d(TAG, "http " + code + " " + TextKit.shortUrl(url));
            }
        }

        @Override
        public void onReceivedHttpAuthRequest(WebView webView, HttpAuthHandler httpAuthHandler,
                                              String str, String str2) {
            String expectedHost = UrlKit.hostOf(MainActivity.this.serverUrl);
            String challengedHost = str == null ? EMPTY : str;
            if (expectedHost.startsWith("[") && expectedHost.endsWith("]")) {
                expectedHost = expectedHost.substring(1, expectedHost.length() - 1);
            }
            if (challengedHost.startsWith("[") && challengedHost.endsWith("]")) {
                challengedHost = challengedHost.substring(1, challengedHost.length() - 1);
            }
            String currentPage = webView == null ? null : webView.getUrl();
            if (expectedHost.isEmpty() || !expectedHost.equalsIgnoreCase(challengedHost)
                    || (currentPage != null && !currentPage.isEmpty()
                    && !"about:blank".equals(currentPage)
                    && !UrlKit.isTrustedMainUrl(currentPage, MainActivity.this.serverUrl))) {
                httpAuthHandler.cancel();
                PerfLog.w(TAG, "http auth rejected outside configured server");
                return;
            }
            String user = MainActivity.this.authUser;
            if (!user.isEmpty() && !MainActivity.this.authRetried) {
                MainActivity.this.authRetried = true;
                // 只记 host/realm，绝不记录凭据
                PerfLog.i(TAG, "http auth: retry with saved user for " + str + " realm=" + str2);
                httpAuthHandler.proceed(user, MainActivity.this.authPass);
            } else {
                PerfLog.w(TAG, "http auth: failed for " + str + " realm=" + str2
                        + " (hasSaved=" + !user.isEmpty() + ")");
                httpAuthHandler.cancel();
                MainActivity.this.showAuthDialog(!user.isEmpty());
            }
        }

        /**
         * 旧实现在这里对每个请求 new Thread 抓全量内容再整包读进内存，等于把 WebKit
         * 网络栈的活重做一遍（双倍流量/解码/内存），且线程数不可控。
         * 现在：命中磁盘缓存直接流式返回；未命中只提交一次有界线程池的后台预取，
         * 当前请求交给 WebKit 自己加载，方法立刻返回。
         */
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView webView,
                                                          WebResourceRequest request) {
            if (request != null && request.getUrl() != null
                    && UrlKit.isBlockedPublicHttp(request.getUrl().toString())) {
                PerfMonitor.count("net.cleartext.blocked");
                return new WebResourceResponse("text/plain", "UTF-8", 403, "HTTPS Required",
                        java.util.Collections.<String, String>emptyMap(),
                        new ByteArrayInputStream(SERVER_URL_HELP.getBytes(StandardCharsets.UTF_8)));
            }
            CacheManager cm = MainActivity.this.cacheManager;
            if (cm == null) {
                return null;
            }
            // 热路径组合入口：一次 URL 解析 + 一次 MD5 + 一次 stat，
            // 同时完成命中判断与预取触发（替代旧 getCached + prefetch 两条路径，
            // 后者要重复解析 4~5 次 URL、算 3 次 MD5）。方法本身在 WebView 网络线程上
            // 执行，每个子资源都会走到，首屏累计可达上百次。
            long t0 = System.nanoTime();
            // 帧率限制生效时：主文档自己取回、在 <head> 后插入 rAF hook 再交还。
            // 这是唯一能保证 hook 早于页面脚本执行的办法（原生 WebView 无 document-start 注入）。
            if (!injectDisabled
                    && request.isForMainFrame()
                    && frameIntervalMs(effectiveFrameRate()) > 0
                    && sameServerOrigin(request.getUrl().toString())) {
                WebResourceResponse injected =
                        fetchInjectedDocument(request.getUrl().toString());
                if (injected != null) {
                    injectFailStreak = 0;
                    PerfMonitor.mark("intercept.total", System.nanoTime() - t0);
                    return injected;
                }
                if (++injectFailStreak >= INJECT_FAIL_LIMIT) {
                    injectDisabled = true;
                    PerfLog.w(TAG, "inject disabled for this session after "
                            + injectFailStreak + " failures");
                    PerfMonitor.count("html.inject.disabled");
                }
            }
            // 空 src 的 <img> 会被浏览器解析成当前页面地址（首页 HTML），
            // SillyTavern 的头像/表情/裁剪弹窗都这么写，实测每 2 秒白下一遍 734KB。
            // 用 Accept 头识别「要的是图片」，连同 URL 一起交给 CacheManager 拦截。
            Map<String, String> hdrs = request.getRequestHeaders();
            String accept = hdrs == null ? null : hdrs.get("Accept");
            boolean wantsImage = accept != null && accept.startsWith("image/");
            WebResourceResponse resp = cm.intercept(request.getUrl().toString(),
                    MainActivity.this.userAgent,
                    MainActivity.this.authUser, MainActivity.this.authPass,
                    "GET".equalsIgnoreCase(request.getMethod()), wantsImage);
            PerfMonitor.mark("intercept.total", System.nanoTime() - t0);
            return resp;
        }

        /**
         * 渲染进程被系统回收（低内存）或崩溃。按官方规范：
         * 返回 false 应用会被系统杀掉；死掉的渲染进程对应的 WebView 实例不能复用，
         * 必须销毁后新建。这里前台当场重建（用户几乎无感），后台则置位等 onStart 再建。
         */
        @Override
        public boolean onRenderProcessGone(WebView webView, RenderProcessGoneDetail detail) {
            MainActivity.this.bridgeSession.clear();
            boolean crashed = detail != null && detail.didCrash();
            Log.w(TAG, "render process gone, didCrash=" + crashed);
            PerfLog.w(TAG, "render process gone, didCrash=" + crashed
                    + ", foreground=" + MainActivity.this.activityResumed);
            if (MainActivity.this.webView == webView) {
                MainActivity.this.webView = null;
            }
            try {
                webView.setDownloadListener(null);
            } catch (Exception ignored) {
            }
            try {
                webView.removeJavascriptInterface("TavernKeep");
            } catch (Exception ignored) {
            }
            ViewGroup parent = (ViewGroup) webView.getParent();
            if (parent != null) {
                parent.removeView(webView);
            }
            try {
                webView.destroy();
            } catch (Exception ignored) {
            }
            if (MainActivity.this.activityResumed) {
                MainActivity.this.createWebView();
            } else {
                MainActivity.this.pendingWebViewRecovery = true;
            }
            return true;
        }
    }

    private String getServerUrl() {
        String url = this.serverUrl;
        if (url.isEmpty()) {
            return DEFAULT_SERVER_URL;
        }
        return url.endsWith("/") ? url : url + "/";
    }

    private String getAuthUser() {
        return this.authUser;
    }

    private String getAuthPass() {
        return this.authPass;
    }

    private void showAuthDialog(boolean wrongCredential) {
        if (isFinishing() || isDestroyed() || this.authDialogShowing) {
            return;
        }
        this.authDialogShowing = true;
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (getResources().getDisplayMetrics().density * 20.0f);
        layout.setPadding(pad, pad / 2, pad, 0);

        final EditText userInput = new EditText(this);
        userInput.setHint("用户名");
        userInput.setSingleLine(true);
        userInput.setText(this.authUser);
        layout.addView(userInput);

        final EditText passInput = new EditText(this);
        passInput.setHint("密码");
        passInput.setSingleLine(true);
        passInput.setInputType(129);
        passInput.setText(this.authPass);
        layout.addView(passInput);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(wrongCredential ? "账号或密码错误，请重新输入" : "登录 SillyTavern")
                .setMessage("请输入服务器 basicAuth 的账号和密码")
                .setView(layout)
                .setCancelable(false)
                .setPositiveButton("登录", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialogInterface, int i) {
                        String user = userInput.getText().toString().trim();
                        MainActivity.this.prefs.edit()
                                .putString(PREF_USER, user)
                                .putString(PREF_PASS, passInput.getText().toString())
                                .apply();
                        MainActivity.this.reloadPrefs();
                        MainActivity.this.authRetried = false;
                        // WebView 内部会缓存 HTTP 认证凭据，而清除它的公开 API
                        // （WebViewDatabase.clearHttpAuthUsernamePassword）在 API 35 已移除。
                        // 这里用空值覆盖该 host 的缓存；preSeedHttpAuth 随后会写入新凭据。
                        try {
                            WebView wv0 = MainActivity.this.webView;
                            String h0 = CacheManager.hostOf(MainActivity.this.serverUrl);
                            if (wv0 != null && !h0.isEmpty()) {
                                wv0.setHttpAuthUsernamePassword(h0, null, "", "");
                            }
                        } catch (Exception unused) {
                        }
                        WebView wv = MainActivity.this.webView;
                        if (wv != null) {
                            String url = MainActivity.this.getServerUrl();
                            if (!url.isEmpty()) {
                                loadServerPage(wv, url);
                            } else {
                                wv.reload();
                            }
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .create();
        dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface dialogInterface) {
                MainActivity.this.authDialogShowing = false;
            }
        });
        dialog.show();
    }

    /**
     * 首次启动引导：一页填完服务器地址与可选凭据。
     *
     * <p>原来是一串裸对话框（先问地址、失败再问账号密码），新用户第一眼看到
     * 一个空输入框，不知道这是什么应用、也不知道要填什么。这里把"这是什么东西、
     * 你需要准备什么、哪些可以不填"一次讲完，减少第一屏的困惑。</p>
     */
    private void showOnboardingDialog() {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        float density = getResources().getDisplayMetrics().density;
        int pad = (int) (20 * density);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad / 2, pad, 0);

        TextView intro = new TextView(this);
        intro.setText("这是 Tavernium —— SillyTavern 的 Android 客户端。\n\n"
                + "它本身不含任何内容，只是把你已经搭好的 SillyTavern "
                + "在手机上跑得更顺：缓存加速、弱网跳过不可达域名、帧率控制、挂机黑屏。\n\n"
                + "所以第一步，需要一个能访问的服务器地址。");
        intro.setTextSize(13);
        intro.setLineSpacing(3 * density, 1.0f);
        root.addView(intro);

        TextView labelServer = new TextView(this);
        labelServer.setText("服务器地址（必填）");
        labelServer.setTextSize(13);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) (12 * density);
        labelServer.setLayoutParams(lp);
        root.addView(labelServer);

        final EditText inputServer = new EditText(this);
        inputServer.setHint("https://your-server.com/");
        inputServer.setText(this.serverUrl);
        inputServer.setSingleLine(true);
        root.addView(inputServer);

        TextView labelUser = new TextView(this);
        labelUser.setText("用户名（可选）");
        labelUser.setTextSize(13);
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp2.topMargin = (int) (10 * density);
        labelUser.setLayoutParams(lp2);
        root.addView(labelUser);

        final EditText inputUser = new EditText(this);
        inputUser.setHint("打开网址时浏览器先弹系统认证框，才需要填");
        inputUser.setText(this.authUser);
        inputUser.setSingleLine(true);
        root.addView(inputUser);

        TextView labelPass = new TextView(this);
        labelPass.setText("密码（可选）");
        labelPass.setTextSize(13);
        LinearLayout.LayoutParams lp3 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp3.topMargin = (int) (10 * density);
        labelPass.setLayoutParams(lp3);
        root.addView(labelPass);

        final EditText inputPass = new EditText(this);
        inputPass.setHint("与上面的用户名配对");
        inputPass.setText(this.authPass);
        inputPass.setSingleLine(true);
        inputPass.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        root.addView(inputPass);

        TextView note = new TextView(this);
        note.setText("账号密码会保存在本机，只用于访问你自己的服务器。\n\n"
                + "如果进页面后看到的是 SillyTavern 自己的登录界面，上面两栏留空即可——"
                + "直接在里面登录，App 会自动记住。\n\n"
                + SERVER_URL_HELP + "\n\n"
                + "填错或以后要改，可在菜单里「重新输入账号密码」或「退出登录」。");
        note.setTextSize(12);
        note.setLineSpacing(2 * density, 1.0f);
        LinearLayout.LayoutParams lp4 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp4.topMargin = (int) (12 * density);
        note.setLayoutParams(lp4);
        root.addView(note);

        new AlertDialog.Builder(this)
                .setTitle("开始使用")
                .setView(root)
                .setCancelable(false)
                .setPositiveButton("开始使用", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String url = inputServer.getText().toString().trim();
                        if (!url.startsWith("http://") && !url.startsWith("https://")) {
                            Toast.makeText(MainActivity.this,
                                    "地址要以 http:// 或 https:// 开头",
                                    Toast.LENGTH_LONG).show();
                            showOnboardingDialog();
                            return;
                        }
                        if (!UrlKit.isAllowedServerUrl(url)) {
                            Toast.makeText(MainActivity.this, SERVER_URL_HELP, Toast.LENGTH_LONG).show();
                            showOnboardingDialog();
                            return;
                        }
                        if (!url.endsWith("/")) {
                            url = url + "/";
                        }
                        MainActivity.this.prefs.edit()
                                .putString(PREF_SERVER_URL, url)
                                .putString(PREF_USER, inputUser.getText().toString().trim())
                                .putString(PREF_PASS, inputPass.getText().toString())
                                .apply();
                        MainActivity.this.reloadPrefs();
                        WebView wv = MainActivity.this.webView;
                        if (wv != null) {
                            loadServerPage(wv, url);
                        }
                        // 配置完成后再提白名单：首次安装时打断引导没有意义
                        MainActivity.this.maybePromptBatteryOptimization();
                    }
                })
                .show();
    }

    private void showServerDialog() {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (getResources().getDisplayMetrics().density * 20.0f);
        layout.setPadding(pad, pad / 2, pad, 0);

        final EditText input = new EditText(this);
        input.setHint("https://your-sillytavern-server.com/");
        input.setText(this.serverUrl);
        input.setSingleLine(true);
        layout.addView(input);

        new AlertDialog.Builder(this)
                .setTitle("设置 SillyTavern 服务器地址")
                .setMessage("输入你的 SillyTavern 网址，保存后自动加载。\n\n" + SERVER_URL_HELP)
                .setView(layout)
                .setCancelable(false)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialogInterface, int i) {
                        String url = input.getText().toString().trim();
                        if (!url.startsWith("http://") && !url.startsWith("https://")) {
                            Toast.makeText(MainActivity.this, "地址必须以 http:// 或 https:// 开头",
                                    Toast.LENGTH_SHORT).show();
                            showServerDialog();
                            return;
                        }
                        if (!UrlKit.isAllowedServerUrl(url)) {
                            Toast.makeText(MainActivity.this, SERVER_URL_HELP, Toast.LENGTH_LONG).show();
                            showServerDialog();
                            return;
                        }
                        if (!url.endsWith("/")) {
                            url = url + "/";
                        }
                        MainActivity.this.prefs.edit().putString(PREF_SERVER_URL, url).apply();
                        MainActivity.this.reloadPrefs();
                        WebView wv = MainActivity.this.webView;
                        if (wv != null) {
                            loadServerPage(wv, url);
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 退出登录：清 Cookie + 清 Basic 凭据 + 重载。
     *
     * <p>两种认证的"登出"动作不同——原生账号清 Cookie，Basic Auth 清凭据。
     * 用户不该被迫先判断自己是哪种，所以一个动作把两样都清掉，覆盖所有情况。</p>
     */
    private void doLogout() {
        try {
            android.webkit.CookieManager cm = android.webkit.CookieManager.getInstance();
            cm.removeAllCookies(null);
            cm.flush();
        } catch (Exception e) {
            PerfLog.w(TAG, "clear cookies failed: " + e);
        }
        this.prefs.edit().remove(PREF_USER).remove(PREF_PASS).apply();
        this.authUser = "";
        this.authPass = "";
        WebView wv = this.webView;
        if (wv != null) {
            // 同 showAuthDialog：清除 HTTP 认证缓存的 API 已被移除，改为置空覆盖。
            // 若该 WebView 版本忽略空值，最坏结果是仍带旧凭据——用户可用
            // 「重新输入账号密码」直接覆盖，不会卡死。
            try {
                String host = CacheManager.hostOf(this.serverUrl);
                if (!host.isEmpty()) {
                    wv.setHttpAuthUsernamePassword(host, null, "", "");
                }
            } catch (Exception ignored) {
            }
            String url = this.serverUrl;
            if (url != null && !url.isEmpty()) {
                loadServerPage(wv, url);
            }
        }
        this.loginModeNative = false;
        this.loginModeProbed = false;
        PerfLog.i(TAG, "logout: cookies + basic credentials cleared");
    }

    private void confirmLogout() {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("退出登录")
                .setMessage("会清除网页 Cookie 与本机保存的账号密码，然后重新加载页面。"
                        + "\n\n下次进入需要重新登录。")
                .setPositiveButton("退出", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        doLogout();
                        Toast.makeText(MainActivity.this, "已退出登录", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showMenuDialog() {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        boolean keepOn = this.prefs.getBoolean(PREF_KEEP_SCREEN_ON, true);
        boolean pauseBg = this.prefs.getBoolean(PREF_PAUSE_IN_BACKGROUND, false);
        int frameRate = this.prefs.getInt(PREF_FRAME_RATE, 0);
        int idleMode = idleMode();
        // 主菜单只留"日常会用到"的项；诊断/日志/网络排查收进二级菜单，
        // 避免一次列出十几条让常用项被淹没。
        String[] items = new String[]{
                "返回上一级",
                keepOn ? "屏幕常亮：开（点击关闭省电）" : "屏幕常亮：关（点击开启）",
                pauseBg ? "后台暂停网页：开（点击切回复旦）" : "后台暂停网页：关（点击开启省电）",
                "修改服务器地址",
                "重新输入账号密码",
                "退出登录" + (this.loginModeNative
                        ? "（SillyTavern 账号）" : "（Basic Auth / 无密码）"),
                "清空网页缓存",
                "重新加载页面",
                idleMode != IDLE_OFF
                        ? "渲染帧率：" + frameRateLabel(frameRate) + "（挂机中，实际 "
                        + frameRateLabel(effectiveFrameRate()) + "）"
                        : "渲染帧率：" + frameRateLabel(frameRate) + "（点击修改）",
                "挂机模式：" + idleModeLabel() + "（点击切换）",
                "jsDelivr 加速：" + jsdAccelLabel() + "（点击设置）",
                "更新渠道：" + UpdateManager.channelLabel(
                        UpdateManager.channelOf(this.prefs)) + "（点击切换）",
                "检查更新（当前 " + BuildConfig.VERSION_NAME + "）",
                "回复完成通知：" + replyNotificationLabel(),
                "调试与诊断…"
        };
        new AlertDialog.Builder(this)
                .setTitle("Tavernium")
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialogInterface, int i) {
                        onMenuItemSelected(i);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void onMenuItemSelected(int i) {
        WebView wv;
        switch (i) {
            case 0:
                wv = this.webView;
                if (wv != null && wv.canGoBack()) {
                    wv.goBack();
                } else {
                    Toast.makeText(this, "没有上一页了", Toast.LENGTH_SHORT).show();
                }
                return;
            case 1:
                boolean nowKeep = !this.prefs.getBoolean(PREF_KEEP_SCREEN_ON, true);
                this.prefs.edit().putBoolean(PREF_KEEP_SCREEN_ON, nowKeep).apply();
                applyKeepScreenOn(nowKeep);
                Toast.makeText(this, nowKeep ? "屏幕常亮已开启" : "屏幕常亮已关闭，省电模式",
                        Toast.LENGTH_SHORT).show();
                return;
            case 2:
                boolean nowPause = !this.prefs.getBoolean(PREF_PAUSE_IN_BACKGROUND, false);
                this.prefs.edit().putBoolean(PREF_PAUSE_IN_BACKGROUND, nowPause).apply();
                Toast.makeText(this, nowPause ? "后台将暂停网页定时器（最省电，但后台生成会暂停）"
                        : "后台保持网页运行（不影响后台生成）", Toast.LENGTH_SHORT).show();
                return;
            case 3:
                showServerDialog();
                return;
            case 4:
                showAuthDialog(false);
                return;
            case 5:
                confirmLogout();
                return;
            case 6:
                if (this.cacheManager != null) {
                    this.cacheManager.clearCache();
                }
                wv = this.webView;
                if (wv != null) {
                    wv.clearCache(true);
                    wv.clearHistory();
                }
                Toast.makeText(this, "缓存已清空", Toast.LENGTH_SHORT).show();
                return;
            case 7:
                wv = this.webView;
                if (wv != null) {
                    wv.reload();
                }
                return;
            case 8:
                showFrameRateDialog();
                return;
            case 9:
                showIdleModeDialog();
                return;
            case 10:
                showJsdAccelDialog();
                return;
            case 11:
                showUpdateChannelDialog();
                return;
            case 12:
                checkUpdates();
                return;
            case 13:
                showReplyNotificationDialog();
                return;
            case 14:
                showDebugMenu();
                return;
            default:
        }
    }

    private String replyNotificationLabel() {
        if (!this.prefs.getBoolean(PREF_REPLY_NOTIFICATIONS, false)) {
            return "关";
        }
        return this.replyNotifier != null && this.replyNotifier.permitted() ? "开" : "系统未允许";
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleReplyIntent(intent);
    }

    private void handleReplyIntent(Intent intent) {
        if (intent != null && ReplyNotifier.ACTION_OPEN_REPLY.equals(intent.getAction())) {
            setIdleMode(IDLE_OFF);
            if (this.replyNotifier != null) this.replyNotifier.clear();
        }
    }

    private void showReplyNotificationDialog() {
        final boolean enabled = this.prefs.getBoolean(PREF_REPLY_NOTIFICATIONS, false);
        new AlertDialog.Builder(this)
                .setTitle("回复完成通知")
                .setMessage("在后台、锁屏或防烧屏挂机时，收到角色回复后提醒您。\n\n"
                        + "通知只显示回复完成提示，不显示角色名和聊天内容。\n\n"
                        + "如果开启了「后台暂停网页」，生成和提醒也可能延后。"
                        + "通知需要酒馆页面仍在运行，并允许系统通知权限。")
                .setPositiveButton(enabled ? "关闭" : "开启", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        boolean turnOn = !enabled;
                        prefs.edit().putBoolean(PREF_REPLY_NOTIFICATIONS, turnOn).apply();
                        if (!turnOn) {
                            replyNotifier.clear();
                        } else {
                            injectScripts(webView);
                            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(ReplyNotifier.PERMISSION)
                                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                                requestPermissions(new String[]{ReplyNotifier.PERMISSION}, REPLY_PERMISSION_REQUEST);
                            } else if (!replyNotifier.permitted()) {
                                Toast.makeText(MainActivity.this, "请在系统设置中允许回复通知", Toast.LENGTH_LONG).show();
                            }
                        }
                    }
                })
                .setNeutralButton("系统通知设置", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        try {
                            startActivity(new Intent("android.settings.APP_NOTIFICATION_SETTINGS")
                                    .putExtra("android.provider.extra.APP_PACKAGE", getPackageName()));
                        } catch (RuntimeException e) {
                            Toast.makeText(MainActivity.this, "请在系统设置中管理 Tavernium 通知", Toast.LENGTH_LONG).show();
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REPLY_PERMISSION_REQUEST) {
            boolean granted = results.length > 0
                    && results[0] == android.content.pm.PackageManager.PERMISSION_GRANTED;
            Toast.makeText(this, granted ? "回复通知已开启"
                    : "系统未允许通知，您可以稍后在系统设置中开启", Toast.LENGTH_LONG).show();
        }
    }

    // ---------------- jsDelivr 加速 ----------------

    private String jsdAccelLabel() {
        if (this.prefs == null || !this.prefs.getBoolean(PREF_JSD_ACCEL, false)) {
            return "关";
        }
        return jsdActiveLine;
    }

    /** 首次启用要给风险确认；已启用则提供"重新探测/关闭"。 */
    private void showJsdAccelDialog() {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        boolean enabled = this.prefs.getBoolean(PREF_JSD_ACCEL, false);
        if (!enabled) {
            new AlertDialog.Builder(this)
                    .setTitle("启用 jsDelivr 加速？")
                    .setMessage("启用后，第三方扩展从 cdn.jsdelivr.net 拉取的 JS/CSS/字体等静态资源，"
                            + "会优先改从公益镜像 jsd.onmicrosoft.cn 获取。\n\n"
                            + "可能的风险：\n"
                            + "• 该镜像由第三方维护，可能随时关停、变慢或返回错误内容；\n"
                            + "• 无法保证每个文件与官方完全一致，理论上存在被篡改的风险；\n"
                            + "• 镜像方可以看到你请求了哪些前端资源（聊天内容不经过它）。\n\n"
                            + "已做的缓解：\n"
                            + "• 只对静态资源生效，API 与聊天数据不走镜像；\n"
                            + "• 镜像失败或变慢时自动回退官方；\n"
                            + "• 缓存仍按原始地址索引，切换线路不会污染缓存。\n\n"
                            + "以上风险由你自行承担。是否启用？")
                    .setPositiveButton("同意并启用", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            MainActivity.this.prefs.edit()
                                    .putBoolean(PREF_JSD_ACCEL, true).apply();
                            MainActivity.this.jsdActiveLine = "探测中";
                            probeJsdAccel();
                            Toast.makeText(MainActivity.this, "正在探测线路…", Toast.LENGTH_SHORT).show();
                        }
                    })
                    .setNegativeButton("取消", null)
                    .show();
            return;
        }
        String detail = "官方 cdn.jsdelivr.net：" + (jsdOfficialMs > 0 ? jsdOfficialMs + " ms" : "未测通")
                + "\n镜像 jsd.onmicrosoft.cn：" + (jsdMirrorMs > 0 ? jsdMirrorMs + " ms" : "未测通")
                + "\n当前线路：" + jsdActiveLine;
        new AlertDialog.Builder(this)
                .setTitle("jsDelivr 加速")
                .setMessage(detail + "\n\n镜像只快 30% 以上时才会启用，否则走官方。")
                .setPositiveButton("重新探测", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        MainActivity.this.jsdActiveLine = "探测中";
                        probeJsdAccel();
                    }
                })
                .setNeutralButton("关闭加速", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        MainActivity.this.prefs.edit()
                                .putBoolean(PREF_JSD_ACCEL, false).apply();
                        if (MainActivity.this.cacheManager != null) {
                            MainActivity.this.cacheManager.setMirrorBase(null);
                        }
                        MainActivity.this.jsdActiveLine = "关";
                        Toast.makeText(MainActivity.this, "已关闭 jsDelivr 加速",
                                Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("返回", null)
                .show();
    }

    /**
     * 启动时探测两条线路（各一次 GET），镜像快 30% 以上才启用。
     * 探测在后台线程，未完成前 mirrorBase 为 null —— 请求照常走官方，不会卡首屏。
     */
    private void probeJsdAccel() {
        final CacheManager cm = this.cacheManager;
        if (cm == null) {
            return;
        }
        if (this.prefs == null || !this.prefs.getBoolean(PREF_JSD_ACCEL, false)) {
            cm.setMirrorBase(null);
            this.jsdActiveLine = "关";
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                long official = probeTtfb("https://" + CacheManager.JSD_HOST + JSD_PROBE_PATH);
                long mirror = probeTtfb(JSD_MIRROR_BASE + JSD_PROBE_PATH);
                jsdOfficialMs = official;
                jsdMirrorMs = mirror;
                boolean useMirror = mirror > 0
                        && (official <= 0 || mirror * 10L < official * 7L);
                cm.setMirrorBase(useMirror ? JSD_MIRROR_BASE : null);
                jsdActiveLine = useMirror ? "镜像" : (official > 0 ? "官方" : "关闭");
                PerfLog.i(TAG, "jsd probe: official=" + official + "ms mirror=" + mirror
                        + "ms -> " + jsdActiveLine);
            }
        }, "tk-jsd-probe").start();
    }

    /** 测一次 TTFB（毫秒）；非 200 或异常返回 -1。 */
    private static long probeTtfb(String url) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            conn.setRequestProperty("User-Agent", "TavernKeep-probe");
            long t0 = System.nanoTime();
            int code = conn.getResponseCode();
            long ms = (System.nanoTime() - t0) / 1_000_000L;
            return code == 200 ? ms : -1;
        } catch (Exception e) {
            return -1;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /**
     * 普通挂机的烧屏风险提示。
     *
     * <p>1fps 意味着画面每秒只刷新一次——远看像静止，而**静止画面恰恰是 OLED
     * 最容易留下残影的状态**（状态栏、固定悬浮球、聊天区边界都会累积）。用户
     * 很容易以为"降帧=省电=安全"，所以第一次选择时必须说清楚。</p>
     */
    private void showNormalIdleWarning() {
        new AlertDialog.Builder(this)
                .setTitle("普通挂机不防烧屏")
                .setMessage("普通挂机只是把帧率降到 1fps，画面仍然显示。\n\n"
                        + "画面长时间静止，恰恰是 OLED 最容易留下残影的状态"
                        + "（状态栏、固定按钮、聊天区边界都会累积）。\n\n"
                        + "短时间离开没问题；要长时间挂机，建议用「防烧屏挂机」——"
                        + "它会把屏幕变成纯黑，像素不发光，既省电也不烧屏。")
                .setPositiveButton("仍用普通挂机", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        MainActivity.this.prefs.edit()
                                .putBoolean(PREF_NORMAL_IDLE_WARNED, true).apply();
                        setIdleMode(IDLE_NORMAL);
                        Toast.makeText(MainActivity.this, idleModeLabel(),
                                Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("改用防烧屏", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        MainActivity.this.prefs.edit()
                                .putBoolean(PREF_NORMAL_IDLE_WARNED, true).apply();
                        setIdleMode(IDLE_BURNIN);
                        Toast.makeText(MainActivity.this, idleModeLabel(),
                                Toast.LENGTH_SHORT).show();
                    }
                })
                .setNeutralButton("取消", null)
                .show();
    }

    private String idleModeLabel() {
        switch (idleMode()) {
            case IDLE_NORMAL:
                return "普通（1fps）";
            case IDLE_BURNIN:
                return "防烧屏（黑屏 1fps）";
            default:
                return "关";
        }
    }

    /**
     * 选择挂机模式。普通模式保持画面可见只锁 1fps；防烧屏模式额外套黑屏遮罩，
     * 需要上滑解除。两者帧率都是 1fps——页面每秒只重绘一帧，聊天生成仍正常进行。
     */
    private void showIdleModeDialog() {
        final int current = idleMode();
        String[] items = new String[]{
                (current == IDLE_OFF ? "✓ " : "") + "关闭（正常使用）",
                (current == IDLE_NORMAL ? "✓ " : "") + "普通挂机：画面保留，锁 1fps（不防烧屏）",
                (current == IDLE_BURNIN ? "✓ " : "") + "防烧屏挂机：黑屏零发光，锁 1fps"
        };
        new AlertDialog.Builder(this)
                .setTitle("挂机模式")
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (which == 1 && !MainActivity.this.prefs
                                .getBoolean(PREF_NORMAL_IDLE_WARNED, false)) {
                            // 第一次选普通挂机：讲清"静止画面反而更容易烧屏"
                            showNormalIdleWarning();
                            return;
                        }
                        int mode = which == 0 ? IDLE_OFF
                                : (which == 1 ? IDLE_NORMAL : IDLE_BURNIN);
                        setIdleMode(mode);
                        Toast.makeText(MainActivity.this, idleModeLabel(), Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 二级菜单：诊断、日志、网络排查。日常使用不需要进来。 */
    private void showDebugMenu() {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        boolean monitorOn = this.prefs.getBoolean(PREF_PERF_MONITOR, false);
        boolean verbose = this.prefs.getBoolean(PREF_VERBOSE_LOG, false);
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        boolean batteryWhitelisted = pm != null
                && pm.isIgnoringBatteryOptimizations(getPackageName());
        String[] items = new String[]{
                monitorOn ? "性能监控：开（点击关闭）" : "性能监控：关（点击开启）",
                verbose ? "详细日志：开（点击关闭）" : "详细日志：关（点击开启）",
                "查看性能报告",
                "导出性能日志",
                "查看运行日志",
                "网络：清空不可达域名",
                batteryWhitelisted ? "电池优化：已加入白名单（点击查看）"
                        : "电池优化：受限（点击加入白名单）"
        };
        new AlertDialog.Builder(this)
                .setTitle("调试与诊断")
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialogInterface, int i) {
                        onDebugMenuItemSelected(i);
                    }
                })
                .setNegativeButton("返回", null)
                .show();
    }

    private void onDebugMenuItemSelected(int i) {
        switch (i) {
            case 0:
                boolean nowMon = !this.prefs.getBoolean(PREF_PERF_MONITOR, false);
                this.prefs.edit().putBoolean(PREF_PERF_MONITOR, nowMon).apply();
                if (nowMon) {
                    startPerfMonitor();
                    injectScripts(this.webView);
                    Toast.makeText(this, "性能监控已开启，日志写入 Android/data/.../files/perf",
                            Toast.LENGTH_LONG).show();
                } else {
                    PerfMonitor.stop();
                    Toast.makeText(this, "性能监控已关闭", Toast.LENGTH_SHORT).show();
                }
                return;
            case 1:
                boolean nowVerbose = !this.prefs.getBoolean(PREF_VERBOSE_LOG, false);
                this.prefs.edit().putBoolean(PREF_VERBOSE_LOG, nowVerbose).apply();
                PerfLog.setVerbose(nowVerbose);
                PerfLog.i(TAG, "verbose log toggled: " + nowVerbose);
                Toast.makeText(this, nowVerbose
                                ? "详细日志已开启（记录生命周期/页面加载/逐请求事件）"
                                : "详细日志已关闭",
                        Toast.LENGTH_SHORT).show();
                return;
            case 2:
                showPerfReport();
                return;
            case 3:
                exportPerfLog();
                return;
            case 4:
                showLogViewer();
                return;
            case 5:
                showSlowHostDialog();
                return;
            case 6:
                openBatteryOptimizationSettings();
                return;
            default:
        }
    }

    // ---------------- 联网更新 ----------------

    /**
     * 手动指定的更新源；null 表示自动选源。
     *
     * <p>自动选源会依次尝试内置候选并缓存 24 小时——更新域名换了也不会失联。</p>
     */
    private String updateBase() {
        String base = this.prefs == null ? null
                : this.prefs.getString(UpdateManager.PREF_UPDATE_BASE, null);
        return base == null || base.trim().isEmpty() ? null : base.trim();
    }

    /** 检查更新：拉 releases.json → 列出全部可选版本（不强制最新）。 */
    /**
     * 更新渠道切换。
     *
     * <p>正式版与调试版是两个独立 appId 的清单，内容一致但构建方式不同
     * （调试版未混淆、日志更详细）。装调试版的人应该继续拿调试包，否则会被
     * "更新"回正式包——而两者 versionCode 相同，Android 不允许覆盖安装，
     * 表面现象就是"更新失败"。</p>
     */
    /**
     * 更新渠道切换。
     *
     * <p>正式版与调试版是两个独立 appId 的清单，内容一致但构建方式不同
     * （调试版未混淆、日志更详细）。装调试版的人应该继续拿调试包，否则会被
     * "更新"回正式包——而两者 versionCode 相同，Android 不允许覆盖安装，
     * 表面现象就是"更新失败"。</p>
     *
     * <p>注意：说明文字必须放在自定义 View 里，不能用 {@code setMessage}。
     * 部分 ROM 的 AlertDialog 在 message 较长时会把 {@code setItems} 的列表区
     * 压成 0 高度，结果是"只剩一个取消按钮"——1.8.14 的帧率对话框踩过同一个坑。</p>
     */
    private void showUpdateChannelDialog() {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        final String current = UpdateManager.channelOf(this.prefs);
        float density = getResources().getDisplayMetrics().density;
        int pad = (int) (20 * density);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, 0, pad, 0);

        TextView desc = new TextView(this);
        desc.setText("两个渠道的版本号是并行的：切到另一渠道后，只有发布了新版本才能装"
                + "（同版本号无法覆盖安装）。\n\n"
                + "当前安装包类型：" + (BuildConfig.DEBUG ? "调试版" : "正式版"));
        desc.setTextSize(13);
        desc.setLineSpacing(3 * density, 1.0f);
        root.addView(desc);

        final String[] labels = new String[]{
                (UpdateManager.CHANNEL_STABLE.equals(current) ? "✓ " : "") + "正式版（发布用，已混淆）",
                (UpdateManager.CHANNEL_DEBUG.equals(current) ? "✓ " : "") + "调试版（未混淆，日志更详细）"
        };
        ListView listView = new ListView(this);
        listView.setAdapter(new ArrayAdapter<String>(this,
                android.R.layout.simple_list_item_1, labels));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) (128 * density));
        lp.topMargin = (int) (6 * density);
        listView.setLayoutParams(lp);
        root.addView(listView);

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("更新渠道")
                .setView(root)
                .setNegativeButton("取消", null)
                .create();
        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int which, long id) {
                String ch = which == 0
                        ? UpdateManager.CHANNEL_STABLE : UpdateManager.CHANNEL_DEBUG;
                MainActivity.this.prefs.edit()
                        .putString(UpdateManager.PREF_UPDATE_CHANNEL, ch).apply();
                PerfLog.i(TAG, "update channel -> " + ch);
                Toast.makeText(MainActivity.this,
                        "更新渠道：" + UpdateManager.channelLabel(ch),
                        Toast.LENGTH_SHORT).show();
                dialog.dismiss();
            }
        });
        dialog.show();
    }

    private void checkUpdates() {
        final String manualBase = updateBase();
        Toast.makeText(this, "正在检查更新…", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String channel = UpdateManager.channelOf(MainActivity.this.prefs);
                final String appId = UpdateManager.appIdFor(channel);
                try {
                    final UpdateManager.FetchResult r = UpdateManager.fetchFromAnySource(
                            MainActivity.this.prefs, manualBase, appId, BuildConfig.VERSION_CODE);
                    PerfLog.i(TAG, "check updates via " + r.base + " app=" + appId
                            + " (" + r.releases.size() + " releases)");
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            showReleaseList(r.releases, r.base, appId, channel);
                        }
                    });
                } catch (final Exception e) {
                    PerfLog.w(TAG, "check updates failed: " + e);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            showUpdateError(e, manualBase == null
                                    ? java.util.Arrays.toString(UpdateManager.DEFAULT_BASES)
                                    : manualBase, UpdateManager.appIdFor(
                                    UpdateManager.channelOf(MainActivity.this.prefs)));
                        }
                    });
                }
            }
        }, "tk-update").start();
    }

    /**
     * 拉起系统安装器。没有可用安装器时（部分定制 ROM 移除了安装界面，或设备策略禁止）
     * 给一条可操作的提示，而不是让异常直接崩掉 Activity。
     */
    private void installApk(java.io.File apk) {
        if (UpdateManager.install(this, apk)) {
            return;
        }
        if (isFinishing() || isDestroyed()) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("无法启动安装器")
                .setMessage("系统没有响应该安装请求。请检查：\n\n"
                        + "• 设置 → 应用 → 特殊权限 → 安装未知应用，允许本应用\n"
                        + "• 部分定制系统移除了安装界面，需要改用文件管理器打开安装包")
                .setPositiveButton("知道了", null)
                .show();
    }

    /**
     * 签名不一致：默认拒绝安装。
     *
     * <p>出现这种情况只有两种可能：更新服务器被篡改，或者应用换过签名。
     * 两者都拒绝进入安装器，需先重新确认更新来源。</p>
     */
    private void showSignatureMismatchDialog(final java.io.File apk) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("安装包签名不一致")
                .setMessage("下载到的安装包与当前应用的签名不匹配，或本机无法校验签名。\n\n"
                        + "正常情况下不应该出现。可能原因：\n"
                        + "• 更新服务器被篡改，下发了伪造的安装包\n"
                        + "• 应用更换过签名（需要用新签名的包重新安装）\n"
                        + "• 本机读不到应用签名（部分定制系统会这样）\n\n"
                        + "已停止安装，请确认来源后重新检查更新。")
                .setPositiveButton("知道了", null)
                .show();
    }

    private void showReleaseList(java.util.List<UpdateManager.Release> list, String base,
                                 String appId, String channel) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        if (list.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("没有可用版本")
                    .setMessage("更新源里没有列出任何版本。\n\n" + UpdateManager.releasesUrl(base, appId)
                            + "\n\n当前渠道：" + UpdateManager.channelLabel(channel))
                    .setPositiveButton("知道了", null)
                    .show();
            return;
        }
        final java.util.List<UpdateManager.Release> releases = list;
        String[] labels = new String[list.size()];
        for (int i = 0; i < list.size(); i++) {
            labels[i] = list.get(i).label(BuildConfig.VERSION_CODE);
        }
        new AlertDialog.Builder(this)
                .setTitle("选择版本（" + UpdateManager.channelLabel(channel)
                        + " · 当前 " + BuildConfig.VERSION_NAME + "）")
                .setItems(labels, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        confirmUpdate(releases.get(which));
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showUpdateError(Exception e, String base, String appId) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("检查更新失败")
                .setMessage(e.getMessage() + "\n\n更新源：" + UpdateManager.releasesUrl(base, appId))
                .setPositiveButton("重试", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        checkUpdates();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void confirmUpdate(final UpdateManager.Release release) {
        final boolean sameVersion = release.versionCode == BuildConfig.VERSION_CODE;
        boolean downgrade = release.versionCode < BuildConfig.VERSION_CODE;
        StringBuilder msg = new StringBuilder();
        msg.append("版本：").append(release.versionName)
                .append(" (").append(release.versionCode).append(")\n");
        if (!release.date.isEmpty()) {
            msg.append("日期：").append(release.date).append('\n');
        }
        if (release.size > 0) {
            msg.append("大小：").append(UpdateManager.formatSize(release.size)).append('\n');
        }
        if (!release.channel.isEmpty()) {
            msg.append("通道：").append(release.channel).append('\n');
        }
        if (!release.notes.isEmpty()) {
            msg.append("\n").append(release.notes);
        }
        if (sameVersion) {
            msg.append("\n\n当前已安装同一版本。重装会覆盖现有安装，")
                    .append("应用内设置与缓存都保留。\n\n")
                    .append("适用场景：\n")
                    .append("• 想在正式版 / 调试版之间切换（两者版本号并行）\n")
                    .append("• 怀疑安装包损坏，需要重新装一遍\n\n")
                    .append("部分系统会拒绝安装同版本号；若提示失败，请先卸载再装。");
        } else if (downgrade) {
            msg.append("\n\n注意：这是比当前更旧的版本，Android 不允许直接降级安装，")
                    .append("需要先卸载当前版本（会丢失应用内设置与缓存）。");
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle((sameVersion ? "重新安装 " : "安装 ") + release.versionName)
                .setMessage(msg.toString())
                .setPositiveButton(sameVersion ? "重新安装" : "下载并安装",
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                startUpdateDownload(release, false);
                            }
                        })
                .setNegativeButton("取消", null);
        if (sameVersion) {
            // 排查"装不上 / 包损坏"时，先丢掉本地副本再来一遍
            builder.setNeutralButton("重新下载", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    startUpdateDownload(release, true);
                }
            });
        }
        builder.show();
    }

    private void startUpdateDownload(final UpdateManager.Release release) {
        startUpdateDownload(release, false);
    }

    private void startUpdateDownload(final UpdateManager.Release release,
                                     final boolean forceRedownload) {
        if (!UpdateManager.canInstall(this)) {
            new AlertDialog.Builder(this)
                    .setTitle("需要安装权限")
                    .setMessage("系统尚未允许本应用安装应用。请在接下来的设置页里为「Tavernium」打开"
                            + "「允许从此来源安装应用」，然后回到这里重新下载。")
                    .setPositiveButton("去设置", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            UpdateManager.openInstallSettings(MainActivity.this);
                        }
                    })
                    .setNegativeButton("取消", null)
                    .show();
            return;
        }
        final ProgressBar bar = new ProgressBar(this, null,
                android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setProgress(0);
        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("正在下载 " + release.versionName)
                .setView(bar)
                .setCancelable(false)
                .setNegativeButton("取消下载", null)
                .create();
        final java.util.concurrent.atomic.AtomicBoolean cancel =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface d) {
                cancel.set(true);
            }
        });
        dialog.show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final java.io.File apk = UpdateManager.download(MainActivity.this, release,
                            new UpdateManager.Progress() {
                                @Override
                                public void onProgress(int percent, long done, long total) {
                                    runOnUiThread(new Runnable() {
                                        @Override
                                        public void run() {
                                            bar.setProgress(percent);
                                        }
                                    });
                                }
                            }, cancel, forceRedownload);
                    PerfLog.i(TAG, "update downloaded: " + apk.getName()
                            + " " + apk.length() + "B");
                    // 签名校验：SHA-256 只能证明"下到的是清单里那个文件"，
                    // 证明不了"清单可信"。签名一致才允许直接安装。
                    final boolean sigOk = UpdateManager.verifyApkSignature(
                            MainActivity.this, apk);
                    PerfLog.i(TAG, "update signature match: " + sigOk);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            dialog.dismiss();
                            if (sigOk) {
                                installApk(apk);
                            } else {
                                showSignatureMismatchDialog(apk);
                            }
                        }
                    });
                } catch (final Exception e) {
                    PerfLog.w(TAG, "update download failed: " + e);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            dialog.dismiss();
                            if (!isFinishing() && !isDestroyed()) {
                                new AlertDialog.Builder(MainActivity.this)
                                        .setTitle("下载失败")
                                        .setMessage(String.valueOf(e.getMessage()))
                                        .setPositiveButton("知道了", null)
                                        .show();
                            }
                        }
                    });
                }
            }
        }, "tk-update-dl").start();
    }

    /** 下载走独立线程，边下边写盘，不再把整个文件读进内存（聊天记录导出可能几十 MB）。 */
    private void downloadToLocal(final String url, final String contentDisposition,
                                 final String mimeType) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                InputStream in = null;
                try {
                    PerfLog.i(TAG, "download start: " + TextKit.shortUrl(url));
                    in = MainActivity.this.cacheManager.fetchStream(url, "GET",
                            MainActivity.this.userAgent,
                            MainActivity.this.authUser, MainActivity.this.authPass);
                    final String name = TextKit.guessFileName(url, contentDisposition, mimeType);
                    saveStreamToDownloads(name, mimeType, in);
                    PerfLog.i(TAG, "download done: " + name);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(MainActivity.this, "已保存到下载/SillyTavern：" + name,
                                    Toast.LENGTH_SHORT).show();
                        }
                    });
                } catch (final Exception e) {
                    PerfLog.w(TAG, "download failed: " + e.getMessage() + " url=" + TextKit.shortUrl(url));
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(MainActivity.this, "下载失败：" + e.getMessage(),
                                    Toast.LENGTH_SHORT).show();
                        }
                    });
                } finally {
                    closeQuietly(in);
                }
            }
        }).start();
    }

    void saveBlobToLocal(final String name, final String mimeType, final String base64) {
        // 文件名来自页面里的 a.download，可以带 ../ 或绝对路径；不消毒就会写到下载目录之外。
        final String safeName = TextKit.sanitizeFileName(name);
        // 本方法由 JS bridge 线程调用（无 Looper），Toast 必须回主线程
        if (base64 == null || base64.isEmpty()) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(MainActivity.this, "保存失败：文件内容为空",
                            Toast.LENGTH_SHORT).show();
                }
            });
            return;
        }
        if (base64.length() > MAX_BLOB_BASE64_CHARS) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    // base64 是原文的 4/3，上限换算成文件大小才符合用户直觉
                    Toast.makeText(MainActivity.this, "保存失败：文件过大（超过 "
                                    + (MAX_BLOB_BASE64_CHARS / (1024 * 1024) * 3 / 4) + "MB 上限）",
                            Toast.LENGTH_LONG).show();
                }
            });
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    byte[] data = Base64.decode(base64, 0);
                    saveStreamToDownloads(safeName, mimeType, new ByteArrayInputStream(data));
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(MainActivity.this, "已保存到下载/SillyTavern：" + safeName,
                                    Toast.LENGTH_SHORT).show();
                        }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(MainActivity.this, "保存失败：" + e.getMessage(),
                                    Toast.LENGTH_SHORT).show();
                        }
                    });
                }
            }
        }).start();
    }


    /** 流式写入下载目录，常量级内存占用。 */
    private void saveStreamToDownloads(String name, String mime, InputStream in)
            throws IOException {
        // 入口统一消毒：name 既可能来自 JS 的 a.download（saveBlobToLocal），
        // 也可能来自服务器的 Content-Disposition（downloadToLocal → guessFileName），
        // 后者是攻击者可控的。两个写入分支（MediaStore / 公开目录）都必须用消毒后的名字，
        // 否则 _display_name 会被塞进原始恶意串（路径穿越、超长、非法字符）。
        name = TextKit.sanitizeFileName(name);
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues values = new ContentValues();
            values.put("_display_name", name);
            values.put("mime_type", mime == null ? "application/octet-stream" : mime);
            values.put("relative_path", Environment.DIRECTORY_DOWNLOADS + "/SillyTavern");
            values.put("is_pending", Integer.valueOf(1));
            Uri uri = getContentResolver()
                    .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) {
                throw new IOException("无法创建下载文件");
            }
            OutputStream os = getContentResolver().openOutputStream(uri);
            if (os == null) {
                throw new IOException("无法打开输出流");
            }
            try {
                copy(in, os);
                os.flush();
            } finally {
                closeQuietly(os);
            }
            values.clear();
            values.put("is_pending", Integer.valueOf(0));
            getContentResolver().update(uri, values, null, null);
            return;
        }
        // 低版本走公开下载目录；name 已在入口消毒过，拼出来的路径一定在 dir 之内
        File dir = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS), "SillyTavern");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("无法创建目录");
        }
        FileOutputStream fos = new FileOutputStream(new File(dir, name));
        try {
            copy(in, fos);
            fos.flush();
        } finally {
            closeQuietly(fos);
        }
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
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


    /**
     * 启动时异步预解析服务器域名：与 WebView 初始化并行。
     * 首个请求不必再等 DNS（国内递归解析偶尔要几百毫秒到数秒），
     * 同时把解析耗时写进日志——启动慢时能直接看出是不是 DNS 的锅。
     */
    private void warmUpDns(final String url) {
        if (url == null || url.isEmpty()) {
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String host = new java.net.URL(url).getHost();
                    long t0 = System.nanoTime();
                    java.net.InetAddress[] addrs = java.net.InetAddress.getAllByName(host);
                    long ms = (System.nanoTime() - t0) / 1_000_000L;
                    PerfLog.i(TAG, "dns warmup " + host + " -> "
                            + (addrs == null ? 0 : addrs.length) + " addrs in " + ms + "ms");
                } catch (Exception e) {
                    PerfLog.w(TAG, "dns warmup failed: " + e.getMessage());
                }
            }
        }, "tk-dns").start();
    }



    public static class WebAppInterface {
        private final MainActivity activity;

        WebAppInterface(MainActivity mainActivity) {
            this.activity = mainActivity;
        }

        @JavascriptInterface
        public void saveBlob(String token, String name, String mime, String base64) {
            MainActivity mainActivity = this.activity;
            if (mainActivity == null) {
                return;
            }
            if (!mainActivity.isTrustedPage(token)) {
                PerfLog.w("TavernKeep", "saveBlob rejected: untrusted page "
                        + TextKit.shortUrl(mainActivity.lastMainUrl));
                return;
            }
            mainActivity.saveBlobToLocal(name, mime, base64);
        }

        @JavascriptInterface
        public void noteNetActivity(String token) {
            MainActivity mainActivity = this.activity;
            if (mainActivity == null) {
                return;
            }
            if (!mainActivity.isTrustedPage(token)) {
                return;
            }
            mainActivity.acquireNetWakeLock();
        }

        @JavascriptInterface
        public void replyReady(final String token, final String event) {
            final MainActivity current = this.activity;
            if (current == null || !current.isTrustedPage(token)) {
                return;
            }
            current.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    // 跨线程投递后再次检查；导航、退出或修改服务器会使旧凭证失效。
                    if (!current.isDestroyed() && current.isTrustedPage(token)
                            && current.replyNotifier != null) {
                        current.replyNotifier.replyReady(event,
                                current.prefs.getBoolean(PREF_REPLY_NOTIFICATIONS, false),
                                current.activityResumed, current.isBurnInIdle());
                    }
                }
            });
        }
    }

    /**
     * JS 接口校验：主文档必须与服务器同源，并持有本次导航生成的凭证。
     *
     * <p>普通网页导航已经由 {@code shouldOverrideUrlLoading} 挡在系统浏览器里，
     * 跨域 iframe 即使拿到原生对象，也无法取得主文档闭包中的凭证。</p>
     */
    private boolean isTrustedPage(String token) {
        return this.bridgeSession.accepts(token, this.lastMainUrl, this.serverUrl);
    }

    /* 按需唤醒：有网络请求时短暂持有 CPU 唤醒锁，空闲即放，让系统深度休眠省电。
       锁的剩余时间还很长时不再反复 release/acquire，减少系统调用抖动。 */
    private void acquireNetWakeLock() {
        try {
            if (this.netWakeLock == null) {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                if (pm == null) {
                    return;
                }
                this.netWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                        "tavernkeep:net_wake");
                this.netWakeLock.setReferenceCounted(false);
            }
            synchronized (this) {
                long now = System.currentTimeMillis();
                if (this.netWakeLock.isHeld() && this.netWakeLockExpiry - now > NET_WAKELOCK_RENEW_MS) {
                    return;
                }
                if (this.netWakeLock.isHeld()) {
                    this.netWakeLock.release();
                }
                this.netWakeLock.acquire(NET_WAKELOCK_MS);
                this.netWakeLockExpiry = now + NET_WAKELOCK_MS;
            }
        } catch (Exception unused) {
        }
    }

    private void releaseNetWakeLock() {
        synchronized (this) {
            if (this.netWakeLock != null && this.netWakeLock.isHeld()) {
                try {
                    this.netWakeLock.release();
                } catch (Exception ignored) {
                }
            }
            this.netWakeLockExpiry = 0L;
        }
    }

    @Override
    protected void onActivityResult(int i, int i2, Intent intent) {
        if (i == FILE_CHOOSER_REQUEST) {
            if (this.filePathCallback == null) {
                return;
            }
            Uri[] uriArr = null;
            if (i2 == RESULT_OK && intent != null) {
                if (intent.getClipData() != null) {
                    int itemCount = intent.getClipData().getItemCount();
                    uriArr = new Uri[itemCount];
                    for (int i3 = 0; i3 < itemCount; i3++) {
                        uriArr[i3] = intent.getClipData().getItemAt(i3).getUri();
                    }
                } else if (intent.getData() != null) {
                    uriArr = new Uri[]{intent.getData()};
                }
            }
            this.filePathCallback.onReceiveValue(uriArr);
            this.filePathCallback = null;
            return;
        }
        super.onActivityResult(i, i2, intent);
    }

    private void showCustomView(View view, WebChromeClient.CustomViewCallback callback) {
        if (this.customView != null) {
            callback.onCustomViewHidden();
            return;
        }
        this.customView = view;
        this.customViewCallback = callback;
        Window window = getWindow();
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                WindowInsetsController controller = window.getInsetsController();
                if (controller != null) {
                    controller.hide(WindowInsets.Type.systemBars());
                    controller.setSystemBarsBehavior(
                            WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                }
            } catch (Exception unused) {
            }
        } else {
            window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
        window.addContentView(view, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void hideCustomView() {
        View view = this.customView;
        if (view == null) {
            return;
        }
        ViewGroup parent = (ViewGroup) view.getParent();
        if (parent != null) {
            parent.removeView(view);
        }
        this.customView = null;
        WebChromeClient.CustomViewCallback callback = this.customViewCallback;
        this.customViewCallback = null;
        if (callback != null) {
            callback.onCustomViewHidden();
        }
        Window window = getWindow();
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                WindowInsetsController controller = window.getInsetsController();
                if (controller != null) {
                    controller.show(WindowInsets.Type.systemBars());
                }
            } catch (Exception unused) {
            }
        } else {
            window.getDecorView().setSystemUiVisibility(0);
        }
    }

    private void startKeepAliveService() {
        Intent intent = new Intent(this, KeepAliveService.class);
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    /** 开启性能监控：启动采样 + 注册 JS 探针 + 立即播种一次探针脚本。 */
    private void startPerfMonitor() {
        PerfMonitor.setJsProbe(makeJsProbe());
        PerfMonitor.start(this);
        WebView wv = this.webView;
        if (wv != null) {
            wv.evaluateJavascript(PERF_PROBE_SEED, null);
        }
    }

    /**
     * JS 探针：由 PerfMonitor 的采样线程调用。
     * evaluateJavascript 必须经 UI 线程发起（回值回调也在 UI 线程），
     * 因此这里 post 到主线程执行、由采样线程用 latch 等待，避免死锁；400ms 超时兜底。
     */
    private PerfMonitor.JsProbe makeJsProbe() {
        return new PerfMonitor.JsProbe() {
            @Override
            public String probe() {
                WebView wv = MainActivity.this.webView;
                Handler h = MainActivity.this.handler;
                if (wv == null || h == null) {
                    return null;
                }
                final CountDownLatch latch = new CountDownLatch(1);
                final String[] out = new String[1];
                h.post(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            wv.evaluateJavascript(PERF_PROBE_JS,
                                    new ValueCallback<String>() {
                                        @Override
                                        public void onReceiveValue(String value) {
                                            out[0] = value;
                                            latch.countDown();
                                        }
                                    });
                        } catch (Exception e) {
                            latch.countDown();
                        }
                    }
                });
                try {
                    if (!latch.await(400L, TimeUnit.MILLISECONDS)) {
                        return null;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
                return out[0];
            }
        };
    }

    private void showPerfReport() {
        final String report = PerfMonitor.report();
        ScrollView sv = new ScrollView(this);
        TextView tv = new TextView(this);
        tv.setText(report);
        tv.setTextSize(12f);
        int pad = (int) (getResources().getDisplayMetrics().density * 12f);
        tv.setPadding(pad, pad, pad, pad);
        sv.addView(tv);
        new AlertDialog.Builder(this)
                .setTitle("性能报告")
                .setView(sv)
                .setPositiveButton("导出日志", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        exportPerfLog();
                    }
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    /** 应用内查看日志尾部（最近 32KB），不必导出到文件再找工具看。 */
    private void showLogViewer() {
        String text = PerfLog.tail(32 * 1024);
        if (text.isEmpty()) {
            Toast.makeText(this, "暂无日志", Toast.LENGTH_SHORT).show();
            return;
        }
        final ScrollView sv = new ScrollView(this);
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(10f);
        int pad = (int) (getResources().getDisplayMetrics().density * 8f);
        tv.setPadding(pad, pad, pad, pad);
        sv.addView(tv);
        new AlertDialog.Builder(this)
                .setTitle("运行日志（尾部 " + (text.length() / 1024) + " KB）")
                .setView(sv)
                .setPositiveButton("导出", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        exportPerfLog();
                    }
                })
                .setNegativeButton("关闭", null)
                .show();
        // 打开就停在最新的一条
        sv.post(new Runnable() {
            @Override
            public void run() {
                sv.fullScroll(View.FOCUS_DOWN);
            }
        });
    }

    /** 把当前性能日志复制到 下载/SillyTavern/，走 MediaStore，无需存储权限。 */
    private void exportPerfLog() {
        final File src = PerfLog.currentLog();
        if (src == null || !src.exists()) {
            Toast.makeText(this, "暂无性能日志", Toast.LENGTH_SHORT).show();
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                InputStream in = null;
                try {
                    // 文件名带时间戳：多次导出不会互相覆盖，便于对比不同场景
                    String name = "tavernkeep-perf-"
                            + new java.text.SimpleDateFormat("MMdd-HHmm", Locale.US)
                            .format(new java.util.Date()) + ".log";
                    in = new FileInputStream(src);
                    saveStreamToDownloads(name, "text/plain", in);
                    final String shown = name;
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(MainActivity.this,
                                    "日志已导出到 下载/SillyTavern/" + shown,
                                    Toast.LENGTH_LONG).show();
                        }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(MainActivity.this,
                                    "导出失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
                        }
                    });
                } finally {
                    closeQuietly(in);
                }
            }
        }).start();
    }

    @Override
    public void onBackPressed() {
        handleBackKey();
    }

    @Override
    public boolean onKeyDown(int i, KeyEvent keyEvent) {
        if (i == KeyEvent.KEYCODE_BACK) {
            return true;
        }
        return super.onKeyDown(i, keyEvent);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent keyEvent) {
        if (keyEvent.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (keyEvent.getAction() == KeyEvent.ACTION_UP && keyEvent.getRepeatCount() == 0) {
                handleBackKey();
            }
            return true;
        }
        return super.dispatchKeyEvent(keyEvent);
    }

    /**
     * 返回键的唯一处理入口（原先 onBackPressed / onKeyDown / dispatchKeyEvent 三处
     * 各写一遍，改一处容易漏另一处，这里合并）。
     *
     * <p>优先级：全屏视频 → 退出全屏；网页可后退 → 后退；到顶 → 打开菜单。
     * 这样用户按返回的直觉（网页后退）和浏览器一致，而不是每次都弹菜单。</p>
     */
    private void handleBackKey() {
        if (this.customView != null) {
            hideCustomView();
            return;
        }
        WebView wv = this.webView;
        if (wv != null && wv.canGoBack()) {
            wv.goBack();
            return;
        }
        showMenuDialog();
    }

    /**
     * 屏幕配置变化（旋转、折叠屏展开、深色模式、字体缩放）的兜底。
     *
     * <p>manifest 里把这些变化声明成自己处理（configChanges），Activity 不重建、
     * WebView 不重载，正在聊的页面状态得以保留。这里只做必要的刷新：
     * 重算上滑解锁阈值（随屏高变化）、重新应用帧率（旋转后刷新率可能变）。
     * 网页本身的主题/布局交给 SillyTavern 前端自行响应，不在此干预。</p>
     */
    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        PerfLog.d(TAG, "onConfigurationChanged: orientation=" + newConfig.orientation
                + ", uiMode=" + Integer.toHexString(newConfig.uiMode));
        recalcIdleUnlockDistance();
        try {
            applyFrameRateLimit(effectiveFrameRate());
        } catch (Exception e) {
            PerfLog.w(TAG, "applyFrameRateLimit on config change failed: " + e);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        PerfLog.d(TAG, "onStart, pendingRecovery=" + this.pendingWebViewRecovery);
        // 后台期间渲染进程被系统回收/崩溃：旧实例已在 onRenderProcessGone 里销毁，
        // 这里回前台后重建 WebView 并重新加载服务器地址。
        if (this.pendingWebViewRecovery) {
            this.pendingWebViewRecovery = false;
            WebView dead = this.webView;
            if (dead != null) {
                ViewGroup parent = (ViewGroup) dead.getParent();
                if (parent != null) {
                    parent.removeView(dead);
                }
            }
            createWebView();
            return;
        }
        WebView wv = this.webView;
        if (wv == null) {
            return;
        }
        // 回到前台：恢复绘制
        wv.setVisibility(View.VISIBLE);
        if (this.webViewPaused) {
            this.webViewPaused = false;
            wv.onResume();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        PerfLog.d(TAG, "onPause");
        if (this.customView != null) {
            hideCustomView();
        }
        // 旧代码在这里还 injectScripts 一次，但 onResume 本来就会注入——纯属一次多余的
        // 跨进程 evaluateJavascript IPC。心跳 runnable 也已在别处覆盖，这里不再补注入。
        this.activityResumed = false;
        if (this.idleController != null) this.idleController.pause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        PerfLog.d(TAG, "onResume");
        this.activityResumed = true;
        if (this.replyNotifier != null && !isBurnInIdle() && this.replyNotifier.screenAvailable()) {
            this.replyNotifier.clear();
        }
        WebView wv = this.webView;
        if (wv != null) {
            wv.onResume();
            this.webViewPaused = false;
            // 挂机中回前台：WebView 保持不可见，只显示黑屏遮罩
            wv.setVisibility(isBurnInIdle() ? View.INVISIBLE : View.VISIBLE);
            injectScripts(wv);
            applyIdleMode(idleMode());
        }
    }

    /**
     * 退到后台时让 WebView 不可见：渲染进程不再为没人看的画面持续合成帧，
     * 这是后台空转最直接的一处 CPU 来源。JS 定时器不受影响，后台生成继续。
     */
    @Override
    protected void onStop() {
        super.onStop();
        boolean pauseBg = this.prefs.getBoolean(PREF_PAUSE_IN_BACKGROUND, false);
        PerfLog.d(TAG, "onStop, pauseBg=" + pauseBg);
        WebView wv = this.webView;
        if (wv == null) {
            return;
        }
        wv.setVisibility(View.INVISIBLE);
        if (pauseBg) {
            this.webViewPaused = true;
            wv.onPause();
        }
    }

    /** 系统内存压力分级回调：配合 jsHeap 一起看，能提前发现被 LMK 杀的征兆。 */
    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        PerfLog.w(TAG, "onTrimMemory level=" + level
                + ", javaHeap=" + (Runtime.getRuntime().totalMemory()
                - Runtime.getRuntime().freeMemory()) / 1048576L + "MB");
    }

    @Override
    protected void onDestroy() {
        this.bridgeSession.clear();
        if (this.idleController != null) {
            this.idleController.dispose();
            this.idleController = null;
        }
        PerfLog.i(TAG, "onDestroy");
        PerfMonitor.stop();
        if (this.handler != null) {
            this.handler.removeCallbacks(this.heartbeatRunnable);
            this.handler = null;
        }
        releaseNetWakeLock();
        WebView wv = this.webView;
        if (wv != null) {
            try {
                wv.setDownloadListener(null);
            } catch (Exception ignored) {
            }
            try {
                wv.removeJavascriptInterface("TavernKeep");
            } catch (Exception ignored) {
            }
            try {
                wv.stopLoading();
            } catch (Exception ignored) {
            }
            try {
                wv.loadUrl("about:blank");
            } catch (Exception ignored) {
            }
            ViewGroup parent = (ViewGroup) wv.getParent();
            if (parent != null) {
                parent.removeView(wv);
            }
            try {
                wv.destroy();
            } catch (Exception ignored) {
            }
            this.webView = null;
        }
        if (this.cacheManager != null) {
            this.cacheManager.shutdown();
            this.cacheManager = null;
        }
        super.onDestroy();
    }
}
