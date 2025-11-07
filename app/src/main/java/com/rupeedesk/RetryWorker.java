package com.rupeedesk;

import android.content.Context;
import android.telephony.SmsManager;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.QuerySnapshot;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * RetryWorker periodically checks "failed_sms" collection and retries sending
 * up to 3 times using available SIM slots.
 */
public class RetryWorker extends Worker {

    private static final String TAG = "RetryWorker";
    private final FirebaseFirestore db = FirebaseFirestore.getInstance();

    public RetryWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Log.d(TAG, "🚀 Auto-retry started");

        AtomicBoolean success = new AtomicBoolean(true);
        CountDownLatch latch = new CountDownLatch(1);

        db.collection("failed_sms")
                .limit(50)
                .get()
                .addOnSuccessListener(snapshot -> {
                    handleRetry(snapshot);
                    latch.countDown();
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "🔥 Firestore fetch failed: " + e.getMessage());
                    success.set(false);
                    latch.countDown();
                });

        try {
            latch.await(); // ⏳ Wait for Firestore task to finish before returning
        } catch (InterruptedException e) {
            Log.e(TAG, "⏹ Interrupted while waiting: " + e.getMessage());
            Thread.currentThread().interrupt();
            return Result.retry();
        }

        return success.get() ? Result.success() : Result.retry();
    }

    private void handleRetry(QuerySnapshot snapshot) {
        if (snapshot == null || snapshot.isEmpty()) {
            Log.d(TAG, "✅ No failed SMS found.");
            return;
        }

        SubscriptionManager subManager =
                (SubscriptionManager) getApplicationContext().getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
        List<SubscriptionInfo> simList = null;
        try {
            simList = subManager != null ? subManager.getActiveSubscriptionInfoList() : null;
        } catch (SecurityException se) {
            Log.w(TAG, "⚠️ Missing READ_PHONE_STATE permission for SIM access");
        }

        for (DocumentSnapshot doc : snapshot.getDocuments()) {
            String id = doc.getId();
            String phone = doc.getString("phone");
            String message = doc.getString("message");
            Long retryCount = doc.getLong("retryCount");
            Long simSlot = doc.getLong("preferredSimSlot");

            if (phone == null || message == null) continue;
            if (retryCount == null) retryCount = 0L;
            if (simSlot == null) simSlot = 0L;

            if (retryCount >= 3) {
                Log.w(TAG, "❌ Skipping " + phone + " (max retries reached)");
                continue;
            }

            try {
                int slot = simSlot.intValue();
                if (simList == null || simList.size() <= slot) slot = 0;
                int subId = simList != null && !simList.isEmpty()
                        ? simList.get(slot).getSubscriptionId()
                        : SubscriptionManager.getDefaultSubscriptionId();

                SmsManager smsManager = SmsManager.getSmsManagerForSubscriptionId(subId);
                smsManager.sendTextMessage(phone, null, message, null, null);

                db.collection("failed_sms").document(id).delete();
                Log.d(TAG, "✅ Retried SMS sent: " + phone);
            } catch (Exception e) {
                Log.e(TAG, "⚠️ Retry failed for " + phone + ": " + e.getMessage());
                Map<String, Object> update = new HashMap<>();
                update.put("retryCount", retryCount + 1);
                update.put("error", e.getMessage());
                db.collection("failed_sms").document(id).update(update);
            }
        }
    }
}