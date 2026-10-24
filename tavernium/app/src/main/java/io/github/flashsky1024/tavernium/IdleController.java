package io.github.flashsky1024.tavernium;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Handler;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** 挂机遮罩、手势与提示计时。由 Activity 调用 pause/dispose 管理生命周期。 */
final class IdleController {
    static final int IDLE_OFF = 0;
    static final int IDLE_NORMAL = 1;
    static final int IDLE_BURNIN = 2;
    private static final String PREF_IDLE_MODE_LEGACY = "idle_mode";
    private static final String PREF_IDLE_MODE = "idle_mode_v2";
    private static final float IDLE_UNLOCK_RATIO = 0.22f;
    private static final long IDLE_HINT_TIMEOUT_MS = 5000L;
    private Activity activity;
    private Handler handler;
    private Listener listener;

    interface Listener {
        void onApplied(int mode);
        void onUnlocked();
    }

    IdleController(Activity activity, Handler handler, Listener listener) {
        this.activity = activity;
        this.handler = handler;
        this.listener = listener;
    }

    static int readMode(SharedPreferences prefs) {
        if (prefs == null) return IDLE_OFF;
        int mode;
        try { mode = prefs.getInt(PREF_IDLE_MODE, -1); }
        catch (ClassCastException e) { mode = -1; }
        if (mode >= IDLE_OFF && mode <= IDLE_BURNIN) return mode;
        boolean legacy;
        try { legacy = prefs.getBoolean(PREF_IDLE_MODE_LEGACY, false); }
        catch (ClassCastException e) { legacy = false; }
        int migrated = legacy ? IDLE_BURNIN : IDLE_OFF;
        prefs.edit().putInt(PREF_IDLE_MODE, migrated).remove(PREF_IDLE_MODE_LEGACY).apply();
        return migrated;
    }

    void pause() {
        if (handler != null) handler.removeCallbacks(idleHideRunnable);
        if (idleBox != null) idleBox.setAlpha(0f);
        idleHintsVisible = false;
        idleProgress = 0f;
    }

    void dispose() {
        pause();
        detachOverlay();
        handler = null;
        listener = null;
        activity = null;
    }

    private void detachOverlay() {
        if (idleOverlay != null) {
            idleOverlay.setOnTouchListener(null);
            ViewGroup parent = (ViewGroup) idleOverlay.getParent();
            if (parent != null) parent.removeView(idleOverlay);
        }
        idleOverlay = null;
        idleBox = null;
        idleArrow = null;
        idleHint = null;
    }

    /**
     * 挂机黑屏遮罩：覆盖在 WebView 之上，进入后就是**纯黑、零像素发光**——
     * OLED 上等于物理断电，既省电又彻底不烧屏。
     *
     * <p>只有用户触摸屏幕时才亮起提示（5 秒后自动熄灭），避免提示文字长期点亮同一簇像素。
     * 解锁参考 iQOO 游戏空间：按住屏幕向上滑动一段距离即解除，单点不会误触。</p>
     */
    private FrameLayout idleOverlay;
    private LinearLayout idleBox;
    private TextView idleArrow;
    private TextView idleHint;
    /** 上滑进度 0-1：控制箭头位移与提示百分比。 */
    private float idleProgress = 0f;
    /** 提示当前是否可见（可见才需要算熄灭倒计时）。 */
    private boolean idleHintsVisible = false;
    private float idleDownRawY = 0f;
    private float idleUnlockDistancePx = 0f;

    /** 触摸后提示亮起的时长；到点自动全黑。 */
    private final Runnable idleHideRunnable = new Runnable() {
        @Override
        public void run() {
            if (idleBox != null) {
                idleBox.setAlpha(0f);
                idleHintsVisible = false;
            }
            idleProgress = 0f;
        }
    };
    void buildIdleOverlay(FrameLayout container) {
        if (activity == null) return;
        pause();
        detachOverlay();
        float density = activity.getResources().getDisplayMetrics().density;
        FrameLayout overlay = new FrameLayout(activity);
        overlay.setBackgroundColor(Color.BLACK);
        overlay.setVisibility(View.GONE);

        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(android.view.Gravity.CENTER);
        box.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // 初始不可见：进挂机就是纯黑
        box.setAlpha(0f);
        overlay.addView(box);

        TextView title = new TextView(activity);
        title.setText("挂机中");
        title.setTextColor(0x1AFFFFFF);
        title.setTextSize(22);
        title.setGravity(android.view.Gravity.CENTER);
        box.addView(title);

        // iQOO 风格：向上箭头，按住上滑时跟着手指走
        TextView arrow = new TextView(activity);
        arrow.setText("↑");
        arrow.setTextColor(0x59FFFFFF);
        arrow.setTextSize(40);
        arrow.setGravity(android.view.Gravity.CENTER);
        LinearLayout.LayoutParams arrowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        arrowLp.topMargin = (int) (24 * density);
        arrow.setLayoutParams(arrowLp);
        box.addView(arrow);

        TextView hint = new TextView(activity);
        hint.setText("上滑解除");
        hint.setTextColor(0x40FFFFFF);
        hint.setTextSize(13);
        hint.setGravity(android.view.Gravity.CENTER);
        box.addView(hint);

        this.idleOverlay = overlay;
        this.idleBox = box;
        this.idleArrow = arrow;
        this.idleHint = hint;
        recalcIdleUnlockDistance();

        overlay.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                return handleIdleTouch(event);
            }
        });

        container.addView(overlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    /**
     * 重算上滑解锁阈值：按当前屏幕高度的 22%（不低于 200dp）。
     * 旋转/折叠屏展开会改变屏高，阈值要跟着重算，否则解锁手感会漂。
     */
    void recalcIdleUnlockDistance() {
        if (activity == null) return;
        float density = activity.getResources().getDisplayMetrics().density;
        this.idleUnlockDistancePx = Math.max(
                (int) (200 * density),
                (int) (activity.getResources().getDisplayMetrics().heightPixels * IDLE_UNLOCK_RATIO));
    }

    /**
     * 上滑解锁：任何触碰先把提示点亮（重置 5 秒计时），按住上滑越过阈值即解除，
     * 未达阈值松手回弹并继续倒计时熄灭。
     */
    private boolean handleIdleTouch(MotionEvent event) {
        if (activity == null || event == null) return false;
        return handleTouch(event.getActionMasked(), event.getRawY());
    }

    boolean handleTouch(int action, float rawY) {
        if (activity == null) return false;
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                this.idleDownRawY = rawY;
                wakeIdleHints();
                setIdleProgress(0f);
                return true;
            case MotionEvent.ACTION_MOVE:
                // 屏幕坐标向下为正，向上滑得正值
                updateIdleProgress((this.idleDownRawY - rawY)
                        / this.idleUnlockDistancePx);
                return true;
            case MotionEvent.ACTION_UP:
                updateIdleProgress((this.idleDownRawY - rawY)
                        / this.idleUnlockDistancePx);
                if (this.idleProgress >= 1f) {
                    unlockIdleMode();
                } else {
                    setIdleProgress(0f);
                    scheduleIdleHide();
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                setIdleProgress(0f);
                scheduleIdleHide();
                return true;
            default:
                return true;
        }
    }

    private int dpToPx(float dp) {
        return Math.round(dp * activity.getResources().getDisplayMetrics().density);
    }

    /**
     * 点亮提示并重置熄灭计时。从全黑状态唤醒时顺便随机偏移几十 dp，
     * 保证每次显示的位置都不同（防止同一簇像素被反复点亮）。
     */
    private void wakeIdleHints() {
        LinearLayout box = this.idleBox;
        if (box != null && !this.idleHintsVisible) {
            float jitter = dpToPx(26);
            box.setTranslationX((float) ((Math.random() * 2 - 1) * jitter));
            box.setTranslationY((float) ((Math.random() * 2 - 1) * jitter));
            box.setAlpha(1f);
            this.idleHintsVisible = true;
        }
        scheduleIdleHide();
    }

    private void scheduleIdleHide() {
        if (this.handler == null) {
            return;
        }
        this.handler.removeCallbacks(this.idleHideRunnable);
        this.handler.postDelayed(this.idleHideRunnable, IDLE_HINT_TIMEOUT_MS);
    }

    /** 更新上滑进度：0-1，箭头随手指上移，提示显示百分比。 */
    private void updateIdleProgress(float progress) {
        setIdleProgress(Math.max(0f, Math.min(1f, progress)));
    }

    private void setIdleProgress(float progress) {
        this.idleProgress = progress;
        TextView arrow = this.idleArrow;
        if (arrow != null) {
            try {
                arrow.setTranslationY(-dpToPx(90) * progress);
            } catch (Exception ignored) {
            }
        }
        TextView hint = this.idleHint;
        if (hint != null) {
            if (progress <= 0f) {
                hint.setText("上滑解除");
            } else if (progress >= 1f) {
                hint.setText("松开即可解除");
            } else {
                hint.setText("继续上滑 " + Math.round(progress * 100) + "%");
            }
        }
    }

    /** 退出挂机：关闭模式、恢复 WebView 显示与帧率。 */
    private void unlockIdleMode() {
        if (listener != null) listener.onUnlocked();
    }

    /**
     * 应用/取消挂机黑屏。开启时遮罩覆盖屏幕（纯黑、提示立即熄灭）、WebView 转 INVISIBLE
     * 停止合成，帧率同步压到 IDLE_FPS；关闭时全部还原。
     */
    void applyIdleMode(int mode, WebView wv) {
        if (activity == null) return;
        if (this.handler != null) {
            this.handler.removeCallbacks(this.idleHideRunnable);
        }
        boolean burnIn = mode == IDLE_BURNIN;
        FrameLayout overlay = this.idleOverlay;
        if (overlay != null) {
            this.idleProgress = 0f;
            this.idleHintsVisible = false;
            if (this.idleBox != null) {
                // 进挂机就是全黑：不显示任何提示，触摸后才亮 5 秒
                this.idleBox.setAlpha(0f);
                this.idleBox.setTranslationX(0f);
                this.idleBox.setTranslationY(0f);
            }
            if (this.idleArrow != null) {
                this.idleArrow.setTranslationY(0f);
            }
            if (this.idleHint != null) {
                this.idleHint.setText("上滑解除");
            }
            overlay.setVisibility(burnIn ? View.VISIBLE : View.GONE);
        }
        if (wv != null) {
            // 只有防烧屏模式才把 WebView 藏起来（停止合成）；普通挂机画面照常显示。
            wv.setVisibility(burnIn ? View.INVISIBLE : View.VISIBLE);
        }
        // 防烧屏要求"整屏零发光"：底部手势导航条是系统渲染的，纯黑遮罩盖不到它，
        // 一直亮着就等于一条常亮白线在烧屏。这里单独隐藏/还原导航栏。
        // 普通挂机不隐藏：用户还得随时看画面、调设置，藏掉导航栏只会碍事。
        SystemBars.setNavigationBarHidden(activity.getWindow(), burnIn);
        if (listener != null) listener.onApplied(mode);
        PerfLog.i("IdleController", "idle mode: " + mode);
        PerfMonitor.count(mode == IDLE_OFF ? "mode.idle.off"
                : (burnIn ? "mode.idle.burnin" : "mode.idle.normal"));
    }

}
