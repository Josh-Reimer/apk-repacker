package com.apkrepacker.ui;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.apkrepacker.apk.InstalledApps;
import com.apkrepacker.databinding.ActivityAppListBinding;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Lists installed apps for selection. Returns the chosen package to MainActivity. */
public final class AppListActivity extends AppCompatActivity {

    public static final String EXTRA_PACKAGE = "package";
    public static final String EXTRA_LABEL = "label";

    private ActivityAppListBinding binding;
    private AppAdapter adapter;
    private final List<InstalledApps.Entry> all = new ArrayList<>();
    private String query = "";
    private boolean includeSystem = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityAppListBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        WindowInsetsHelper.pad(binding.getRoot());
        setTitle(getString(com.apkrepacker.R.string.select_installed_app));

        adapter = new AppAdapter(entry -> {
            getIntent().putExtra(EXTRA_PACKAGE, entry.packageName);
            getIntent().putExtra(EXTRA_LABEL, entry.label);
            setResult(RESULT_OK, getIntent());
            finish();
        });
        binding.recycler.setLayoutManager(new LinearLayoutManager(this));
        binding.recycler.addItemDecoration(
                new DividerItemDecoration(this, DividerItemDecoration.VERTICAL));
        binding.recycler.setAdapter(adapter);

        binding.editSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                query = s.toString().trim().toLowerCase(Locale.getDefault());
                applyFilter();
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        binding.switchSystem.setOnCheckedChangeListener((b, checked) -> {
            includeSystem = checked;
            load();
        });

        load();
    }

    private void load() {
        binding.progress.setVisibility(View.VISIBLE);
        new Thread(() -> {
            List<InstalledApps.Entry> entries = new InstalledApps(this).list(includeSystem);
            runOnUiThread(() -> {
                all.clear();
                all.addAll(entries);
                binding.progress.setVisibility(View.GONE);
                applyFilter();
            });
        }).start();
    }

    private void applyFilter() {
        if (query.isEmpty()) {
            adapter.submit(all);
            return;
        }
        List<InstalledApps.Entry> filtered = new ArrayList<>();
        for (InstalledApps.Entry e : all) {
            if (e.label.toLowerCase(Locale.getDefault()).contains(query)
                    || e.packageName.toLowerCase(Locale.getDefault()).contains(query)) {
                filtered.add(e);
            }
        }
        adapter.submit(filtered);
    }
}
