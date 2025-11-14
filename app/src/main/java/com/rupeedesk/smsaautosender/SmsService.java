package com.rupeedesk.smsaautosender;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler; // <-- ADDED
import android.os.IBinder;
import android.os.Looper;  // <-- ADDED
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.OneTimeWorkRequest; // <-- CHANGED
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import java.util.concurrent.TimeUnit;

public class SmsService extends Service {
    private static final String CHANNEL_ID = "SmsServiceChannel";
    public static final String WORK_TAG = "SmsAutoWorker";
    private static final String TAG = "SmsService";

    // --- ADDED FOR FAST POLLING ---
    private Handler mHandler;
    private Runnable mWorkerSchedulerRunnable;
    // Set your interval here (e.g., 2 minutes)
    private static final long RUN_INTERVAL_MS = 2 * 60 * 1000;
    // --- END ---


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

        // --- REPLACED scheduleSmsWorker() WITH THIS HANDLER ---
        mHandler = new Handler(Looper.getMainLooper());
        mWorkerSchedulerRunnable = new Runnable() {
            @Override
            public void run() {
                // Schedule a ONE-TIME worker to run immediately
                Log.d(TAG, "Handler is scheduling a new OneTimeWorkRequest.");
                OneTimeWorkRequest smsWork = new OneTimeWorkRequest.Builder(AutoSmsWorker.class)
                        .addTag(WORK_TAG)
                        .build();
                WorkManager.getInstance(SmsService.this)
                        .enqueue(smsWork);

                // Re-post this same runnable to run again after the interval
                mHandler.postDelayed(this, RUN_INTERVAL_MS);
            }
        };

        // Start the loop immediately
        mHandler.post(mWorkerSchedulerRunnable);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // We want the service to restart if it's killed
        return START_STICKY;
    }

    // --- OLD scheduleSmsWorker() METHOD IS DELETED ---

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "SmsService destroyed.");

        // --- ADDED: Stop the handler loop when service is destroyed ---
        if (mHandler != null && mWorkerSchedulerRunnable != null) {
            mHandler.removeCallbacks(mWorkerSchedulerRunnable);
        }

        // You may also want to cancel any pending workers
        WorkManager.getInstance(this).cancelAllWorkByTag(WORK_TAG);

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
