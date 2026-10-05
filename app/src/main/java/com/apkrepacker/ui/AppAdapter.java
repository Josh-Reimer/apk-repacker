package com.apkrepacker.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.apkrepacker.R;
import com.apkrepacker.apk.InstalledApps;

import java.util.ArrayList;
import java.util.List;

/** Simple list of installed apps with a click callback. */
public final class AppAdapter extends RecyclerView.Adapter<AppAdapter.VH> {

    public interface OnClick {
        void onClick(InstalledApps.Entry entry);
    }

    static final class VH extends RecyclerView.ViewHolder {
        final TextView label;
        final TextView pkg;
        VH(View v) {
            super(v);
            label = v.findViewById(R.id.txtLabel);
            pkg = v.findViewById(R.id.txtPackage);
        }
    }

    private final List<InstalledApps.Entry> items = new ArrayList<>();
    private final OnClick onClick;

    public AppAdapter(OnClick onClick) {
        this.onClick = onClick;
    }

    public void submit(List<InstalledApps.Entry> entries) {
        items.clear();
        items.addAll(entries);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_app, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        InstalledApps.Entry e = items.get(position);
        holder.label.setText(e.label);
        holder.pkg.setText(e.packageName + (e.system ? "  (system)" : ""));
        holder.itemView.setOnClickListener(v -> onClick.onClick(e));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }
}
