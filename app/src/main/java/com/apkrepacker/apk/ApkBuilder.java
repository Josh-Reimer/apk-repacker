package com.apkrepacker.apk;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Enumeration;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Rebuilds an APK from the original, substituting the transformed entries, and performs
 * zip alignment in the same pass.
 *
 * <ul>
 *   <li>Entries copied verbatim keep their original compression method.</li>
 *   <li>Replaced entries (manifest, DEX) are re-deflated.</li>
 *   <li>Old v1 signature files (META-INF/*.SF/.RSA/.DSA/.EC, MANIFEST.MF) are dropped —
 *       the output is re-signed.</li>
 *   <li>STORED entries are aligned: 4096 bytes for uncompressed {@code .so}, 4 bytes
 *       otherwise, so the result is zipalign-clean before signing.</li>
 * </ul>
 */
public final class ApkBuilder {

    private static final int ALIGN_DEFAULT = 4;
    private static final int ALIGN_SO = 4096;

    private static final class Counting extends FilterOutputStream {
        long count;
        Counting(OutputStream out) { super(out); }
        @Override public void write(int b) throws IOException { out.write(b); count++; }
        @Override public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len); count += len;
        }
    }

    public File build(File originalApk, Map<String, File> replacements,
                      File outApk, BuildLog log) throws PipelineException {
        try (ZipFile in = new ZipFile(originalApk);
             Counting counting = new Counting(
                     new BufferedOutputStream(new FileOutputStream(outApk)));
             ZipOutputStream out = new ZipOutputStream(counting)) {

            out.setLevel(Deflater.BEST_COMPRESSION);
            int copied = 0, replaced = 0, dropped = 0;

            Enumeration<? extends ZipEntry> entries = in.entries();
            while (entries.hasMoreElements()) {
                ZipEntry src = entries.nextElement();
                String name = src.getName();

                if (isOldSignatureFile(name)) {
                    dropped++;
                    continue;
                }
                if (src.isDirectory()) {
                    continue; // directory entries are not needed; files carry their paths
                }

                File replacement = replacements.get(name);
                if (replacement != null) {
                    writeDeflated(out, counting, name, Files.readAllBytes(replacement.toPath()));
                    replaced++;
                } else if (src.getMethod() == ZipEntry.STORED) {
                    writeStoredAligned(in, src, out, counting, name);
                    copied++;
                } else {
                    writeDeflated(out, counting, name, readAll(in, src));
                    copied++;
                }
            }

            out.finish();
            log.info("Rebuilt APK: " + replaced + " replaced, " + copied
                    + " copied, " + dropped + " old-signature entries dropped.");
            return outApk;
        } catch (IOException e) {
            throw new PipelineException(Stage.REBUILD,
                    "Failed to rebuild the APK: " + e.getMessage(), e);
        }
    }

    private static boolean isOldSignatureFile(String name) {
        String u = name.toUpperCase(java.util.Locale.US);
        if (!u.startsWith("META-INF/")) {
            return false;
        }
        return u.equals("META-INF/MANIFEST.MF")
                || u.endsWith(".SF") || u.endsWith(".RSA")
                || u.endsWith(".DSA") || u.endsWith(".EC");
    }

    private static void writeDeflated(ZipOutputStream out, Counting counting,
                                      String name, byte[] data) throws IOException {
        ZipEntry e = new ZipEntry(name);
        e.setMethod(ZipEntry.DEFLATED);
        out.putNextEntry(e);
        out.write(data);
        out.closeEntry();
    }

    private static void writeStoredAligned(ZipFile in, ZipEntry src, ZipOutputStream out,
                                           Counting counting, String name) throws IOException {
        byte[] data = readAll(in, src);
        int align = name.endsWith(".so") ? ALIGN_SO : ALIGN_DEFAULT;

        ZipEntry e = new ZipEntry(name);
        e.setMethod(ZipEntry.STORED);
        e.setSize(data.length);
        e.setCompressedSize(data.length);
        CRC32 crc = new CRC32();
        crc.update(data);
        e.setCrc(crc.getValue());

        int nameLen = name.getBytes(StandardCharsets.UTF_8).length;
        long headerStart = counting.count;
        long dataStart = headerStart + 30 + nameLen; // local header has no extra yet
        int pad = (int) ((align - (dataStart % align)) % align);
        if (pad > 0) {
            e.setExtra(new byte[pad]); // zero padding in the local-header extra field
        }

        out.putNextEntry(e);
        out.write(data);
        out.closeEntry();
    }

    private static byte[] readAll(ZipFile zip, ZipEntry entry) throws IOException {
        try (InputStream is = zip.getInputStream(entry)) {
            java.io.ByteArrayOutputStream bos =
                    new java.io.ByteArrayOutputStream(Math.max(32, (int) entry.getSize()));
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = is.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        }
    }
}
