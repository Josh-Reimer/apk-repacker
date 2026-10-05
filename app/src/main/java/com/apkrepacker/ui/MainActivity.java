package com.apkrepacker.ui;

import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.apkrepacker.R;
import com.apkrepacker.apk.ApkAnalysis;
import com.apkrepacker.apk.BuildLog;
import com.apkrepacker.apk.InstalledApps;
import com.apkrepacker.apk.PackageNames;
import com.apkrepacker.apk.PipelineException;
import com.apkrepacker.apk.RepackageEngine;
import com.apkrepacker.databinding.ActivityMainBinding;
import com.apkrepacker.security.KeystoreManager;

import java.io.File;
import java.io.InputStream;
import java.util.Locale;

public final class MainActivity extends AppCompatActivity {

    private ActivityMainBinding binding;

    private String selectedPackage;
    private String selectedLabel;
    private int analyzedMinSdk = 0;

    private ActivityResultLauncher<Intent> appPicker;
    private ActivityResultLauncher<String[]> keystorePicker;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        WindowInsetsHelper.pad(binding.getRoot());
        setSupportActionBar(binding.toolbar);

        appPicker = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        onAppSelected(
                                result.getData().getStringExtra(AppListActivity.EXTRA_PACKAGE),
                                result.getData().getStringExtra(AppListActivity.EXTRA_LABEL));
                    }
                });

        keystorePicker = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(), this::onKeystorePicked);

        binding.btnSelectApp.setOnClickListener(v ->
                appPicker.launch(new Intent(this, AppListActivity.class)));

        binding.btnAnalyze.setOnClickListener(v -> analyze());
        binding.btnRepackage.setOnClickListener(v -> confirmAndRepackage());

        binding.editPackage.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                validatePackageField();
            }
            @Override public void afterTextChanged(Editable s) {}
        });
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_import_key) {
            keystorePicker.launch(new String[]{"*/*"});
            return true;
        } else if (id == R.id.action_use_default_key) {
            SigningHolder.clear();
            toast("Using the app's default AndroidKeyStore signing key.");
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void onAppSelected(String pkg, String label) {
        selectedPackage = pkg;
        selectedLabel = label;
        analyzedMinSdk = 0;
        binding.txtSelectedApp.setText(label != null ? label : "(unknown)");
        binding.txtSelectedPackage.setText(pkg);
        binding.txtAnalysis.setText("");

        String apkName = "(unknown)";
        try {
            ApplicationInfo ai = getPackageManager().getApplicationInfo(pkg, 0);
            if (ai.sourceDir != null) {
                apkName = new File(ai.sourceDir).getName();
            }
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        binding.txtOriginalApk.setText(apkName);

        binding.btnAnalyze.setEnabled(true);
        validatePackageField();
    }

    private String currentPackageInput() {
        return binding.editPackage.getText() == null
                ? "" : binding.editPackage.getText().toString().trim();
    }

    private void validatePackageField() {
        String name = currentPackageInput();
        boolean appChosen = selectedPackage != null;

        String error = PackageNames.validate(name);
        if (error != null) {
            binding.tilPackage.setError(error);
            binding.btnRepackage.setEnabled(false);
            return;
        }
        if (appChosen && name.equals(selectedPackage)) {
            binding.tilPackage.setError("Must differ from the original package name.");
            binding.btnRepackage.setEnabled(false);
            return;
        }
        binding.tilPackage.setError(null);

        if (new InstalledApps(this).isInstalled(name)) {
            binding.tilPackage.setError(null);
            binding.tilPackage.setHelperText(
                    "⚠ A package with this name is already installed.");
        } else {
            binding.tilPackage.setHelperText(null);
        }
        binding.btnRepackage.setEnabled(appChosen);
    }

    private void analyze() {
        if (selectedPackage == null) {
            return;
        }
        binding.btnAnalyze.setEnabled(false);
        binding.txtAnalysis.setText("Analyzing…");
        final BuildLog log = new BuildLog();
        new Thread(() -> {
            try {
                ApkAnalysis a = new RepackageEngine(this).analyzeInstalled(selectedPackage, log);
                runOnUiThread(() -> {
                    analyzedMinSdk = a.minSdk;
                    binding.txtAnalysis.setText(formatAnalysis(a));
                    binding.btnAnalyze.setEnabled(true);
                });
            } catch (PipelineException e) {
                runOnUiThread(() -> {
                    binding.txtAnalysis.setText("Analysis failed [" + e.stage.label + "]:\n"
                            + e.getMessage());
                    binding.btnAnalyze.setEnabled(true);
                });
            }
        }).start();
    }

    private String formatAnalysis(ApkAnalysis a) {
        StringBuilder sb = new StringBuilder();
        sb.append("APK Analysis\n\n");
        sb.append("Package: ").append(a.packageName).append('\n');
        sb.append("Version: ").append(a.versionName).append('\n');
        sb.append("Version Code: ").append(a.versionCode).append('\n');
        sb.append("Min SDK: ").append(a.minSdk).append('\n');
        sb.append("Target SDK: ").append(a.targetSdk).append('\n');
        sb.append("DEX files: ").append(a.dexCount).append('\n');
        sb.append("Native libraries: ")
                .append(a.nativeAbis.isEmpty() ? "none" : String.join(", ", a.nativeAbis))
                .append('\n');
        sb.append("Split APK: ").append(a.hasSplits ? "Yes" : "No").append('\n');
        if (!a.potentialIssues.isEmpty()) {
            sb.append("\nPotential issues:\n");
            for (String i : a.potentialIssues) {
                sb.append("⚠ ").append(i).append('\n');
            }
        }
        return sb.toString();
    }

    private void confirmAndRepackage() {
        final String newPackage = currentPackageInput();
        String error = PackageNames.validate(newPackage);
        if (error != null) {
            binding.tilPackage.setError(error);
            return;
        }
        if (newPackage.equals(selectedPackage)) {
            binding.tilPackage.setError("Must differ from the original package name.");
            return;
        }

        if (new InstalledApps(this).isInstalled(newPackage)) {
            new AlertDialog.Builder(this)
                    .setTitle("Package already installed")
                    .setMessage(getString(R.string.conflict_installed))
                    .setPositiveButton("Choose another", (d, w) -> {})
                    .show();
            return;
        }

        new AlertDialog.Builder(this)
                .setTitle(R.string.limitations_title)
                .setMessage(R.string.limitations_body)
                .setPositiveButton(R.string.continue_label, (d, w) -> launchRepackage(newPackage))
                .setNegativeButton("Cancel", (d, w) -> {})
                .show();
    }

    private void launchRepackage(String newPackage) {
        Intent i = new Intent(this, ResultActivity.class);
        i.putExtra(ResultActivity.EXTRA_ORIGINAL, selectedPackage);
        i.putExtra(ResultActivity.EXTRA_NEW, newPackage);
        i.putExtra(ResultActivity.EXTRA_MIN_SDK, analyzedMinSdk);
        i.putExtra(ResultActivity.EXTRA_KEEP, binding.chkKeepIntermediates.isChecked());
        startActivity(i);
    }

    // ---- advanced: import user signing keystore ----------------------------

    private void onKeystorePicked(@Nullable Uri uri) {
        if (uri == null) {
            return;
        }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad, pad, 0);
        final EditText storePw = hint("Keystore password");
        final EditText alias = hint("Key alias (blank = first)");
        final EditText keyPw = hint("Key password (blank = keystore password)");
        box.addView(storePw);
        box.addView(alias);
        box.addView(keyPw);

        new AlertDialog.Builder(this)
                .setTitle("Import signing key")
                .setView(box)
                .setPositiveButton("Import", (d, w) -> importKey(uri,
                        storePw.getText().toString(),
                        alias.getText().toString(),
                        keyPw.getText().toString()))
                .setNegativeButton("Cancel", (d, w) -> {})
                .show();
    }

    private EditText hint(String h) {
        EditText e = new EditText(this);
        e.setHint(h);
        return e;
    }

    private void importKey(Uri uri, String storePw, String alias, String keyPw) {
        new Thread(() -> {
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                String type = guessKeystoreType(uri);
                KeystoreManager.SigningIdentity id = new KeystoreManager(this).importFromKeystore(
                        in, type,
                        storePw.toCharArray(),
                        alias.isEmpty() ? null : alias,
                        keyPw.isEmpty() ? null : keyPw.toCharArray());
                SigningHolder.set(id);
                runOnUiThread(() -> toast("Imported signing key: " + id.description));
            } catch (Exception e) {
                runOnUiThread(() -> toast("Import failed: " + e.getMessage()));
            }
        }).start();
    }

    private String guessKeystoreType(Uri uri) {
        String s = uri.toString().toLowerCase(Locale.US);
        if (s.endsWith(".bks")) return "BKS";
        if (s.endsWith(".jks")) return "JKS";
        return "PKCS12"; // .p12/.pfx/.keystore default
    }

    private void toast(String m) {
        Toast.makeText(this, m, Toast.LENGTH_LONG).show();
    }
}
