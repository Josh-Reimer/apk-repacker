package com.apkrepacker.ui;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import androidx.core.content.FileProvider;

import java.io.File;

/**
 * Hands a finished APK to Android's normal package installer via a FileProvider content
 * URI. We never install silently and never try to bypass the "install unknown apps"
 * restriction — the system UI asks the user, as it should.
 */
public final class Installer {

    public static final String AUTHORITY_SUFFIX = ".fileprovider";

    public static void launchInstall(Context context, File apk) {
        Uri uri = FileProvider.getUriForFile(
                context, context.getPackageName() + AUTHORITY_SUFFIX, apk);
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, "application/vnd.android.package-archive");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }
}
