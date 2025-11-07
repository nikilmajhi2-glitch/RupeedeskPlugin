package com.rupeedesk;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.TextView;

import java.util.List;
import java.util.Map;

public class SmsListAdapter extends BaseAdapter {

    public interface OnSelectionChangedListener {
        void onSelectionChanged();
    }

    private final Context context;
    private final List<Map<String, Object>> smsList;
    private final OnSelectionChangedListener listener;

    public SmsListAdapter(Context context, List<Map<String, Object>> smsList,
                          OnSelectionChangedListener listener) {
        this.context = context;
        this.smsList = smsList;
        this.listener = listener;
    }

    @Override
    public int getCount() { return smsList.size(); }

    @Override
    public Object getItem(int position) { return smsList.get(position); }

    @Override
    public long getItemId(int position) { return position; }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        Map<String, Object> sms = smsList.get(position);

        if (convertView == null)
            convertView = LayoutInflater.from(context).inflate(R.layout.item_sms, parent, false);

        TextView phoneText = convertView.findViewById(R.id.phoneText);
        TextView messageText = convertView.findViewById(R.id.messageText);
        CheckBox checkBox = convertView.findViewById(R.id.smsCheckbox);
        View rowLayout = convertView.findViewById(R.id.smsRowLayout);

        phoneText.setText(sms.get("phone").toString());
        messageText.setText(sms.get("message").toString());
        checkBox.setChecked((boolean) sms.get("selected"));

        checkBox.setOnCheckedChangeListener((btn, isChecked) -> {
            sms.put("selected", isChecked);
            if (listener != null) listener.onSelectionChanged();
        });

        rowLayout.setOnClickListener(v -> {
            boolean newState = !(boolean) sms.get("selected");
            sms.put("selected", newState);
            checkBox.setChecked(newState);
            if (listener != null) listener.onSelectionChanged();
        });

        return convertView;
    }
}