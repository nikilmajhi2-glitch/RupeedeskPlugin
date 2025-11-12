package com.rupeedesk.smsaautosender;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import java.util.concurrent.TimeUnit;

/**
 * Modernized SMS foreground service.
 * - Schedules the new AutoSmsWorker.
 * - Provides a static method to be started from MainActivity.
 */
public class SmsService extends Service {

    private static final String CHANNEL_ID = "SmsServiceChannel";
    public static final String WORK_TAG = "SmsAutoWorker"; // FIXED: Changed from private to public
    private static final String TAG = "SmsService";

    /**
     * Helper method to start this service correctly based on SDK version.
     */
    public static void startService(Context context) {
        Intent serviceIntent = new Intent(context, SmsService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent);
        } else {
            context.startService(serviceIntent);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("RupeeDesk SMS Service")
                .setContentText("Actively processing SMS tasks")
                .setSmallIcon(android.R.drawable.ic_dialog_info) // TODO: Replace with your app's icon
                .setOngoing(true)
                .build();

        startForeground(1, notification);
        Log.d(TAG, "SmsService started in foreground.");

        // ✅ Schedule the NEW AutoSmsWorker
        scheduleSmsWorker();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // We want the service to restart if it's killed
        return START_STICKY;
    }

    private void scheduleSmsWorker() {
        // Schedule the new worker to run every 15 minutes
        PeriodicWorkRequest smsWork = new PeriodicWorkRequest.Builder(
                AutoSmsWorker.class, // <-- Use the NEW worker
                15,
                TimeUnit.MINUTES)
                .addTag(WORK_TAG)
                .build();

        WorkManager.getInstance(this)
                .enqueueUniquePeriodicWork(WORK_TAG, ExistingPeriodicWorkPolicy.REPLACE, smsWork);

        Log.d(TAG, "Scheduled " + WORK_TAG + " to run every 15 minutes.");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "SmsService destroyed.");
        // Note: WorkManager tasks will still run even if the service is destroyed.
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
