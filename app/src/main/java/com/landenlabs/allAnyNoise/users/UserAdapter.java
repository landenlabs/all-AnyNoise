package com.landenlabs.allAnyNoise.users;

import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.landenlabs.allAnyNoise.R;
import com.landenlabs.allAnyNoise.model.DeviceDoc;

import java.util.ArrayList;
import java.util.List;

public class UserAdapter extends RecyclerView.Adapter<UserAdapter.ViewHolder> {

    private final List<DeviceDoc> devices = new ArrayList<>();

    public void submit(List<DeviceDoc> newDevices) {
        devices.clear();
        devices.addAll(newDevices);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_device, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        DeviceDoc device = devices.get(position);
        holder.name.setText(device.displayName);
        if (device.updatedAt != null) {
            holder.lastSeen.setText(holder.lastSeen.getContext().getString(
                    R.string.users_last_seen,
                    DateUtils.getRelativeTimeSpanString(device.updatedAt.getTime())));
        } else {
            holder.lastSeen.setText(null);
        }
        bindBattery(holder, device);
    }

    private void bindBattery(ViewHolder holder, DeviceDoc device) {
        if (device.batteryLevelPct == null || device.batteryHealth == null || device.batteryTempC == null) {
            holder.battery.setText(R.string.users_battery_unknown);
            holder.battery.setTextColor(holder.defaultTextColor);
            holder.batteryReported.setText(null);
            return;
        }

        holder.battery.setText(holder.battery.getContext().getString(R.string.users_battery_status,
                device.batteryLevelPct, device.batteryHealth, device.batteryTempC));
        holder.battery.setTextColor(healthColor(holder, device.batteryHealth));

        if (device.batteryUpdatedAt != null) {
            holder.batteryReported.setText(holder.batteryReported.getContext().getString(
                    R.string.users_battery_reported,
                    DateUtils.getRelativeTimeSpanString(device.batteryUpdatedAt.getTime())));
        } else {
            holder.batteryReported.setText(null);
        }
    }

    private int healthColor(ViewHolder holder, String health) {
        switch (health) {
            case "Good":
                return ContextCompat.getColor(holder.battery.getContext(), R.color.anynoise_status_ok);
            case "Overheat":
            case "Dead":
            case "Over voltage":
            case "Cold":
                return ContextCompat.getColor(holder.battery.getContext(), R.color.anynoise_status_bad);
            default:
                return holder.defaultTextColor;
        }
    }

    @Override
    public int getItemCount() {
        return devices.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final TextView name;
        final TextView lastSeen;
        final TextView battery;
        final TextView batteryReported;
        final int defaultTextColor;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            name = itemView.findViewById(R.id.tv_name);
            lastSeen = itemView.findViewById(R.id.tv_last_seen);
            battery = itemView.findViewById(R.id.tv_battery);
            batteryReported = itemView.findViewById(R.id.tv_battery_reported);
            defaultTextColor = battery.getCurrentTextColor();
        }
    }
}
