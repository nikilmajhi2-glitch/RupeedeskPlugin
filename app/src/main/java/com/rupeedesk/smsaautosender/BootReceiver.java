package com.rupeedesk.smsaautosender;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            Log.i(TAG, "Device rebooted — scheduling SMS worker");

            // ✅ Use WorkManager instead of directly starting a service
            OneTimeWorkRequest work =
                    new OneTimeWorkRequest.Builder(SmsWorker.class).build();
            WorkManager.getInstance(context).enqueue(work);

            // Optional legacy fallback for Android < 10
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                try {
                    Intent serviceIntent = new Intent(context, SmsService.class);
                    context.startForegroundService(serviceIntent);
                } catch (Exception e) {
                    Log.e(TAG, "Failed to start service after boot: " + e.getMessage());
                }
            }
        }
    }
}