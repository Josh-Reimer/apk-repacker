package com.apkrepacker.apk;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;

import java.io.File;
import java.io.IOException;
import java.util.Enumeration;
import java.util.Locale;
import java.util.TreeSet;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Inspects a copied APK and produces a human-readable {@link ApkAnalysis}. */
public final class ApkAnalyzer {

    private final Context context;

    public ApkAnalyzer(Context context) {
        this.context = context.getApplicationContext();
    }

    public ApkAnalysis analyze(File apk, boolean hasSplits, BuildLog log)
            throws PipelineException {
        try (ZipFile zip = new ZipFile(apk)) {
            int dexCount = 0;
            boolean hasManifest = false;
            TreeSet<String> abis = new TreeSet<>();

            Enumeration<? extends ZipEntry> e = zip.entries();
            while (e.hasMoreElements()) {
                String name = e.nextElement().getName();
                if (name.equals("AndroidManifest.xml")) {
                    hasManifest = true;
                } else if (name.startsWith("classes") && name.endsWith(".dex")) {
                    dexCount++;
                } else if (name.startsWith("lib/") && name.endsWith(".so")) {
                    String[] parts = name.split("/");
                    if (parts.length >= 2) {
                        abis.add(parts[1]);
                    }
                }
            }

            if (!hasManifest) {
                throw new PipelineException(Stage.VALIDATE,
                        "APK has no AndroidManifest.xml — not a valid Android package.");
            }
            if (dexCount == 0) {
                log.warn("APK contains no classes*.dex files.");
            }

            PackageManager pm = context.getPackageManager();
            PackageInfo pi = pm.getPackageArchiveInfo(apk.getAbsolutePath(), 0);
            String pkg = "(unknown)";
            String versionName = "(unknown)";
            long versionCode = -1;
            int minSdk = -1;
            int targetSdk = -1;
            if (pi != null) {
                pkg = pi.packageName;
                versionName = pi.versionName != null ? pi.versionName : "(none)";
                versionCode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                        ? pi.getLongVersionCode() : pi.versionCode;
                ApplicationInfo ai = pi.applicationInfo;
                if (ai != null) {
                    targetSdk = ai.targetSdkVersion;
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        minSdk = ai.minSdkVersion;
                    }
                }
            } else {
                log.warn("PackageManager could not parse archive metadata; "
                        + "falling back to ZIP inspection only.");
            }

            List<String> issues = new ArrayList<>();
            if (hasSplits) {
                issues.add("Split APK: a full repackage requires the complete split set.");
            }
            if (dexCount > 1) {
                issues.add("Multiple DEX files (" + dexCount + "): all are rewritten together.");
            }
            if (!abis.isEmpty()) {
                issues.add("Native libraries present (" + String.join(", ", abis)
                        + "): preserved unchanged; native code may assume the old package.");
            }
            issues.add("Certificate differs from original: the output is re-signed with "
                    + "your key, so signature-dependent features may break.");

            log.info(String.format(Locale.US,
                    "Analyzed %s v%s (code %d), minSdk=%d targetSdk=%d, dex=%d, abis=%s, splits=%b",
                    pkg, versionName, versionCode, minSdk, targetSdk, dexCount, abis, hasSplits));

            return new ApkAnalysis(pkg, versionName, versionCode, minSdk, targetSdk,
                    dexCount, new ArrayList<>(abis), hasSplits, issues);
        } catch (IOException ex) {
            throw new PipelineException(Stage.VALIDATE,
                    "Could not open the APK as a ZIP: " + ex.getMessage(), ex);
        }
    }
}
