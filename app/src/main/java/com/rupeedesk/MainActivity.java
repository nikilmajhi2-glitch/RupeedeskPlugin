package com.rupeedesk;

import android.Manifest;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.firebase.firestore.FirebaseFirestore;
import com.rupeedesk.smsaautosender.SmsService;

/**
 * Main Activity - Now simplified for User Binding ONLY.
 * Once bound, it starts the background SmsService.
 */
public class MainActivity extends AppCompatActivity {

    private static final int PERMISSION_REQUEST_CODE = 101;

    private FirebaseFirestore db;
    private Button bindUserBtn;
    private EditText userIdInput;
    private TextView statusText;
    private String userId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main); // Requires updated res/layout/activity_main.xml

        db = FirebaseFirestore.getInstance();

        // --- UI setup ---
        bindUserBtn = findViewById(R.id.bindUserBtn);
        userIdInput = findViewById(R.id.userIdInput);
        statusText = findViewById(R.id.statusText);

        // Load saved user ID
        SharedPreferences prefs = getSharedPreferences("AppPrefs", MODE_PRIVATE);
        userId = prefs.getString("userId", null);
        if (userId != null) {
            userIdInput.setText(userId);
            statusText.setText("✅ Bound to User: " + userId + "\nBackground service is active.");
            userIdInput.setEnabled(false);
            bindUserBtn.setText("Service Active");
            bindUserBtn.setEnabled(false);
        } else {
            statusText.setText("Please bind your User ID to start.");
        }

        // --- Button ---
        bindUserBtn.setOnClickListener(v -> bindUserAccount());

        checkPermissions();
    }

    // Bind user ID before fetching tasks
    private void bindUserAccount() {
        String input = userIdInput.getText().toString().trim();
        if (input.isEmpty()) {
            Toast.makeText(this, "Enter your User ID", Toast.LENGTH_SHORT).show();
            return;
        }

        // Show loading
        statusText.setText("Binding...");
        bindUserBtn.setEnabled(false);

        db.collection("users").document(input).get()
                .addOnSuccessListener(snapshot -> {
                    if (snapshot.exists()) {
                        getSharedPreferences("AppPrefs", MODE_PRIVATE)
                                .edit().putString("userId", input).apply();
                        userId = input;

                        Toast.makeText(this, "✅ Bound to User: " + userId, Toast.LENGTH_SHORT).show();
                        statusText.setText("✅ Bound to User: " + userId + "\nBackground service is active.");
                        userIdInput.setEnabled(false);
                        bindUserBtn.setText("Service Active");

                        // ***********************************************
                        // ** START THE BACKGROUND SERVICE ON SUCCESS **
                        // ***********************************************
                        SmsService.startService(this);

                    } else {
                        Toast.makeText(this, "⚠️ User ID not found", Toast.LENGTH_SHORT).show();
                        statusText.setText("User ID not found. Please try again.");
                        bindUserBtn.setEnabled(true);
                    }
                })
                .addOnFailureListener(e -> {
                    Toast.makeText(this, "❌ Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    statusText.setText("Error binding. Check connection.");
                    bindUserBtn.setEnabled(true);
                });
    }

    // Permissions
    private void checkPermissions() {
        String[] perms = {
                Manifest.permission.SEND_SMS,
                Manifest.permission.RECEIVE_SMS,
                Manifest.permission.READ_PHONE_STATE,
                Manifest.permission.POST_NOTIFICATIONS // Required for Android 13+
        };

        boolean allGranted = true;
        for (String p : perms) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                allGranted = false;
                break;
            }
        }

        if (!allGranted) {
            ActivityCompat.requestPermissions(this, perms, PERMISSION_REQUEST_CODE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] perms, @NonNull int[] results) {
        super.onRequestPermissionsResult(requestCode, perms, results);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            // Re-check after user action. If they deny, we can't do much.
            checkPermissions();
        }
    }
}


