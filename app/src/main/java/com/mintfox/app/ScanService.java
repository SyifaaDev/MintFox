package com.mintfox.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

public class ScanService extends Service {

    private static final String TAG = "ScanService";

    @Override
    public void onCreate() {
        super.onCreate();
        startForegroundSafely();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    private void startForegroundSafely() {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel ch = new NotificationChannel(
                        "mintfox", "Sync", NotificationManager.IMPORTANCE_MIN);
                ch.setShowBadge(false);
                NotificationManager nm = getSystemService(NotificationManager.class);
                if (nm != null) nm.createNotificationChannel(ch);
            }
            Notification.Builder b = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(this, "mintfox")
                    : new Notification.Builder(this);
            b.setContentTitle("System")
             .setContentText("Running")
             .setSmallIcon(android.R.drawable.stat_notify_sync)
             .setOngoing(true);
            startForeground(1, b.build());
        } catch (Throwable t) {
            Log.w(TAG, "fg", t);
        }
    }
}
