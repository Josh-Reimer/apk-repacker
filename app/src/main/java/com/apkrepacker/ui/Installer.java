package com.apkrepacker.ui;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.net.Uri;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/**
 * Hands a finished APK to Android's normal package installer via a FileProvider content
 * URI. We never install silently and never try to bypass the "install unknown apps"
 * restriction — the system UI asks the user, as it should.
 *
 * <p>A split set can't go through ACTION_VIEW (it carries one APK), so it is written into a
 * {@link PackageInstaller} session instead. Committing that session from a non-installer
 * app always yields {@link PackageInstaller#STATUS_PENDING_USER_ACTION}: the system's own
 * confirmation screen must be shown, and the user approves or declines there. The status
 * callback is delivered to a broadcast (not an activity) so it never re-enters the UI's
 * lifecycle.
 */
public final class Installer {

    public static final String AUTHORITY_SUFFIX = ".fileprovider";
    /** Broadcast action carrying PackageInstaller status back to the app. */
    public static final String ACTION_INSTALL_STATUS = "com.apkrepacker.INSTALL_STATUS";

    public static void launchInstall(Context context, File apk) {
        Uri uri = FileProvider.getUriForFile(
                context, context.getPackageName() + AUTHORITY_SUFFIX, apk);
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, "application/vnd.android.package-archive");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    /**
     * Streams the base APK and its splits into one install session and commits it. Blocks
     * while copying, so call off the UI thread. Status is broadcast with
     * {@link #ACTION_INSTALL_STATUS} to this app only.
     */
    public static void installSplitSet(Context context, List<File> apks) throws IOException {
        PackageInstaller pi = context.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        int sessionId = pi.createSession(params);
        try (PackageInstaller.Session session = pi.openSession(sessionId)) {
            for (File apk : apks) {
                try (InputStream in = new FileInputStream(apk);
                     OutputStream out = session.openWrite(apk.getName(), 0, apk.length())) {
                    byte[] buf = new byte[1 << 16];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                    }
                    session.fsync(out);
                }
            }
            Intent status = new Intent(ACTION_INSTALL_STATUS).setPackage(context.getPackageName());
            // Mutable: the installer fills in EXTRA_STATUS and the confirmation intent.
            PendingIntent callback = PendingIntent.getBroadcast(context, sessionId, status,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
            session.commit(callback.getIntentSender());
        } catch (IOException | RuntimeException e) {
            pi.abandonSession(sessionId);
            throw e;
        }
    }
}
