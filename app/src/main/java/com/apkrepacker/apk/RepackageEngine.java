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
            for (String abi : splitNativeAbis(extracted)) {
                if (!analysis.nativeAbis.contains(abi)) {
                    analysis.nativeAbis.add(abi + " (split)");
                }
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

    /** ABIs whose native libraries ship in split APKs (e.g. split_config.arm64_v8a). */
    private static List<String> splitNativeAbis(ApkExtractor.Extracted extracted) {
        List<String> abis = new java.util.ArrayList<>();
        for (File split : extracted.splits) {
            try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(split)) {
                java.util.Enumeration<? extends java.util.zip.ZipEntry> e = zip.entries();
                while (e.hasMoreElements()) {
                    String n = e.nextElement().getName();
                    if (n.startsWith("lib/") && n.endsWith(".so")) {
                        String abi = n.split("/")[1];
                        if (!abis.contains(abi)) {
                            abis.add(abi);
                        }
                    }
                }
            } catch (java.io.IOException ignored) {
                // Analysis is informational; extraction/decoding report real failures.
            }
        }
        return abis;
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
            // Index 0 is the base; splits follow in install order.
            List<File> inputs = new java.util.ArrayList<>();
            List<String> names = new java.util.ArrayList<>();
            inputs.add(extracted.baseApk);
            names.add("base");
            inputs.addAll(extracted.splits);
            names.addAll(extracted.splitNames);
            List<ApkDecoder.Decoded> decodedSet = new java.util.ArrayList<>();
            for (int i = 0; i < inputs.size(); i++) {
                File dir = new File(decodeDir, "apk-" + i);
                decodedSet.add(new ApkDecoder().decode(inputs.get(i), dir, log));
            }
            progress.onStageDone(Stage.DECODE);

            // ---- Stage: transform (manifest + dex + optional display name & icon) ----
            progress.onStageStart(Stage.TRANSFORM);
            List<String> originalAuthorities = installedAuthorities(req.originalPackage);
            PackageTransformer.Result transform = new PackageTransformer().transformSet(
                    decodedSet, names, analysis.packageName, req.newPackage, req.newLabel,
                    minSdk, originalAuthorities, modDir, log);
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
            List<File> unsignedSet = new java.util.ArrayList<>();
            for (int i = 0; i < inputs.size(); i++) {
                File unsigned = new File(workDir, i == 0 ? "unsigned.apk"
                        : "unsigned-" + i + ".apk");
                Map<String, File> additions = i == 0 ? extracted.externalNativeLibs
                        : java.util.Collections.emptyMap();
                new ApkBuilder().build(inputs.get(i), transform.replacementsPerApk.get(i),
                        additions, unsigned, log);
                unsignedSet.add(unsigned);
            }
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
            // A single APK keeps the flat outputs/<pkg>.apk; a split set gets its own folder
            // (outputs/<pkg>/base.apk, split_*.apk), replaced wholesale on each run.
            List<File> signedSet = new java.util.ArrayList<>();
            if (inputs.size() == 1) {
                signedSet.add(new File(outputsDir, safeFileName(req.newPackage) + ".apk"));
            } else {
                File setDir = new File(outputsDir, safeFileName(req.newPackage));
                ApkStorage.deleteTree(setDir);
                if (!setDir.mkdirs()) {
                    throw new PipelineException(Stage.SIGN,
                            "Output location is not writable: " + setDir);
                }
                for (String n : names) {
                    signedSet.add(new File(setDir, safeFileName(n) + ".apk"));
                }
            }
            File signed = signedSet.get(0);
            int signingMinSdk = ApkSigner.signingMinSdk(minSdk);
            if (signingMinSdk != minSdk) {
                log.info("App declares minSdk " + minSdk + "; signing for API " + signingMinSdk
                        + "+ so the v1 signature uses SHA-256 (output won't install below "
                        + "Android 4.3).");
            }
            // Android requires every APK of a split set to carry the same signing certificate.
            for (int i = 0; i < inputs.size(); i++) {
                new ApkSigner().sign(unsignedSet.get(i), signedSet.get(i), identity,
                        signingMinSdk, log);
            }
            progress.onStageDone(Stage.SIGN);

            // ---- Stage: verify ----
            progress.onStageStart(Stage.VERIFY);
            ApkVerifier.VerifyResult verify = null;
            for (int i = 0; i < signedSet.size(); i++) {
                ApkVerifier.VerifyResult v =
                        new ApkVerifier().verify(signedSet.get(i), signingMinSdk, log);
                if (!v.verified) {
                    throw new PipelineException(Stage.VERIFY, "The signed " + names.get(i)
                            + " APK did not pass signature verification. "
                            + "Original APK has not been modified.");
                }
                if (i == 0) {
                    verify = v;
                } else if (!v.signerSubject.equals(verify.signerSubject)) {
                    throw new PipelineException(Stage.VERIFY, "Split " + names.get(i)
                            + " is signed by a different certificate than the base.");
                }
            }
            progress.onStageDone(Stage.VERIFY);

            log.info("Done. Output: " + signed.getAbsolutePath()
                    + (signedSet.size() > 1 ? " (+ " + (signedSet.size() - 1) + " split APKs)" : ""));
            return new RepackageResult(signed, signedSet.subList(1, signedSet.size()),
                    req.newPackage, transform.detectedOldPackage,
                    verify, transform.manifestStringsChanged, transform.dexTypesRemapped,
                    transform.resXmlStringsChanged, conflicts, identity.description, transform.labelChanged, req.newLabel, iconsWatermarked);
        } finally {
            if (!req.keepIntermediates) {
                ApkStorage.deleteTree(decodeDir);
                // Keep the signed output (in outputsDir); remove the rest of the run tree.
                ApkStorage.deleteTree(modDir);
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
