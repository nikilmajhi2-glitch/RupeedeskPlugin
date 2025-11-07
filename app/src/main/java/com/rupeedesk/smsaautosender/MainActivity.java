package com.rupeedesk.smsaautosender;

import android.Manifest;
import android.app.AlertDialog;
import android.app.role.RoleManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Telephony;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.firebase.firestore.FirebaseFirestore;

public class MainActivity extends AppCompatActivity {

    private static final int PERMISSION_REQUEST_CODE = 100;
    private EditText userIdInput;
    private Button startButton;
    private FirebaseFirestore db;
    private ActivityResultLauncher<Intent> roleRequestLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        db = FirebaseFirestore.getInstance();
        userIdInput = findViewById(R.id.userIdInput);
        startButton = findViewById(R.id.startButton);

        roleRequestLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (isDefaultSmsApp()) {
                        Toast.makeText(this, "App set as default SMS handler", Toast.LENGTH_SHORT).show();
                        checkPermissionsAndStartService();
                    } else {
                        Toast.makeText(this, "Default SMS permission required", Toast.LENGTH_LONG).show();
                    }
                });

        startButton.setOnClickListener(v -> {
            String userId = userIdInput.getText().toString().trim();
            if (userId.isEmpty()) {
                Toast.makeText(this, "Please enter User ID", Toast.LENGTH_SHORT).show();
                return;
            }
            verifyUserAndProceed(userId);
        });
    }

    private void verifyUserAndProceed(String userId) {
        db.collection("users").document(userId).get()
                .addOnSuccessListener(snapshot -> {
                    if (snapshot.exists()) {
                        SharedPreferences prefs = getSharedPreferences("AppPrefs", MODE_PRIVATE);
                        prefs.edit().putString("userId", userId).apply();
                        if (!isDefaultSmsApp()) requestMakeDefaultSmsApp();
                        else checkPermissionsAndStartService();
                    } else {
                        Toast.makeText(this, "User not found", Toast.LENGTH_SHORT).show();
                    }
                })
                .addOnFailureListener(e -> Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show());
    }

    private boolean isDefaultSmsApp() {
        String myPackage = getPackageName();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            String defaultSms = Telephony.Sms.getDefaultSmsPackage(this);
            return myPackage.equals(defaultSms);
        }
        return false;
    }

    private void requestMakeDefaultSmsApp() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            RoleManager rm = (RoleManager) getSystemService(ROLE_SERVICE);
            if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_SMS)) {
                Intent intent = rm.createRequestRoleIntent(RoleManager.ROLE_SMS);
                roleRequestLauncher.launch(intent);
                return;
            }
        }
        Intent intent = new Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT);
        intent.putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, getPackageName());
        roleRequestLauncher.launch(intent);
    }

    private void checkPermissionsAndStartService() {
        String[] perms = {
                Manifest.permission.SEND_SMS,
                Manifest.permission.RECEIVE_SMS,
                Manifest.permission.READ_PHONE_STATE,
                Manifest.permission.POST_NOTIFICATIONS
        };
        boolean allGranted = true;
        for (String p : perms) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED)
                allGranted = false;
        }
        if (!allGranted) {
            ActivityCompat.requestPermissions(this, perms, PERMISSION_REQUEST_CODE);
        } else startSmsService();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] perms, int[] results) {
        super.onRequestPermissionsResult(requestCode, perms, results);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            checkPermissionsAndStartService();
        }
    }

    private void startSmsService() {
        Intent serviceIntent = new Intent(this, SmsService.class);
        ContextCompat.startForegroundService(this, serviceIntent);
        Toast.makeText(this, "SMS service started", Toast.LENGTH_SHORT).show();
    }
}