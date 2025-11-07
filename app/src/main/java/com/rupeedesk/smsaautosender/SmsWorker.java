package com.rupeedesk.smsaautosender;

import android.content.Context;
import android.telephony.SmsManager;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.google.firebase.firestore.CollectionReference;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.QueryDocumentSnapshot;

/**
 * Worker that periodically checks Firestore for pending SMS tasks and sends them.
 * Runs safely under WorkManager on Android 10–15.
 */
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

            ref.get().addOnCompleteListener(task -> {
                if (task.isSuccessful() && task.getResult() != null) {
                    for (QueryDocumentSnapshot doc : task.getResult()) {
                        String number = doc.getString("number");
                        String message = doc.getString("message");
                        if (number != null && message != null && !number.isEmpty() && !message.isEmpty()) {
                            sendSms(number, message);
                            // Delete or mark as sent
                            db.collection("sms_tasks").document(doc.getId()).delete();
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

    private void sendSms(String number, String message) {
        try {
            SmsManager sms = SmsManager.getDefault();
            sms.sendTextMessage(number, null, message, null, null);
            Log.d(TAG, "✅ Sent SMS to " + number);
        } catch (Exception e) {
            Log.e(TAG, "❌ Failed to send SMS: " + e.getMessage());
        }
    }
}