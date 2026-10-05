package com.apkrepacker.apk;

import com.apkrepacker.storage.SafeZip;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * "Decode" stage: pulls the entries we intend to rewrite (the binary manifest and the
 * DEX files) out of the APK into a scratch directory, with full zip-slip and size-bomb
 * protection. Everything else stays inside the original APK and is copied verbatim at
 * rebuild time — we never fully explode and recompile resources we don't need to touch.
 */
public final class ApkDecoder {

    public static final class Decoded {
        public final File manifestFile;      // extracted AndroidManifest.xml (binary)
        public final List<File> dexFiles;    // extracted classesN.dex, in entry order
        public final List<String> dexEntryNames;

        Decoded(File manifestFile, List<File> dexFiles, List<String> dexEntryNames) {
            this.manifestFile = manifestFile;
            this.dexFiles = dexFiles;
            this.dexEntryNames = dexEntryNames;
        }
    }

    public Decoded decode(File apk, File decodeDir, BuildLog log) throws PipelineException {
        File manifestOut = null;
        List<File> dexFiles = new ArrayList<>();
        List<String> dexNames = new ArrayList<>();
        long total = 0;

        try (ZipFile zip = new ZipFile(apk)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName();
                boolean isManifest = name.equals("AndroidManifest.xml");
                boolean isDex = name.matches("classes\\d*\\.dex");
                if (!isManifest && !isDex) {
                    continue;
                }

                File out = SafeZip.resolveSafely(decodeDir, name);
                //noinspection ResultOfMethodCallIgnored
                out.getParentFile().mkdirs();
                long written = extract(zip, entry, out);
                SafeZip.checkEntrySize(name, written);
                total += written;
                SafeZip.checkTotalSize(total);

                if (isManifest) {
                    manifestOut = out;
                    log.info("Decoded AndroidManifest.xml (" + written + " bytes)");
                } else {
                    dexFiles.add(out);
                    dexNames.add(name);
                    log.info("Decoded " + name + " (" + written + " bytes)");
                }
            }
        } catch (IOException e) {
            throw new PipelineException(Stage.DECODE,
                    "Failed to decode APK entries: " + e.getMessage(), e);
        }

        if (manifestOut == null) {
            throw new PipelineException(Stage.DECODE, "AndroidManifest.xml not found in APK.");
        }
        return new Decoded(manifestOut, dexFiles, dexNames);
    }

    private static long extract(ZipFile zip, ZipEntry entry, File out) throws IOException {
        long written = 0;
        try (InputStream in = zip.getInputStream(entry);
             OutputStream os = new BufferedOutputStream(new FileOutputStream(out))) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) {
                os.write(buf, 0, n);
                written += n;
                // Guard against a lying/huge entry mid-stream.
                SafeZip.checkEntrySize(entry.getName(), written);
            }
        }
        return written;
    }
}
