package wiki.nandflash.tavernkeep;

import android.content.SharedPreferences;
import android.webkit.ValueCallback;
import android.webkit.WebView;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;

/** 通过真实 MainActivity 拼装脚本，再由 Node 执行该脚本验证闭包中的桥调用。 */
public final class MainBridgeTest {
    private static int passed;
    public static void main(String[] args) throws Exception {
        String url = "https://tavern.example.com/";
        TestActivity activity = new TestActivity();
        set(activity, "serverUrl", url);
        set(activity, "lastMainUrl", url);
        SharedPreferences prefs = (SharedPreferences) Proxy.newProxyInstance(
                MainBridgeTest.class.getClassLoader(), new Class<?>[]{SharedPreferences.class},
                (proxy, method, values) -> {
                    if (method.getName().equals("getBoolean")) return "reply_notifications".equals(values[0]);
                    if (method.getName().equals("getInt")) return 0;
                    return null;
                });
        set(activity, "prefs", prefs);
        set(activity, "replyScript", Files.readString(Path.of(System.getProperty("tk.appSrc"),
                "app/src/main/assets/reply-notifications.js")));
        BridgeSession session = (BridgeSession) field("bridgeSession").get(activity);
        Method inject = MainActivity.class.getDeclaredMethod("injectScripts", WebView.class);
        inject.setAccessible(true);
        TestView view = new TestView(url);
        inject.invoke(activity, view);
        check("未建立页面凭证时不注入", view.script == null);
        session.beginDocument(url, url);
        inject.invoke(activity, view);
        check("真实 Activity 能拼装脚本", view.script != null && view.script.contains("replyReady"));
        Files.writeString(Path.of(System.getProperty("java.io.tmpdir"), "injected_bridge.js"), view.script);
        TestView foreign = new TestView("https://other.example.com/");
        inject.invoke(activity, foreign);
        check("外站不注入凭证", foreign.script == null);
        MainActivity.WebAppInterface bridge = new MainActivity.WebAppInterface(activity);
        bridge.replyReady("wrong", "1");
        check("错误凭证不能投递通知", activity.dispatches == 0);
        String token = session.tokenFor(url, url);
        bridge.replyReady(token, "1");
        check("有效凭证可投递主线程", activity.dispatches == 1);
        session.beginDocument(url, url);
        bridge.replyReady(token, "2");
        check("导航后旧凭证不能投递", activity.dispatches == 1);
        bridge.saveBlob("wrong", "unsafe.txt", "text/plain", "eA==");
        System.out.println("PASSED: " + passed + " FAILED: 0");
    }
    private static Field field(String name) throws Exception {
        Field field = MainActivity.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
    private static void set(MainActivity activity, String name, Object value) throws Exception {
        field(name).set(activity, value);
    }
    private static void check(String name, boolean value) {
        if (!value) throw new AssertionError(name);
        passed++;
        System.out.println("[PASS] " + name);
    }
    private static final class TestActivity extends MainActivity {
        int dispatches;
        @Override public void runOnUiThread(Runnable runnable) { dispatches++; }
    }
    private static final class TestView extends WebView {
        final String url;
        String script;
        TestView(String url) { super(null); this.url = url; }
        @Override public String getUrl() { return url; }
        @Override public void evaluateJavascript(String script, ValueCallback<String> callback) { this.script = script; }
    }
}
