package com.rupeedesk.smsaautosender;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.telephony.SmsManager;
import android.util.Log;

public class SmsUtils {

    public static final String SMS_SENT_ACTION = "com.rupeedesk.smsaautosender.SMS_SENT";

    public static void sendSms(Context context, String phoneNumber, String message, PendingIntent sentPI) {
        try {
            SmsManager smsManager = SmsManager.getDefault();
            smsManager.sendTextMessage(phoneNumber, null, message, sentPI, null);
            Log.d("SmsUtils", "SMS send initiated to " + phoneNumber);
        } catch (Exception e) {
            Log.e("SmsUtils", "Failed to send SMS: " + e.getMessage());
        }
    }
}