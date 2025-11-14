package com.rupeedesk.smsaautosender;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.IBinder;
import android.telephony.SmsManager;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
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
    private static final int NOTIFICATION_ID = 1;

    private final FirebaseFirestore db = FirebaseFirestore.getInstance();
    private String userId;
    // private int subscriptionId; // <-- Hata diya gaya
    private ListenerRegistration firestoreListener;

    private NotificationManager notificationManager;
    private NotificationCompat.Builder notificationBuilder;

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

        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        notificationBuilder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("RupeeDesk SMS Service")
                .setContentText("Initializing service...")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setOngoing(true);

        startForeground(NOTIFICATION_ID, notificationBuilder.build());

        Log.d(TAG, "SmsService started in foreground.");
        startSmsListener();
    }

    private void updateNotification(String text) {
        notificationBuilder.setContentText(text);
        notificationManager.notify(NOTIFICATION_ID, notificationBuilder.build());
    }

    private void startSmsListener() {
        if (firestoreListener != null) {
            firestoreListener.remove();
        }

        SharedPreferences prefs = getApplicationContext().getSharedPreferences("AppPrefs", Context.MODE_PRIVATE);
        userId = prefs.getString("userId", null);
        // subscriptionId = prefs.getInt("subscriptionId", -1); // <-- Hata diya gaya

        if (userId == null || userId.isEmpty()) {
            String errorMsg = "Error: User ID not bound.";
            Log.e(TAG, errorMsg);
            updateNotification(errorMsg); 
            return;
        }

        if (!isNetworkAvailable()) {
            String errorMsg = "Error: No internet connection.";
            Log.e(TAG, errorMsg);
            updateNotification(errorMsg);
            return;
        }

        Log.d(TAG, "Attaching Firestore Snapshot Listener...");
        updateNotification("Actively listening for new tasks...");

        Query query = db.collection("sms_tasks")
                .whereIn("status", Arrays.asList("pending", "failed"))
                .orderBy("createdAt", Query.Direction.ASCENDING)
                .limit(5);

        firestoreListener = query.addSnapshotListener((snapshot, error) -> {
            if (error != null) {
                String errorMsg = "Listen failed: " + error.getMessage();
                Log.e(TAG, "🔥 " + errorMsg);
                updateNotification(errorMsg);
                return;
            }

            if (snapshot != null && !snapshot.isEmpty()) {
                Log.d(TAG, "✅ New tasks received. Processing " + snapshot.size() + " tasks...");
                updateNotification("Processing " + snapshot.size() + " tasks...");
                
                for (DocumentSnapshot doc : snapshot.getDocuments()) {
                    leaseAndProcessTask(doc); // <-- subId hata diya
                }
            } else {
                Log.d(TAG, "Queue is empty or snapshot was null.");
                updateNotification("Listening... Queue is empty.");
            }
        });
    }

    private boolean isNetworkAvailable() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
        return activeNetwork != null && activeNetwork.isConnectedOrConnecting();
    }


    private void leaseAndProcessTask(DocumentSnapshot doc) { // <-- subId hata diya
        String id = doc.getId();
        DocumentReference docRef = db.collection("sms_tasks").document(id);

        db.runTransaction((Transaction.Function<DocumentSnapshot>) transaction -> {
            DocumentSnapshot snapshot = transaction.get(docRef);
            String status = snapshot.getString("status");

            if ("pending".equals(status) || "failed".equals(status)) {
                Long retryCount = snapshot.getLong("retryCount");
                if (retryCount == null) retryCount = 0L;
                if (retryCount >= 3) {
                    Log.w(TAG, "🗑️ Deleting task " + id + " (max retries reached).");
                    transaction.delete(docRef);
                    return null;
                }
                Log.d(TAG, "Leasing task: " + id);
                transaction.update(docRef, "status", "sending", "leasedBy", userId, "leasedAt", new Date());
                return snapshot;
            } else {
                Log.d(TAG, "Task " + id + " was already leased. Skipping.");
                return null;
            }
        }).addOnSuccessListener(snapshot -> {
            if (snapshot != null) {
                sendSms(snapshot, userId); // <-- subId hata diya
            }
        }).addOnFailureListener(e -> {
            String errorMsg = "Lease failed: " + e.getMessage();
            Log.e(TAG, errorMsg);
            updateNotification(errorMsg);
        });
    }

    private void sendSms(DocumentSnapshot doc, String senderUserId) { // <-- subId hata diya
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

            // --- YEH SABSE SIMPLE LOGIC HAI ---
            Log.d(TAG, "Using SmsManager.getDefault()...");
            SmsManager smsManager = SmsManager.getDefault();
            // --- LOGIC END ---
            
            smsManager.sendTextMessage(phone, null, message, sentPI, null);
            Log.d(TAG, "📬 Sending SMS for leased task: " + id + " (Attempt #" + (retryCount + 1) + ")");

        } catch (Exception e) {
            String errorMsg = "Send failed: " + e.getMessage();
            Log.e(TAG, "❌ " + errorMsg);
            updateNotification(errorMsg); 

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
            NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }
}
