package com.apkrepacker.storage;

import java.io.File;
import java.io.IOException;

/**
 * Hardening helpers for treating APK/ZIP contents as untrusted input.
 *
 * <ul>
 *   <li>Rejects entries whose resolved path escapes the destination (zip-slip / {@code ../}).</li>
 *   <li>Enforces per-entry and total uncompressed-size ceilings (decompression bombs).</li>
 * </ul>
 */
public final class SafeZip {

    /** Default ceiling for a single decompressed entry: 512 MiB. */
    public static final long MAX_ENTRY_BYTES = 512L * 1024 * 1024;
    /** Default ceiling for the whole archive once decompressed: 2 GiB. */
    public static final long MAX_TOTAL_BYTES = 2L * 1024 * 1024 * 1024;

    private SafeZip() {}

    /**
     * Resolves {@code entryName} under {@code destDir}, rejecting absolute paths, {@code ..}
     * traversal, and anything that would land outside {@code destDir}.
     */
    public static File resolveSafely(File destDir, String entryName) throws IOException {
        if (entryName == null || entryName.isEmpty()) {
            throw new IOException("Empty ZIP entry name");
        }
        if (entryName.startsWith("/") || entryName.startsWith("\\") || entryName.contains("..")) {
            throw new IOException("Unsafe ZIP entry (path traversal): " + entryName);
        }
        File canonicalDest = destDir.getCanonicalFile();
        File target = new File(canonicalDest, entryName).getCanonicalFile();
        String destPath = canonicalDest.getPath() + File.separator;
        if (!target.getPath().equals(canonicalDest.getPath())
                && !target.getPath().startsWith(destPath)) {
            throw new IOException("ZIP entry escapes working directory: " + entryName);
        }
        return target;
    }

    public static void checkEntrySize(String name, long bytes) throws IOException {
        if (bytes > MAX_ENTRY_BYTES) {
            throw new IOException("ZIP entry too large (possible bomb): " + name
                    + " = " + bytes + " bytes");
        }
    }

    public static void checkTotalSize(long bytes) throws IOException {
        if (bytes > MAX_TOTAL_BYTES) {
            throw new IOException("Archive expands beyond the safety ceiling ("
                    + MAX_TOTAL_BYTES + " bytes)");
        }
    }
}
