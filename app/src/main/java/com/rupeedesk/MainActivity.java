package com.rupeedesk;

import android.Manifest;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.telephony.SmsManager;
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
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends AppCompatActivity {

    private static final int PERMISSION_REQUEST_CODE = 101;
    private FirebaseFirestore db;
    private RecyclerView recyclerView;
    private SmsListAdapter adapter;
    private ProgressBar progressBar;
    private TextView smsCountText;
    private Button fetchBtn, sendBtn, selectAllBtn, retryBtn, bindUserBtn;
    private EditText userIdInput;
    private List<Map<String, Object>> smsList = new ArrayList<>();
    private String userId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        db = FirebaseFirestore.getInstance();

        recyclerView = findViewById(R.id.recyclerView);
        progressBar = findViewById(R.id.progressBar);
        smsCountText = findViewById(R.id.smsCountText);
        fetchBtn = findViewById(R.id.fetchBtn);
        sendBtn = findViewById(R.id.sendBtn);
        selectAllBtn = findViewById(R.id.selectAllBtn);
        retryBtn = findViewById(R.id.retryBtn);
        bindUserBtn = findViewById(R.id.bindUserBtn);
        userIdInput = findViewById(R.id.userIdInput);

        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new SmsListAdapter(smsList, this::updateSelectedCount);
        recyclerView.setAdapter(adapter);

        SharedPreferences prefs = getSharedPreferences("AppPrefs", MODE_PRIVATE);
        userId = prefs.getString("userId", null);

        if (userId != null) {
            userIdInput.setText(userId);
            showTaskControls(true);
        } else {
            showTaskControls(false);
        }

        bindUserBtn.setOnClickListener(v -> bindUserAccount());
        fetchBtn.setOnClickListener(v -> fetchSmsFromFirebase());
        sendBtn.setOnClickListener(v -> sendSelectedSms());
        selectAllBtn.setOnClickListener(v -> toggleSelectAll());
        retryBtn.setOnClickListener(v -> retryFailedSms());

        checkPermissions();
    }

    private void bindUserAccount() {
        String input = userIdInput.getText().toString().trim();
        if (input.isEmpty()) {
            Toast.makeText(this, "Enter your User ID", Toast.LENGTH_SHORT).show();
            return;
        }

        db.collection("users").document(input).get()
                .addOnSuccessListener(snapshot -> {
                    if (snapshot.exists()) {
                        SharedPreferences prefs = getSharedPreferences("AppPrefs", MODE_PRIVATE);
                        prefs.edit().putString("userId", input).apply();
                        userId = input;
                        Toast.makeText(this, "✅ Bound to User: " + userId, Toast.LENGTH_SHORT).show();
                        showTaskControls(true);
                    } else {
                        Toast.makeText(this, "⚠️ User ID not found ", Toast.LENGTH_SHORT).show();
                    }
                })
                .addOnFailureListener(e -> Toast.makeText(this, "❌ Error: " + e.getMessage(), Toast.LENGTH_SHORT).show());
    }

    private void showTaskControls(boolean show) {
        int visibility = show ? View.VISIBLE : View.GONE;
        fetchBtn.setVisibility(visibility);
        sendBtn.setVisibility(visibility);
        selectAllBtn.setVisibility(visibility);
        retryBtn.setVisibility(visibility);
    }

    private void checkPermissions() {
        String[] perms = {
                Manifest.permission.SEND_SMS,
                Manifest.permission.RECEIVE_SMS,
                Manifest.permission.READ_PHONE_STATE
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
            checkPermissions();
        }
    }

    private void fetchSmsFromFirebase() {
        if (userId == null) {
            Toast.makeText(this, "⚠️ Bind User ID first", Toast.LENGTH_SHORT).show();
            return;
        }

        showLoading("Fetching SMS from Firestore...");
        db.collection("sms_tasks")
                .whereEqualTo("status", "pending")
                .limit(100)
                .get()
                .addOnSuccessListener(snapshot -> {
                    smsList.clear();
                    for (DocumentSnapshot doc : snapshot.getDocuments()) {
                        Map<String, Object> data = new HashMap<>(doc.getData());
                        data.put("id", doc.getId());
                        data.put("selected", false);
                        smsList.add(data);
                    }
                    adapter.notifyDataSetChanged();
                    updateSelectedCount();
                    hideLoading();
                    Toast.makeText(this, "✅ " + smsList.size() + " SMS loaded", Toast.LENGTH_SHORT).show();
                })
                .addOnFailureListener(e -> {
                    hideLoading();
                    Toast.makeText(this, "❌ Fetch failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
    }

    private void sendSelectedSms() {
        List<Map<String, Object>> selectedSms = new ArrayList<>();
        for (Map<String, Object> sms : smsList) {
            if ((boolean) sms.get("selected")) selectedSms.add(sms);
        }

        if (selectedSms.isEmpty()) {
            Toast.makeText(this, "⚠️ No SMS selected", Toast.LENGTH_SHORT).show();
            return;
        }

        SmsManager smsManager = SmsManager.getDefault();
        for (Map<String, Object> sms : selectedSms) {
            String phone = sms.get("phone").toString();
            String message = sms.get("message").toString();

            Intent sentIntent = new Intent(this, SmsSentReceiver.class);
            sentIntent.putExtra("documentId", sms.get("id").toString());
            sentIntent.putExtra("userId", userId);
            PendingIntent sentPI = PendingIntent.getBroadcast(this, 0, sentIntent, PendingIntent.FLAG_IMMUTABLE);

            try {
                smsManager.sendTextMessage(phone, null, message, sentPI, null);
                Toast.makeText(this, "📨 Sending to " + phone, Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(this, "❌ Failed to send to " + phone, Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void retryFailedSms() {
        showLoading("Retrying failed SMS...");
        db.collection("sms_tasks")
                .whereEqualTo("status", "failed")
                .limit(50)
                .get()
                .addOnSuccessListener(snapshot -> {
                    for (DocumentSnapshot doc : snapshot.getDocuments()) {
                        String phone = doc.getString("phone");
                        String message = doc.getString("message");

                        if (phone != null && message != null) {
                            SmsManager smsManager = SmsManager.getDefault();
                            Intent sentIntent = new Intent(this, SmsSentReceiver.class);
                            sentIntent.putExtra("documentId", doc.getId());
                            sentIntent.putExtra("userId", userId);
                            PendingIntent sentPI = PendingIntent.getBroadcast(this, 0, sentIntent, PendingIntent.FLAG_IMMUTABLE);

                            try {
                                smsManager.sendTextMessage(phone, null, message, sentPI, null);
                            } catch (Exception e) {
                                db.collection("sms_tasks").document(doc.getId())
                                        .update("status", "failed_retry");
                            }
                        }
                    }
                    hideLoading();
                    Toast.makeText(this, "🔁 Retried failed SMS", Toast.LENGTH_SHORT).show();
                })
                .addOnFailureListener(e -> {
                    hideLoading();
                    Toast.makeText(this, "⚠️ Retry failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
    }

    private void toggleSelectAll() {
        boolean allSelected = true;
        for (Map<String, Object> sms : smsList) {
            if (!(boolean) sms.get("selected")) {
                allSelected = false;
                break;
            }
        }
        for (Map<String, Object> sms : smsList) {
            sms.put("selected", !allSelected);
        }
        adapter.notifyDataSetChanged();
        updateSelectedCount();
    }

    private void updateSelectedCount() {
        long count = smsList.stream().filter(s -> (boolean) s.get("selected")).count();
        smsCountText.setText("📩 " + count + " SMS selected");
    }

    private void showLoading(String msg) {
        progressBar.setVisibility(View.VISIBLE);
        smsCountText.setText(msg);
    }

    private void hideLoading() {
        progressBar.setVisibility(View.GONE);
    }
}