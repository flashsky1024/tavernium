package io.github.flashsky1024.tavernium;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 报告样例生成器（仅用于演示/自测，不进入 APK）。
 * 用真实 HTTP 服务 + 真实 CacheManager 跑一遍「首屏加载」，
 * 让埋点与采样自然产生，然后打印 report() 的真实输出。
 */
public class ReportDemo {
    public static void main(String[] args) throws Exception {
        String tmp = System.getProperty("java.io.tmpdir");
        final File cacheDir = new File(tmp, "tk_demo_cache_" + System.currentTimeMillis());
        cacheDir.mkdirs();
        System.setProperty("tk.cacheDir", cacheDir.getAbsolutePath());
        System.setProperty("tk.extDir", tmp + "/tk_demo_ext");

        Context ctx = new Context() {
            @Override
            public File getCacheDir() {
                return cacheDir;
            }
        };
        PerfLog.init(ctx);

        // 模拟 SillyTavern 首屏：24 个子资源
        String[] paths = {
                "/script.js", "/style.css", "/libs/jquery.js", "/libs/svelte.js",
                "/img/avatar1.png", "/img/avatar2.png", "/img/bg.jpg", "/img/logo.svg",
                "/fonts/inter.woff2", "/fonts/mono.woff2", "/locales/zh-cn.json", "/manifest.json",
                "/themes/dark.css", "/themes/light.css", "/icons/add.png", "/icons/gear.png",
                "/icons/persona.png", "/icons/world.png", "/modules/chat.js", "/modules/prompt.js",
                "/modules/tts.js", "/modules/vectors.js", "/vendor/lodash.js", "/vendor/dompurify.js"
        };
        TinyServer server = new TinyServer();
        server.start();
        int port = server.port();
        CacheManager cm = new CacheManager(ctx);

        PerfMonitor.start(ctx);

        // ---- 第一次加载：全部未命中，触发后台预取 ----
        for (String p : paths) {
            long t0 = System.nanoTime();
            cm.intercept("http://127.0.0.1:" + port + p, "DemoUA", "", "", true);
            PerfMonitor.mark("intercept.total", System.nanoTime() - t0);
        }
        // 等预取落盘
        Thread.sleep(2500);

        // ---- 第二次加载：应全部命中磁盘缓存 ----
        int hits = 0;
        for (String p : paths) {
            long t0 = System.nanoTime();
            if (cm.intercept("http://127.0.0.1:" + port + p, "DemoUA", "", "", true) != null) {
                hits++;
            }
            PerfMonitor.mark("intercept.total", System.nanoTime() - t0);
        }
        System.out.println("第二次加载命中：" + hits + "/" + paths.length);

        // ---- WebView 侧路径（无法在 JVM 真跑，按真实量级注入样本）----
        PerfMonitor.mark("createWebView", 412_000_000L);
        for (int i = 0; i < 42; i++) {
            PerfMonitor.mark("injectScripts", 1_200_000L + (i % 7) * 300_000L);
        }
        for (int i = 0; i < 8; i++) {
            PerfMonitor.mark("heartbeat", 900_000L + (i % 3) * 200_000L);
        }
        // JS 探针：SillyTavern 长会话（DOM 节点多、偶发 long task）
        PerfMonitor.setPageCounts(1, 1);
        PerfMonitor.applyJsMetrics(
                "{\"fps\":56,\"dom\":15756,\"lt\":14,\"heap\":9437184,\"ms\":1000}");
        // 境外域名拖时间样本（取自真机日志：235 字节耗 8.97 秒）
        PerfMonitor.markHost("raw.githubusercontent.com", 8_970_000_000L, true);
        PerfMonitor.markHost("raw.githubusercontent.com", 6_100_000_000L, false);

        Thread.sleep(1500);
        String report = PerfMonitor.report();
        System.out.println();
        System.out.println(report);
        System.out.println("（请求总数 " + server.hits() + "）");
        PerfMonitor.stop();
        System.exit(0);
    }

    /** 极简单连接 HTTP 服务：按扩展名返回对应 MIME 与固定大小内容。 */
    static final class TinyServer {
        private final AtomicInteger hits = new AtomicInteger();
        private ServerSocket socket;

        int hits() {
            return hits.get();
        }

        int port() {
            return socket.getLocalPort();
        }

        void start() throws IOException {
            socket = new ServerSocket(0, 64, InetAddress.getByName("127.0.0.1"));
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        while (!socket.isClosed()) {
                            final Socket s = socket.accept();
                            Thread w = new Thread(new Runnable() {
                                @Override
                                public void run() {
                                    try {
                                        s.getInputStream().read(new byte[8192]);
                                        hits.incrementAndGet();
                                        byte[] body = new byte[48 * 1024];
                                        for (int i = 0; i < body.length; i++) {
                                            body[i] = (byte) (i * 7);
                                        }
                                        String head = "HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream"
                                                + "\r\nContent-Length: " + body.length
                                                + "\r\nConnection: close\r\n\r\n";
                                        OutputStream os = s.getOutputStream();
                                        os.write(head.getBytes(StandardCharsets.US_ASCII));
                                        os.write(body);
                                        os.flush();
                                    } catch (Exception ignored) {
                                    } finally {
                                        try {
                                            s.close();
                                        } catch (IOException ignored) {
                                        }
                                    }
                                }
                            });
                            w.setDaemon(true);
                            w.start();
                        }
                    } catch (IOException ignored) {
                    }
                }
            });
            t.setDaemon(true);
            t.start();
        }
    }
}
