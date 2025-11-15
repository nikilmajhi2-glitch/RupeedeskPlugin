package com.rupeedesk.smsaautosender;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.telephony.SmsManager;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;

import com.google.firebase.FirebaseApp;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.Transaction;
import com.rupeedesk.R;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SmsService extends Service {
    private static final String CHANNEL_ID = "SmsServiceChannel";
    private static final String TAG = "SmsService";
    private static final int NOTIFICATION_ID = 1;
    private static final long LEASE_TIMEOUT_MS = 120000; // 2 minutes
    private static final long NETWORK_RETRY_DELAY_MS = 30000; // 30 seconds
    private static final long LISTENER_KEEPALIVE_MS = 300000; // 5 minutes

    // ✅ Don't initialize here - will be initialized in onCreate()
    private FirebaseFirestore db;
    private String userId;
    private ListenerRegistration firestoreListener;
    private NotificationManager notificationManager;
    private NotificationCompat.Builder notificationBuilder;
    private Handler networkRetryHandler;
    private Handler keepAliveHandler;
    private int taskProcessedCount = 0;

    // Keepalive runnable to prevent listener detachment
    private final Runnable keepAliveRunnable = new Runnable() {
        @Override
        public void run() {
            Log.d(TAG, "🔄 Keepalive: Re-attaching Firestore listener...");
            startSmsListener();
            if (keepAliveHandler != null) {
                keepAliveHandler.postDelayed(this, LISTENER_KEEPALIVE_MS);
            }
        }
    };

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
        Log.d(TAG, "🚀 SmsService onCreate()");
        
        // ✅ CRITICAL: Initialize Firebase in service
        try {
            FirebaseApp.initializeApp(this);
            db = FirebaseFirestore.getInstance();
            Log.d(TAG, "✅ Firebase initialized in SmsService");
        } catch (Exception e) {
            Log.e(TAG, "⚠️ Firebase init failed in service: " + e.getMessage(), e);
            // Try to get instance anyway (might already be initialized)
            db = FirebaseFirestore.getInstance();
        }
        
        networkRetryHandler = new Handler(Looper.getMainLooper());
        keepAliveHandler = new Handler(Looper.getMainLooper());
        
        createNotificationChannel();
        startForegroundService();
        startSmsListener();
    }

    /**
     * Fixed: Proper foreground service initialization with correct type
     */
    private void startForegroundService() {
        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        notificationBuilder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("RupeeDesk SMS Service")
                .setContentText("Initializing service...")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW);

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // Android 14+ (API 34+)
                startForeground(NOTIFICATION_ID, notificationBuilder.build(), 
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+ (API 29+)
                startForeground(NOTIFICATION_ID, notificationBuilder.build());
            } else {
                startForeground(NOTIFICATION_ID, notificationBuilder.build());
            }
            Log.d(TAG, "✅ Foreground service started successfully");
        } catch (Exception e) {
            Log.e(TAG, "❌ Failed to start foreground service: " + e.getMessage(), e);
        }
    }

    private void updateNotification(String text) {
        if (notificationBuilder != null && notificationManager != null) {
            notificationBuilder.setContentText(text);
            notificationManager.notify(NOTIFICATION_ID, notificationBuilder.build());
        }
    }

    private void startSmsListener() {
        // Remove old listener if exists
        if (firestoreListener != null) {
            firestoreListener.remove();
            firestoreListener = null;
        }

        // Get user ID
        SharedPreferences prefs = getApplicationContext().getSharedPreferences("AppPrefs", Context.MODE_PRIVATE);
        userId = prefs.getString("userId", null);

        if (userId == null || userId.isEmpty()) {
            String errorMsg = "❌ Error: User ID not bound";
            Log.e(TAG, errorMsg);
            updateNotification(errorMsg); 
            return;
        }

        // Check network
        if (!isNetworkAvailable()) {
            String errorMsg = "⚠️ No internet. Retrying in 30s...";
            Log.w(TAG, errorMsg);
            updateNotification(errorMsg);
            
            // Schedule retry
            networkRetryHandler.postDelayed(() -> {
                Log.d(TAG, "🔄 Retrying network connection...");
                startSmsListener();
            }, NETWORK_RETRY_DELAY_MS);
            return;
        }

        // Check SMS permission
        if (!hasSmsPermission()) {
            String errorMsg = "❌ Error: SEND_SMS permission not granted";
            Log.e(TAG, errorMsg);
            updateNotification(errorMsg);
            return;
        }

        Log.d(TAG, "📡 Attaching Firestore Snapshot Listener for user: " + userId);
        updateNotification("Actively listening for tasks... (Processed: " + taskProcessedCount + ")");

        // Query with lease timeout consideration
        Query query = db.collection("sms_tasks")
                .whereIn("status", Arrays.asList("pending", "failed"))
                .orderBy("createdAt", Query.Direction.ASCENDING)
                .limit(10);

        firestoreListener = query.addSnapshotListener((snapshot, error) -> {
            if (error != null) {
                String errorMsg = "🔥 Firestore listen failed: " + error.getMessage();
                Log.e(TAG, errorMsg);
                updateNotification(errorMsg);
                
                // Retry on error
                networkRetryHandler.postDelayed(() -> {
                    Log.d(TAG, "🔄 Retrying after Firestore error...");
                    startSmsListener();
                }, NETWORK_RETRY_DELAY_MS);
                return;
            }

            if (snapshot != null && !snapshot.isEmpty()) {
                int taskCount = snapshot.size();
                Log.d(TAG, "✅ Received " + taskCount + " tasks from Firestore");
                updateNotification("Processing " + taskCount + " tasks...");
                
                for (DocumentSnapshot doc : snapshot.getDocuments()) {
                    leaseAndProcessTask(doc); 
                }
            } else {
                Log.d(TAG, "📭 Queue is empty");
                updateNotification("Listening... Queue empty (Processed: " + taskProcessedCount + ")");
            }
        });

        // Start keepalive mechanism
        keepAliveHandler.removeCallbacks(keepAliveRunnable);
        keepAliveHandler.postDelayed(keepAliveRunnable, LISTENER_KEEPALIVE_MS);
    }

    private boolean isNetworkAvailable() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
        return activeNetwork != null && activeNetwork.isConnectedOrConnecting();
    }

    /**
     * Fixed: Check SMS permission in service context
     */
    private boolean hasSmsPermission() {
        return ActivityCompat.checkSelfPermission(this, android.Manifest.permission.SEND_SMS) 
                == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Fixed: Transaction with lease timeout and better error handling
     */
    private void leaseAndProcessTask(DocumentSnapshot doc) { 
        String id = doc.getId();
        DocumentReference docRef = db.collection("sms_tasks").document(id);

        db.runTransaction((Transaction.Function<DocumentSnapshot>) transaction -> {
            DocumentSnapshot snapshot = transaction.get(docRef);
            String status = snapshot.getString("status");

            if ("pending".equals(status) || "failed".equals(status)) {
                Long retryCount = snapshot.getLong("retryCount");
                if (retryCount == null) retryCount = 0L;
                
                // Check max retries
                if (retryCount >= 3) {
                    Log.w(TAG, "🗑️ Deleting task " + id + " (max retries: " + retryCount + ")");
                    transaction.delete(docRef);
                    return null;
                }
                
                // Lease the task with timeout
                Date now = new Date();
                Date leaseExpiry = new Date(System.currentTimeMillis() + LEASE_TIMEOUT_MS);
                
                Log.d(TAG, "🔒 Leasing task: " + id + " (attempt #" + (retryCount + 1) + ")");
                
                transaction.update(docRef, 
                    "status", "sending", 
                    "leasedBy", userId, 
                    "leasedAt", now,
                    "leaseExpiresAt", leaseExpiry
                );
                
                return snapshot;
            } else if ("sending".equals(status)) {
                // Check if lease expired
                Date leaseExpiresAt = snapshot.getDate("leaseExpiresAt");
                if (leaseExpiresAt != null && leaseExpiresAt.before(new Date())) {
                    Log.w(TAG, "⏰ Lease expired for task: " + id + ", re-leasing...");
                    
                    Long retryCount = snapshot.getLong("retryCount");
                    if (retryCount == null) retryCount = 0L;
                    
                    if (retryCount >= 3) {
                        Log.w(TAG, "🗑️ Deleting expired task " + id + " (max retries)");
                        transaction.delete(docRef);
                        return null;
                    }
                    
                    // Re-lease
                    Date now = new Date();
                    Date leaseExpiry = new Date(System.currentTimeMillis() + LEASE_TIMEOUT_MS);
                    
                    transaction.update(docRef,
                        "status", "sending",
                        "leasedBy", userId,
                        "leasedAt", now,
                        "leaseExpiresAt", leaseExpiry,
                        "retryCount", retryCount + 1
                    );
                    
                    return snapshot;
                } else {
                    Log.d(TAG, "⏭️ Task " + id + " already leased by another service");
                    return null;
                }
            } else {
                Log.d(TAG, "⏭️ Task " + id + " status is '" + status + "', skipping");
                return null;
            }
        }).addOnSuccessListener(snapshot -> {
            if (snapshot != null) {
                sendSms(snapshot, userId); 
            }
        }).addOnFailureListener(e -> {
            String errorMsg = "❌ Lease transaction failed: " + e.getMessage();
            Log.e(TAG, errorMsg, e);
            updateNotification(errorMsg);
        });
    }

    /**
     * FIXED: SMS sending with MUTABLE PendingIntent for proper error handling
     * This is the critical fix - FLAG_MUTABLE allows the system to update the Intent with error codes
     */
    private void sendSms(DocumentSnapshot doc, String senderUserId) { 
        String id = doc.getId();
        String phone = doc.getString("phone");
        String message = doc.getString("message");
        Long retryCount = doc.getLong("retryCount");
        if (retryCount == null) retryCount = 0L;

        // Validate data
        if (phone == null || message == null || phone.isEmpty() || message.isEmpty()) {
            Log.e(TAG, "❌ Skipping task " + id + " (missing phone or message)");
            markTaskAsFailed(id, retryCount, "Missing phone number or message");
            return;
        }

        // Validate phone number format (basic E.164 check)
        if (!isValidPhoneNumber(phone)) {
            Log.e(TAG, "❌ Invalid phone number format: " + phone);
            markTaskAsFailed(id, retryCount, "Invalid phone number format: " + phone);
            return;
        }

        // Check permission again before sending
        if (!hasSmsPermission()) {
            Log.e(TAG, "❌ SEND_SMS permission not granted!");
            updateNotification("❌ Error: SMS permission denied");
            markTaskAsFailed(id, retryCount, "SEND_SMS permission not granted");
            return;
        }

        try {
            // Create unique intent for this SMS
            Intent sentIntent = new Intent("com.rupeedesk.SMS_SENT");
            sentIntent.setClass(getApplicationContext(), SmsSentReceiver.class);
            sentIntent.putExtra("documentId", id);
            sentIntent.putExtra("userId", senderUserId);
            sentIntent.putExtra("retryCount", retryCount);
            sentIntent.putExtra("timestamp", System.currentTimeMillis());
            sentIntent.putExtra("phone", phone);
            
            // Create unique request code to avoid PendingIntent collision
            int requestCode = generateUniqueRequestCode(id);
            
            // ✅ CRITICAL FIX: Use FLAG_MUTABLE for Android 12+ to allow system to update with error codes
            // FLAG_IMMUTABLE prevents the system from writing SMS result codes back to the Intent
            // This was causing all SMS failures to appear as success (RESULT_OK)
            PendingIntent sentPI = PendingIntent.getBroadcast(
                    getApplicationContext(),
                    requestCode,
                    sentIntent,
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S 
                        ? (PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT)  // ✅ FIXED
                        : PendingIntent.FLAG_UPDATE_CURRENT
            );

            // Get appropriate SmsManager (handles dual-SIM)
            SmsManager smsManager = getSmsManager();
            
            // Handle multipart messages (>160 chars)
            if (message.length() > 160) {
                Log.d(TAG, "📬 Sending MULTIPART SMS (length: " + message.length() + ") for task: " + id + " to " + phone);
                
                ArrayList<String> parts = smsManager.divideMessage(message);
                ArrayList<PendingIntent> sentIntents = new ArrayList<>();
                
                for (int i = 0; i < parts.size(); i++) {
                    sentIntents.add(sentPI);
                }
                
                smsManager.sendMultipartTextMessage(phone, null, parts, sentIntents, null);
                Log.d(TAG, "✅ Multipart SMS initiated (" + parts.size() + " parts) - Task: " + id);
                
            } else {
                Log.d(TAG, "📬 Sending SMS for task: " + id + " to " + phone + " (Attempt #" + (retryCount + 1) + ")");
                smsManager.sendTextMessage(phone, null, message, sentPI, null);
                Log.d(TAG, "✅ SMS send initiated - Task: " + id);
            }
            
            taskProcessedCount++;
            updateNotification("Processing... (Total: " + taskProcessedCount + ")");

        } catch (SecurityException e) {
            String errorMsg = "❌ SecurityException: " + e.getMessage();
            Log.e(TAG, errorMsg, e);
            updateNotification("❌ Permission error");
            markTaskAsFailed(id, retryCount, "SecurityException: " + e.getMessage());
            
        } catch (IllegalArgumentException e) {
            String errorMsg = "❌ IllegalArgumentException: " + e.getMessage();
            Log.e(TAG, errorMsg, e);
            markTaskAsFailed(id, retryCount, "Invalid argument: " + e.getMessage());
            
        } catch (Exception e) {
            String errorMsg = "❌ SMS send exception: " + e.getMessage();
            Log.e(TAG, errorMsg, e);
            updateNotification("❌ Send failed");
            markTaskAsFailed(id, retryCount, "Exception: " + e.getMessage());
        }
    }

    /**
     * Fixed: Get SmsManager with dual-SIM support
     */
    private SmsManager getSmsManager() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            try {
                if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.READ_PHONE_STATE) 
                    == PackageManager.PERMISSION_GRANTED) {
                    
                    SubscriptionManager subManager = SubscriptionManager.from(this);
                    List<SubscriptionInfo> subInfoList = subManager.getActiveSubscriptionInfoList();
                    
                    if (subInfoList != null && !subInfoList.isEmpty()) {
                        // Use first active subscription
                        int subId = subInfoList.get(0).getSubscriptionId();
                        Log.d(TAG, "📱 Using subscription ID: " + subId + " (SIM: " + subInfoList.get(0).getDisplayName() + ")");
                        return SmsManager.getSmsManagerForSubscriptionId(subId);
                    } else {
                        Log.w(TAG, "⚠️ No active SIM found, using default SmsManager");
                    }
                } else {
                    Log.w(TAG, "⚠️ No READ_PHONE_STATE permission, using default SmsManager");
                }
            } catch (Exception e) {
                Log.e(TAG, "⚠️ Error getting subscription manager: " + e.getMessage() + ", using default");
            }
        }
        
        Log.d(TAG, "📱 Using default SmsManager");
        return SmsManager.getDefault();
    }

    /**
     * Fixed: Validate phone number (basic E.164 format)
     */
    private boolean isValidPhoneNumber(String phone) {
        if (phone == null || phone.isEmpty()) {
            return false;
        }
        // Basic validation: should start with + and have 7-15 digits
        // Or just have 10-15 digits (local format)
        return phone.matches("^\\+?[1-9]\\d{6,14}$") || phone.matches("^[0-9]{10,15}$");
    }

    /**
     * Fixed: Generate unique request code to avoid PendingIntent collision
     */
    private int generateUniqueRequestCode(String documentId) {
        return Math.abs(documentId.hashCode()) + (int)(System.currentTimeMillis() % 100000);
    }

    /**
     * Fixed: Mark task as failed with detailed error
     */
    private void markTaskAsFailed(String documentId, long retryCount, String errorMessage) {
        Map<String, Object> update = new HashMap<>();
        update.put("status", "failed");
        update.put("retryCount", retryCount + 1);
        update.put("lastError", errorMessage);
        update.put("lastErrorAt", new Date());
        update.put("leasedBy", null);
        update.put("leasedAt", null);
        update.put("leaseExpiresAt", null);

        db.collection("sms_tasks").document(documentId)
                .update(update)
                .addOnSuccessListener(aVoid -> 
                    Log.d(TAG, "↪️ Task " + documentId + " marked as failed for retry"))
                .addOnFailureListener(e -> 
                    Log.e(TAG, "⚠️ Failed to update task status: " + documentId, e));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "📥 onStartCommand() called");
        return START_STICKY; 
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "🛑 SmsService destroyed");

        // Cleanup listeners
        if (firestoreListener != null) {
            firestoreListener.remove();
            firestoreListener = null;
        }

        // Cleanup handlers
        if (networkRetryHandler != null) {
            networkRetryHandler.removeCallbacksAndMessages(null);
        }
        
        if (keepAliveHandler != null) {
            keepAliveHandler.removeCallbacks(keepAliveRunnable);
            keepAliveHandler.removeCallbacksAndMessages(null);
        }

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
            channel.setDescription("Keeps the SMS sending service running in background");
            
            NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.createNotificationChannel(channel);
                Log.d(TAG, "✅ Notification channel created");
            }
        }
    }
}