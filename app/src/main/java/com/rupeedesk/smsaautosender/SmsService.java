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

// --- THIS IS THE FIX ---
// You must import the R file from your main package
import com.rupeedesk.R;
// --- END FIX ---

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
                .setContentText("Actively listening for new tasks...")
                .setSmallIcon(R.mipmap.ic_launcher) // This line will now work
                .setOngoing(true)
                .build();
        startForeground(1, notification);

        Log.d(TAG, "SmsService started in foreground.");
        startSmsListener();
    }

    private void startSmsListener() {
        if (firestoreListener != null) {
            firestoreListener.remove();
        }

        SharedPreferences prefs = getApplicationContext().getSharedPreferences("AppPrefs", Context.MODE_PRIVATE);
        userId = prefs.getString("userId", null);
        subscriptionId = prefs.getInt("subscriptionId", -1);

        if (userId == null || userId.isEmpty()) {
            Log.e(TAG, "No UserID bound. Listener not started.");
            return;
        }

        if (!isNetworkAvailable()) {
            Log.e(TAG, "No network. Listener not started.");
            return;
        }

        Log.d(TAG, "Attaching Firestore Snapshot Listener...");

        Query query = db.collection("sms_tasks")
                .whereIn("status", Arrays.asList("pending", "failed"))
                .orderBy("createdAt", Query.Direction.ASCENDING)
                .limit(5); 

        firestoreListener = query.addSnapshotListener((snapshot, error) -> {
            if (error != null) {
                Log.e(TAG, "🔥 Firestore Listen failed: ", error);
                return;
            }

            if (snapshot != null && !snapshot.isEmpty()) {
                Log.d(TAG, "✅ New tasks received. Processing " + snapshot.size() + " tasks...");
                for (DocumentSnapshot doc : snapshot.getDocuments()) {
                    leaseAndProcessTask(doc, subscriptionId);
                }
            } else {
                Log.d(TAG, "Queue is empty or snapshot was null.");
            }
        });
    }

    private boolean isNetworkAvailable() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
        return activeNetwork != null && activeNetwork.isConnectedOrConnecting();
    }


    private void leaseAndProcessTask(DocumentSnapshot doc, int subId) {
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
                Log.d(TAG, "Task " + id + " was already leased/sending. Skipping.");
                return null;
            }
        }).addOnSuccessListener(snapshot -> {
            if (snapshot != null) {
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
