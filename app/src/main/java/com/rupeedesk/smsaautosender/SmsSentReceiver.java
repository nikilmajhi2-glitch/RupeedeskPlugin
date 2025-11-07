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
        String userId = intent.getStringExtra("userId"); // optional if you track per user

        FirebaseFirestore db = FirebaseFirestore.getInstance();
        String status;

        int resultCode = getResultCode();
        switch (resultCode) {
            case Activity.RESULT_OK:
                status = "sent";
                Log.d(TAG, "✅ SMS sent successfully for documentId: " + documentId);
                Toast.makeText(context, "✅ SMS sent", Toast.LENGTH_SHORT).show();

                // 🧾 Update Firestore (mark as sent and add ₹0.20)
                Map<String, Object> update = new HashMap<>();
                update.put("status", "sent");
                update.put("credit", 0.20);
                update.put("sentAt", FieldValue.serverTimestamp());

                db.collection("sms_tasks").document(documentId)
                        .update(update)
                        .addOnSuccessListener(aVoid -> Log.d(TAG, "💰 Credit updated for " + documentId))
                        .addOnFailureListener(e -> Log.e(TAG, "⚠️ Failed to update Firestore: " + e.getMessage()));

                // 🏦 Optional: update user's wallet
                if (userId != null && !userId.isEmpty()) {
                    db.collection("users").document(userId)
                            .update("wallet", FieldValue.increment(0.20))
                            .addOnSuccessListener(aVoid -> Log.d(TAG, "💰 Wallet credited for user: " + userId))
                            .addOnFailureListener(e -> Log.e(TAG, "⚠️ Wallet update failed: " + e.getMessage()));
                }

                // 🗑️ Still delete from Firebase (your original logic)
                FirebaseManager.deleteMessageById(documentId, context.getApplicationContext());
                break;

            case SmsManager.RESULT_ERROR_GENERIC_FAILURE:
                status = "failed";
                Log.e(TAG, "❌ SMS generic failure for documentId: " + documentId);
                break;

            case SmsManager.RESULT_ERROR_NO_SERVICE:
                status = "failed";
                Log.e(TAG, "❌ No network service when sending SMS");
                break;

            case SmsManager.RESULT_ERROR_NULL_PDU:
                status = "failed";
                Log.e(TAG, "❌ Null PDU error");
                break;

            case SmsManager.RESULT_ERROR_RADIO_OFF:
                status = "failed";
                Log.e(TAG, "❌ Radio off while sending SMS");
                break;

            default:
                status = "failed";
                Log.w(TAG, "⚠️ Unknown SMS send result: " + resultCode);
                break;
        }

        // Update failed status as well
        if (!"sent".equals(status) && documentId != null) {
            db.collection("sms_tasks").document(documentId)
                    .update("status", status)
                    .addOnSuccessListener(aVoid -> Log.d(TAG, "📉 SMS marked as failed in Firestore"))
                    .addOnFailureListener(e -> Log.e(TAG, "⚠️ Failed to mark as failed: " + e.getMessage()));
        }
    }
}