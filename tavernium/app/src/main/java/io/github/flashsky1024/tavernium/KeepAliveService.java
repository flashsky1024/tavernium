/*
 * Copyright (C) 2026 flashsky1024
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package io.github.flashsky1024.tavernium;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;

/* 保活服务：仅常驻通知 + START_STICKY + 被杀自启。
   不再持有唤醒锁、不再定闹钟，空闲时 CPU 可深度休眠，发热大幅下降。
   生成回复时的 CPU 保活由 MainActivity 的按需唤醒锁（net_wake）负责。 */
public class KeepAliveService extends Service {
    private static final String CHANNEL_ID = "tavern_keep_alive";
    private static final int NOTIFICATION_ID = 10086;
    /** 自重启最小间隔：避免被系统反复杀死时形成紧密重启循环（每次重启都是一轮进程创建开销）。 */
    private static final long MIN_RESTART_INTERVAL_MS = 5000L;
    /** 通知栏「退出」按钮发来的动作：主动停止，不再自重启。 */
    private static final String ACTION_STOP = "io.github.flashsky1024.tavernium.action.STOP_KEEPALIVE";

    private long lastStartElapsed = 0L;
    /** 用户主动点了「退出」：onDestroy/onTaskRemoved 时不再 restartSelf，否则退出按钮形同虚设。 */
    private boolean stopRequested = false;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int i, int i2) {
        this.lastStartElapsed = SystemClock.elapsedRealtime();
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            // 用户点通知栏「退出」：置位后停止，onDestroy 不再自重启
            this.stopRequested = true;
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        startForegroundWithNotification();
    }

    private void startForegroundWithNotification() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Tavernium 保活", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("保持应用后台运行");
            channel.setShowBadge(false);
            channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            NotificationManager nm = (NotificationManager) getSystemService(
                    NotificationManager.class);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }
        PendingIntent activity = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= 26) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        builder.setContentTitle("Tavernium 运行中")
                .setContentText("正在后台保持连接")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(activity)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setPriority(Notification.PRIORITY_LOW)
                .setShowWhen(false)
                .setVisibility(Notification.VISIBILITY_PUBLIC);
        // 退出入口：没有它用户只能去系统设置强停。ACTION_STOP 触发 onStartCommand 的主动退出分支。
        Intent stopIntent = new Intent(this, KeepAliveService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPending = PendingIntent.getService(this, 1, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        builder.addAction(new Notification.Action.Builder(
                android.R.drawable.ic_menu_close_clear_cancel, "退出", stopPending).build());
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, builder.build(),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, builder.build());
        }
    }

    /** 自重启加节流：距离上次启动不足 MIN_RESTART_INTERVAL_MS 就不再重启。 */
    private void restartSelf() {
        long now = SystemClock.elapsedRealtime();
        if (now - this.lastStartElapsed < MIN_RESTART_INTERVAL_MS) {
            return;
        }
        Intent intent = new Intent(getApplicationContext(), KeepAliveService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                getApplicationContext().startForegroundService(intent);
            } else {
                getApplicationContext().startService(intent);
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onDestroy() {
        if (!this.stopRequested) {
            restartSelf();
        }
        super.onDestroy();
    }

    @Override
    public void onTaskRemoved(Intent intent) {
        if (!this.stopRequested) {
            restartSelf();
        }
        super.onTaskRemoved(intent);
    }
}
