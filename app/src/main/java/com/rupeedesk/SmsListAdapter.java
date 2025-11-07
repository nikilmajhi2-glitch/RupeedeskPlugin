package com.rupeedesk.smsaautosender;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import java.util.List;
import java.util.Map;

public class SmsListAdapter extends RecyclerView.Adapter<SmsListAdapter.ViewHolder> {

    private final Context context;
    private final List<Map<String, Object>> smsList;
    private final OnSelectionChangedListener listener;

    public interface OnSelectionChangedListener {
        void onSelectionChanged();
    }

    public SmsListAdapter(Context context, List<Map<String, Object>> smsList, OnSelectionChangedListener listener) {
        this.context = context;
        this.smsList = smsList;
        this.listener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_sms, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Map<String, Object> sms = smsList.get(position);
        String phone = (String) sms.get("phone");
        String message = (String) sms.get("message");
        boolean selected = (boolean) sms.get("selected");

        holder.phoneText.setText(phone);
        holder.messageText.setText(message);
        holder.checkBox.setChecked(selected);

        View.OnClickListener toggle = v -> {
            sms.put("selected", !selected);
            notifyItemChanged(position);
            listener.onSelectionChanged();
        };

        holder.itemView.setOnClickListener(toggle);
        holder.checkBox.setOnClickListener(toggle);
    }

    @Override
    public int getItemCount() {
        return smsList.size();
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        TextView phoneText, messageText;
        CheckBox checkBox;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            phoneText = itemView.findViewById(R.id.smsPhone);
            messageText = itemView.findViewById(R.id.smsMessage);
            checkBox = itemView.findViewById(R.id.smsCheckBox);
        }
    }
}