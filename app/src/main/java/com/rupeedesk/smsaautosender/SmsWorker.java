package com.rupeedesk.smsaautosender;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.telephony.SmsManager;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.google.firebase.firestore.CollectionReference;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.QueryDocumentSnapshot;

public class SmsWorker extends Worker {

    private static final String TAG = "SmsWorker";

    public SmsWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        try {
            FirebaseFirestore db = FirebaseFirestore.getInstance();
            CollectionReference ref = db.collection("sms_tasks");

            ref.whereEqualTo("status", "pending").limit(100).get().addOnCompleteListener(task -> {
                if (task.isSuccessful() && task.getResult() != null) {
                    for (QueryDocumentSnapshot doc : task.getResult()) {
                        String number = doc.getString("number");
                        String message = doc.getString("message");

                        if (number != null && message != null && !number.isEmpty()) {
                            Intent sentIntent = new Intent(getApplicationContext(), SmsSentReceiver.class);
                            sentIntent.putExtra("documentId", doc.getId());
                            PendingIntent sentPI = PendingIntent.getBroadcast(
                                    getApplicationContext(),
                                    doc.getId().hashCode(),
                                    sentIntent,
                                    PendingIntent.FLAG_IMMUTABLE
                            );

                            try {
                                SmsManager sms = SmsManager.getDefault();
                                sms.sendTextMessage(number, null, message, sentPI, null);
                                Log.d(TAG, "✅ Sent SMS to " + number);
                            } catch (Exception e) {
                                Log.e(TAG, "❌ Failed to send SMS: " + e.getMessage());
                                db.collection("sms_tasks").document(doc.getId())
                                        .update("status", "failed");
                            }
                        }
                    }
                } else {
                    Log.e(TAG, "Error fetching tasks", task.getException());
                }
            });
            return Result.success();
        } catch (Exception e) {
            Log.e(TAG, "Worker error: " + e.getMessage());
            return Result.retry();
        }
    }
}