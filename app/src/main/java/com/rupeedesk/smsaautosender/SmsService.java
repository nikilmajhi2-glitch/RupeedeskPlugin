package com.rupeedesk.smsaautosender;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import java.util.concurrent.TimeUnit;

/**
 * Modernized SMS foreground service for Android 10–15.
 * - Starts as a proper foreground service (no crash)
 * - Schedules background work via WorkManager (battery-safe)
 * - Handles Doze mode correctly
 * - Complies with FOREGROUND_SERVICE + notification rules
 */
public class SmsService extends Service {

    private static final String CHANNEL_ID = "SmsServiceChannel";
    private static final String WORK_TAG = "SmsAutoWorker";

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("RupeeDesk SMS Auto Sender")
                .setContentText("Running background SMS tasks")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setOngoing(true)
                .build();

        startForeground(1, notification);

        // ✅ Schedule WorkManager job every 15 minutes (minimum allowed)
        scheduleSmsWorker();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Keep service alive; WorkManager does actual work
        return START_STICKY;
    }

    private void scheduleSmsWorker() {
        // Cancels and replaces any existing periodic work with the same name
        PeriodicWorkRequest smsWork = new PeriodicWorkRequest.Builder(
                SmsWorker.class,
                15, // minimum periodic interval (in minutes)
                TimeUnit.MINUTES)
                .addTag(WORK_TAG)
                .build();

        WorkManager.getInstance(this)
                .enqueueUniquePeriodicWork(WORK_TAG, ExistingPeriodicWorkPolicy.REPLACE, smsWork);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        // Service cleanup
        stopForeground(true);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "SMS Auto Sender Service",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }
}