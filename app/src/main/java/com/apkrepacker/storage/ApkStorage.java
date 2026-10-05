package com.apkrepacker.storage;

import android.content.Context;

import java.io.File;
import java.io.IOException;

/**
 * Owns the app-private working areas. Nothing here ever touches another app's files or
 * the original installed APK.
 *
 * <pre>
 *   filesDir/work/&lt;runId&gt;/        per-run working directory (inputs + outputs)
 *   cacheDir/decode/&lt;runId&gt;/      scratch for decoded entries
 * </pre>
 */
public final class ApkStorage {

    private final Context context;

    public ApkStorage(Context context) {
        this.context = context.getApplicationContext();
    }

    public File newRunDir(String runId) throws IOException {
        File dir = new File(new File(context.getFilesDir(), "work"), runId);
        if (!dir.mkdirs() && !dir.isDirectory()) {
            throw new IOException("Cannot create working directory: " + dir);
        }
        return dir;
    }

    public File newDecodeDir(String runId) throws IOException {
        // Deliberately under filesDir (not cacheDir): these decoded entries are live
        // inputs for the rest of the run, and the OS can evict cacheDir at any moment
        // under storage/memory pressure — which a large multi-DEX rewrite can trigger.
        File dir = new File(new File(new File(context.getFilesDir(), "work"), runId), "decode");
        if (!dir.mkdirs() && !dir.isDirectory()) {
            throw new IOException("Cannot create decode directory: " + dir);
        }
        return dir;
    }

    /** Directory exposed via FileProvider for hand-off to the package installer. */
    public File outputsDir() throws IOException {
        File dir = new File(context.getFilesDir(), "outputs");
        if (!dir.mkdirs() && !dir.isDirectory()) {
            throw new IOException("Cannot create outputs directory: " + dir);
        }
        return dir;
    }

    /** Recursively delete a working tree. Safe to call on a missing path. */
    public static void deleteTree(File root) {
        if (root == null || !root.exists()) {
            return;
        }
        File[] children = root.listFiles();
        if (children != null) {
            for (File c : children) {
                deleteTree(c);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        root.delete();
    }
}
