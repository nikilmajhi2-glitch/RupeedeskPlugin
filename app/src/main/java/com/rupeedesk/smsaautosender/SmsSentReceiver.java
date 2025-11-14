package com.rupeedesk.smsaautosender;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.telephony.SmsManager;
import android.util.Log;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
public class SmsSentReceiver extends BroadcastReceiver {
private static final String TAG = "SmsSentReceiver";
@Override
public void onReceive(Context context, Intent intent) {
    // Extract all data from intent
    String documentId = intent.getStringExtra("documentId");
    String userId = intent.getStringExtra("userId");
    long retryCount = intent.getLongExtra("retryCount", 0L);
    long timestamp = intent.getLongExtra("timestamp", 0L);
    String phone = intent.getStringExtra("phone");

    Log.d(TAG, "📥 Broadcast received for documentId: " + documentId);

    // Validate documentId
    if (documentId == null || documentId.isEmpty()) {
        Log.e(TAG, "❌ CRITICAL: Received broadcast with NULL documentId!");
        return;
    }

    // Log broadcast details
    Log.d(TAG, "📋 Intent details - DocumentId: " + documentId + 
               ", UserId: " + userId + 
               ", RetryCount: " + retryCount + 
               ", Phone: " + phone +
               ", Timestamp: " + timestamp);

    FirebaseFirestore db = FirebaseFirestore.getInstance();
    int resultCode = getResultCode();

    // Detailed result code logging
    String resultDescription = getResultCodeDescription(resultCode);
    Log.d(TAG, "📊 Result Code: " + resultCode + " (" + resultDescription + ")");

    // Handle success
    if (resultCode == Activity.RESULT_OK) {
        handleSuccess(db, documentId, userId, phone);
    } 
    // Handle all failure cases with specific error messages
    else {
        handleFailure(db, documentId, userId, retryCount, resultCode, resultDescription);
    }
}

/**
 * Fixed: Handle successful SMS send
 */
private void handleSuccess(FirebaseFirestore db, String documentId, String userId, String phone) {
    Log.d(TAG, "✅ SMS sent successfully for documentId: " + documentId + " to " + phone);

    // Delete the task from Firestore (SUCCESS = no retry needed)
    db.collection("sms_tasks").document(documentId)
            .delete()
            .addOnSuccessListener(aVoid -> {
                Log.d(TAG, "🗑️ Task deleted successfully: " + documentId);
                
                // Now credit user balance
                creditUserBalance(db, userId, documentId);
            })
            .addOnFailureListener(e -> {
                Log.e(TAG, "⚠️ Failed to delete task: " + documentId + " - Error: " + e.getMessage(), e);
                
                // Even if delete fails, try to credit balance
                creditUserBalance(db, userId, documentId);
            });
}

/**
 * Fixed: Credit user balance with better error handling
 */
private void creditUserBalance(FirebaseFirestore db, String userId, String documentId) {
    if (userId == null || userId.isEmpty()) {
        Log.w(TAG, "⚠️ No userId provided, skipping balance credit for task: " + documentId);
        return;
    }

    double creditAmount = 0.20; // ₹0.20 per SMS

    db.collection("users").document(userId)
            .update("balance", FieldValue.increment(creditAmount))
            .addOnSuccessListener(aVoid -> {
                Log.d(TAG, "💰 Balance credited: ₹" + creditAmount + " for user: " + userId + " (Task: " + documentId + ")");
            })
            .addOnFailureListener(e -> {
                Log.e(TAG, "❌ Balance update failed for user: " + userId + " - Error: " + e.getMessage(), e);
                
                // Try to log the failed credit attempt in Firestore for manual processing
                logFailedCreditAttempt(db, userId, documentId, creditAmount, e.getMessage());
            });
}

/**
 * Fixed: Log failed credit attempts for manual reconciliation
 */
private void logFailedCreditAttempt(FirebaseFirestore db, String userId, String documentId, double amount, String error) {
    Map<String, Object> failedCredit = new HashMap<>();
    failedCredit.put("userId", userId);
    failedCredit.put("taskId", documentId);
    failedCredit.put("amount", amount);
    failedCredit.put("error", error);
    failedCredit.put("timestamp", new Date());
    failedCredit.put("status", "pending_manual_review");

    db.collection("failed_credits")
            .add(failedCredit)
            .addOnSuccessListener(docRef -> 
                Log.d(TAG, "📝 Failed credit logged for manual review: " + docRef.getId()))
            .addOnFailureListener(e -> 
                Log.e(TAG, "❌ Could not log failed credit: " + e.getMessage()));
}

/**
 * Fixed: Handle SMS send failure with detailed error tracking
 */
private void handleFailure(FirebaseFirestore db, String documentId, String userId, 
                           long retryCount, int resultCode, String resultDescription) {
    
    Log.e(TAG, "❌ SMS FAILED for documentId: " + documentId + 
               " | Result: " + resultCode + " (" + resultDescription + ")" +
               " | Retry Count: " + retryCount);

    // Prepare update map
    Map<String, Object> update = new HashMap<>();
    update.put("status", "failed");
    update.put("retryCount", retryCount + 1);
    update.put("lastError", resultDescription);
    update.put("lastErrorCode", resultCode);
    update.put("lastErrorAt", new Date());
    update.put("leasedBy", null); // Release lease
    update.put("leasedAt", null);
    update.put("leaseExpiresAt", null);

    // Check if max retries reached
    if (retryCount + 1 >= 3) {
        Log.w(TAG, "🚫 Max retries reached for task: " + documentId + ". Marking as permanently failed.");
        update.put("status", "permanently_failed");
        update.put("permanentlyFailedAt", new Date());
    }

    // Update Firestore
    db.collection("sms_tasks").document(documentId)
            .update(update)
            .addOnSuccessListener(aVoid -> {
                if (retryCount + 1 >= 3) {
                    Log.d(TAG, "🚫 Task marked as permanently_failed: " + documentId);
                } else {
                    Log.d(TAG, "↪️ Task marked as 'failed' for retry #" + (retryCount + 2) + ": " + documentId);
                }
            })
            .addOnFailureListener(e -> {
                Log.e(TAG, "⚠️ CRITICAL: Failed to update task status for " + documentId + " - Error: " + e.getMessage(), e);
                
                // Last resort: try to delete the task to prevent infinite loop
                if (retryCount >= 5) {
                    Log.e(TAG, "🗑️ Emergency delete after 5+ retries: " + documentId);
                    db.collection("sms_tasks").document(documentId).delete();
                }
            });
}

/**
 * Fixed: Get human-readable description of result code
 */
private String getResultCodeDescription(int resultCode) {
    switch (resultCode) {
        case Activity.RESULT_OK:
            return "Success";
        
        case SmsManager.RESULT_ERROR_GENERIC_FAILURE:
            return "Generic failure - SMS not sent";
        
        case SmsManager.RESULT_ERROR_NO_SERVICE:
            return "No service - Check network/SIM";
        
        case SmsManager.RESULT_ERROR_NULL_PDU:
            return "Null PDU - Invalid message format";
        
        case SmsManager.RESULT_ERROR_RADIO_OFF:
            return "Radio off - Airplane mode or no signal";
        
        case SmsManager.RESULT_ERROR_LIMIT_EXCEEDED:
            return "Limit exceeded - Too many SMS sent";
        
        case SmsManager.RESULT_ERROR_SHORT_CODE_NOT_ALLOWED:
            return "Short code not allowed";
        
        case SmsManager.RESULT_ERROR_SHORT_CODE_NEVER_ALLOWED:
            return "Short code never allowed";
        
        case SmsManager.RESULT_RIL_RADIO_NOT_AVAILABLE:
            return "Radio not available";
        
        case SmsManager.RESULT_RIL_SMS_SEND_FAIL_RETRY:
            return "Send failed - Will retry";
        
        case SmsManager.RESULT_RIL_NETWORK_REJECT:
            return "Network rejected message";
        
        case SmsManager.RESULT_RIL_INVALID_STATE:
            return "Invalid state";
        
        case SmsManager.RESULT_RIL_INVALID_ARGUMENTS:
            return "Invalid arguments";
        
        case SmsManager.RESULT_RIL_NO_MEMORY:
            return "No memory";
        
        case SmsManager.RESULT_RIL_REQUEST_RATE_LIMITED:
            return "Request rate limited";
        
        case SmsManager.RESULT_RIL_INVALID_SMS_FORMAT:
            return "Invalid SMS format";
        
        case SmsManager.RESULT_RIL_SYSTEM_ERR:
            return "System error";
        
        case SmsManager.RESULT_RIL_ENCODING_ERR:
            return "Encoding error";
        
        case SmsManager.RESULT_RIL_INVALID_SMSC_ADDRESS:
            return "Invalid SMSC address";
        
        case SmsManager.RESULT_RIL_MODEM_ERR:
            return "Modem error";
        
        case SmsManager.RESULT_RIL_NETWORK_ERR:
            return "Network error";
        
        case SmsManager.RESULT_RIL_INTERNAL_ERR:
            return "Internal error";
        
        case SmsManager.RESULT_RIL_REQUEST_NOT_SUPPORTED:
            return "Request not supported";
        
        case SmsManager.RESULT_RIL_INVALID_MODEM_STATE:
            return "Invalid modem state";
        
        case SmsManager.RESULT_RIL_NETWORK_NOT_READY:
            return "Network not ready";
        
        case SmsManager.RESULT_RIL_OPERATION_NOT_ALLOWED:
            return "Operation not allowed";
        
        case SmsManager.RESULT_RIL_NO_RESOURCES:
            return "No resources";
        
        case SmsManager.RESULT_RIL_CANCELLED:
            return "Cancelled";
        
        case SmsManager.RESULT_RIL_SIM_ABSENT:
            return "SIM absent - Insert SIM card";
        
        default:
            return "Unknown error code: " + resultCode;
    }
}

/**
 * Fixed: Additional SmsManager result codes (API 30+)
 */
private static final int RESULT_RIL_RADIO_NOT_AVAILABLE = 9;
private static final int RESULT_RIL_SMS_SEND_FAIL_RETRY = 10;
private static final int RESULT_RIL_NETWORK_REJECT = 11;
private static final int RESULT_RIL_INVALID_STATE = 12;
private static final int RESULT_RIL_INVALID_ARGUMENTS = 13;
private static final int RESULT_RIL_NO_MEMORY = 14;
private static final int RESULT_RIL_REQUEST_RATE_LIMITED = 15;
private static final int RESULT_RIL_INVALID_SMS_FORMAT = 16;
private static final int RESULT_RIL_SYSTEM_ERR = 17;
private static final int RESULT_RIL_ENCODING_ERR = 18;
private static final int RESULT_RIL_INVALID_SMSC_ADDRESS = 19;
private static final int RESULT_RIL_MODEM_ERR = 20;
private static final int RESULT_RIL_NETWORK_ERR = 21;
private static final int RESULT_RIL_INTERNAL_ERR = 22;
private static final int RESULT_RIL_REQUEST_NOT_SUPPORTED = 23;
private static final int RESULT_RIL_INVALID_MODEM_STATE = 24;
private static final int RESULT_RIL_NETWORK_NOT_READY = 25;
private static final int RESULT_RIL_OPERATION_NOT_ALLOWED = 26;
private static final int RESULT_RIL_NO_RESOURCES = 27;
private static final int RESULT_RIL_CANCELLED = 28;
private static final int RESULT_RIL_SIM_ABSENT = 29;
}
