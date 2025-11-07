package com.rupeedesk.smsaautosender.sms;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Telephony;
import android.telephony.SmsMessage;
import android.util.Log;

public class SmsBroadcastReceiver extends BroadcastReceiver {
    private static final String TAG = "SmsBroadcastReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (Telephony.Sms.Intents.SMS_RECEIVED_ACTION.equals(intent.getAction())) {
            for (SmsMessage msg : Telephony.Sms.Intents.getMessagesFromIntent(intent)) {
                String from = msg.getDisplayOriginatingAddress();
                String body = msg.getMessageBody();
                Log.d(TAG, "📩 Incoming SMS from " + from + ": " + body);
                // You can forward to Firebase, save locally, or trigger logic here.
            }
        }
    }
}