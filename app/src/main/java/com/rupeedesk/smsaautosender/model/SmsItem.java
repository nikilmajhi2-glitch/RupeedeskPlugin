package com.rupeedesk.smsaautosender.model;

import java.util.Date;

public class SmsItem {
    private String id;              // Firestore document ID
    private String recipient;       // recipientNumber
    private String message;         // messageBody
    private long scheduledTime;     // optional (if you schedule SMS)
    private boolean sent;           // isSent
    private String sentBy;          // phone number or user id
    private Date addedAt;           // Firestore timestamp
    private Date sentAt;            // Firestore timestamp when sent

    public SmsItem() {}

    public SmsItem(String id, String recipient, String message) {
        this.id = id;
        this.recipient = recipient;
        this.message = message;
        this.sent = false;
        this.scheduledTime = System.currentTimeMillis();
    }

    // ✅ Getters and setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getRecipient() { return recipient; }
    public void setRecipient(String recipient) { this.recipient = recipient; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public long getScheduledTime() { return scheduledTime; }
    public void setScheduledTime(long scheduledTime) { this.scheduledTime = scheduledTime; }

    public boolean isSent() { return sent; }
    public void setSent(boolean sent) { this.sent = sent; }

    public String getSentBy() { return sentBy; }
    public void setSentBy(String sentBy) { this.sentBy = sentBy; }

    public Date getAddedAt() { return addedAt; }
    public void setAddedAt(Date addedAt) { this.addedAt = addedAt; }

    public Date getSentAt() { return sentAt; }
    public void setSentAt(Date sentAt) { this.sentAt = sentAt; }
}