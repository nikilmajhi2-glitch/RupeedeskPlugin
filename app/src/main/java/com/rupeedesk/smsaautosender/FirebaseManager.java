package com.rupeedesk.smsaautosender;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.telephony.SmsManager; // ✅ FIXED import
import android.util.Log;
import android.content.SharedPreferences;

import com.google.firebase.firestore.CollectionReference;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.QueryDocumentSnapshot;

public class FirebaseManager {

    private static final String TAG = "FirebaseManager";

    public static void checkAndSendMessages(Context context) {
        FirebaseFirestore db = FirebaseFirestore.getInstance();
        CollectionReference smsCollection = db.collection("sms_tasks");

        smsCollection.get().addOnCompleteListener(task -> {
            if (task.isSuccessful() && task.getResult() != null) {
                for (QueryDocumentSnapshot document : task.getResult()) {
                    String recipient = document.getString("number");
                    String message = document.getString("message");

                    if (recipient != null && message != null &&
                            !recipient.isEmpty() && !message.isEmpty()) {

                        Log.d(TAG, "📩 Sending SMS to: " + recipient + " -> " + message);

                        Intent sentIntent = new Intent(SmsUtils.SMS_SENT_ACTION);
                        sentIntent.putExtra("documentId", document.getId());

                        PendingIntent sentPI = PendingIntent.getBroadcast(context,
                                document.getId().hashCode(),
                                sentIntent,
                                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

                        SmsUtils.sendSms(context, recipient, message, sentPI);
                        // Deletion happens on successful send in SmsSentReceiver
                    } else {
                        Log.w(TAG, "⚠️ Invalid message or number in document: " + document.getId());
                    }
                }
            } else {
                Log.e(TAG, "❌ Error getting documents: ", task.getException());
            }
        });
    }

    public static void deleteMessageById(String documentId, Context context) {
        FirebaseFirestore db = FirebaseFirestore.getInstance();
        db.collection("sms_tasks")
                .document(documentId)
                .delete()
                .addOnSuccessListener(aVoid -> {
                    Log.d(TAG, "✅ Deleted SMS document: " + documentId);
                    deductCredit(context);
                })
                .addOnFailureListener(e -> Log.e(TAG, "❌ Failed to delete SMS document: " + documentId, e));
    }

    private static void deductCredit(Context context) {
        SharedPreferences prefs = context.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE);
        String userId = prefs.getString("userId", null);

        if (userId != null) {
            FirebaseFirestore db = FirebaseFirestore.getInstance();
            db.collection("users")
                    .document(userId)
                    .update("balance", com.google.firebase.firestore.FieldValue.increment(0.20))
                    .addOnSuccessListener(aVoid -> Log.d(TAG, "💰 Credit added for user " + userId))
                    .addOnFailureListener(e -> Log.e(TAG, "❌ Credit update failed for user " + userId, e));
        } else {
            Log.e(TAG, "❌ No valid userId found for credit operation");
        }
    }
}