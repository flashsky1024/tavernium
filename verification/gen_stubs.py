#!/usr/bin/env python3
"""生成最小 Android API 桩，用于在无 Android SDK 的环境下对源文件做 javac 编译校验。
只覆盖源码实际引用到的符号，签名与真实 API 保持一致。

用法: ./gen_stubs.py [应用目录]   # 默认 ../tavernium
"""
import os
import re
import sys

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "stubs")
_HERE = os.path.dirname(os.path.abspath(__file__))
APP_SRC = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
    os.path.dirname(_HERE), "tavernium")


def read_app_version():
    """从 app/build.gradle 读取版本，保证桩里的 BuildConfig 与真实构建一致。"""
    try:
        with open(os.path.join(APP_SRC, "app", "build.gradle"), encoding="utf-8") as f:
            text = f.read()
    except OSError:
        return ("0.0.0", 0)
    name = re.search(r"def\s+tkVersionName\s*=\s*'([^']+)'", text)
    if not name:
        name = re.search(r"versionName\s+'([^']+)'", text)
    code = re.search(r"versionCode\s+(\d+)", text)
    return (name.group(1) if name else "0.0.0", int(code.group(1)) if code else 0)

FILES = {
"android/R.java": """
package android;
public final class R {
    public static final class attr {
        public static final int progressBarStyleHorizontal = 16842872;
    }
    public static final class drawable {
        public static final int ic_dialog_info = 17301659;
        public static final int ic_menu_close_clear_cancel = 17301543;
    }
    public static final class layout {
        public static final int simple_list_item_1 = 17367043;
    }
}
""",
"android/os/Build.java": """
package android.os;
public class Build { public static class VERSION { public static int SDK_INT = 34; } }
""",
"android/os/Bundle.java": """
package android.os;
public class Bundle {}
""",
"android/os/Environment.java": """
package android.os;
public class Environment {
    public static final String DIRECTORY_DOWNLOADS = "Download";
    public static java.io.File getExternalStoragePublicDirectory(String type) {
        return new java.io.File(System.getProperty("tk.downloadDir", System.getProperty("java.io.tmpdir")));
    }
}
""",
"android/os/Handler.java": """
package android.os;
public class Handler {
    public Handler() {}
    public Handler(Looper looper) {}
    public boolean post(Runnable r) { return true; }
    public boolean postDelayed(Runnable r, long delayMillis) { return true; }
    public void removeCallbacks(Runnable r) {}
}
""",
"android/os/Looper.java": """
package android.os;
public class Looper { public static Looper getMainLooper() { return null; } }
""",
"android/os/PowerManager.java": """
package android.os;
public class PowerManager {
    public static final int PARTIAL_WAKE_LOCK = 1;
    public boolean isInteractive() { return true; }
    public boolean isIgnoringBatteryOptimizations(String pkg) { return true; }
    public WakeLock newWakeLock(int levelAndFlags, String tag) { return null; }
    public static class WakeLock {
        public void setReferenceCounted(boolean value) {}
        public void acquire(long timeout) {}
        public void acquire() {}
        public void release() {}
        public boolean isHeld() { return false; }
    }
}
""",
"android/os/SystemClock.java": """
package android.os;
public class SystemClock { public static long elapsedRealtime() { return System.nanoTime() / 1000000L; } }
""",
"android/os/Process.java": """
package android.os;
public class Process {
    public static int myPid() {
        return (int) ProcessHandle.current().pid();
    }
    public static int myUid() { return 1000; }
    public static long getStartElapsedRealtime() { return System.nanoTime() / 1000000L; }
}
""",
"android/os/Debug.java": """
package android.os;
public class Debug {
    public static long getNativeHeapAllocatedSize() { return 0L; }
    public static long getNativeHeapSize() { return 0L; }
}
""",
"android/os/IBinder.java": """
package android.os;
public interface IBinder {}
""",
"android/app/ActivityManager.java": """
package android.app;
import java.util.List;
public class ActivityManager {
    public List<RunningAppProcessInfo> getRunningAppProcesses() {
        return new java.util.ArrayList<RunningAppProcessInfo>();
    }
    public static class RunningAppProcessInfo {
        public int pid;
        public String processName;
        public String[] pkgList;
    }
}
""",
"android/content/Context.java": """
package android.content;
public class Context {
    public static final int MODE_PRIVATE = 0;
    public static final int RESULT_OK = -1;
    public static final String POWER_SERVICE = "power";
    public static final String ACTIVITY_SERVICE = "activity";
    public SharedPreferences getSharedPreferences(String name, int mode) { return null; }
    public java.io.File getCacheDir() {
        return new java.io.File(System.getProperty("tk.cacheDir", System.getProperty("java.io.tmpdir")));
    }
    public java.io.File getFilesDir() {
        return new java.io.File(System.getProperty("tk.filesDir", System.getProperty("java.io.tmpdir")));
    }
    public java.io.File getExternalFilesDir(String type) {
        return new java.io.File(System.getProperty("tk.extDir", System.getProperty("java.io.tmpdir")));
    }
    public ContentResolver getContentResolver() { return null; }
    public android.content.pm.PackageManager getPackageManager() { return null; }
    public String getPackageName() { return null; }
    public android.content.res.Resources getResources() { return null; }
    public android.content.res.AssetManager getAssets() { return null; }
    public int checkSelfPermission(String permission) { return android.content.pm.PackageManager.PERMISSION_GRANTED; }
    public Object getSystemService(String name) { return null; }
    public <T> T getSystemService(Class<T> serviceClass) { return null; }
    public Context getApplicationContext() { return null; }
    public void startActivity(Intent intent) {}
    public void startService(Intent service) {}
    public void startForegroundService(Intent service) {}
    public void startActivityForResult(Intent intent, int requestCode) {}
}
""",
"android/content/ContentValues.java": """
package android.content;
public class ContentValues {
    public void put(String key, String value) {}
    public void put(String key, Integer value) {}
    public void put(String key, Long value) {}
    public void clear() {}
}
""",
"android/content/SharedPreferences.java": """
package android.content;
public interface SharedPreferences {
    String getString(String key, String defValue);
    boolean getBoolean(String key, boolean defValue);
    int getInt(String key, int defValue);
    long getLong(String key, long defValue);
    Editor edit();
    interface Editor {
        Editor putString(String key, String value);
        Editor putBoolean(String key, boolean value);
        Editor putInt(String key, int value);
        Editor putLong(String key, long value);
        Editor remove(String key);
        void apply();
    }
}
""",
"android/content/Intent.java": """
package android.content;
public class Intent {
    public static final String ACTION_VIEW = "android.intent.action.VIEW";
    public Intent() {}
    public Intent(Context packageContext) {}
    public Intent(Context packageContext, Class<?> cls) {}
    public Intent(String action) {}
    public Intent(String action, android.net.Uri uri) {}
    public Intent addCategory(String category) { return this; }
    public Intent setType(String type) { return this; }
    public Intent putExtra(String name, boolean value) { return this; }
    public Intent putExtra(String name, String value) { return this; }
    public Intent setData(android.net.Uri data) { return this; }
    public Intent setAction(String action) { return this; }
    public Intent setDataAndType(android.net.Uri data, String type) { return this; }
    public Intent addFlags(int flags) { return this; }
    public static final int FLAG_GRANT_READ_URI_PERMISSION = 1;
    public static final int FLAG_ACTIVITY_NEW_TASK = 268435456;
    public android.net.Uri getData() { return null; }
    public ClipData getClipData() { return null; }
    public String getAction() { return null; }
}
""",
"android/content/ClipData.java": """
package android.content;
public class ClipData {
    public int getItemCount() { return 0; }
    public Item getItemAt(int index) { return null; }
    public static class Item { public android.net.Uri getUri() { return null; } }
}
""",
"android/content/ContentResolver.java": """
package android.content;
public class ContentResolver {
    public android.net.Uri insert(android.net.Uri url, ContentValues values) { return null; }
    public java.io.OutputStream openOutputStream(android.net.Uri uri) { return null; }
    public int update(android.net.Uri uri, ContentValues values, String where, String[] selectionArgs) { return 0; }
}
""",
"android/content/DialogInterface.java": """
package android.content;
public interface DialogInterface {
    void cancel();
    void dismiss();
    interface OnClickListener { void onClick(DialogInterface dialog, int which); }
    interface OnDismissListener { void onDismiss(DialogInterface dialog); }
}
""",
"android/content/pm/ServiceInfo.java": """
package android.content.pm;
public class ServiceInfo { public static final int FOREGROUND_SERVICE_TYPE_SPECIAL_USE = 1073741824; }
""",
"android/content/pm/PackageInfo.java": """
package android.content.pm;
public class PackageInfo {
    public String packageName;
    public String versionName;
    public int versionCode;
    public Signature[] signatures;
}
""",
"android/content/res/Configuration.java": """
package android.content.res;
public class Configuration {
    public static final int ORIENTATION_PORTRAIT = 1;
    public static final int ORIENTATION_LANDSCAPE = 2;
    public int orientation;
    public int uiMode;
    public int screenWidthDp;
    public int screenHeightDp;
    public float fontScale;
}
""",
"android/content/res/Resources.java": """
package android.content.res;
public class Resources { public android.util.DisplayMetrics getDisplayMetrics() { return null; } }
""",
"android/util/DisplayMetrics.java": """
package android.util;
public class DisplayMetrics { public float density; public int widthPixels; public int heightPixels; }
""",
"android/util/Base64.java": """
package android.util;
public class Base64 {
    public static final int NO_WRAP = 2;
    public static String encodeToString(byte[] input, int flags) {
        return java.util.Base64.getEncoder().encodeToString(input);
    }
    public static byte[] decode(String str, int flags) {
        return java.util.Base64.getDecoder().decode(str);
    }
}
""",
"android/util/Log.java": """
package android.util;
public class Log {
    private static void p(String lvl, String tag, String msg, Throwable tr) {
        System.err.println("[" + lvl + "/" + tag + "] " + msg);
        if (tr != null) tr.printStackTrace();
    }
    public static int w(String tag, String msg) { p("W", tag, msg, null); return 0; }
    public static int w(String tag, String msg, Throwable tr) { p("W", tag, msg, tr); return 0; }
    public static int d(String tag, String msg) { p("D", tag, msg, null); return 0; }
    public static int d(String tag, String msg, Throwable tr) { p("D", tag, msg, tr); return 0; }
    public static int i(String tag, String msg) { p("I", tag, msg, null); return 0; }
    public static int i(String tag, String msg, Throwable tr) { p("I", tag, msg, tr); return 0; }
    public static int e(String tag, String msg) { p("E", tag, msg, null); return 0; }
}
""",
"android/net/Uri.java": """
package android.net;
public class Uri {
    private final String raw;
    private final String path;
    private final String userInfo;
    private Uri(String raw, String path, String userInfo) {
        this.raw = raw; this.path = path; this.userInfo = userInfo;
    }
    public static Uri parse(String s) {
        if (s == null) return null;
        String p = s;
        int q = p.indexOf('?'); if (q >= 0) p = p.substring(0, q);
        int h = p.indexOf('#'); if (h >= 0) p = p.substring(0, h);
        int sc = p.indexOf("://");
        String rest = sc >= 0 ? p.substring(sc + 3) : p;
        int slash = rest.indexOf('/');
        String authority = slash >= 0 ? rest.substring(0, slash) : rest;
        String path = slash >= 0 ? rest.substring(slash) : "";
        String ui = null;
        int at = authority.indexOf('@');
        if (at >= 0) ui = authority.substring(0, at);
        return new Uri(s, path, ui);
    }
    public String getUserInfo() { return userInfo; }
    public String getPath() { return path; }
    public String getLastPathSegment() {
        if (path == null || path.isEmpty()) return null;
        int slash = path.lastIndexOf('/');
        String seg = slash >= 0 ? path.substring(slash + 1) : path;
        return seg.isEmpty() ? null : seg;
    }
    public String getHost() { return null; }
    public String getScheme() { return null; }
    @Override public String toString() { return raw; }
}
""",
"android/graphics/Bitmap.java": """
package android.graphics;
public class Bitmap {}
""",
"android/graphics/Color.java": """
package android.graphics;
public class Color { public static final int BLACK = -16777216; public static final int WHITE = -1; public static final int TRANSPARENT = 0; public static int parseColor(String colorString) { return 0; } }
""",
"android/graphics/Paint.java": """
package android.graphics;
public class Paint {}
""",
"android/provider/MediaStore.java": """
package android.provider;
public class MediaStore {
    public static class Downloads { public static final android.net.Uri EXTERNAL_CONTENT_URI = android.net.Uri.parse("content://media/external/downloads"); }
}
""",
"android/text/Editable.java": """
package android.text;
public interface Editable extends CharSequence {}
""",
"android/view/View.java": """
package android.view;
public class View {
    protected ViewGroup parent;
    private int visibility;
    private int systemUiVisibility;
    private float alpha = 1f;
    public static final int VISIBLE = 0;
    public static final int INVISIBLE = 4;
    public static final int GONE = 8;
    public static final int SYSTEM_UI_FLAG_IMMERSIVE_STICKY = 4096;
    public static final int SYSTEM_UI_FLAG_FULLSCREEN = 4;
    public static final int SYSTEM_UI_FLAG_HIDE_NAVIGATION = 2;
    public static final int SYSTEM_UI_FLAG_LAYOUT_STABLE = 256;
    public static final int SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN = 1024;
    public static final int SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION = 512;
    public static final int FOCUS_DOWN = 130;
    public View(android.content.Context context) {}
    public void setBackgroundColor(int color) {}
    public void setKeepScreenOn(boolean keepScreenOn) {}
    public void setVisibility(int visibility) { this.visibility = visibility; }
    public void setSystemUiVisibility(int visibility) { systemUiVisibility = visibility; }
    public int getSystemUiVisibility() { return systemUiVisibility; }
    public ViewGroup getParent() { return parent; }
    public void setPadding(int left, int top, int right, int bottom) {}
    public void setMinimumHeight(int minHeight) {}
    public android.view.ViewGroup.LayoutParams getLayoutParams() { return null; }
    public void setOnTouchListener(OnTouchListener listener) {}
    public void setOnClickListener(OnClickListener listener) {}
    public interface OnClickListener {
        void onClick(View v);
    }
    public int getWidth() { return 0; }
    public interface OnTouchListener {
        boolean onTouch(View v, android.view.MotionEvent event);
    }
    public void setAlpha(float alpha) { this.alpha = alpha; }
    public float getAlpha() { return alpha; }
    public void setTranslationX(float translationX) {}
    public void setTranslationY(float translationY) {}
    public void setLineSpacing(float add, float mult) {}
    public void setLayoutParams(ViewGroup.LayoutParams params) {}
    public int getVisibility() { return visibility; }
    public boolean post(Runnable action) { return true; }
}
""",
"android/view/ViewGroup.java": """
package android.view;
public class ViewGroup extends View {
    public ViewGroup(android.content.Context context) { super(context); }
    public static class LayoutParams {
        public static final int MATCH_PARENT = -1;
        public static final int WRAP_CONTENT = -2;
        public int width;
        public int height;
        public LayoutParams() {}
        public LayoutParams(int width, int height) { this.width = width; this.height = height; }
    }
    private final java.util.List<View> children = new java.util.ArrayList<>();
    public int getChildCount() { return children.size(); }
    public void addView(View child) { children.add(child); child.parent = this; }
    public void addView(View child, ViewGroup.LayoutParams params) { addView(child); }
    public void removeView(View view) { children.remove(view); view.parent = null; }
    public void removeAllViews() { for (View view : children) view.parent = null; children.clear(); }
}
""",
"android/view/Window.java": """
package android.view;
public class Window {
    public void setFlags(int flags, int mask) {}
    public void addFlags(int flags) {}
    public void clearFlags(int flags) {}
    public View getDecorView() { return null; }
    public void addContentView(View view, ViewGroup.LayoutParams params) {}
    public WindowInsetsController getInsetsController() { return null; }
    public WindowManager.LayoutParams getAttributes() { return new WindowManager.LayoutParams(); }
    public void setAttributes(WindowManager.LayoutParams params) {}
    // 注意：真实 SDK 中 Window 没有 setFrameRate —— 帧率 API 在 Surface(View 层为 API 35+ 的
    // View.setRequestedFrameRate)。桩不得臆造 API，否则会掩盖真实编译错误。
}
""",
"android/view/WindowInsets.java": """
package android.view;
public class WindowInsets {
    public static class Type {
        public static int systemBars() { return 3; }
        public static int navigationBars() { return 2; }
    }
}
""",
"android/view/WindowInsetsController.java": """
package android.view;
public interface WindowInsetsController {
    int BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE = 2;
    void hide(int types);
    void show(int types);
    void setSystemBarsBehavior(int behavior);
}
""",
"android/view/WindowManager.java": """
package android.view;
public class WindowManager {
    public Display getDefaultDisplay() { return null; }
    public static class LayoutParams {
        public static final int FLAG_KEEP_SCREEN_ON = 128;
        public static final int FLAG_FULLSCREEN = 1024;
        public float preferredRefreshRate;
        /** 窗口亮度：0-1 为绝对值，-1 表示跟随系统。 */
        public float screenBrightness = -1f;
        // 注意：FRAME_RATE_COMPATIBILITY_* 常量在真实 SDK 中属于 android.view.Surface，
        // 不在 LayoutParams 上；桩不得臆造（曾因此掩盖过真实编译错误）。
    }
}
""",
"android/view/Gravity.java": """
package android.view;
public class Gravity {
    public static final int CENTER = 17;
    public static final int CENTER_VERTICAL = 16;
}
""",
"android/view/MotionEvent.java": """
package android.view;
public class MotionEvent {
    public static final int ACTION_DOWN = 0;
    public static final int ACTION_UP = 1;
    public static final int ACTION_MOVE = 2;
    public static final int ACTION_CANCEL = 3;
    public int getActionMasked() { return 0; }
    public float getRawX() { return 0f; }
    public float getRawY() { return 0f; }
}
""",
"android/view/Display.java": """
package android.view;
public final class Display {
    public float getRefreshRate() { return 60f; }
}
""",
"android/view/KeyEvent.java": """
package android.view;
public class KeyEvent {
    public static final int KEYCODE_BACK = 4;
    public static final int ACTION_UP = 1;
    public int getKeyCode() { return 0; }
    public int getAction() { return 0; }
    public int getRepeatCount() { return 0; }
}
""",
"android/app/Activity.java": """
package android.app;
import android.content.Intent;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
public class Activity extends android.content.Context {
    public Intent getIntent() { return null; }
    protected void onNewIntent(Intent intent) {}
    protected void onCreate(android.os.Bundle savedInstanceState) {}
    protected void onStart() {}
    protected void onResume() {}
    protected void onPause() {}
    protected void onStop() {}
    protected void onDestroy() {}
    public void onTrimMemory(int level) {}
    public void setContentView(View view) {}
    public Window getWindow() { return null; }
    public android.view.WindowManager getWindowManager() { return null; }
    public boolean isFinishing() { return false; }
    public boolean isDestroyed() { return false; }
    public void setShowWhenLocked(boolean showWhenLocked) {}
    public void setTurnScreenOn(boolean turnScreenOn) {}
    public void runOnUiThread(Runnable action) {}
    public void onBackPressed() {}
    public boolean onKeyDown(int keyCode, KeyEvent event) { return false; }
    public boolean dispatchKeyEvent(KeyEvent event) { return false; }
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {}
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {}
    public void requestPermissions(String[] permissions, int requestCode) {}
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {}
}
""",
"android/app/AlertDialog.java": """
package android.app;
import android.content.DialogInterface;
import android.view.View;
public class AlertDialog implements android.content.DialogInterface {
    public void setOnDismissListener(OnDismissListener listener) {}
    public void show() {}
    public void cancel() {}
    public void dismiss() {}
    public static class Builder {
        public Builder(android.content.Context context) {}
        public Builder setTitle(CharSequence title) { return this; }
        public Builder setTitle(int titleId) { return this; }
        public Builder setMessage(CharSequence message) { return this; }
        public Builder setView(View view) { return this; }
        public Builder setCancelable(boolean cancelable) { return this; }
        public Builder setPositiveButton(CharSequence text, OnClickListener listener) { return this; }
        public Builder setNeutralButton(CharSequence text, DialogInterface.OnClickListener listener) { return this; }
        public Builder setNegativeButton(CharSequence text, OnClickListener listener) { return this; }
        public Builder setItems(CharSequence[] items, OnClickListener listener) { return this; }
        public Builder setItems(int itemsId, OnClickListener listener) { return this; }
        public AlertDialog create() { return null; }
        public AlertDialog show() { return null; }
    }
}
""",
"android/app/Service.java": """
package android.app;
import android.content.Intent;
import android.os.IBinder;
public class Service extends android.content.Context {
    public static final int START_STICKY = 1;
    public static final int START_NOT_STICKY = 2;
    public IBinder onBind(Intent intent) { return null; }
    public void onCreate() {}
    public int onStartCommand(Intent intent, int flags, int startId) { return 0; }
    public void onDestroy() {}
    public void onTaskRemoved(Intent rootIntent) {}
    public final void startForeground(int id, Notification notification) {}
    public final void startForeground(int id, Notification notification, int foregroundServiceType) {}
    public final void stopForeground(boolean removeNotification) {}
    public final void stopSelf() {}
}
""",
"android/app/Notification.java": """
package android.app;
public class Notification {
    public CharSequence title;
    public CharSequence text;
    public int visibility;
    public boolean autoCancel;
    public static final int VISIBILITY_PUBLIC = 1;
    public static final int PRIORITY_LOW = -1;
    public static final String CATEGORY_SERVICE = "service";
    public static final String CATEGORY_MESSAGE = "msg";
    public static class Builder {
        private final Notification notification = new Notification();
        public Builder(android.content.Context context) {}
        public Builder(android.content.Context context, String channelId) {}
        public Builder setContentTitle(CharSequence title) { notification.title = title; return this; }
        public Builder setContentText(CharSequence text) { notification.text = text; return this; }
        public Builder setSmallIcon(int icon) { return this; }
        public Builder setContentIntent(PendingIntent intent) { return this; }
        public Builder setOngoing(boolean ongoing) { return this; }
        public Builder setCategory(String category) { return this; }
        public Builder setPriority(int pri) { return this; }
        public Builder setShowWhen(boolean show) { return this; }
        public Builder setVisibility(int visibility) { notification.visibility = visibility; return this; }
        public Builder setAutoCancel(boolean value) { notification.autoCancel = value; return this; }
        public Builder addAction(Action action) { return this; }
        public Notification build() { return notification; }
    }
    public static class Action {
        public static class Builder {
            public Builder(int icon, CharSequence title, PendingIntent intent) {}
            public Action build() { return null; }
        }
    }
}
""",
"android/app/NotificationChannel.java": """
package android.app;
public class NotificationChannel {
    private final int importance;
    public NotificationChannel(String id, CharSequence name, int importance) { this.importance = importance; }
    public int getImportance() { return importance; }
    public void setDescription(String description) {}
    public void setShowBadge(boolean showBadge) {}
    public void setLockscreenVisibility(int lockscreenVisibility) {}
}
""",
"android/app/NotificationManager.java": """
package android.app;
public class NotificationManager {
    public static final int IMPORTANCE_NONE = 0;
    public static final int IMPORTANCE_LOW = 2;
    public static final int IMPORTANCE_DEFAULT = 3;
    public boolean areNotificationsEnabled() { return true; }
    public NotificationChannel getNotificationChannel(String id) { return null; }
    public void createNotificationChannel(NotificationChannel channel) {}
    public void notify(int id, Notification notification) {}
    public void cancel(int id) {}
}
""",
"android/app/KeyguardManager.java": """
package android.app;
public class KeyguardManager {
    public boolean isKeyguardLocked() { return false; }
}
""",
"android/content/res/AssetManager.java": """
package android.content.res;
public class AssetManager {
    public java.io.InputStream open(String name) throws java.io.IOException {
        throw new java.io.FileNotFoundException(name);
    }
}
""",
"android/app/PendingIntent.java": """
package android.app;
import android.content.Intent;
public class PendingIntent {
    public static final int FLAG_UPDATE_CURRENT = 134217728;
    public static final int FLAG_IMMUTABLE = 67108864;
    public static PendingIntent getActivity(android.content.Context context, int requestCode,
                                            Intent intent, int flags) { return null; }
    public static PendingIntent getService(android.content.Context context, int requestCode,
                                           Intent intent, int flags) { return null; }
}
""",
"android/webkit/JavascriptInterface.java": """
package android.webkit;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface JavascriptInterface {}
""",
"android/webkit/WebViewDatabase.java": """
package android.webkit;
public class WebViewDatabase {
    private static final WebViewDatabase INSTANCE = new WebViewDatabase();
    public static WebViewDatabase getInstance(android.content.Context context) { return INSTANCE; }
    public void clearHttpAuthUsernamePassword() {}
    public void clearUsernamePassword() {}
}
""",
"android/webkit/ValueCallback.java": """
package android.webkit;
public interface ValueCallback<T> { void onReceiveValue(T value); }
""",
"android/webkit/DownloadListener.java": """
package android.webkit;
public interface DownloadListener {
    void onDownloadStart(String url, String userAgent, String contentDisposition,
                         String mimetype, long contentLength);
}
""",
"android/webkit/HttpAuthHandler.java": """
package android.webkit;
public class HttpAuthHandler {
    public void proceed(String username, String password) {}
    public void cancel() {}
}
""",
"android/webkit/ValueCallback.java": """
package android.webkit;
public interface ValueCallback<T> {
    void onReceiveValue(T value);
}
""",
"android/webkit/WebResourceResponse.java": """
package android.webkit;
public class WebResourceResponse {
    public final String mimeType;
    public final String encoding;
    public final java.io.InputStream data;
    public WebResourceResponse(String mimeType, String encoding, java.io.InputStream data) {
        this.mimeType = mimeType; this.encoding = encoding; this.data = data;
    }
    public WebResourceResponse(String mimeType, String encoding, int statusCode, String reason,
                               java.util.Map<String, String> headers, java.io.InputStream data) {
        this(mimeType, encoding, data);
        this.statusCode = statusCode;
        this.reason = reason;
        setResponseHeaders(headers);
    }
    private int statusCode = 200;
    private String reason = "OK";
    private java.util.Map<String, String> headers = new java.util.HashMap<>();
    public void setResponseHeaders(java.util.Map<String, String> headers) {
        this.headers = headers == null ? new java.util.HashMap<>() : new java.util.HashMap<>(headers);
    }
    public java.util.Map<String, String> getResponseHeaders() { return this.headers; }
    public int getStatusCode() { return statusCode; }
    public String getReasonPhrase() { return reason; }
}
""",
"android/webkit/WebResourceError.java": """
package android.webkit;
public abstract class WebResourceError {
    public abstract int getErrorCode();
    public abstract CharSequence getDescription();
}
""",
"android/webkit/WebResourceRequest.java": """
package android.webkit;
public interface WebResourceRequest {
    android.net.Uri getUrl();
    String getMethod();
    boolean isForMainFrame();
    boolean hasGesture();
    java.util.Map<String, String> getRequestHeaders();
}
""",
"android/webkit/WebSettings.java": """
package android.webkit;
public class WebSettings {
    public static final int LOAD_DEFAULT = -1;
    public static final int LOAD_NO_CACHE = 2;
    public static final int MIXED_CONTENT_ALWAYS_ALLOW = 0;
    public static final int MIXED_CONTENT_NEVER_ALLOW = 1;
    public void setJavaScriptEnabled(boolean flag) {}
    public void setDomStorageEnabled(boolean flag) {}
    public void setDatabaseEnabled(boolean flag) {}
    public void setMediaPlaybackRequiresUserGesture(boolean require) {}
    public void setCacheMode(int mode) {}
    public void setMixedContentMode(int mode) {}
    public void setAllowFileAccess(boolean allow) {}
    public void setAllowContentAccess(boolean allow) {}
    public void setAllowFileAccessFromFileURLs(boolean flag) {}
    public void setAllowUniversalAccessFromFileURLs(boolean flag) {}
    public void setSupportZoom(boolean support) {}
    public void setBuiltInZoomControls(boolean enabled) {}
    public void setGeolocationEnabled(boolean flag) {}
    public void setUserAgentString(String ua) {}
    public String getUserAgentString() { return null; }
}
""",
"android/webkit/WebViewClient.java": """
package android.webkit;
public class WebViewClient {
    public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) { return false; }
    public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {}
    public void onPageFinished(WebView view, String url) {}
    public void onReceivedHttpAuthRequest(WebView view, HttpAuthHandler handler,
                                          String host, String realm) {}
    public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) { return null; }
    public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) { return false; }
    public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {}
    public void onReceivedHttpError(WebView view, WebResourceRequest request,
                                    WebResourceResponse errorResponse) {}
}
""",
"android/webkit/RenderProcessGoneDetail.java": """
package android.webkit;
/** API 26+；compileSdk 34+ 起为顶层类（旧版曾嵌套在 WebViewClient 内）。 */
public abstract class RenderProcessGoneDetail {
    public abstract boolean didCrash();
    public abstract int rendererPriorityAtExit();
}
""",
"android/webkit/WebChromeClient.java": """
package android.webkit;
import android.net.Uri;
import android.view.View;
public class WebChromeClient {
    public boolean onShowFileChooser(WebView webView, ValueCallback<android.net.Uri[]> filePathCallback,
                                     FileChooserParams fileChooserParams) { return false; }
    public void onShowCustomView(View view, CustomViewCallback callback) {}
    public void onHideCustomView() {}
    public boolean onConsoleMessage(ConsoleMessage consoleMessage) { return false; }
    public static class FileChooserParams {
        public static final int MODE_OPEN = 1;
        public static final int MODE_OPEN_MULTIPLE = 2;
        public int getMode() { return 0; }
    }
    public interface CustomViewCallback { void onCustomViewHidden(); }
}
""",
"android/webkit/ConsoleMessage.java": """
package android.webkit;
public class ConsoleMessage {
    public String message() { return ""; }
    public String sourceId() { return ""; }
    public int lineNumber() { return 0; }
    public MessageLevel messageLevel() { return MessageLevel.LOG; }
    public enum MessageLevel { TIP, LOG, WARNING, ERROR, DEBUG }
}
""",
"android/webkit/CookieManager.java": """
package android.webkit;
public class CookieManager {
    private static final CookieManager INSTANCE = new CookieManager();
    public static CookieManager getInstance() { return INSTANCE; }
    public void removeAllCookies(ValueCallback<Boolean> callback) {}
    public void flush() {}
    public void setAcceptCookie(boolean accept) {}
    public void setAcceptThirdPartyCookies(WebView webView, boolean accept) {}
}
""",
"android/webkit/WebViewDatabase.java": """
package android.webkit;
public class WebViewDatabase {
    public static WebViewDatabase getInstance(android.content.Context context) { return null; }
}
""",
"android/webkit/WebView.java": """
package android.webkit;
public class WebView extends android.view.View {
    public WebView(android.content.Context context) { super(context); }
    public WebSettings getSettings() { return null; }
    public void addJavascriptInterface(Object object, String name) {}
    public void removeJavascriptInterface(String name) {}
    public void setWebViewClient(WebViewClient client) {}
    public void setWebChromeClient(WebChromeClient client) {}
    public void setDownloadListener(DownloadListener listener) {}
    public void evaluateJavascript(String script, ValueCallback<String> resultCallback) {}
    public void loadUrl(String url) {}
    public String getUrl() { return null; }
    public void reload() {}
    public void goBack() {}
    public boolean canGoBack() { return false; }
    public void clearCache(boolean includeDiskFiles) {}
    public void clearHistory() {}
    public void stopLoading() {}
    public void destroy() {}
    public void onResume() {}
    public void onPause() {}
    public void setLayerType(int layerType, android.graphics.Paint paint) {}
    /** 预设 HTTP Basic Auth 凭据；realm=null 匹配任意 realm。 */
    public void setHttpAuthUsernamePassword(String host, String realm, String username, String password) {}
    /** API 33+：在文档创建时执行脚本（用于在页面脚本之前安装 hook）。 */
    public Object addDocumentStartJavaScript(String script, java.util.Set<String> allowedOriginRules) { return null; }
    public static android.content.pm.PackageInfo getCurrentWebViewPackage() { return null; }
}
""",
"android/widget/TextView.java": """
package android.widget;
public class TextView extends android.view.View {
    public TextView(android.content.Context context) { super(context); }
    public void setHint(CharSequence hint) {}
    public void setText(CharSequence text) {}
    public void setTextSize(float size) {}
    public void setTextColor(int color) {}
    public void setGravity(int gravity) {}
    public android.text.Editable getText() { return null; }
    public void setSingleLine(boolean singleLine) {}
    public void setInputType(int type) {}
}
""",
"android/widget/ScrollView.java": """
package android.widget;
public class ScrollView extends android.view.ViewGroup {
    public ScrollView(android.content.Context context) { super(context); }
    public void addView(android.view.View child) {}
    public void fullScroll(int direction) {}
}
""",
"android/widget/EditText.java": """
package android.widget;
public class EditText extends TextView {
    public EditText(android.content.Context context) { super(context); }
}
""",
"android/widget/LinearLayout.java": """
package android.widget;
public class LinearLayout extends android.view.ViewGroup {
    public static final int VERTICAL = 1;
    public static final int HORIZONTAL = 0;
    public LinearLayout(android.content.Context context) { super(context); }
    public void setOrientation(int orientation) {}
    public void setGravity(int gravity) {}
    public static class LayoutParams extends android.view.ViewGroup.LayoutParams {
        public int topMargin;
        public LayoutParams() {}
        public LayoutParams(int width, int height) { super(width, height); }
    }
}
""",
"android/widget/FrameLayout.java": """
package android.widget;
public class FrameLayout extends android.view.ViewGroup {
    public FrameLayout(android.content.Context context) { super(context); }
    public static class LayoutParams extends android.view.ViewGroup.LayoutParams {
        public int gravity;
        public LayoutParams() {}
        public LayoutParams(int width, int height) { super(width, height); }
    }
}
""",
"android/content/pm/PackageManager.java": """
package android.content.pm;
public class PackageManager {
    public static final int PERMISSION_GRANTED = 0;
    public static final int PERMISSION_DENIED = -1;
    public static final int GET_SIGNATURES = 64;
    public boolean canRequestPackageInstalls() { return true; }
    public PackageInfo getPackageInfo(String packageName, int flags)
            throws NameNotFoundException { return null; }
    public PackageInfo getPackageArchiveInfo(String archiveFilePath, int flags) { return null; }
    public static class NameNotFoundException extends Exception {}
}
""",
"android/content/pm/Signature.java": """
package android.content.pm;
public class Signature {
    private final byte[] data;
    public Signature(byte[] data) { this.data = data; }
    public byte[] toByteArray() { return data; }
    public int hashCode() { return java.util.Arrays.hashCode(data); }
    public boolean equals(Object o) {
        return o instanceof Signature && java.util.Arrays.equals(data, ((Signature) o).data);
    }
}
""",
"android/provider/Settings.java": """
package android.provider;
public class Settings {
    public static final String ACTION_MANAGE_UNKNOWN_APP_SOURCES = "android.settings.MANAGE_UNKNOWN_APP_SOURCES";
    public static final String ACTION_APPLICATION_DETAILS_SETTINGS = "android.settings.APPLICATION_DETAILS_SETTINGS";
}
""",
"android/provider/OpenableColumns.java": """
package android.provider;
public interface OpenableColumns {
    String DISPLAY_NAME = "_display_name";
    String SIZE = "_size";
}
""",
"android/database/Cursor.java": """
package android.database;
public interface Cursor {
    boolean moveToFirst();
    int getColumnIndex(String columnName);
    String getString(int columnIndex);
    long getLong(int columnIndex);
    void close();
}
""",
"android/database/MatrixCursor.java": """
package android.database;
public class MatrixCursor implements Cursor {
    private final String[] columns;
    private Object[] row;
    public MatrixCursor(String[] columnNames, int initialCapacity) { this.columns = columnNames; }
    public void addRow(Object[] values) { this.row = values; }
    public boolean moveToFirst() { return row != null; }
    public int getColumnIndex(String columnName) {
        for (int i = 0; i < columns.length; i++) if (columns[i].equals(columnName)) return i;
        return -1;
    }
    public String getString(int columnIndex) { return row == null ? null : String.valueOf(row[columnIndex]); }
    public long getLong(int columnIndex) { return row == null ? 0 : ((Number) row[columnIndex]).longValue(); }
    public void close() {}
}
""",
"android/os/ParcelFileDescriptor.java": """
package android.os;
public class ParcelFileDescriptor {
    public static final int MODE_READ_ONLY = 268435456;
    public static ParcelFileDescriptor open(java.io.File file, int mode) throws java.io.FileNotFoundException {
        return new ParcelFileDescriptor();
    }
}
""",
"android/content/ContentProvider.java": """
package android.content;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
public abstract class ContentProvider {
    public abstract boolean onCreate();
    public abstract Cursor query(Uri uri, String[] projection, String selection,
                                 String[] selectionArgs, String sortOrder);
    public abstract String getType(Uri uri);
    public abstract Uri insert(Uri uri, ContentValues values);
    public abstract int delete(Uri uri, String selection, String[] selectionArgs);
    public abstract int update(Uri uri, ContentValues values, String selection, String[] selectionArgs);
    public abstract ParcelFileDescriptor openFile(Uri uri, String mode)
            throws java.io.FileNotFoundException;
    public Context getContext() { return null; }
}
""",
"android/widget/ProgressBar.java": """
package android.widget;
public class ProgressBar extends android.view.View {
    public ProgressBar(android.content.Context context) { super(context); }
    public ProgressBar(android.content.Context context, android.util.AttributeSet attrs, int defStyleAttr) { super(context); }
    public void setMax(int max) {}
    public void setProgress(int progress) {}
    public int getProgress() { return 0; }
}
""",
"android/util/AttributeSet.java": """
package android.util;
public interface AttributeSet {
}
""",
"org/json/JSONObject.java": """
package org.json;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
public class JSONObject {
    final Map<String, Object> map = new LinkedHashMap<>();
    public JSONObject() {}
    @SuppressWarnings("unchecked")
    public JSONObject(String src) {
        Object o = JSONParser.parse(src);
        if (o instanceof Map) { map.putAll((Map<String, Object>) o); }
        else { throw new IllegalArgumentException("not a JSON object"); }
    }
    static JSONObject wrap(Map<String, Object> m) {
        JSONObject j = new JSONObject();
        j.map.putAll(m);
        return j;
    }
    public Object opt(String key) { return map.get(key); }
    public String optString(String key) { return optString(key, ""); }
    public String optString(String key, String def) {
        Object v = map.get(key);
        if (v == null) return def;
        return v instanceof String ? (String) v : String.valueOf(v);
    }
    public int optInt(String key, int def) {
        Object v = map.get(key);
        return v instanceof Number ? ((Number) v).intValue() : def;
    }
    public long optLong(String key, long def) {
        Object v = map.get(key);
        return v instanceof Number ? ((Number) v).longValue() : def;
    }
    @SuppressWarnings("unchecked")
    public JSONObject optJSONObject(String key) {
        Object v = map.get(key);
        return v instanceof Map ? wrap((Map<String, Object>) v) : null;
    }
    @SuppressWarnings("unchecked")
    public JSONArray optJSONArray(String key) {
        Object v = map.get(key);
        return v instanceof List ? new JSONArray((List<Object>) v) : null;
    }
    public int length() { return map.size(); }
}
""",
"org/json/JSONArray.java": """
package org.json;
import java.util.List;
public class JSONArray {
    private final List<Object> list;
    JSONArray(List<Object> list) { this.list = list; }
    public int length() { return list.size(); }
    @SuppressWarnings("unchecked")
    public JSONObject optJSONObject(int index) {
        if (index < 0 || index >= list.size()) return null;
        Object v = list.get(index);
        return v instanceof java.util.Map ? JSONObject.wrap((java.util.Map<String, Object>) v) : null;
    }
    public Object opt(int index) {
        return index >= 0 && index < list.size() ? list.get(index) : null;
    }
}
""",
"org/json/JSONParser.java": """
package org.json;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
/** 极简 JSON 解析器：仅用于离线验证桩，支持对象/数组/字符串/数字/布尔/null。 */
final class JSONParser {
    private final String s;
    private int i;
    private JSONParser(String s) { this.s = s; }
    static Object parse(String src) {
        JSONParser p = new JSONParser(src);
        p.ws();
        Object v = p.value();
        p.ws();
        return v;
    }
    private void ws() {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
    }
    private Object value() {
        char c = s.charAt(i);
        if (c == '{') return object();
        if (c == '[') return array();
        if (c == '"') return string();
        if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
        if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
        if (s.startsWith("null", i)) { i += 4; return null; }
        return number();
    }
    private Map<String, Object> object() {
        Map<String, Object> m = new LinkedHashMap<>();
        i++; ws();
        if (s.charAt(i) == '}') { i++; return m; }
        while (true) {
            ws();
            String k = string();
            ws();
            i++; // ':'
            ws();
            m.put(k, value());
            ws();
            char c = s.charAt(i++);
            if (c == '}') return m;
        }
    }
    private List<Object> array() {
        List<Object> l = new ArrayList<>();
        i++; ws();
        if (s.charAt(i) == ']') { i++; return l; }
        while (true) {
            ws();
            l.add(value());
            ws();
            char c = s.charAt(i++);
            if (c == ']') return l;
        }
    }
    private String string() {
        StringBuilder sb = new StringBuilder();
        i++; // opening quote
        while (true) {
            char c = s.charAt(i++);
            if (c == '"') break;
            if (c == (char) 92) { // backslash
                char e = s.charAt(i++);
                if (e == 'u') {
                    sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                    i += 4;
                } else if (e == 'n') {
                    sb.append((char) 10);
                } else if (e == 't') {
                    sb.append((char) 9);
                } else if (e == 'r') {
                    sb.append((char) 13);
                } else if (e == 'b') {
                    sb.append((char) 8);
                } else if (e == 'f') {
                    sb.append((char) 12);
                } else {
                    sb.append(e);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
    private Object number() {
        int start = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
        String n = s.substring(start, i);
        if (n.contains(".") || n.contains("e") || n.contains("E")) return Double.parseDouble(n);
        return Long.parseLong(n);
    }
}
""",
"android/text/InputType.java": """
package android.text;
public interface InputType {
    int TYPE_CLASS_TEXT = 1;
    int TYPE_TEXT_VARIATION_PASSWORD = 128;
}
""",
"android/widget/Toast.java": """
package android.widget;
public class Toast {
    public static final int LENGTH_SHORT = 0;
    public static final int LENGTH_LONG = 1;
    public static Toast makeText(android.content.Context context, CharSequence text, int duration) { return null; }
    public void show() {}
}
""",
"android/widget/AdapterView.java": """
package android.widget;
public abstract class AdapterView<T extends Adapter> extends android.view.ViewGroup {
    public AdapterView(android.content.Context context) { super(context); }
    public interface OnItemClickListener {
        void onItemClick(AdapterView<?> parent, android.view.View view, int position, long id);
    }
    public void setOnItemClickListener(OnItemClickListener listener) {}
}
""",
"android/widget/Adapter.java": """
package android.widget;
public interface Adapter {
}
""",
"android/widget/ListAdapter.java": """
package android.widget;
public interface ListAdapter extends Adapter {
}
""",
"android/widget/ArrayAdapter.java": """
package android.widget;
public class ArrayAdapter<T> implements ListAdapter {
    public ArrayAdapter(android.content.Context context, int resource, T[] objects) {}
    public ArrayAdapter(android.content.Context context, int resource, java.util.List<T> objects) {}
}
""",
"android/widget/ListView.java": """
package android.widget;
public class ListView extends AdapterView<ListAdapter> {
    public ListView(android.content.Context context) { super(context); }
    public void setAdapter(ListAdapter adapter) {}
}
""",
}

for rel, body in FILES.items():
    path = os.path.join(OUT, rel)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        f.write(body.lstrip())

# BuildConfig 由 AGP 生成，桩里按 app/build.gradle 的真实版本号合成
_ver_name, _ver_code = read_app_version()
_build_config = os.path.join(OUT, "wiki", "nandflash", "tavernkeep", "BuildConfig.java")
os.makedirs(os.path.dirname(_build_config), exist_ok=True)
with open(_build_config, "w", encoding="utf-8") as f:
    f.write("""package io.github.flashsky1024.tavernium;
public final class BuildConfig {
    public static final boolean DEBUG = false;
    public static final String APPLICATION_ID = "io.github.flashsky1024.tavernium";
    public static final int VERSION_CODE = %d;
    public static final String VERSION_NAME = "%s";
}
""" % (_ver_code, _ver_name))

print("generated", len(FILES) + 1, "stub files ->", OUT,
      "(app version %s/%d)" % (_ver_name, _ver_code))
