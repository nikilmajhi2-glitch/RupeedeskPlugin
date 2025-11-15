package com.rupeedesk;
import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
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
        boolean isRunning = prefs.getBoolean(KEY_SERVICE_RUNNING, false);
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
    // Re-check UI state when activity resumes
    updateUI();
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
    
    Intent serviceIntent = new Intent(this, SmsService.class);
    stopService(serviceIntent);

    prefs.edit().putBoolean(KEY_SERVICE_RUNNING, false).apply();
    
    hideLoading();
    
    Toast.makeText(this, "✅ Service Stopped", Toast.LENGTH_SHORT).show();
    Log.d(TAG, "Service stopped by user");
}

private void startSmsService() {
    try {
        SmsService.startService(this); 
        
        prefs.edit().putBoolean(KEY_SERVICE_RUNNING, true).apply();
        hideLoading();
        
        Toast.makeText(this, "✅ Service Started Successfully", Toast.LENGTH_SHORT).show();
        Log.d(TAG, "SmsService started successfully");
    } catch (Exception e) {
        Log.e(TAG, "Failed to start service: ", e);
        Toast.makeText(this, "❌ Failed to start service: " + e.getMessage(), Toast.LENGTH_LONG).show();
        prefs.edit().putBoolean(KEY_SERVICE_RUNNING, false).apply();
        hideLoading();
    }
}

private void updateUI() {
    boolean isRunning = prefs.getBoolean(KEY_SERVICE_RUNNING, false);
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
 * Fixed: Comprehensive permission check including runtime permissions
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
    
    // Notifications (Android 13+)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        requiredPermissions.add(Manifest.permission.POST_NOTIFICATIONS);
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
        Log.d(TAG, "All permissions granted");
    }
}

/**
 * Fixed: Check if all required permissions are granted
 */
private boolean hasAllPermissions() {
    String[] requiredPermissions = {
        Manifest.permission.SEND_SMS,
        Manifest.permission.READ_SMS,
        Manifest.permission.RECEIVE_SMS,
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.ACCESS_NETWORK_STATE,
        Manifest.permission.RECEIVE_BOOT_COMPLETED
    };

    for (String perm : requiredPermissions) {
        if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Permission not granted: " + perm);
            return false;
        }
    }

    // Android 13+ notification permission
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) 
            != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "POST_NOTIFICATIONS permission not granted");
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