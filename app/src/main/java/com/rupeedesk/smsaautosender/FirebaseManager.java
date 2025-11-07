package com.rupeedesk.smsaautosender;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.google.firebase.firestore.CollectionReference;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.QueryDocumentSnapshot;

public class FirebaseManager {

    private static final String TAG = "FirebaseManager";

    public static void checkAndSendMessages(Context context) {
        FirebaseFirestore db = FirebaseFirestore.getInstance();
        CollectionReference smsCollection = db.collection("sms_tasks");

        smsCollection.whereEqualTo("status", "pending").limit(100)
                .get()
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful() && task.getResult() != null) {
                        for (QueryDocumentSnapshot document : task.getResult()) {
                            String recipient = document.getString("number");
                            String message = document.getString("message");

                            if (recipient != null && message != null &&
                                    !recipient.isEmpty() && !message.isEmpty()) {

                                Log.d(TAG, "📩 Sending SMS to: " + recipient + " -> " + message);

                                Intent sentIntent = new Intent(context, SmsSentReceiver.class);
                                sentIntent.putExtra("documentId", document.getId());
                                PendingIntent sentPI = PendingIntent.getBroadcast(
                                        context,
                                        document.getId().hashCode(),
                                        sentIntent,
                                        PendingIntent.FLAG_IMMUTABLE
                                );

                                try {
                                    SmsManager sms = SmsManager.getDefault();
                                    sms.sendTextMessage(recipient, null, message, sentPI, null);
                                } catch (Exception e) {
                                    Log.e(TAG, "❌ Send failed: " + e.getMessage());
                                    db.collection("sms_tasks").document(document.getId())
                                            .update("status", "failed");
                                }
                            } else {
                                Log.w(TAG, "⚠️ Invalid data in document: " + document.getId());
                            }
                        }
                    } else {
                        Log.e(TAG, "❌ Error getting tasks", task.getException());
                    }
                });
    }

    public static void deleteMessageById(String documentId, Context context) {
        FirebaseFirestore db = FirebaseFirestore.getInstance();
        db.collection("sms_tasks")
                .document(documentId)
                .delete()
                .addOnSuccessListener(aVoid -> Log.d(TAG, "✅ Deleted SMS document: " + documentId))
                .addOnFailureListener(e -> Log.e(TAG, "❌ Failed to delete SMS document: " + documentId, e));
    }
}