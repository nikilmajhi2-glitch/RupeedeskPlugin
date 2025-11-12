package com.rupeedesk;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import com.google.firebase.firestore.FirebaseFirestore;
import com.rupeedesk.smsaautosender.SmsService;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class MainActivity extends AppCompatActivity {

    private static final int PERMISSION_REQUEST_CODE = 101;
    private static final String PREFS_NAME = "AppPrefs";
    private static final String KEY_USER_ID = "userId";
    private static final String KEY_SERVICE_RUNNING = "isServiceRunning";

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

        // Load saved user ID
        String savedUserId = prefs.getString(KEY_USER_ID, null);
        if (savedUserId != null) {
            userIdInput.setText(savedUserId);
        }

        startServiceBtn.setOnClickListener(v -> handleStartService());

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

        // 1. Verify User ID exists in Firestore
        db.collection("users").document(input).get()
                .addOnSuccessListener(snapshot -> {
                    if (snapshot.exists()) {
                        // 2. Save User ID
                        prefs.edit().putString(KEY_USER_ID, input).apply();
                        Toast.makeText(this, "✅ User ID Bound: " + input, Toast.LENGTH_SHORT).show();
                        
                        // 3. Start the service
                        startSmsService();
                    } else {
                        Toast.makeText(this, "❌ User ID not found in database", Toast.LENGTH_SHORT).show();
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
    
    private void startSmsService() {
        // Schedule the periodic worker
        PeriodicWorkRequest smsWork = new PeriodicWorkRequest.Builder(
                com.rupeedesk.smsaautosender.AutoSmsWorker.class, 15, TimeUnit.MINUTES)
                .addTag(SmsService.WORK_TAG)
                .build();
        
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                SmsService.WORK_TAG, 
                ExistingPeriodicWorkPolicy.KEEP, // Keep the old one if it exists
                smsWork
        );

        // Start the foreground service to keep the app alive
        Intent serviceIntent = new Intent(this, SmsService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }

        prefs.edit().putBoolean(KEY_SERVICE_RUNNING, true).apply();
        updateUI();
    }

    // --- UI and Permission Methods ---

    private void updateUI() {
        boolean isRunning = prefs.getBoolean(KEY_SERVICE_RUNNING, false);
        if (isRunning) {
            statusText.setText("Service is RUNNING");
            statusText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark));
            startServiceBtn.setEnabled(false); // Disable button
            startServiceBtn.setText("Service Started");
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
        // updateUI will fix the status text and button
        updateUI(); 
    }

    private void checkPermissions() {
        String[] requiredPermissions = {
                Manifest.permission.SEND_SMS,
                Manifest.permission.READ_PHONE_STATE,
                Manifest.permission.RECEIVE_BOOT_COMPLETED,
                Manifest.permission.POST_NOTIFICATIONS // Required for Android 13+
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
            // Check if all permissions were granted
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


