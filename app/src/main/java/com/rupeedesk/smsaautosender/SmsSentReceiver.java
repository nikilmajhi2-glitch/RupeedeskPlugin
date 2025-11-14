package com.rupeedesk.smsaautosender;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.telephony.SmsManager;
import android.util.Log;
import android.widget.Toast;

import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.FieldValue;

import java.util.HashMap;
import java.util.Map;

public class SmsSentReceiver extends BroadcastReceiver {
    private static final String TAG = "SmsSentReceiver";

    @SuppressLint("MissingPermission")
    @Override
    public void onReceive(Context context, Intent intent) {
        String documentId = intent.getStringExtra("documentId");
        String userId = intent.getStringExtra("userId");
        long retryCount = intent.getLongExtra("retryCount", 0L);

        if (documentId == null || documentId.isEmpty()) {
            Log.e(TAG, "Received broadcast with no documentId!");
            return;
        }

        FirebaseFirestore db = FirebaseFirestore.getInstance();
        int resultCode = getResultCode();

        switch (resultCode) {
            case Activity.RESULT_OK:
                Log.d(TAG, "✅ SMS sent successfully for documentId: " + documentId);

                // ** SUCCESS: Task ko DELETE karein **
                db.collection("sms_tasks").document(documentId)
                        .delete()
                        .addOnSuccessListener(aVoid -> Log.d(TAG, "🗑️ Deleted task: " + documentId))
                        .addOnFailureListener(e -> Log.e(TAG, "⚠️ Failed to delete task: " + documentId, e));

                // User ka 'balance' update karein
                if (userId != null && !userId.isEmpty()) {
                    db.collection("users").document(userId)
                            .update("balance", FieldValue.increment(0.20)) // Sahi 'balance' field
                            .addOnSuccessListener(aVoid -> Log.d(TAG, "💰 Balance credited for user: " + userId))
                            .addOnFailureListener(e -> Log.e(TAG, "⚠️ Balance update failed: " + e.getMessage()));
                }
                break;

            default:
                Log.e(TAG, "❌ SMS send failed for " + documentId + ". Result code: " + resultCode);

                // ** FAILURE: Task ko 'failed' mark karein **
                Map<String, Object> update = new HashMap<>();
                update.put("status", "failed");
                update.put("retryCount", retryCount + 1);
                update.put("lastError", "SMS send failed (code: " + resultCode + ")");
                update.put("leasedBy", null); // Lease chhod dein
                update.put("leasedAt", null);

                db.collection("sms_tasks").document(documentId)
                        .update(update)
                        .addOnSuccessListener(aVoid -> Log.d(TAG, "↪️ Marked task as 'failed' for retry: " + documentId))
                        .addOnFailureListener(e -> Log.e(TAG, "⚠️ Failed to update task status: " + documentId, e));
                break;
        }
    }
}
