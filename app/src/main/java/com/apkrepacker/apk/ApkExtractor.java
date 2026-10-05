package com.apkrepacker.apk;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Copies an installed application's APK out of its read-only install location into our
 * private working directory. The original is only ever <em>read</em>.
 */
public final class ApkExtractor {

    public static final class Extracted {
        public final String originalPackage;
        public final File baseApk;        // the copied base APK (our writable input)
        public final List<File> splits;   // copied split APKs, if any
        public final boolean hasSplits;

        Extracted(String originalPackage, File baseApk, List<File> splits) {
            this.originalPackage = originalPackage;
            this.baseApk = baseApk;
            this.splits = splits;
            this.hasSplits = !splits.isEmpty();
        }
    }

    private final Context context;

    public ApkExtractor(Context context) {
        this.context = context.getApplicationContext();
    }

    public Extracted extract(String packageName, File workDir, BuildLog log)
            throws PipelineException {
        try {
            PackageManager pm = context.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(packageName, 0);

            if (ai.sourceDir == null) {
                throw new PipelineException(Stage.EXTRACT,
                        "Application has no sourceDir (not a normal installed APK).");
            }

            File src = new File(ai.sourceDir);
            File baseCopy = new File(workDir, "base-original.apk");
            copy(src, baseCopy, log);
            log.info("Copied base APK from " + ai.sourceDir + " (" + baseCopy.length() + " bytes)");

            List<File> splitCopies = new ArrayList<>();
            String[] splitDirs = ai.splitSourceDirs;
            if (splitDirs != null) {
                for (int i = 0; i < splitDirs.length; i++) {
                    File s = new File(splitDirs[i]);
                    File dst = new File(workDir, "split-" + i + "-original.apk");
                    copy(s, dst, log);
                    splitCopies.add(dst);
                    log.warn("Split APK detected and copied: " + s.getName());
                }
            }

            if (!splitCopies.isEmpty()) {
                log.warn("This application uses " + splitCopies.size()
                        + " split APK(s). The base APK is NOT a complete standalone app; "
                        + "a full repackage would require processing the entire split set.");
            }

            return new Extracted(packageName, baseCopy, splitCopies);
        } catch (PackageManager.NameNotFoundException e) {
            throw new PipelineException(Stage.EXTRACT,
                    "No installed application with package name: " + packageName, e);
        } catch (IOException e) {
            throw new PipelineException(Stage.EXTRACT,
                    "Failed to copy the installed APK: " + e.getMessage(), e);
        }
    }

    private static void copy(File src, File dst, BuildLog log) throws IOException {
        try (InputStream in = new FileInputStream(src);
             OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        }
    }
}
