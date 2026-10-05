package com.apkrepacker.ui;

import android.os.Bundle;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;

import com.apkrepacker.apk.BuildLog;
import com.apkrepacker.apk.PipelineException;
import com.apkrepacker.apk.ProgressListener;
import com.apkrepacker.apk.RepackageEngine;
import com.apkrepacker.apk.RepackageRequest;
import com.apkrepacker.apk.RepackageResult;
import com.apkrepacker.apk.Stage;
import com.apkrepacker.databinding.ActivityResultBinding;

import java.io.File;
import java.util.EnumMap;
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

        binding.btnInstall.setOnClickListener(v -> {
            if (outputApk != null) {
                try {
                    Installer.launchInstall(this, outputApk);
                } catch (Exception e) {
                    appendLog("Install launch failed: " + e.getMessage());
                }
            }
        });

        String original = getIntent().getStringExtra(EXTRA_ORIGINAL);
        String newPkg = getIntent().getStringExtra(EXTRA_NEW);
        int minSdk = getIntent().getIntExtra(EXTRA_MIN_SDK, 0);
        boolean keep = getIntent().getBooleanExtra(EXTRA_KEEP, false);
        String label = getIntent().getStringExtra(EXTRA_LABEL);
        boolean watermark = getIntent().getBooleanExtra(EXTRA_WATERMARK, false);
        String watermarkText = getIntent().getStringExtra(EXTRA_WATERMARK_TEXT);

        runPipeline(original, newPkg, minSdk, keep, label, watermark, watermarkText);
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
                });
            }
        }).start();
    }

    private void onSuccess(RepackageResult r) {
        outputApk = r.outputApk;
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
        sb.append("Manifest strings changed: ").append(r.manifestStringsChanged).append('\n');
        sb.append("DEX type refs remapped: ").append(r.dexTypesRemapped).append("\n\n");
        sb.append("Schemes: v1=").append(r.verification.v1)
                .append(" v2=").append(r.verification.v2)
                .append(" v3=").append(r.verification.v3).append("\n\n");
        sb.append("Signature:\n").append(r.verification.verified ? "VALID" : "INVALID");

        binding.txtSummary.setText(sb.toString());
        binding.cardSummary.setVisibility(View.VISIBLE);
        binding.btnInstall.setEnabled(true);
    }

    private void onFailure(PipelineException e) {
        stageState.put(e.stage, '✗');
        renderStages();
        String msg = "Repackaging failed.\n\n"
                + "Stage: " + e.stage.label + "\n\n"
                + "Reason:\n" + e.getMessage() + "\n\n"
                + "Original APK has not been modified.";
        binding.txtSummary.setText(msg);
        binding.cardSummary.setVisibility(View.VISIBLE);
    }

    private void renderStages() {
        StringBuilder sb = new StringBuilder();
        for (Stage s : Stage.values()) {
            sb.append(stageState.get(s)).append(' ').append(s.label).append('\n');
        }
        binding.txtStages.setText(sb.toString().trim());
    }

    private void appendLog(String line) {
        binding.txtLog.append(line + "\n");
    }
}
