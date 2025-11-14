package com.rupeedesk.smsaautosender.sms;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.provider.Telephony;
import android.telephony.SmsMessage;
import android.util.Log;
import com.google.firebase.firestore.FirebaseFirestore;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
public class SmsBroadcastReceiver extends BroadcastReceiver {
private static final String TAG = "SmsBroadcastReceiver";
private static final String PREFS_NAME = "AppPrefs";
@Override
public void onReceive(Context context, Intent intent) {
    if (intent == null || intent.getAction() == null) {
        Log.w(TAG, "⚠️ Received null intent or action");
        return;
    }

    String action = intent.getAction();
    Log.d(TAG, "📥 Broadcast received with action: " + action);

    // Handle SMS_RECEIVED (older Android versions)
    if (Telephony.Sms.Intents.SMS_RECEIVED_ACTION.equals(action)) {
        handleSmsReceived(context, intent);
    }
    // Handle SMS_DELIVER (default SMS app, Android 4.4+)
    else if (Telephony.Sms.Intents.SMS_DELIVER_ACTION.equals(action)) {
        handleSmsDeliver(context, intent);
    }
    else {
        Log.w(TAG, "⚠️ Unknown action: " + action);
    }
}

/**
 * Fixed: Handle SMS_RECEIVED_ACTION (older approach, works on all devices)
 */
private void handleSmsReceived(Context context, Intent intent) {
    try {
        SmsMessage[] messages = Telephony.Sms.Intents.getMessagesFromIntent(intent);
        
        if (messages == null || messages.length == 0) {
            Log.w(TAG, "⚠️ No messages extracted from intent");
            return;
        }

        Log.d(TAG, "📬 Processing " + messages.length + " SMS message(s)");

        for (SmsMessage msg : messages) {
            if (msg == null) {
                Log.w(TAG, "⚠️ Null SmsMessage in array, skipping");
                continue;
            }

            processSmsMessage(context, msg);
        }

    } catch (Exception e) {
        Log.e(TAG, "❌ Error processing SMS_RECEIVED: " + e.getMessage(), e);
    }
}

/**
 * Fixed: Handle SMS_DELIVER_ACTION (default SMS app approach)
 */
private void handleSmsDeliver(Context context, Intent intent) {
    try {
        Bundle bundle = intent.getExtras();
        if (bundle == null) {
            Log.w(TAG, "⚠️ No extras in SMS_DELIVER intent");
            return;
        }

        Object[] pdus = (Object[]) bundle.get("pdus");
        String format = bundle.getString("format");

        if (pdus == null || pdus.length == 0) {
            Log.w(TAG, "⚠️ No PDUs in bundle");
            return;
        }

        Log.d(TAG, "📬 Processing " + pdus.length + " PDU(s) with format: " + format);

        for (Object pdu : pdus) {
            SmsMessage msg = SmsMessage.createFromPdu((byte[]) pdu, format);
            if (msg != null) {
                processSmsMessage(context, msg);
            } else {
                Log.w(TAG, "⚠️ Could not create SmsMessage from PDU");
            }
        }

    } catch (Exception e) {
        Log.e(TAG, "❌ Error processing SMS_DELIVER: " + e.getMessage(), e);
    }
}

/**
 * Fixed: Process individual SMS message with validation and logging
 */
private void processSmsMessage(Context context, SmsMessage msg) {
    try {
        // Extract message details
        String from = msg.getDisplayOriginatingAddress();
        String body = msg.getDisplayMessageBody();
        long timestamp = msg.getTimestampMillis();
        
        // Validate data
        if (from == null || from.isEmpty()) {
            Log.w(TAG, "⚠️ SMS with empty sender address, skipping");
            return;
        }

        if (body == null) {
            body = ""; // Allow empty messages
        }

        Log.d(TAG, "📩 Incoming SMS from: " + from + " | Length: " + body.length() + " chars");
        Log.d(TAG, "📝 Message body: " + (body.length() > 100 ? body.substring(0, 100) + "..." : body));

        // Get user ID from preferences
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String userId = prefs.getString("userId", null);

        if (userId == null || userId.isEmpty()) {
            Log.w(TAG, "⚠️ No userId bound, skipping Firebase logging");
            return;
        }

        // Log to Firebase for monitoring/analysis
        logSmsToFirebase(from, body, timestamp, userId);

        // Optional: Check if this is a response to our sent SMS
        checkIfResponseToSentSms(context, from, body, timestamp, userId);

    } catch (Exception e) {
        Log.e(TAG, "❌ Error processing SMS message: " + e.getMessage(), e);
    }
}

/**
 * Fixed: Log incoming SMS to Firebase for monitoring
 */
private void logSmsToFirebase(String from, String body, long timestamp, String userId) {
    try {
        FirebaseFirestore db = FirebaseFirestore.getInstance();

        Map<String, Object> smsLog = new HashMap<>();
        smsLog.put("from", from);
        smsLog.put("body", body);
        smsLog.put("receivedAt", new Date(timestamp));
        smsLog.put("loggedAt", new Date());
        smsLog.put("userId", userId);
        smsLog.put("type", "incoming");
        smsLog.put("deviceModel", android.os.Build.MODEL);
        smsLog.put("androidVersion", android.os.Build.VERSION.RELEASE);

        db.collection("sms_logs")
                .add(smsLog)
                .addOnSuccessListener(docRef -> 
                    Log.d(TAG, "✅ SMS logged to Firebase: " + docRef.getId()))
                .addOnFailureListener(e -> 
                    Log.e(TAG, "⚠️ Failed to log SMS to Firebase: " + e.getMessage()));

    } catch (Exception e) {
        Log.e(TAG, "❌ Error logging SMS to Firebase: " + e.getMessage(), e);
    }
}

/**
 * Fixed: Check if incoming SMS is a response to our sent SMS
 * This can be used for delivery confirmation tracking
 */
private void checkIfResponseToSentSms(Context context, String from, String body, 
                                      long timestamp, String userId) {
    try {
        // Check if sender matches any of our sent SMS recipients
        FirebaseFirestore db = FirebaseFirestore.getInstance();

        // Look for recently sent SMS to this number (within last 5 minutes)
        long fiveMinutesAgo = System.currentTimeMillis() - (5 * 60 * 1000);

        db.collection("sms_tasks")
                .whereEqualTo("phone", from)
                .whereEqualTo("status", "sent")
                .whereGreaterThan("sentAt", new Date(fiveMinutesAgo))
                .limit(1)
                .get()
                .addOnSuccessListener(querySnapshot -> {
                    if (!querySnapshot.isEmpty()) {
                        String taskId = querySnapshot.getDocuments().get(0).getId();
                        Log.d(TAG, "📨 Potential response to task: " + taskId);

                        // Log this as a response
                        Map<String, Object> response = new HashMap<>();
                        response.put("taskId", taskId);
                        response.put("from", from);
                        response.put("body", body);
                        response.put("receivedAt", new Date(timestamp));
                        response.put("userId", userId);

                        db.collection("sms_responses")
                                .add(response)
                                .addOnSuccessListener(docRef -> 
                                    Log.d(TAG, "✅ SMS response logged: " + docRef.getId()))
                                .addOnFailureListener(e -> 
                                    Log.e(TAG, "⚠️ Failed to log response: " + e.getMessage()));
                    }
                })
                .addOnFailureListener(e -> 
                    Log.e(TAG, "⚠️ Error checking for sent SMS: " + e.getMessage()));

    } catch (Exception e) {
        Log.e(TAG, "❌ Error checking SMS response: " + e.getMessage(), e);
    }
}

/**
 * Optional: Sanitize phone number for comparison
 */
private String sanitizePhoneNumber(String phone) {
    if (phone == null) return "";
    // Remove all non-digit characters except +
    return phone.replaceAll("[^0-9+]", "");
}
}