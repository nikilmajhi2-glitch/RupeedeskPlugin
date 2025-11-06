package com.rupeedesk.smsaautosender;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;

public class MainActivity extends AppCompatActivity {
    private static final int PERMISSION_REQUEST_CODE = 1;

    private EditText userIdInput;
    private Button startButton;

    private FirebaseFirestore db;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        db = FirebaseFirestore.getInstance();

        userIdInput = findViewById(R.id.userIdInput);
        startButton = findViewById(R.id.startButton);

        startButton.setOnClickListener(v -> {
            String userId = userIdInput.getText().toString().trim();
            if (userId.isEmpty()) {
                Toast.makeText(this, "Please enter User ID", Toast.LENGTH_SHORT).show();
                return;
            }

            // Verify user ID from Firestore
            verifyUserIdAndStartService(userId);
        });
    }

    private void verifyUserIdAndStartService(String userId) {
        db.collection("users").document(userId).get()
                .addOnSuccessListener(documentSnapshot -> {
                    if (documentSnapshot.exists()) {
                        // User ID exists: save locally and start service
                        saveUserIdAndStartService(userId);
                    } else {
                        Toast.makeText(MainActivity.this, "User ID not found", Toast.LENGTH_SHORT).show();
                    }
                })
                .addOnFailureListener(e -> {
                    Toast.makeText(MainActivity.this, "Error verifying User ID", Toast.LENGTH_SHORT).show();
                });
    }

    private void saveUserIdAndStartService(String userId) {
        SharedPreferences prefs = getSharedPreferences("AppPrefs", MODE_PRIVATE);
        prefs.edit().putString("userId", userId).apply();

        if (!hasAllPermissions()) {
            requestPermissions();
        } else {
            startSmsService();
        }
    }

    private boolean hasAllPermissions() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestPermissions() {
        ActivityCompat.requestPermissions(this, new String[]{
                Manifest.permission.SEND_SMS,
                Manifest.permission.RECEIVE_SMS,
                Manifest.permission.READ_PHONE_STATE
        }, PERMISSION_REQUEST_CODE);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (hasAllPermissions()) {
                startSmsService();
            } else {
                Toast.makeText(this, "Permission denied", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void startSmsService() {
        Intent serviceIntent = new Intent(this, SmsService.class);
        ContextCompat.startForegroundService(this, serviceIntent);
        Toast.makeText(this, "SMS Service Started", Toast.LENGTH_SHORT).show();
    }
}