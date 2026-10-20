package com.apkrepacker.apk;

import com.apkrepacker.security.KeystoreManager;

import java.io.File;
import java.util.Collections;
import java.util.List;

/**
 * Signs an APK using Google's apksig with APK Signature Scheme v1 + v2 + v3.
 *
 * <p>The original developer's signature cannot be preserved — the output is signed with
 * the user's selected key. This is surfaced in the UI, not hidden.
 */
public final class ApkSigner {

    /**
     * apksig signs v1 (JAR) with SHA-1 for minSdk below 18. The AndroidKeyStore key is only
     * authorized for SHA-256/SHA-512 (and existing keys can't gain digests), so signing an
     * app with e.g. minSdk 14 failed. Signing for at least API 18 makes v1 use SHA-256; the
     * only cost is that the output no longer installs on Android 4.2 and older.
     */
    public static final int MIN_SIGNING_SDK = 18;

    /** The min SDK to sign and verify for, given the app's declared min SDK. */
    public static int signingMinSdk(int appMinSdk) {
        return Math.max(appMinSdk, MIN_SIGNING_SDK);
    }

    public File sign(File unsignedApk, File signedApk,
                     KeystoreManager.SigningIdentity identity, int minSdk, BuildLog log)
            throws PipelineException {
        try {
            com.android.apksig.ApkSigner.SignerConfig signerConfig =
                    new com.android.apksig.ApkSigner.SignerConfig.Builder(
                            "apkrepacker",
                            identity.privateKey,
                            (List<java.security.cert.X509Certificate>) identity.certificateChain)
                            .build();

            com.android.apksig.ApkSigner.Builder builder =
                    new com.android.apksig.ApkSigner.Builder(
                            Collections.singletonList(signerConfig))
                            .setInputApk(unsignedApk)
                            .setOutputApk(signedApk)
                            .setV1SigningEnabled(true)
                            .setV2SigningEnabled(true)
                            .setV3SigningEnabled(true);
            if (minSdk > 0) {
                builder.setMinSdkVersion(minSdk);
            }

            builder.build().sign();
            log.info("Signed APK with v1+v2+v3 schemes using: " + identity.description);
            return signedApk;
        } catch (Exception e) {
            // apksig wraps the useful part ("Failed to sign using signer X"); surface the cause.
            Throwable root = e;
            while (root.getCause() != null && root.getCause() != root) {
                root = root.getCause();
            }
            String reason = root == e ? String.valueOf(e.getMessage())
                    : e.getMessage() + " (" + root.getClass().getSimpleName() + ": "
                    + root.getMessage() + ")";
            throw new PipelineException(Stage.SIGN, "Signing failed: " + reason, e);
        }
    }
}
