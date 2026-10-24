package io.github.flashsky1024.tavernium;

import android.os.Build;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;

/** 只控制导航栏；不持有 Activity 或 Window，也不安排异步任务。 */
final class SystemBars {
    private SystemBars() {}
    static void setNavigationBarHidden(Window window, boolean hidden) {
        if (window == null) {
            return;
        }
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                WindowInsetsController controller = window.getInsetsController();
                if (controller != null) {
                    if (hidden) {
                        controller.hide(WindowInsets.Type.navigationBars());
                        // 从边缘滑出的导航栏是"transient"，几秒后自动隐藏，不留常亮白条
                        controller.setSystemBarsBehavior(
                                WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                    } else {
                        controller.show(WindowInsets.Type.navigationBars());
                    }
                    return;
                }
            }
            // API < 30 走旧的 systemUiVisibility 路径
            View decor = window.getDecorView();
            int flags = decor.getSystemUiVisibility();
            int hiddenFlags = View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
            if (hidden) {
                decor.setSystemUiVisibility(flags | hiddenFlags | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
            } else {
                decor.setSystemUiVisibility(flags & ~hiddenFlags);
            }
        } catch (Exception e) {
            PerfLog.w("SystemBars", "setNavigationBarHidden failed: " + e);
        }
    }
}
