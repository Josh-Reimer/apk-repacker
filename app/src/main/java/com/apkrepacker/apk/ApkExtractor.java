package com.apkrepacker.apk;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

import com.apkrepacker.storage.SafeZip;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;

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
        /**
         * Native libraries the installed app loads from outside its APK, keyed by the APK
         * entry they should become ({@code lib/<abi>/libfoo.so}). Preinstalled system apps
         * commonly ship this way; without them the rebuilt APK is missing native code.
         */
        public final Map<String, File> externalNativeLibs;

        Extracted(String originalPackage, File baseApk, List<File> splits,
                  Map<String, File> externalNativeLibs) {
            this.originalPackage = originalPackage;
            this.baseApk = baseApk;
            this.splits = splits;
            this.hasSplits = !splits.isEmpty();
            this.externalNativeLibs = Collections.unmodifiableMap(externalNativeLibs);
        }

        /** ABIs of {@link #externalNativeLibs}, in first-seen order. */
        public List<String> externalNativeAbis() {
            List<String> abis = new ArrayList<>();
            for (String entry : externalNativeLibs.keySet()) {
                String abi = entry.split("/")[1];
                if (!abis.contains(abi)) {
                    abis.add(abi);
                }
            }
            return abis;
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

            Map<String, File> nativeLibs = copyExternalNativeLibs(ai, baseCopy, workDir, log);

            return new Extracted(packageName, baseCopy, splitCopies, nativeLibs);
        } catch (PackageManager.NameNotFoundException e) {
            throw new PipelineException(Stage.EXTRACT,
                    "No installed application with package name: " + packageName, e);
        } catch (IOException e) {
            throw new PipelineException(Stage.EXTRACT,
                    "Failed to copy the installed APK: " + e.getMessage(), e);
        }
    }

    /**
     * Copies {@code .so} files from {@link ApplicationInfo#nativeLibraryDir} that the APK
     * itself does not contain. For apps whose libraries came from the APK this finds nothing
     * new; for preinstalled apps with libraries under e.g. /product/app/X/lib/arm64 it is the
     * only copy of that native code.
     */
    private static Map<String, File> copyExternalNativeLibs(ApplicationInfo ai, File apk,
                                                            File workDir, BuildLog log)
            throws IOException {
        Map<String, File> libs = new LinkedHashMap<>();
        if (ai.nativeLibraryDir == null) {
            return libs;
        }
        File dir = new File(ai.nativeLibraryDir);
        File[] files = dir.listFiles((d, name) -> name.endsWith(".so"));
        if (files == null || files.length == 0) {
            return libs;
        }
        String abi = abiForLibDir(dir.getName());
        if (abi == null) {
            log.warn("Native libraries in " + dir + " use an unrecognized ABI directory; "
                    + "they were not bundled.");
            return libs;
        }
        try (ZipFile zip = new ZipFile(apk)) {
            for (File f : files) {
                String entry = "lib/" + abi + "/" + f.getName();
                if (zip.getEntry(entry) != null) {
                    continue;
                }
                SafeZip.checkEntrySize(entry, f.length());
                File dst = SafeZip.resolveSafely(new File(workDir, "native"), entry);
                //noinspection ResultOfMethodCallIgnored
                dst.getParentFile().mkdirs();
                copy(f, dst, log);
                libs.put(entry, dst);
            }
        }
        if (!libs.isEmpty()) {
            log.warn("Found " + libs.size() + " native librar" + (libs.size() == 1 ? "y" : "ies")
                    + " installed outside the APK in " + dir
                    + "; bundling them into the output as " + "lib/" + abi + "/.");
        }
        return libs;
    }

    /** Maps the install-time ISA directory name to the APK's lib/ ABI directory name. */
    private static String abiForLibDir(String isa) {
        switch (isa) {
            case "arm64": return "arm64-v8a";
            case "arm": return "armeabi-v7a";
            case "x86": return "x86";
            case "x86_64": return "x86_64";
            case "riscv64": return "riscv64";
            default: return null;
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
