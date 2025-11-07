package com.rupeedesk;

import android.Manifest;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.telephony.SmsManager;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.view.View;
import android.widget.Button;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class MainActivity extends AppCompatActivity {

    private ListView smsListView;
    private Button fetchButton, sendButton, retryButton, selectAllButton;
    private TextView selectedCountText, progressText;
    private View progressOverlay;
    private FloatingActionButton fabSendSelected;

    private FirebaseFirestore db;
    private List<Map<String, Object>> smsList = new ArrayList<>();
    private SmsListAdapter adapter;

    private static final int PERMISSION_REQUEST_CODE = 101;
    private List<SubscriptionInfo> simList = new ArrayList<>();
    private boolean allSelected = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        db = FirebaseFirestore.getInstance();

        smsListView = findViewById(R.id.smsListView);
        fetchButton = findViewById(R.id.fetchButton);
        sendButton = findViewById(R.id.sendButton);
        retryButton = findViewById(R.id.retryButton);
        selectAllButton = findViewById(R.id.selectAllButton);
        selectedCountText = findViewById(R.id.selectedCountText);
        progressOverlay = findViewById(R.id.progressOverlay);
        progressText = findViewById(R.id.progressText);
        fabSendSelected = findViewById(R.id.fabSendSelected);

        adapter = new SmsListAdapter(this, smsList, this::updateSelectedCount);
        smsListView.setAdapter(adapter);

        fetchButton.setOnClickListener(v -> fetchSmsFromFirebase());
        sendButton.setOnClickListener(v -> {
            if (checkPermission()) sendSelectedSms();
            else requestPermission();
        });
        retryButton.setOnClickListener(v -> retryFailedSms());
        selectAllButton.setOnClickListener(v -> toggleSelectAll());
        fabSendSelected.setOnClickListener(v -> sendSelectedSms());

        scheduleAutoRetry();
        loadSimInfo();
        updateSelectedCount();
    }

    private void loadSimInfo() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.READ_PHONE_STATE}, PERMISSION_REQUEST_CODE);
            return;
        }

        SubscriptionManager subManager = (SubscriptionManager) getSystemService(TELEPHONY_SUBSCRIPTION_SERVICE);
        simList = subManager.getActiveSubscriptionInfoList();

        if (simList == null || simList.isEmpty())
            Toast.makeText(this, "❌ No active SIM found", Toast.LENGTH_LONG).show();
    }

    private void showLoading(String message) {
        runOnUiThread(() -> {
            progressText.setText(message);
            progressOverlay.setAlpha(0f);
            progressOverlay.setVisibility(View.VISIBLE);
            progressOverlay.animate().alpha(1f).setDuration(200).start();
        });
    }

    private void hideLoading() {
        runOnUiThread(() -> {
            progressOverlay.animate()
                    .alpha(0f)
                    .setDuration(250)
                    .withEndAction(() -> progressOverlay.setVisibility(View.GONE))
                    .start();
        });
    }

    private void fetchSmsFromFirebase() {
        showLoading("Fetching SMS from server...");
        db.collection("tasks").whereEqualTo("status", "pending").limit(100)
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
                    Toast.makeText(this, "✅ 100 SMS loaded", Toast.LENGTH_SHORT).show();
                })
                .addOnFailureListener(e -> {
                    hideLoading();
                    Toast.makeText(this, "❌ Failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
    }

    private void sendSelectedSms() {
        if (simList == null || simList.isEmpty()) {
            Toast.makeText(this, "❌ No SIM available", Toast.LENGTH_LONG).show();
            return;
        }

        showLoading("Sending selected SMS...");
        new Thread(() -> {
            for (Map<String, Object> sms : smsList) {
                if ((boolean) sms.get("selected")) {
                    int slot = 0;
                    if (sms.containsKey("preferredSimSlot")) {
                        Object pref = sms.get("preferredSimSlot");
                        if (pref instanceof Number) slot = ((Number) pref).intValue();
                    }
                    if (slot < 0 || slot >= simList.size()) slot = 0;

                    int subId = simList.get(slot).getSubscriptionId();
                    SmsManager smsManager = SmsManager.getSmsManagerForSubscriptionId(subId);
                    sendSingleSms(smsManager, sms, slot);
                }
            }
            runOnUiThread(() -> {
                hideLoading();
                Toast.makeText(this, "✅ All selected SMS sent", Toast.LENGTH_SHORT).show();
            });
        }).start();
    }

    private void sendSingleSms(SmsManager smsManager, Map<String, Object> sms, int slot) {
        try {
            String phone = sms.get("phone").toString();
            String message = sms.get("message").toString();
            PendingIntent sentPI = PendingIntent.getBroadcast(this, 0, new Intent("SMS_SENT"), PendingIntent.FLAG_IMMUTABLE);
            smsManager.sendTextMessage(phone, null, message, sentPI, null);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void retryFailedSms() {
        showLoading("Retrying failed SMS...");
        db.collection("failed_sms").limit(100)
                .get()
                .addOnSuccessListener(snapshot -> {
                    if (snapshot.isEmpty()) {
                        hideLoading();
                        Toast.makeText(this, "✅ No failed SMS to retry", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    for (DocumentSnapshot doc : snapshot.getDocuments()) {
                        String phone = doc.getString("phone");
                        String message = doc.getString("message");
                        Long slot = doc.getLong("preferredSimSlot");
                        if (slot == null) slot = 0L;
                        int simSlot = slot.intValue();

                        if (simSlot < 0 || simSlot >= simList.size()) simSlot = 0;
                        int subId = simList.get(simSlot).getSubscriptionId();
                        SmsManager smsManager = SmsManager.getSmsManagerForSubscriptionId(subId);
                        smsManager.sendTextMessage(phone, null, message, null, null);
                    }

                    hideLoading();
                    Toast.makeText(this, "✅ Retried failed SMS", Toast.LENGTH_SHORT).show();
                })
                .addOnFailureListener(e -> {
                    hideLoading();
                    Toast.makeText(this, "❌ Retry failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
    }

    private void toggleSelectAll() {
        allSelected = !allSelected;
        for (Map<String, Object> sms : smsList) sms.put("selected", allSelected);
        adapter.notifyDataSetChanged();
        selectAllButton.setText(allSelected ? "Deselect All" : "Select All");
        updateSelectedCount();
    }

    private void updateSelectedCount() {
        int count = 0;
        for (Map<String, Object> sms : smsList)
            if ((boolean) sms.get("selected")) count++;

        selectedCountText.setText("📩 " + count + " SMS selected");
        if (count > 0) fabSendSelected.show();
        else fabSendSelected.hide();
    }

    private void scheduleAutoRetry() {
        PeriodicWorkRequest work =
                new PeriodicWorkRequest.Builder(RetryWorker.class, 30, TimeUnit.MINUTES)
                        .setInitialDelay(5, TimeUnit.MINUTES)
                        .build();

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                "AutoRetryWorker",
                androidx.work.ExistingPeriodicWorkPolicy.KEEP,
                work
        );
    }

    private boolean checkPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestPermission() {
        ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.SEND_SMS, Manifest.permission.READ_PHONE_STATE},
                PERMISSION_REQUEST_CODE);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] perms, @NonNull int[] results) {
        super.onRequestPermissionsResult(requestCode, perms, results);
        if (requestCode == PERMISSION_REQUEST_CODE && checkPermission()) loadSimInfo();
    }
}