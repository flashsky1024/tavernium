package wiki.nandflash.tavernkeep;

import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.PowerManager;

public final class ReplyNotifierTest {
    private static int passed;
    public static void main(String[] args) {
        int sdk = Build.VERSION.SDK_INT;
        try {
            for (boolean enabled : new boolean[]{false, true})
                for (boolean resumed : new boolean[]{false, true})
                    for (boolean screen : new boolean[]{false, true})
                        for (boolean idle : new boolean[]{false, true})
                            check("提醒场景矩阵", ReplyNotifier.shouldNotify(enabled, resumed, screen, idle)
                                    == (enabled && !(resumed && screen && !idle)));
            FakeContext context = new FakeContext();
            ReplyNotifier notifier = new ReplyNotifier(context);
            Build.VERSION.SDK_INT = 34;
            notifier.replyReady("1", true, true, false);
            check("前台不提醒", context.manager.count == 0);
            notifier.replyReady("1", true, false, false);
            check("已在前台消费的事件不补发", context.manager.count == 0);
            notifier.replyReady("PRIVATE-CHAT-CONTENT", true, false, false);
            check("后台提醒", context.manager.count == 1);
            check("不暴露事件或聊天内容", !context.manager.last.text.toString().contains("PRIVATE"));
            check("点击后可消除", context.manager.last.autoCancel);
            notifier.replyReady("PRIVATE-CHAT-CONTENT", true, false, false);
            check("重复事件只提醒一次", context.manager.count == 1);
            context.interactive = false;
            notifier.replyReady("2", true, true, false);
            check("息屏仍可提醒", context.manager.count == 2);
            context.interactive = true;
            context.locked = true;
            notifier.replyReady("3", true, true, false);
            check("锁屏仍可提醒", context.manager.count == 3);
            context.locked = false;
            notifier.replyReady("4", true, true, true);
            check("防烧屏挂机提醒", context.manager.count == 4);
            notifier.replyReady("5", false, false, false);
            check("关闭后不提醒", context.manager.count == 4);
            context.permission = PackageManager.PERMISSION_DENIED;
            notifier.replyReady("6", true, false, false);
            check("Android 13+ 拒绝权限不提醒", context.manager.count == 4);
            Build.VERSION.SDK_INT = 24;
            notifier.replyReady("7", true, false, false);
            check("Android 7 不要求新通知权限", context.manager.count == 5);
            context.manager.enabled = false;
            notifier.replyReady("8", true, false, false);
            check("系统总开关关闭不提醒", context.manager.count == 5);
            Build.VERSION.SDK_INT = 34;
            context.permission = PackageManager.PERMISSION_GRANTED;
            context.manager.enabled = true;
            context.manager.blockChannel = true;
            notifier.replyReady("9", true, false, false);
            check("独立回复渠道关闭不提醒", context.manager.count == 5);
            context.manager.blockChannel = false;
            context.manager.throwOnNotify = true;
            notifier.replyReady("10", true, false, false);
            check("发送时权限被撤销不崩溃", context.manager.count == 5);
            notifier.clear();
            check("回前台可清除提醒", context.manager.cancelled == context.manager.id);
            notifier.resetDocument();
            context.manager.throwOnNotify = false;
            notifier.replyReady("7", true, false, false);
            check("新页面可重新使用序号", context.manager.count == 6);
            notifier.replyReady("", true, false, false);
            notifier.replyReady(null, true, false, false);
            notifier.replyReady("x".repeat(81), true, false, false);
            check("非法事件不提醒", context.manager.count == 6);
            System.out.println("PASSED: " + passed + " FAILED: 0");
        } finally {
            Build.VERSION.SDK_INT = sdk;
        }
    }
    private static void check(String name, boolean value) {
        if (!value) throw new AssertionError(name);
        passed++;
        System.out.println("[PASS] " + name);
    }
    private static final class FakeContext extends Context {
        final FakeManager manager = new FakeManager();
        boolean interactive = true;
        boolean locked;
        int permission = PackageManager.PERMISSION_GRANTED;
        @Override public int checkSelfPermission(String ignored) { return permission; }
        @Override public <T> T getSystemService(Class<T> type) {
            if (type == NotificationManager.class) return type.cast(manager);
            if (type == PowerManager.class) return type.cast(new PowerManager() {
                @Override public boolean isInteractive() { return interactive; }
            });
            if (type == KeyguardManager.class) return type.cast(new KeyguardManager() {
                @Override public boolean isKeyguardLocked() { return locked; }
            });
            return null;
        }
    }
    private static final class FakeManager extends NotificationManager {
        int count, id, cancelled;
        Notification last;
        boolean enabled = true, blockChannel, throwOnNotify;
        @Override public boolean areNotificationsEnabled() { return enabled; }
        @Override public NotificationChannel getNotificationChannel(String channel) {
            return blockChannel ? new NotificationChannel(channel, "blocked", IMPORTANCE_NONE) : null;
        }
        @Override public void notify(int id, Notification notification) {
            if (throwOnNotify) throw new SecurityException("permission revoked");
            count++;
            this.id = id;
            last = notification;
        }
        @Override public void cancel(int id) { cancelled = id; }
    }
}
