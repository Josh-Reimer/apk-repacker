package com.apkrepacker.ui;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInstaller;
import android.os.Build;
import android.os.Bundle;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.content.IntentCompat;

import com.apkrepacker.apk.BuildLog;
import com.apkrepacker.apk.PipelineException;
import com.apkrepacker.apk.ProgressListener;
import com.apkrepacker.apk.RepackageEngine;
import com.apkrepacker.apk.RepackageRequest;
import com.apkrepacker.apk.RepackageResult;
import com.apkrepacker.apk.Stage;
import com.apkrepacker.databinding.ActivityResultBinding;

import java.io.File;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Runs one repackage pipeline off the UI thread and renders live stage + log output. */
public final class ResultActivity extends AppCompatActivity {

    public static final String EXTRA_ORIGINAL = "original";
    public static final String EXTRA_NEW = "new";
    public static final String EXTRA_MIN_SDK = "min_sdk";
    public static final String EXTRA_KEEP = "keep";
    public static final String EXTRA_LABEL = "label";
    public static final String EXTRA_WATERMARK = "watermark";
    public static final String EXTRA_WATERMARK_TEXT = "watermark_text";

    private ActivityResultBinding binding;
    private final Map<Stage, Character> stageState = new EnumMap<>(Stage.class);
    private File outputApk;
    private List<File> splitApks = new ArrayList<>();
    private BroadcastReceiver installStatusReceiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityResultBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        WindowInsetsHelper.pad(binding.getRoot());
        setTitle("Repackaging");

        for (Stage s : Stage.values()) {
            stageState.put(s, '•');
        }
        renderStages();
        binding.txtStatus.setText("Starting…");

        binding.btnInstall.setOnClickListener(v -> {
            if (outputApk == null) {
                return;
            }
            if (splitApks.isEmpty()) {
                try {
                    Installer.launchInstall(this, outputApk);
                } catch (Exception e) {
                    appendLog("Install launch failed: " + e.getMessage());
                }
                return;
            }
            installSplitSet();
        });

        registerInstallStatusReceiver();

        String original = getIntent().getStringExtra(EXTRA_ORIGINAL);
        String newPkg = getIntent().getStringExtra(EXTRA_NEW);
        if (newPkg == null) {
            // Not a repackage launch (e.g. the activity was recreated); nothing to run.
            binding.txtStatus.setText("");
            return;
        }
        int minSdk = getIntent().getIntExtra(EXTRA_MIN_SDK, 0);
        boolean keep = getIntent().getBooleanExtra(EXTRA_KEEP, false);
        String label = getIntent().getStringExtra(EXTRA_LABEL);
        boolean watermark = getIntent().getBooleanExtra(EXTRA_WATERMARK, false);
        String watermarkText = getIntent().getStringExtra(EXTRA_WATERMARK_TEXT);

        runPipeline(original, newPkg, minSdk, keep, label, watermark, watermarkText);
    }

    private void installSplitSet() {
        List<File> set = new ArrayList<>();
        set.add(outputApk);
        set.addAll(splitApks);
        binding.btnInstall.setEnabled(false);
        appendLog("Install: writing base + " + splitApks.size()
                + " split APK(s) into an install session…");
        new Thread(() -> {
            try {
                Installer.installSplitSet(getApplicationContext(), set);
            } catch (Exception e) {
                runOnUiThread(() -> {
                    appendLog("Install failed: " + e.getMessage());
                    binding.btnInstall.setEnabled(true);
                });
            }
        }).start();
    }

    private void registerInstallStatusReceiver() {
        installStatusReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                onInstallStatus(intent);
            }
        };
        IntentFilter filter = new IntentFilter(Installer.ACTION_INSTALL_STATUS);
        ContextCompat.registerReceiver(this, installStatusReceiver, filter,
                ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    private void onInstallStatus(Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS,
                PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            // The system's confirmation screen; the user approves or declines there.
            Intent confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT,
                    Intent.class);
            if (confirm != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                }
                startActivity(confirm);
            }
            return;
        }
        String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        if (status == PackageInstaller.STATUS_SUCCESS) {
            appendLog("Install: split set installed.");
            binding.txtStatus.setText("✓ Installed");
        } else {
            appendLog("Install failed (status " + status + "): " + message);
            binding.btnInstall.setEnabled(true);
        }
    }

    @Override
    protected void onDestroy() {
        if (installStatusReceiver != null) {
            unregisterReceiver(installStatusReceiver);
            installStatusReceiver = null;
        }
        super.onDestroy();
    }

    private void runPipeline(String original, String newPkg, int minSdk, boolean keep,
                             String label, boolean watermark, String watermarkText) {
        final BuildLog log = new BuildLog();
        log.setObserver(entry -> runOnUiThread(() -> appendLog(entry.toString())));

        final ProgressListener progress = new ProgressListener() {
            @Override public void onStageStart(Stage stage) {
                runOnUiThread(() -> { stageState.put(stage, '…'); renderStages(); });
            }
            @Override public void onStageDone(Stage stage) {
                runOnUiThread(() -> { stageState.put(stage, '✓'); renderStages(); });
            }
        };

        RepackageRequest req = new RepackageRequest(
                original, newPkg, minSdk, SigningHolder.get(), keep,
                label, watermark, watermarkText);

        new Thread(() -> {
            try {
                RepackageResult result = new RepackageEngine(this).run(req, progress, log);
                runOnUiThread(() -> onSuccess(result));
            } catch (PipelineException e) {
                runOnUiThread(() -> onFailure(e));
            } catch (Exception e) {
                runOnUiThread(() -> {
                    appendLog("Unexpected error: " + e.getMessage());
                    showFailedStatus("Repackaging failed: unexpected error");
                });
            }
        }).start();
    }

    private void onSuccess(RepackageResult r) {
        outputApk = r.outputApk;
        splitApks = r.splitApks;
        StringBuilder sb = new StringBuilder();
        sb.append("Package:\n").append(r.newPackage).append("\n\n");
        sb.append("Original package:\n").append(r.originalPackage).append("\n\n");
        if (r.labelChanged && r.newLabel != null) {
            sb.append("Display name:\n").append(r.newLabel.trim()).append("\n\n");
        }
        if (r.iconsWatermarked > 0) {
            sb.append("Icon watermark: applied to ")
                    .append(r.iconsWatermarked).append(" icon file(s)\n\n");
        }
        sb.append("Signer:\n").append(r.verification.signerSummary).append("\n\n");
        sb.append("Signing key:\n").append(r.signerDescription).append("\n\n");
        sb.append("APK:\n").append(r.outputApk.getName()).append("\n")
                .append(r.outputApk.getAbsolutePath()).append("\n\n");
        if (!r.splitApks.isEmpty()) {
            sb.append("Split APKs (installed together with the base):\n");
            for (File f : r.splitApks) {
                sb.append("  ").append(f.getName()).append('\n');
            }
            sb.append('\n');
        }
        sb.append("Manifest strings changed: ").append(r.manifestStringsChanged).append('\n');
        sb.append("DEX type refs remapped: ").append(r.dexTypesRemapped).append('\n');
        sb.append("Resource XML class refs: ").append(r.resXmlStringsChanged).append("\n\n");
        sb.append("Schemes: v1=").append(r.verification.v1)
                .append(" v2=").append(r.verification.v2)
                .append(" v3=").append(r.verification.v3).append("\n\n");
        sb.append("Signature:\n").append(r.verification.verified ? "VALID" : "INVALID");
        if (!r.providerConflicts.isEmpty()) {
            sb.append("\n\n⚠ Will not install while these provider authorities are taken:\n");
            for (String c : r.providerConflicts) {
                sb.append("  ").append(c).append('\n');
            }
        }

        binding.txtSummary.setText(sb.toString());
        binding.cardSummary.setVisibility(View.VISIBLE);
        binding.btnInstall.setEnabled(true);

        binding.progressBar.setProgressCompat(100, true);
        binding.txtStatus.setText(!r.verification.verified ? "Complete — signature INVALID"
                : !r.providerConflicts.isEmpty() ? "Complete — install will conflict"
                : "✓ Complete");
        setTitle("Repackaging complete");
    }

    private void onFailure(PipelineException e) {
        stageState.put(e.stage, '✗');
        renderStages();
        showFailedStatus("✗ Failed at: " + e.stage.label);
        String msg = "Repackaging failed.\n\n"
                + "Stage: " + e.stage.label + "\n\n"
                + "Reason:\n" + e.getMessage() + "\n\n"
                + "Original APK has not been modified.";
        binding.txtSummary.setText(msg);
        binding.cardSummary.setVisibility(View.VISIBLE);
    }

    private void showFailedStatus(String status) {
        int error = com.google.android.material.color.MaterialColors.getColor(
                binding.progressBar, androidx.appcompat.R.attr.colorError);
        binding.progressBar.setIndicatorColor(error);
        binding.txtStatus.setText(status);
        binding.txtStatus.setTextColor(error);
        setTitle("Repackaging failed");
    }

    private void renderStages() {
        StringBuilder sb = new StringBuilder();
        int done = 0;
        Stage running = null;
        for (Stage s : Stage.values()) {
            char state = stageState.get(s);
            if (state == '✓') {
                done++;
            } else if (state == '…') {
                running = s;
            }
            sb.append(state).append(' ').append(s.label).append('\n');
        }
        binding.txtStages.setText(sb.toString().trim());

        // The final 100% is set by onSuccess, so the bar only fills once results are shown.
        int total = Stage.values().length;
        binding.progressBar.setProgressCompat(Math.min(99, done * 100 / total), true);
        if (running != null) {
            binding.txtStatus.setText(String.format(java.util.Locale.US,
                    "Step %d of %d: %s…", running.ordinal() + 1, total, running.label));
        }
    }

    private void appendLog(String line) {
        binding.txtLog.append(line + "\n");
    }
}
