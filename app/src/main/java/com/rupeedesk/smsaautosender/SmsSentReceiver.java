package com.rupeedesk.smsaautosender;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class SmsSentReceiver extends BroadcastReceiver {

    private static final String TAG = "SmsSentReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        String documentId = intent.getStringExtra("documentId");

        switch (getResultCode()) {
            case android.app.Activity.RESULT_OK:
                Log.d(TAG, "SMS sent successfully for documentId: " + documentId);
                // Pass context to use correct user ID
                FirebaseManager.deleteMessageById(documentId, context);
                break;
            case android.telephony.SmsManager.RESULT_ERROR_GENERIC_FAILURE:
            case android.telephony.SmsManager.RESULT_ERROR_NO_SERVICE:
            case android.telephony.SmsManager.RESULT_ERROR_NULL_PDU:
            case android.telephony.SmsManager.RESULT_ERROR_RADIO_OFF:
                Log.e(TAG, "SMS send failed for documentId: " + documentId);
                // Optional: retry or log failure
                break;
        }
    }
}