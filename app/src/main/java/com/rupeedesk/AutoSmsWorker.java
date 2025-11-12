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

import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.Query;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The new "brains" of the app. This single worker runs periodically to:
 * 1. Find new ("pending") and "failed" SMS tasks for the bound user.
 * 2. Delete tasks that have failed 3 or more times.
 * 3. Send all other tasks.
 */
public class AutoSmsWorker extends Worker {

    private static final String TAG = "AutoSmsWorker";
    private final FirebaseFirestore db = FirebaseFirestore.getInstance();

    public AutoSmsWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Log.d(TAG, "🚀 AutoSmsWorker started.");

        // Step 1: Get the bound User ID
        SharedPreferences prefs = getApplicationContext().getSharedPreferences("AppPrefs", Context.MODE_PRIVATE);
        String userId = prefs.getString("userId", null);

        if (userId == null || userId.isEmpty()) {
            Log.w(TAG, "⚠️ No User ID bound. Worker stopping.");
            return Result.success(); // Not an error, just nothing to do
        }

        // We use a latch to wait for the async Firestore call to finish
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean success = new AtomicBoolean(true);

        // Step 2: Fetch tasks that are "pending" OR "failed" for this user
        db.collection("sms_tasks")
                .whereEqualTo("userId", userId) // IMPORTANT: Only tasks for this user
                .whereIn("status", Arrays.asList("pending", "failed"))
                .orderBy("createdAt", Query.Direction.ASCENDING) // Process oldest first
                .limit(50)
                .get()
                .addOnSuccessListener(snapshot -> {
                    if (snapshot.isEmpty()) {
                        Log.d(TAG, "✅ No pending or failed tasks found for user: " + userId);
                        latch.countDown();
                        return;
                    }

                    Log.d(TAG, "Found " + snapshot.size() + " tasks to process...");

                    for (DocumentSnapshot doc : snapshot.getDocuments()) {
                        processTask(doc, userId);
                    }
                    latch.countDown();
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "🔥 Firestore fetch failed: " + e.getMessage());
                    success.set(false);
                    latch.countDown();
                });

        try {
            latch.await(); // Wait for processing
        } catch (InterruptedException e) {
            Log.e(TAG, "Interrupted while waiting: " + e.getMessage());
            Thread.currentThread().interrupt();
            return Result.retry();
        }

        Log.d(TAG, "🏁 AutoSmsWorker finished.");
        return success.get() ? Result.success() : Result.retry();
    }

    private void processTask(DocumentSnapshot doc, String userId) {
        String id = doc.getId();
        String phone = doc.getString("phone");
        String message = doc.getString("message");
        Long retryCount = doc.getLong("retryCount");

        if (retryCount == null) {
            retryCount = 0L;
        }

        // Step 3: Check retry limit
        if (retryCount >= 3) {
            Log.w(TAG, "🗑️ Deleting task " + id + " (max retries reached).");
            db.collection("sms_tasks").document(id).delete();
            return;
        }

        if (phone == null || message == null) {
            Log.e(TAG, "Skipping task " + id + " (missing phone or message).");
            return;
        }

        // Step 4: Send the SMS
        try {
            Intent sentIntent = new Intent(getApplicationContext(), SmsSentReceiver.class);
            sentIntent.putExtra("documentId", id);
            sentIntent.putExtra("userId", userId);
            sentIntent.putExtra("retryCount", retryCount); // Pass current retry count

            PendingIntent sentPI = PendingIntent.getBroadcast(
                    getApplicationContext(),
                    id.hashCode(), // Use unique hashcode for each PI
                    sentIntent,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
            );

            SmsManager smsManager = SmsManager.getDefault();
            smsManager.sendTextMessage(phone, null, message, sentPI, null);
            Log.d(TAG, "📨 Sending SMS for task: " + id + " (Attempt #" + (retryCount + 1) + ")");

            // Optimistically update status to "sending"
            db.collection("sms_tasks").document(id).update("status", "sending");

        } catch (Exception e) {
            Log.e(TAG, "❌ Failed to initiate send for task " + id + ": " + e.getMessage());
            // Update Firestore to "failed" so we can retry next time
            Map<String, Object> update = new HashMap<>();
            update.put("status", "failed");
            update.put("retryCount", retryCount + 1);
            update.put("lastError", "Send initiation failed: " + e.getMessage());
            db.collection("sms_tasks").document(id).update(update);
        }
    }
    }
