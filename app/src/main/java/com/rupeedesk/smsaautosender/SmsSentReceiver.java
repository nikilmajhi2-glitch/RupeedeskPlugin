package com.rupeedesk.smsaautosender;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.app.Activity;
import android.telephony.SmsManager;

public class SmsSentReceiver extends BroadcastReceiver {

    private static final String TAG = "SmsSentReceiver";

    @SuppressLint("MissingPermission")
    @Override
    public void onReceive(Context context, Intent intent) {
        String documentId = intent.getStringExtra("documentId");

        int resultCode = getResultCode();
        switch (resultCode) {
            case Activity.RESULT_OK:
                Log.d(TAG, "✅ SMS sent successfully for documentId: " + documentId);
                FirebaseManager.deleteMessageById(documentId, context.getApplicationContext());
                break;

            case SmsManager.RESULT_ERROR_GENERIC_FAILURE:
                Log.e(TAG, "❌ SMS generic failure for documentId: " + documentId);
                break;

            case SmsManager.RESULT_ERROR_NO_SERVICE:
                Log.e(TAG, "❌ No network service when sending SMS");
                break;

            case SmsManager.RESULT_ERROR_NULL_PDU:
                Log.e(TAG, "❌ Null PDU error");
                break;

            case SmsManager.RESULT_ERROR_RADIO_OFF:
                Log.e(TAG, "❌ Radio off while sending SMS");
                break;

            default:
                Log.w(TAG, "⚠️ Unknown SMS send result: " + resultCode);
                break;
        }
    }
}