package io.github.flashsky1024.tavernium;

import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.PowerManager;

/** 回复提醒与保活通知使用独立渠道；不接收或保存聊天正文、角色名。只在主线程调用。 */
final class ReplyNotifier {
    static final String PERMISSION = "android.permission.POST_NOTIFICATIONS";
    static final String ACTION_OPEN_REPLY = "io.github.flashsky1024.tavernium.OPEN_REPLY";
    private static final String CHANNEL_ID = "tavernium_replies";
    private static final int NOTIFICATION_ID = 10087;
    private final Context context;
    private String lastEvent = "";

    ReplyNotifier(Context context) {
        this.context = context;
    }

    boolean permitted() {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null || !manager.areNotificationsEnabled()
                || (Build.VERSION.SDK_INT >= 33
                && context.checkSelfPermission(PERMISSION) != PackageManager.PERMISSION_GRANTED)) {
            return false;
        }
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = manager.getNotificationChannel(CHANNEL_ID);
            return channel == null || channel.getImportance() != NotificationManager.IMPORTANCE_NONE;
        }
        return true;
    }

    boolean screenAvailable() {
        PowerManager power = context.getSystemService(PowerManager.class);
        KeyguardManager keyguard = context.getSystemService(KeyguardManager.class);
        return power != null && power.isInteractive()
                && (keyguard == null || !keyguard.isKeyguardLocked());
    }

    static boolean shouldNotify(boolean enabled, boolean resumed, boolean screenAvailable,
                                boolean burnInIdle) {
        return enabled && (!resumed || !screenAvailable || burnInIdle);
    }

    void resetDocument() {
        lastEvent = "";
    }

    void replyReady(String event, boolean enabled, boolean resumed, boolean burnInIdle) {
        if (event == null || event.isEmpty() || event.length() > 80 || event.equals(lastEvent)) {
            return;
        }
        // 即使当前前台可见也消费此事件，回后台时不能补发已经看过的消息。
        lastEvent = event;
        if (!shouldNotify(enabled, resumed, screenAvailable(), burnInIdle) || !permitted()) {
            return;
        }
        try {
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                        "回复完成提醒", NotificationManager.IMPORTANCE_DEFAULT);
                channel.setDescription("酒馆在后台、锁屏或防烧屏挂机时收到回复的提醒");
                channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
                manager.createNotificationChannel(channel);
            }
            PendingIntent open = PendingIntent.getActivity(context, NOTIFICATION_ID,
                    new Intent(context, MainActivity.class).setAction(ACTION_OPEN_REPLY),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(context, CHANNEL_ID)
                    : new Notification.Builder(context);
            builder.setContentTitle("回复已完成")
                    .setContentText("酒馆收到了一条新回复，点此返回聊天。")
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .setVisibility(Notification.VISIBILITY_PUBLIC)
                    .setCategory(Notification.CATEGORY_MESSAGE);
            manager.notify(NOTIFICATION_ID, builder.build());
        } catch (RuntimeException e) {
            // 权限可能在检查后被系统撤销，不能让提醒影响生成或使 Activity 崩溃。
            PerfLog.w("ReplyNotifier", "notification unavailable: " + e.getClass().getSimpleName());
        }
    }

    void clear() {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.cancel(NOTIFICATION_ID);
        }
    }
}
