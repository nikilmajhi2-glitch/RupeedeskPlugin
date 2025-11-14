package com.rupeedesk.smsaautosender;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;
public class BootReceiver extends BroadcastReceiver {
private static final String TAG = "BootReceiver";
private static final String PREFS_NAME = "AppPrefs";
private static final String KEY_SERVICE_RUNNING = "isServiceRunning";
private static final String KEY_USER_ID = "userId";
@Override
public void onReceive(Context context, Intent intent) {
    if (intent == null || intent.getAction() == null) {
        Log.w(TAG, "⚠️ Received null intent or action");
        return;
    }

    String action = intent.getAction();
    Log.d(TAG, "📥 Boot broadcast received with action: " + action);

    // Handle different boot events
    switch (action) {
        case Intent.ACTION_BOOT_COMPLETED:
            Log.i(TAG, "🔄 Device boot completed");
            handleBootCompleted(context);
            break;

        case Intent.ACTION_LOCKED_BOOT_COMPLETED:
            Log.i(TAG, "🔒 Locked boot completed (Direct Boot)");
            handleLockedBootCompleted(context);
            break;

        case "android.intent.action.QUICKBOOT_POWERON":
            Log.i(TAG, "⚡ Quick boot power on");
            handleBootCompleted(context);
            break;

        default:
            Log.w(TAG, "⚠️ Unknown boot action: " + action);
            break;
    }
}

/**
 * Fixed: Handle regular boot completion
 */
private void handleBootCompleted(Context context) {
    try {
        // Check if service was previously running
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        boolean wasServiceRunning = prefs.getBoolean(KEY_SERVICE_RUNNING, false);
        String userId = prefs.getString(KEY_USER_ID, null);

        Log.d(TAG, "📋 Service was running before reboot: " + wasServiceRunning);
        Log.d(TAG, "📋 User ID bound: " + (userId != null ? userId : "None"));

        // Only restart if service was previously running AND user ID is bound
        if (wasServiceRunning && userId != null && !userId.isEmpty()) {
            Log.i(TAG, "✅ Conditions met: Restarting SmsService for user: " + userId);
            
            // Add a small delay to ensure system is fully booted
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                try {
                    SmsService.startService(context);
                    Log.i(TAG, "✅ SmsService started successfully after boot");
                } catch (Exception e) {
                    Log.e(TAG, "❌ Failed to start SmsService after boot: " + e.getMessage(), e);
                    // Don't reset the flag - let user manually restart from app
                }
            }, 3000); // 3 second delay
            
        } else {
            if (!wasServiceRunning) {
                Log.i(TAG, "⏭️ Service was not running before reboot, skipping auto-start");
            }
            if (userId == null || userId.isEmpty()) {
                Log.i(TAG, "⏭️ No user ID bound, skipping auto-start");
            }
        }

    } catch (Exception e) {
        Log.e(TAG, "❌ Error in handleBootCompleted: " + e.getMessage(), e);
    }
}

/**
 * Fixed: Handle locked boot (Direct Boot mode - Android 7.0+)
 * This runs before user unlocks device
 */
private void handleLockedBootCompleted(Context context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        Log.d(TAG, "🔒 Direct Boot mode - device locked");
        
        // In Direct Boot mode, we can't access normal SharedPreferences
        // We would need to use device-protected storage
        // For now, we'll just log and wait for full boot
        
        Log.i(TAG, "⏳ Waiting for user unlock to start service...");
        
        // Optional: You could start service in restricted mode here
        // but it won't have access to user data until device is unlocked
    }
}

/**
 * Optional: Validate device state before starting service
 */
private boolean isDeviceReady(Context context) {
    try {
        // Check if device has been unlocked at least once
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            android.os.UserManager userManager = (android.os.UserManager) 
                context.getSystemService(Context.USER_SERVICE);
            
            if (userManager != null && !userManager.isUserUnlocked()) {
                Log.w(TAG, "⚠️ Device not yet unlocked");
                return false;
            }
        }

        // Check if we have network connectivity (optional)
        android.net.ConnectivityManager cm = (android.net.ConnectivityManager) 
            context.getSystemService(Context.CONNECTIVITY_SERVICE);
        
        if (cm != null) {
            android.net.NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
            if (activeNetwork == null || !activeNetwork.isConnectedOrConnecting()) {
                Log.w(TAG, "⚠️ No network connectivity at boot");
                // Don't block on this - service can start and wait for network
            }
        }

        return true;

    } catch (Exception e) {
        Log.e(TAG, "⚠️ Error checking device state: " + e.getMessage(), e);
        return true; // Assume ready if check fails
    }
}

/**
 * Optional: Log boot event to Firebase for analytics
 */
private void logBootEvent(Context context, String action) {
    try {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String userId = prefs.getString(KEY_USER_ID, null);

        if (userId != null && !userId.isEmpty()) {
            com.google.firebase.firestore.FirebaseFirestore db = 
                com.google.firebase.firestore.FirebaseFirestore.getInstance();

            java.util.Map<String, Object> bootLog = new java.util.HashMap<>();
            bootLog.put("userId", userId);
            bootLog.put("action", action);
            bootLog.put("timestamp", new java.util.Date());
            bootLog.put("deviceModel", Build.MODEL);
            bootLog.put("androidVersion", Build.VERSION.RELEASE);
            bootLog.put("serviceAutoRestarted", prefs.getBoolean(KEY_SERVICE_RUNNING, false));

            db.collection("boot_events")
                    .add(bootLog)
                    .addOnSuccessListener(docRef -> 
                        Log.d(TAG, "✅ Boot event logged to Firebase: " + docRef.getId()))
                    .addOnFailureListener(e -> 
                        Log.e(TAG, "⚠️ Failed to log boot event: " + e.getMessage()));
        }

    } catch (Exception e) {
        Log.e(TAG, "❌ Error logging boot event: " + e.getMessage(), e);
    }
}
}
