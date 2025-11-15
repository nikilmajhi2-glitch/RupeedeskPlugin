package com.rupeedesk;

import android.util.Log;

import androidx.multidex.MultiDexApplication;

import com.google.firebase.FirebaseApp;

/**
 * Application class - Required for MultiDex support on Android 4.x - 6.x
 * This prevents crashes when app exceeds 64k method limit
 */
public class MyApplication extends MultiDexApplication {
    
    private static final String TAG = "MyApplication";

    @Override
    public void onCreate() {
        super.onCreate();
        
        Log.d(TAG, "🚀 Application starting...");
        
        // Initialize Firebase
        try {
            FirebaseApp.initializeApp(this);
            Log.d(TAG, "✅ Firebase initialized successfully");
        } catch (Exception e) {
            Log.e(TAG, "⚠️ Firebase initialization failed: " + e.getMessage(), e);
            // App can still run without Firebase (for testing)
        }
        
        Log.d(TAG, "✅ Application started successfully");
    }
}