package com.rupeedesk.smsaautosender;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

public class SmsService extends Service {

    private static final String CHANNEL_ID = "SmsServiceChannel";
    private boolean isRunning = false;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("SMS Auto Sender")
                .setContentText("Send Service Running")
                .setSmallIcon(android.R.drawable.ic_dialog_info) // ✅ fixed icon
                .setOngoing(true)
                .build();

        startForeground(1, notification);

        isRunning = true;
        new Thread(() -> {
            while (isRunning) {
                FirebaseManager.checkAndSendMessages(getApplicationContext());
                try {
                    Thread.sleep(10000); // Check every 10 seconds
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }).start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY; // ✅ Keeps service alive after app cleared
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        isRunning = false;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "SMS Auto Sender Channel",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(serviceChannel);
            }
        }
    }
}