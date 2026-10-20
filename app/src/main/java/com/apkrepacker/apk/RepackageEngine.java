package com.apkrepacker.apk;

import android.content.Context;

import com.apkrepacker.security.KeystoreManager;
import com.apkrepacker.storage.ApkStorage;

import java.io.File;
import java.util.List;
import java.util.Map;

/**
 * Drives the full repackage pipeline as explicit, ordered stages. Lives entirely off the
 * UI thread; everything it touches is app-private and local.
 *
 * <pre>
 *   extract → validate → decode → transform → rebuild → zipalign → sign → verify → output
 * </pre>
 */
public final class RepackageEngine {

    private final Context context;
    private final ApkStorage storage;

    public RepackageEngine(Context context) {
        this.context = context.getApplicationContext();
        this.storage = new ApkStorage(this.context);
    }

    /** Analyze-only entry point used before the user commits to a repackage. */
    public ApkAnalysis analyzeInstalled(String packageName, BuildLog log)
            throws PipelineException {
        String runId = "analyze-" + System.currentTimeMillis();
        File workDir;
        try {
            workDir = storage.newRunDir(runId);
        } catch (Exception e) {
            throw new PipelineException(Stage.EXTRACT,
                    "Cannot create working directory: " + e.getMessage(), e);
        }
        try {
            ApkExtractor.Extracted extracted =
                    new ApkExtractor(context).extract(packageName, workDir, log);
            ApkAnalysis analysis = new ApkAnalyzer(context)
                    .analyze(extracted.baseApk, extracted.hasSplits, log);
            for (String abi : extracted.externalNativeAbis()) {
                analysis.nativeAbis.add(abi + " (outside APK)");
            }
            if (!extracted.externalNativeLibs.isEmpty()) {
                analysis.potentialIssues.add("Native libraries are installed outside the APK "
                        + "(typical of preinstalled apps); they will be bundled into the output.");
            }
            return analysis;
        } finally {
            ApkStorage.deleteTree(workDir);
        }
    }

    /** The original app's provider authorities, resolved by PackageManager. */
    private List<String> installedAuthorities(String pkg) {
        List<String> out = new java.util.ArrayList<>();
        try {
            android.content.pm.PackageInfo pi = context.getPackageManager().getPackageInfo(pkg,
                    android.content.pm.PackageManager.GET_PROVIDERS
                            | android.content.pm.PackageManager.MATCH_DISABLED_COMPONENTS);
            if (pi.providers != null) {
                for (android.content.pm.ProviderInfo p : pi.providers) {
                    if (p.authority != null) {
                        out.add(p.authority);
                    }
                }
            }
        } catch (android.content.pm.PackageManager.NameNotFoundException ignored) {
            // Extraction already failed loudly for a missing package.
        }
        return out;
    }

    /**
     * Authorities the output will declare that an installed app already owns. Android refuses
     * to install a package whose provider authority is taken (INSTALL_FAILED_CONFLICTING_
     * PROVIDER), so these are reported up front instead of surfacing as an install failure.
     */
    private List<String> providerConflicts(List<String> originalAuthorities,
                                           Map<String, String> renames, String originalPkg,
                                           BuildLog log) {
        List<String> conflicts = new java.util.ArrayList<>();
        android.content.pm.PackageManager pm = context.getPackageManager();
        for (String declared : originalAuthorities) {
            for (String a : declared.split(";")) {
                String fin = renames.containsKey(a) ? renames.get(a) : a;
                android.content.pm.ProviderInfo owner = pm.resolveContentProvider(fin,
                        android.content.pm.PackageManager.MATCH_DISABLED_COMPONENTS);
                if (owner != null) {
                    String msg = fin + " (already used by " + owner.packageName + ")";
                    conflicts.add(msg);
                    log.warn("Provider authority conflict: " + msg + ". Android will refuse to "
                            + "install the output while " + owner.packageName
                            + " is installed.");
                }
            }
        }
        return conflicts;
    }

    public RepackageResult run(RepackageRequest req, ProgressListener progress, BuildLog log)
            throws PipelineException {
        // ---- Stage 1: validate the request up front ----
        String nameError = PackageNames.validate(req.newPackage);
        if (nameError != null) {
            throw new PipelineException(Stage.VALIDATE, "Invalid new package name: " + nameError);
        }
        if (req.newPackage.equals(req.originalPackage)) {
            throw new PipelineException(Stage.VALIDATE,
                    "New package name must differ from the original (" + req.originalPackage + ").");
        }

        String runId = "run-" + System.currentTimeMillis();
        File workDir;
        File decodeDir;
        try {
            workDir = storage.newRunDir(runId);
            decodeDir = storage.newDecodeDir(runId);
        } catch (Exception e) {
            throw new PipelineException(Stage.EXTRACT,
                    "Cannot create working directories: " + e.getMessage(), e);
        }

        File modDir = new File(workDir, "modified");
        //noinspection ResultOfMethodCallIgnored
        modDir.mkdirs();

        try {
            // ---- Stage: extract ----
            progress.onStageStart(Stage.EXTRACT);
            ApkExtractor.Extracted extracted =
                    new ApkExtractor(context).extract(req.originalPackage, workDir, log);
            if (extracted.hasSplits) {
                log.warn("Proceeding with the BASE APK only; split APKs are not merged.");
            }
            progress.onStageDone(Stage.EXTRACT);

            // ---- Stage: validate the APK itself ----
            progress.onStageStart(Stage.VALIDATE);
            ApkAnalysis analysis =
                    new ApkAnalyzer(context).analyze(extracted.baseApk, extracted.hasSplits, log);
            int minSdk = req.minSdk > 0 ? req.minSdk
                    : (analysis.minSdk > 0 ? analysis.minSdk : 26);
            progress.onStageDone(Stage.VALIDATE);

            // ---- Stage: decode ----
            progress.onStageStart(Stage.DECODE);
            ApkDecoder.Decoded decoded =
                    new ApkDecoder().decode(extracted.baseApk, decodeDir, log);
            progress.onStageDone(Stage.DECODE);

            // ---- Stage: transform (manifest + dex + optional display name & icon) ----
            progress.onStageStart(Stage.TRANSFORM);
            List<String> originalAuthorities = installedAuthorities(req.originalPackage);
            PackageTransformer.Result transform = new PackageTransformer().transform(
                    decoded, analysis.packageName, req.newPackage, req.newLabel, minSdk,
                    originalAuthorities, modDir, log);
            List<String> conflicts = providerConflicts(originalAuthorities,
                    transform.authorityRenames, req.originalPackage, log);

            int iconsWatermarked = 0;
            if (req.watermarkIcon) {
                Map<String, File> iconRepl = new IconWatermarker().watermark(
                        context, req.originalPackage, extracted.baseApk,
                        req.watermarkText, modDir, log);
                transform.replacements.putAll(iconRepl);
                iconsWatermarked = iconRepl.size();
            }
            progress.onStageDone(Stage.TRANSFORM);

            // ---- Stage: rebuild (also aligns) ----
            progress.onStageStart(Stage.REBUILD);
            File unsigned = new File(workDir, "unsigned.apk");
            new ApkBuilder().build(extracted.baseApk, transform.replacements,
                    extracted.externalNativeLibs, unsigned, log);
            progress.onStageDone(Stage.REBUILD);

            // ---- Stage: zipalign (performed during rebuild; recorded explicitly) ----
            progress.onStageStart(Stage.ZIPALIGN);
            log.info("Zip alignment applied during rebuild (4096B for .so, 4B otherwise).");
            progress.onStageDone(Stage.ZIPALIGN);

            // ---- Stage: sign ----
            progress.onStageStart(Stage.SIGN);
            KeystoreManager.SigningIdentity identity = req.signingIdentity;
            if (identity == null) {
                try {
                    identity = new KeystoreManager(context).getOrCreateDefault();
                } catch (Exception e) {
                    throw new PipelineException(Stage.SIGN,
                            "Could not obtain a signing key: " + e.getMessage(), e);
                }
            }
            File outputsDir;
            try {
                outputsDir = storage.outputsDir();
            } catch (Exception e) {
                throw new PipelineException(Stage.SIGN,
                        "Output location is not writable: " + e.getMessage(), e);
            }
            File signed = new File(outputsDir,
                    safeFileName(req.newPackage) + ".apk");
            int signingMinSdk = ApkSigner.signingMinSdk(minSdk);
            if (signingMinSdk != minSdk) {
                log.info("App declares minSdk " + minSdk + "; signing for API " + signingMinSdk
                        + "+ so the v1 signature uses SHA-256 (output won't install below "
                        + "Android 4.3).");
            }
            new ApkSigner().sign(unsigned, signed, identity, signingMinSdk, log);
            progress.onStageDone(Stage.SIGN);

            // ---- Stage: verify ----
            progress.onStageStart(Stage.VERIFY);
            ApkVerifier.VerifyResult verify =
                    new ApkVerifier().verify(signed, signingMinSdk, log);
            if (!verify.verified) {
                throw new PipelineException(Stage.VERIFY,
                        "The signed APK did not pass signature verification. "
                                + "Original APK has not been modified.");
            }
            progress.onStageDone(Stage.VERIFY);

            log.info("Done. Output: " + signed.getAbsolutePath());
            return new RepackageResult(signed, req.newPackage, transform.detectedOldPackage,
                    verify, transform.manifestStringsChanged, transform.dexTypesRemapped,
                    transform.resXmlStringsChanged, conflicts, identity.description, transform.labelChanged, req.newLabel, iconsWatermarked);
        } finally {
            if (!req.keepIntermediates) {
                ApkStorage.deleteTree(decodeDir);
                // Keep the signed output (in outputsDir); remove the rest of the run tree.
                ApkStorage.deleteTree(modDir);
                File unsigned = new File(workDir, "unsigned.apk");
                //noinspection ResultOfMethodCallIgnored
                unsigned.delete();
                ApkStorage.deleteTree(workDir);
                log.info("Cleaned up temporary files.");
            } else {
                log.info("Keeping intermediates at: " + workDir.getAbsolutePath());
            }
        }
    }

    private static String safeFileName(String pkg) {
        return pkg.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
