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
            throw new PipelineException(Stage.SIGN,
                    "Signing failed: " + e.getMessage(), e);
        }
    }
}
