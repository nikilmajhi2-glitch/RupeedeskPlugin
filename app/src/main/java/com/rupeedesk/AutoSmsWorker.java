package com.rupeedesk.smsaautosender;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.telephony.SmsManager;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.Transaction;
import com.google.firebase.firestore.WriteBatch;

import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

public class AutoSmsWorker extends Worker {

    private static final String TAG = "AutoSmsWorker";
    private final FirebaseFirestore db = FirebaseFirestore.getInstance();
    private String userId;

    public AutoSmsWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Log.d(TAG, "🚀 AutoSmsWorker (Global) started.");

        SharedPreferences prefs = getApplicationContext().getSharedPreferences("AppPrefs", Context.MODE_PRIVATE);
        userId = prefs.getString("userId", null);

        if (userId == null || userId.isEmpty()) {
            Log.w(TAG, "⚠️ No User ID bound. Wallet cannot be credited. Worker stopping.");
            return Result.success();
        }

        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean success = new AtomicBoolean(true);

        // --- IMPROVEMENT 1: Prevent Spam Blocking ---
        // We only fetch a small batch to avoid sending too many SMS at once.
        // We also fetch "stuck" tasks (see Improvement 2).
        long tenMinutesAgo = System.currentTimeMillis() - (10 * 60 * 1000);
        Date stuckDate = new Date(tenMinutesAgo);

        db.collection("sms_tasks")
                .whereIn("status", Arrays.asList("pending", "failed", "sending"))
                .orderBy("createdAt", Query.Direction.ASCENDING)
                .limit(20) // Get 20 to check for stuck tasks
                .get()
                .addOnSuccessListener(snapshot -> {
                    if (snapshot.isEmpty()) {
                        Log.d(TAG, "✅ No tasks found in global queue.");
                        latch.countDown();
                        return;
                    }

                    Log.d(TAG, "Found " + snapshot.size() + " tasks... checking status.");
                    
                    WriteBatch batch = db.batch();
                    int tasksToLease = 5; // --- THIS IS THE RATE LIMIT ---

                    for (DocumentSnapshot doc : snapshot.getDocuments()) {
                        String status = doc.getString("status");
                        
                        // --- IMPROVEMENT 2: Fix "Stuck" Tasks ---
                        if ("sending".equals(status)) {
                            Date leasedAt = doc.getDate("leasedAt");
                            if (leasedAt != null && leasedAt.before(stuckDate)) {
                                Log.w(TAG, "Task " + doc.getId() + " is stuck! Resetting to 'failed'.");
                                batch.update(doc.getReference(), "status", "failed", "leasedBy", null);
                            }
                            continue; // Move to the next document
                        }

                        // We only care about "pending" or "failed" from here
                        if (tasksToLease > 0 && ("pending".equals(status) || "failed".equals(status))) {
                            // This is a task we can try to lease and send
                            leaseAndProcessTask(doc);
                            tasksToLease--; // Decrement our sending quota
                        }
                    }

                    // Commit any "stuck" task fixes
                    batch.commit().addOnSuccessListener(v -> {
                        Log.d(TAG, "Stuck task check complete.");
                        latch.countDown();
                    }).addOnFailureListener(e -> {
                        Log.e(TAG, "Failed to update stuck tasks: " + e.getMessage());
                        latch.countDown();
                    });

                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "🔥 Firestore fetch failed: " + e.getMessage());
                    success.set(false);
                    latch.countDown();
                });

        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // FIXED: Added parentheses
            return Result.retry();
        }

        Log.d(TAG, "🏁 AutoSmsWorker (Global) finished.");
        return success.get() ? Result.success() : Result.retry();
    }

    private void leaseAndProcessTask(DocumentSnapshot doc) {
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

                // ** LEASE THE TASK **
                transaction.update(docRef, 
                    "status", "sending", 
                    "leasedBy", userId, 
                    "leasedAt", new Date() // --- IMPROVEMENT 2: Add lease timestamp ---
                );
                return snapshot;
            } else {
                Log.d(TAG, "Task " + id + " was already leased. Skipping.");
                return null;
            }
        }).addOnSuccessListener(snapshot -> {
            if (snapshot != null) {
                // We successfully leased this task
                sendSms(snapshot, userId);
            }
        }).addOnFailureListener(e -> {
            Log.e(TAG, "Failed to lease task " + id + ": " + e.getMessage());
        });
    }
    
    // sendSms function remains the same as before...
    private void sendSms(DocumentSnapshot doc, String senderUserId) {
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

            SmsManager smsManager = SmsManager.getDefault();
            smsManager.sendTextMessage(phone, null, message, sentPI, null);
            Log.d(TAG, "📨 Sending SMS for leased task: " + id + " (Attempt #" + (retryCount + 1) + ")");

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
    }
