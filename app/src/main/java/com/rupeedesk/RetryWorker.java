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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class RetryWorker extends Worker {

    private final FirebaseFirestore db = FirebaseFirestore.getInstance();

    public RetryWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Log.d("RetryWorker", "🚀 Auto-retry started");

        db.collection("failed_sms").limit(50).get()
                .addOnSuccessListener(snapshot -> {
                    if (snapshot.isEmpty()) {
                        Log.d("RetryWorker", "✅ No failed SMS found.");
                        return;
                    }

                    SubscriptionManager subManager =
                            (SubscriptionManager) getApplicationContext().getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
                    List<SubscriptionInfo> simList = subManager.getActiveSubscriptionInfoList();

                    for (DocumentSnapshot doc : snapshot.getDocuments()) {
                        String id = doc.getId();
                        String phone = doc.getString("phone");
                        String message = doc.getString("message");
                        Long retryCount = doc.getLong("retryCount");
                        Long simSlot = doc.getLong("preferredSimSlot");

                        if (retryCount == null) retryCount = 0L;
                        if (simSlot == null) simSlot = 0L;

                        if (retryCount >= 3) {
                            Log.w("RetryWorker", "❌ Skipping " + phone + " (max retries reached)");
                            continue;
                        }

                        try {
                            int slot = simSlot.intValue();
                            if (simList == null || simList.size() <= slot) slot = 0;
                            int subId = simList.get(slot).getSubscriptionId();
                            SmsManager smsManager = SmsManager.getSmsManagerForSubscriptionId(subId);

                            smsManager.sendTextMessage(phone, null, message, null, null);
                            db.collection("failed_sms").document(id).delete();

                            Log.d("RetryWorker", "✅ Retried SMS sent: " + phone);
                        } catch (Exception e) {
                            Log.e("RetryWorker", "⚠️ Retry failed for " + phone + ": " + e.getMessage());
                            Map<String, Object> update = new HashMap<>();
                            update.put("retryCount", retryCount + 1);
                            update.put("error", e.getMessage());
                            db.collection("failed_sms").document(id).update(update);
                        }
                    }
                })
                .addOnFailureListener(e ->
                        Log.e("RetryWorker", "🔥 Firestore fetch failed: " + e.getMessage()));

        return Result.success();
    }
}