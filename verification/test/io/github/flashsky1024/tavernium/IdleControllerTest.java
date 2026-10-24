package io.github.flashsky1024.tavernium;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.os.Build;
import android.os.Handler;
import android.util.DisplayMetrics;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowInsetsController;
import android.webkit.WebView;
import android.widget.FrameLayout;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

public final class IdleControllerTest {
    private static int passed;
    public static void main(String[] args) throws Exception {
        Map<String, Object> values = new HashMap<>();
        SharedPreferences prefs = preferences(values);
        check("首次使用默认关闭", IdleController.readMode(prefs) == 0);
        values.clear(); values.put("idle_mode", true);
        check("旧版黑屏设置迁移", IdleController.readMode(prefs) == 2 && !values.containsKey("idle_mode"));
        values.put("idle_mode_v2", 99);
        check("无效模式恢复关闭", IdleController.readMode(prefs) == 0);
        values.put("idle_mode_v2", "broken");
        check("损坏类型不崩溃", IdleController.readMode(prefs) == 0);
        FakeActivity activity = new FakeActivity();
        FakeHandler handler = new FakeHandler();
        int[] callbacks = new int[2];
        IdleController controller = new IdleController(activity, handler, new IdleController.Listener() {
            public void onApplied(int mode) { callbacks[0]++; }
            public void onUnlocked() { callbacks[1]++; }
        });
        FrameLayout first = new FrameLayout(activity);
        controller.buildIdleOverlay(first);
        check("创建遮罩", first.getChildCount() == 1);
        WebView web = new WebView(activity);
        controller.applyIdleMode(2, web);
        View overlay = (View) field(controller, "idleOverlay");
        View box = (View) field(controller, "idleBox");
        check("防烧屏隐藏网页并显示遮罩", web.getVisibility() == View.INVISIBLE && overlay.getVisibility() == View.VISIBLE);
        check("进入时立即全黑", box.getAlpha() == 0f && handler.task == null);
        check("应用模式通知宿主刷新帧率", callbacks[0] == 1);
        controller.handleTouch(MotionEvent.ACTION_DOWN, 1000);
        check("触摸后提示亮起且计时五秒", box.getAlpha() == 1f && handler.delay == 5000);
        controller.handleTouch(MotionEvent.ACTION_MOVE, 900);
        controller.handleTouch(MotionEvent.ACTION_UP, 900);
        check("短滑不解除", callbacks[1] == 0);
        handler.task.run();
        check("到时提示熄灭", box.getAlpha() == 0f);
        controller.handleTouch(MotionEvent.ACTION_DOWN, 1000);
        controller.handleTouch(MotionEvent.ACTION_MOVE, 400);
        controller.handleTouch(MotionEvent.ACTION_CANCEL, 400);
        check("系统取消手势不解除", callbacks[1] == 0);
        controller.handleTouch(MotionEvent.ACTION_DOWN, 1000);
        controller.handleTouch(MotionEvent.ACTION_UP, 400);
        check("达到阈值松手解除", callbacks[1] == 1);
        controller.applyIdleMode(1, web);
        check("普通挂机保留网页", web.getVisibility() == View.VISIBLE && overlay.getVisibility() == View.GONE);
        controller.handleTouch(MotionEvent.ACTION_DOWN, 1000);
        controller.pause();
        check("暂停时取消定时器并熄灭提示", handler.task == null && box.getAlpha() == 0f);
        FrameLayout second = new FrameLayout(activity);
        controller.buildIdleOverlay(second);
        check("重建时旧容器移除遮罩", first.getChildCount() == 0 && second.getChildCount() == 1);
        controller.handleTouch(MotionEvent.ACTION_DOWN, 1000);
        Runnable pending = handler.task;
        controller.dispose();
        check("销毁时清除任务和遮罩", handler.task == null && second.getChildCount() == 0);
        check("销毁后释放宿主和处理器引用", field(controller, "activity") == null
                && field(controller, "handler") == null && field(controller, "listener") == null);
        check("销毁后拒绝手势", !controller.handleTouch(MotionEvent.ACTION_UP, 0));
        pending.run();
        controller.applyIdleMode(2, web);
        check("迟到回调不能再次操作宿主", callbacks[0] == 2 && callbacks[1] == 1);
        int sdk = Build.VERSION.SDK_INT;
        try {
            FakeWindow window = new FakeWindow();
            Build.VERSION.SDK_INT = 34;
            SystemBars.setNavigationBarHidden(window, true);
            check("新 API 只隐藏导航栏", window.hidden == 2 && window.behavior == 2);
            SystemBars.setNavigationBarHidden(window, false);
            check("新 API 恢复导航栏", window.shown == 2);
            Build.VERSION.SDK_INT = 24;
            window.decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN);
            SystemBars.setNavigationBarHidden(window, true);
            check("旧 API 隐藏导航栏", (window.decor.getSystemUiVisibility() & View.SYSTEM_UI_FLAG_HIDE_NAVIGATION) != 0);
            SystemBars.setNavigationBarHidden(window, false);
            check("恢复时保留原有全屏标志", (window.decor.getSystemUiVisibility() & View.SYSTEM_UI_FLAG_FULLSCREEN) != 0
                    && (window.decor.getSystemUiVisibility() & View.SYSTEM_UI_FLAG_HIDE_NAVIGATION) == 0);
        } finally { Build.VERSION.SDK_INT = sdk; }
        System.out.println("PASSED: " + passed + " FAILED: 0");
    }
    private static SharedPreferences preferences(Map<String, Object> values) {
        Object editor = Proxy.newProxyInstance(IdleControllerTest.class.getClassLoader(),
                new Class<?>[]{SharedPreferences.Editor.class}, (proxy, method, args) -> {
                    if (method.getName().startsWith("put")) values.put((String) args[0], args[1]);
                    else if (method.getName().equals("remove")) values.remove(args[0]);
                    return method.getName().equals("apply") ? null : proxy;
                });
        return (SharedPreferences) Proxy.newProxyInstance(IdleControllerTest.class.getClassLoader(),
                new Class<?>[]{SharedPreferences.class}, (proxy, method, args) -> {
                    if (method.getName().equals("edit")) return editor;
                    Object value = values.getOrDefault(args[0], args[1]);
                    if (method.getName().equals("getInt") && !(value instanceof Integer)) throw new ClassCastException();
                    return value;
                });
    }
    private static Object field(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true); return field.get(object);
    }
    private static void check(String name, boolean value) {
        if (!value) throw new AssertionError(name);
        passed++; System.out.println("[PASS] " + name);
    }
    private static final class FakeHandler extends Handler {
        Runnable task; long delay;
        @Override public boolean postDelayed(Runnable task, long delay) { this.task = task; this.delay = delay; return true; }
        @Override public void removeCallbacks(Runnable task) { if (this.task == task) this.task = null; }
    }
    private static final class FakeActivity extends Activity {
        @Override public Resources getResources() { return new Resources() {
            @Override public DisplayMetrics getDisplayMetrics() {
                DisplayMetrics metrics = new DisplayMetrics(); metrics.density = 2; metrics.heightPixels = 1600; return metrics;
            }
        }; }
        @Override public Window getWindow() { return new FakeWindow(); }
    }
    private static final class FakeWindow extends Window {
        final View decor = new View(null);
        int hidden, shown, behavior;
        @Override public View getDecorView() { return decor; }
        @Override public WindowInsetsController getInsetsController() { return new WindowInsetsController() {
            public void hide(int types) { hidden = types; }
            public void show(int types) { shown = types; }
            public void setSystemBarsBehavior(int value) { behavior = value; }
        }; }
    }
}
