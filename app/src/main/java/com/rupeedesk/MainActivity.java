package com.rupeedesk;

import android.Manifest;
import android.app.PendingIntent;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.telephony.SmsManager;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.Transaction;
import com.rupeedesk.smsaautosender.SmsSentReceiver; // We still use this

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    private static final int PERMISSION_REQUEST_CODE = 101;
    private static final String PREFS_NAME = "AppPrefs";
    private static final String KEY_USER_ID = "userId";
    private static final String KEY_SERVICE_RUNNING = "isServiceRunning"; // We can rename this
    private static final String KEY_SUBSCRIPTION_ID = "subscriptionId";
    private static final int SMS_BATCH_SIZE = 25; // As you requested

    private FirebaseFirestore db;
    private EditText userIdInput;
    private Button bindServiceBtn; // Renamed from startServiceBtn
    private Button sendTasksBtn;   // <-- YOU MUST ADD THIS BUTTON to activity_main.xml
    private ProgressBar progressBar;
    private TextView statusText;
    private SharedPreferences prefs;

    private String boundUserId;
    private int boundSubId;

    // This will hold the tasks we fetch
    private List<DocumentSnapshot> tasksToSend = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        db = FirebaseFirestore.getInstance();
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        // UI setup
        userIdInput = findViewById(R.id.userIdInput);
        bindServiceBtn = findViewById(R.id.startServiceBtn); // Assumes old ID
        sendTasksBtn = findViewById(R.id.sendTasksBtn);     // Assumes new ID R.id.sendTasksBtn
        progressBar = findViewById(R.id.progressBar);
        statusText = findViewById(R.id.statusText);

        // Load saved user ID
        boundUserId = prefs.getString(KEY_USER_ID, null);
        boundSubId = prefs.getInt(KEY_SUBSCRIPTION_ID, -1);
        if (boundUserId != null) {
            userIdInput.setText(boundUserId);
        }

        bindServiceBtn.setOnClickListener(v -> handleBindButton());
        sendTasksBtn.setOnClickListener(v -> handleSendTasksButton());
        
        checkPermissions();
        updateUI();
    }

    private void handleBindButton() {
        boolean isBound = (boundUserId != null && !boundUserId.isEmpty());

        if (isBound) {
            // If already bound, the button acts as "Fetch"
            fetchTasksFromFirestore();
        } else {
            // If not bound, the button acts as "Bind"
            bindUserAndCheckSims();
        }
    }

    private void bindUserAndCheckSims() {
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
                        boundUserId = input;
                        Toast.makeText(this, "✅ User ID Bound: " + input, Toast.LENGTH_SHORT).show();
                        checkSimsAndStart(); // This will check sims and then update the UI
                    } else {
                        Toast.makeText(this, "❌ User ID not found", Toast.LENGTH_SHORT).show();
                        hideLoading();
                    }
                })
                .addOnFailureListener(e -> {
                    Toast.makeText(this, "🔥 Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    hideLoading();
                });
    }

    private void fetchTasksFromFirestore() {
        if (boundUserId == null) {
            Toast.makeText(this, "Please bind your User ID first", Toast.LENGTH_SHORT).show();
            return;
        }
        showLoading("Fetching " + SMS_BATCH_SIZE + " tasks...");

        db.collection("sms_tasks")
                .whereIn("status", Arrays.asList("pending", "failed"))
                .orderBy("createdAt", Query.Direction.ASCENDING)
                .limit(SMS_BATCH_SIZE)
                .get()
                .addOnSuccessListener(snapshot -> {
                    if (snapshot.isEmpty()) {
                        Toast.makeText(this, "No tasks found in queue.", Toast.LENGTH_SHORT).show();
                        hideLoading();
                        return;
                    }

                    tasksToSend = snapshot.getDocuments();
                    Log.d(TAG, "Fetched " + tasksToSend.size() + " tasks.");
                    statusText.setText(tasksToSend.size() + " tasks are ready to send.");
                    sendTasksBtn.setVisibility(View.VISIBLE); // Show the "Send" button
                    hideLoading();
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Failed to fetch tasks: " + e.getMessage());
                    statusText.setText("Failed to fetch tasks.");
                    hideLoading();
                });
    }

    private void handleSendTasksButton() {
        if (tasksToSend.isEmpty()) {
            Toast.makeText(this, "No tasks to send. Fetch tasks first.", Toast.LENGTH_SHORT).show();
            return;
        }

        showLoading("Sending " + tasksToSend.size() + " messages...");
        sendTasksBtn.setVisibility(View.GONE); // Hide send button while sending

        for (DocumentSnapshot doc : tasksToSend) {
            // We lease AND send at the same time to prevent stuck tasks
            leaseAndSendTask(doc);
        }

        tasksToSend.clear(); // Clear the list
        hideLoading();
        statusText.setText("All tasks sent. Check results in receiver or fetch more.");
        // Here you would update your UI to show a list of "pending" sends
        // The SmsSentReceiver will update the final status in Firestore
    }

    // --- All logic is now in MainActivity ---

    private void leaseAndSendTask(DocumentSnapshot doc) {
        String id = doc.getId();
        DocumentReference docRef = db.collection("sms_tasks").document(id);

        db.runTransaction((Transaction.Function<DocumentSnapshot>) transaction -> {
            DocumentSnapshot snapshot = transaction.get(docRef);
            String status = snapshot.getString("status");

            if ("pending".equals(status) || "failed".equals(status)) {
                Long retryCount = snapshot.getLong("retryCount");
                if (retryCount == null) retryCount = 0L;
                if (retryCount >= 3) {
                    transaction.delete(docRef);
                    return null; // Skip this task, it's dead
                }
                transaction.update(docRef, "status", "sending", "leasedBy", boundUserId, "leasedAt", new Date());
                return snapshot; // Return the task we leased
            } else {
                return null; // Task was already leased by someone else
            }
        }).addOnSuccessListener(snapshot -> {
            if (snapshot != null) {
                // We successfully leased this task
                sendSms(snapshot, boundUserId, boundSubId);
            } else {
                Log.w(TAG, "Task " + id + " was already leased by another user. Skipping.");
            }
        }).addOnFailureListener(e -> {
            Log.e(TAG, "Failed to lease task " + id + ": " + e.getMessage());
        });
    }

    private void sendSms(DocumentSnapshot doc, String senderUserId, int subId) {
        String id = doc.getId();
        String phone = doc.getString("phone");
        String message = doc.getString("message");
        Long retryCount = doc.getLong("retryCount");
        if (retryCount == null) retryCount = 0L;

        try {
            Intent sentIntent = new Intent(getApplicationContext(), SmsSentReceiver.class);
            sentIntent.putExtra("documentId", id);
            sentIntent.putExtra("userId", senderUserId);
            sentIntent.putExtra("retryCount", retryCount);
            PendingIntent sentPI = PendingIntent.getBroadcast(
                    getApplicationContext(), id.hashCode(), sentIntent,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
            );

            SmsManager smsManager;
            if (subId != -1 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                smsManager = SmsManager.getSmsManagerForSubscriptionId(subId);
            } else {
                smsManager = SmsManager.getDefault();
            }

            smsManager.sendTextMessage(phone, null, message, sentPI, null);
            Log.d(TAG, "📬 Sending SMS for leased task: " + id);
        } catch (Exception e) {
            Log.e(TAG, "❌ Failed to initiate send for task " + id + ": " + e.getMessage());
            Map<String, Object> update = new HashMap<>();
            update.put("status", "failed");
            update.put("retryCount", retryCount + 1);
            update.put("lastError", "Send initiation failed: " + e.getMessage());
            update.put("leasedBy", null);
            update.put("leasedAt", null);
            db.collection("sms_tasks").document(id).update(update);
        }
    }
    
    // --- UI and Permission Methods (Mostly unchanged) ---

    private void checkSimsAndStart() {
        // (This method is unchanged... it just calls updateUI at the end)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP_MR1) {
            prefs.edit().putInt(KEY_SUBSCRIPTION_ID, -1).apply();
            boundSubId = -1;
            hideLoading();
            updateUI();
            return;
        }
        try {
            SubscriptionManager subManager = (SubscriptionManager) getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
            List<SubscriptionInfo> simList = subManager.getActiveSubscriptionInfoList();
            if (simList == null || simList.isEmpty()) {
                Toast.makeText(this, "❌ No SIM card detected.", Toast.LENGTH_LONG).show();
                hideLoading();
                updateUI();
                return;
            }
            if (simList.size() == 1) {
                int subId = simList.get(0).getSubscriptionId();
                prefs.edit().putInt(KEY_SUBSCRIPTION_ID, subId).apply();
                boundSubId = subId;
                hideLoading();
                updateUI();
                return;
            }
            String[] simDisplayNames = new String[simList.size()];
            for (int i = 0; i < simList.size(); i++) {
                simDisplayNames[i] = "SIM " + (simList.get(i).getSimSlotIndex() + 1) + ": " + simList.get(i).getDisplayName();
            }
            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setTitle("Select SIM for Sending");
            builder.setCancelable(false);
            builder.setSingleChoiceItems(simDisplayNames, 0, null);
            builder.setPositiveButton("OK", (dialog, which) -> {
                int selectedPosition = ((AlertDialog) dialog).getListView().getCheckedItemPosition();
                int subId = simList.get(selectedPosition).getSubscriptionId();
                prefs.edit().putInt(KEY_SUBSCRIPTION_ID, subId).apply();
                boundSubId = subId;
                hideLoading();
                updateUI();
            });
            builder.setNegativeButton("Cancel", (dialog, which) -> {
                hideLoading();
                updateUI();
            });
            builder.show();
        } catch (SecurityException e) {
            Toast.makeText(this, "Error: Please grant READ_PHONE_STATE permission.", Toast.LENGTH_LONG).show();
            hideLoading();
            updateUI();
        }
    }

    private void updateUI() {
        boolean isBound = (boundUserId != null && !boundUserId.isEmpty());
        if (isBound) {
            statusText.setText("Bound to User: " + boundUserId);
            statusText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark));
            bindServiceBtn.setText("Fetch " + SMS_BATCH_SIZE + " Tasks");
            userIdInput.setEnabled(false);
        } else {
            statusText.setText("Not Bound");
            statusText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark));
            bindServiceBtn.setText("Bind User ID");
            userIdInput.setEnabled(true);
        }
        sendTasksBtn.setVisibility(View.GONE); // Always hide on UI update
    }

    private void showLoading(String msg) {
        progressBar.setVisibility(View.VISIBLE);
        statusText.setText(msg);
        bindServiceBtn.setEnabled(false);
    }

    private void hideLoading() {
        progressBar.setVisibility(View.GONE);
        bindServiceBtn.setEnabled(true);
        updateUI(); // Resets the text and status
    }

    private void checkPermissions() {
        // (This method is unchanged)
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
        // (This method is unchanged)
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
                Toast.makeText(this, "All permissions are required to run.", Toast.LENGTH_LONG).show();
            }
        }
    }
}
