package com.rupeedesk;

import android.Manifest;
import android.content.Context;
// import android.content.DialogInterface; // Hata diya
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
// import android.telephony.SubscriptionInfo; // Hata diya
// import android.telephony.SubscriptionManager; // Hata diya
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
// import androidx.appcompat.app.AlertDialog; // Hata diya
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.firebase.firestore.FirebaseFirestore;
import com.rupeedesk.smsaautosender.SmsService;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {
    private static final int PERMISSION_REQUEST_CODE = 101;
    private static final String PREFS_NAME = "AppPrefs";
    private static final String KEY_USER_ID = "userId";
    private static final String KEY_SERVICE_RUNNING = "isServiceRunning";
    // private static final String KEY_SUBSCRIPTION_ID = "subscriptionId"; // Hata diya

    private FirebaseFirestore db;
    private EditText userIdInput;
    private Button startServiceBtn;
    private ProgressBar progressBar;
    private TextView statusText;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main); 
        db = FirebaseFirestore.getInstance();
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        // UI setup
        userIdInput = findViewById(R.id.userIdInput);
        startServiceBtn = findViewById(R.id.startServiceBtn);
        progressBar = findViewById(R.id.progressBar);
        statusText = findViewById(R.id.statusText);

        String savedUserId = prefs.getString(KEY_USER_ID, null);
        if (savedUserId != null) {
            userIdInput.setText(savedUserId);
        }

        startServiceBtn.setOnClickListener(v -> {
            boolean isRunning = prefs.getBoolean(KEY_SERVICE_RUNNING, false);
            if (isRunning) {
                handleStopService();
            } else {
                handleStartService();
            }
        });
        
        checkPermissions();
        updateUI();
    }

    private void handleStartService() {
        String input = userIdInput.getText().toString().trim();
        if (input.isEmpty()) {
            Toast.makeText(this, "Please enter your User ID", Toast.LENGTH_SHORT).show();
            return;
        }
        showLoading("Verifying User ID...");

        db.collection("users").document(input).get()
                .addOnSuccessListener(snapshot -> {
                    if (snapshot.exists()) {
                        prefs.edit().putString(KEY_USER_ID, input).apply();
                        Toast.makeText(this, "✅ User ID Bound: " + input, Toast.LENGTH_SHORT).show();
                        
                        // --- BADLAV YAHAN HAI ---
                        // Ab hum seedha service start karenge
                        startSmsService(); 
                        // checkSimsAndStart() ko hata diya gaya hai
                    } else {
                        Toast.makeText(this, "❌ User ID not found", Toast.LENGTH_SHORT).show();
                        hideLoading();
                        updateUI();
                    }
                })
                .addOnFailureListener(e -> {
                    Toast.makeText(this, "🔥 Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    hideLoading();
                    updateUI();
                });
    }

    private void handleStopService() {
        showLoading("Stopping service...");
        
        Intent serviceIntent = new Intent(this, SmsService.class);
        stopService(serviceIntent);

        prefs.edit().putBoolean(KEY_SERVICE_RUNNING, false).apply();
        
        hideLoading();
        updateUI();
        
        Toast.makeText(this, "Service Stopped", Toast.LENGTH_SHORT).show();
    }

    // --- checkSimsAndStart() METHOD POORI TARAH HATA DIYA GAYA HAI ---

    private void startSmsService() {
        SmsService.startService(this); 
        
        prefs.edit().putBoolean(KEY_SERVICE_RUNNING, true).apply();
        hideLoading();
        updateUI();
    }
    
    private void updateUI() {
        boolean isRunning = prefs.getBoolean(KEY_SERVICE_RUNNING, false);
        if (isRunning) {
            statusText.setText("Service is RUNNING");
            statusText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark));
            startServiceBtn.setEnabled(true);
            startServiceBtn.setText("Stop Service");
            userIdInput.setEnabled(false);
        } else {
            statusText.setText("Service is STOPPED");
            statusText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark));
            startServiceBtn.setEnabled(true);
            startServiceBtn.setText("Bind & Start Service");
            userIdInput.setEnabled(true);
        }
    }

    private void showLoading(String msg) {
        progressBar.setVisibility(View.VISIBLE);
        statusText.setText(msg);
        startServiceBtn.setEnabled(false);
    }

    private void hideLoading() {
        progressBar.setVisibility(View.GONE);
        updateUI();
    }

    private void checkPermissions() {
        // READ_PHONE_STATE ab bhi zaroori hai (kuch phones mein SMSManager ke liye)
        String[] requiredPermissions = {
                Manifest.permission.SEND_SMS,
                Manifest.permission.READ_PHONE_STATE,
                Manifest.permission.RECEIVE_BOOT_COMPLETED,
                Manifest.permission.POST_NOTIFICATIONS,
                Manifest.permission.ACCESS_NETWORK_STATE
        };
        List<String> permissionsToRequest = new ArrayList<>();
        for (String perm : requiredPermissions) {
            if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(perm);
            }
        }
        if (!permissionsToRequest.isEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsToRequest.toArray(new String[0]), PERMISSION_REQUEST_CODE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (!allGranted) {
                Toast.makeText(this, "All permissions are required to run the service.", Toast.LENGTH_LONG).show();
            }
        }
    }
}
