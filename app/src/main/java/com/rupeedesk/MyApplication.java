package com.rupeedesk;

import android.app.Application;
import android.util.Log;
import com.google.firebase.FirebaseApp;

public class MyApplication extends Application {
    private static final String TAG = "MyApplication";

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "🚀 Application starting...");
        
        try {
            FirebaseApp.initializeApp(this);
            Log.d(TAG, "✅ Firebase initialized");
        } catch (Exception e) {
            Log.e(TAG, "⚠️ Firebase init failed: " + e.getMessage(), e);
        }
        
        Log.d(TAG, "✅ Application started");
    }
}