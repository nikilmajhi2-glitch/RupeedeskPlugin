package com.rupeedesk;

import android.Manifest;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
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

import com.google.firebase.firestore.FirebaseFirestore;
import com.rupeedesk.smsaautosender.SmsService;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
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

        String savedUserId = prefs.getString(KEY_USER_ID, null);
        if (savedUserId != null) {
            userIdInput.setText(savedUserId);
        }

        startServiceBtn.setOnClickListener(v -> {
            // Check actual service state, not just SharedPreferences
            boolean isRunning = isServiceActuallyRunning(SmsService.class);
            if (isRunning) {
                handleStopService();
            } else {
                handleStartService();
            }
        });
        
        checkAndRequestPermissions();
        updateUI();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Sync SharedPreferences with actual service state
        syncServiceState();
        updateUI();
    }

    /**
     * Fixed: Sync SharedPreferences with actual service running state
     */
    private void syncServiceState() {
        boolean actuallyRunning = isServiceActuallyRunning(SmsService.class);
        boolean prefState = prefs.getBoolean(KEY_SERVICE_RUNNING, false);
        
        if (actuallyRunning != prefState) {
            Log.d(TAG, "Service state mismatch detected. Syncing... (Actual: " + actuallyRunning + ", Pref: " + prefState + ")");
            prefs.edit().putBoolean(KEY_SERVICE_RUNNING, actuallyRunning).apply();
        }
    }

    /**
     * Fixed: Actually check if the service is running in system
     */
    private boolean isServiceActuallyRunning(Class<?> serviceClass) {
        ActivityManager manager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        if (manager == null) return false;
        
        try {
            for (ActivityManager.RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
                if (serviceClass.getName().equals(service.service.getClassName())) {
                    Log.d(TAG, "Service " + serviceClass.getSimpleName() + " is running");
                    return true;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error checking service state: " + e.getMessage());
        }
        
        Log.d(TAG, "Service " + serviceClass.getSimpleName() + " is NOT running");
        return false;
    }

    private void handleStartService() {
        String input = userIdInput.getText().toString().trim();
        if (input.isEmpty()) {
            Toast.makeText(this, "Please enter your User ID", Toast.LENGTH_SHORT).show();
            return;
        }

        // Check permissions before starting
        if (!hasAllPermissions()) {
            Toast.makeText(this, "⚠️ Please grant all permissions first", Toast.LENGTH_LONG).show();
            checkAndRequestPermissions();
            return;
        }

        showLoading("Verifying User ID...");

        db.collection("users").document(input).get()
                .addOnSuccessListener(snapshot -> {
                    if (snapshot.exists()) {
                        prefs.edit().putString(KEY_USER_ID, input).apply();
                        Toast.makeText(this, "✅ User ID Bound: " + input, Toast.LENGTH_SHORT).show();
                        Log.d(TAG, "User ID verified: " + input);
                        
                        // Start service
                        startSmsService(); 
                    } else {
                        Toast.makeText(this, "❌ User ID not found in database", Toast.LENGTH_SHORT).show();
                        Log.e(TAG, "User ID not found: " + input);
                        hideLoading();
                    }
                })
                .addOnFailureListener(e -> {
                    Toast.makeText(this, "🔥 Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    Log.e(TAG, "Firestore error: ", e);
                    hideLoading();
                });
    }

    private void handleStopService() {
        showLoading("Stopping service...");
        
        try {
            Intent serviceIntent = new Intent(this, SmsService.class);
            boolean stopped = stopService(serviceIntent);
            
            if (stopped) {
                Log.d(TAG, "Service stop command sent successfully");
            } else {
                Log.w(TAG, "Service was not running or already stopped");
            }
            
            prefs.edit().putBoolean(KEY_SERVICE_RUNNING, false).apply();
            
            // Give service time to stop
            new android.os.Handler().postDelayed(() -> {
                hideLoading();
                boolean stillRunning = isServiceActuallyRunning(SmsService.class);
                if (stillRunning) {
                    Toast.makeText(this, "⚠️ Service may still be running", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "✅ Service Stopped", Toast.LENGTH_SHORT).show();
                }
            }, 1000);
            
        } catch (Exception e) {
            Log.e(TAG, "Error stopping service: ", e);
            Toast.makeText(this, "❌ Error stopping service: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            hideLoading();
        }
    }

    private void startSmsService() {
        try {
            SmsService.startService(this); 
            
            // Give service time to start
            new android.os.Handler().postDelayed(() -> {
                boolean actuallyRunning = isServiceActuallyRunning(SmsService.class);
                
                if (actuallyRunning) {
                    prefs.edit().putBoolean(KEY_SERVICE_RUNNING, true).apply();
                    Toast.makeText(this, "✅ Service Started Successfully", Toast.LENGTH_SHORT).show();
                    Log.d(TAG, "SmsService started and verified running");
                } else {
                    prefs.edit().putBoolean(KEY_SERVICE_RUNNING, false).apply();
                    Toast.makeText(this, "❌ Service failed to start", Toast.LENGTH_LONG).show();
                    Log.e(TAG, "Service start command sent but service not running");
                }
                
                hideLoading();
            }, 1500);
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to start service: ", e);
            Toast.makeText(this, "❌ Failed to start service: " + e.getMessage(), Toast.LENGTH_LONG).show();
            prefs.edit().putBoolean(KEY_SERVICE_RUNNING, false).apply();
            hideLoading();
        }
    }

    private void updateUI() {
        // Use actual service state, not just SharedPreferences
        boolean isRunning = isServiceActuallyRunning(SmsService.class);
        boolean hasPermissions = hasAllPermissions();
        
        if (isRunning) {
            statusText.setText("✅ Service is RUNNING");
            statusText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark));
            startServiceBtn.setEnabled(true);
            startServiceBtn.setText("Stop Service");
            userIdInput.setEnabled(false);
        } else {
            if (!hasPermissions) {
                statusText.setText("⚠️ Permissions Required");
                statusText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_orange_dark));
                startServiceBtn.setEnabled(false);
            } else {
                statusText.setText("⭕ Service is STOPPED");
                statusText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark));
                startServiceBtn.setEnabled(true);
            }
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

    /**
     * Fixed: Comprehensive permission check including all Android versions
     */
    private void checkAndRequestPermissions() {
        List<String> requiredPermissions = new ArrayList<>();
        
        // Core SMS permissions
        requiredPermissions.add(Manifest.permission.SEND_SMS);
        requiredPermissions.add(Manifest.permission.READ_SMS);
        requiredPermissions.add(Manifest.permission.RECEIVE_SMS);
        
        // Phone state (needed for dual-SIM)
        requiredPermissions.add(Manifest.permission.READ_PHONE_STATE);
        
        // Network
        requiredPermissions.add(Manifest.permission.ACCESS_NETWORK_STATE);
        
        // Boot receiver
        requiredPermissions.add(Manifest.permission.RECEIVE_BOOT_COMPLETED);
        
        // Foreground service (Android 9+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            requiredPermissions.add(Manifest.permission.FOREGROUND_SERVICE);
        }
        
        // Notifications (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requiredPermissions.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        
        // Foreground service type for Android 14+ (if using special use)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            requiredPermissions.add(Manifest.permission.FOREGROUND_SERVICE_SPECIAL_USE);
        }

        List<String> permissionsToRequest = new ArrayList<>();
        for (String perm : requiredPermissions) {
            if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(perm);
                Log.d(TAG, "Missing permission: " + perm);
            }
        }

        if (!permissionsToRequest.isEmpty()) {
            Log.d(TAG, "Requesting " + permissionsToRequest.size() + " permissions");
            ActivityCompat.requestPermissions(
                this, 
                permissionsToRequest.toArray(new String[0]), 
                PERMISSION_REQUEST_CODE
            );
        } else {
            Log.d(TAG, "✅ All permissions granted");
        }
    }

    /**
     * Fixed: Check if all required permissions are granted
     */
    private boolean hasAllPermissions() {
        List<String> requiredPermissions = new ArrayList<>();
        
        // Core permissions
        requiredPermissions.add(Manifest.permission.SEND_SMS);
        requiredPermissions.add(Manifest.permission.READ_SMS);
        requiredPermissions.add(Manifest.permission.RECEIVE_SMS);
        requiredPermissions.add(Manifest.permission.READ_PHONE_STATE);
        requiredPermissions.add(Manifest.permission.ACCESS_NETWORK_STATE);
        requiredPermissions.add(Manifest.permission.RECEIVE_BOOT_COMPLETED);
        
        // Android 9+ foreground service
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            requiredPermissions.add(Manifest.permission.FOREGROUND_SERVICE);
        }
        
        // Android 13+ notifications
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requiredPermissions.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        
        // Android 14+ foreground service type
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            requiredPermissions.add(Manifest.permission.FOREGROUND_SERVICE_SPECIAL_USE);
        }

        for (String perm : requiredPermissions) {
            if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "Permission not granted: " + perm);
                return false;
            }
        }

        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        
        if (requestCode == PERMISSION_REQUEST_CODE) {
            int grantedCount = 0;
            int deniedCount = 0;
            
            for (int i = 0; i < permissions.length; i++) {
                if (grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                    grantedCount++;
                    Log.d(TAG, "✅ Granted: " + permissions[i]);
                } else {
                    deniedCount++;
                    Log.w(TAG, "❌ Denied: " + permissions[i]);
                }
            }
            
            if (deniedCount == 0) {
                Toast.makeText(this, "✅ All permissions granted!", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, 
                    "⚠️ " + deniedCount + " permission(s) denied. App may not work correctly.", 
                    Toast.LENGTH_LONG).show();
            }
            
            updateUI();
        }
    }
}