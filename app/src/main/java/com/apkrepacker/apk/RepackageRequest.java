package com.apkrepacker.apk;

import com.apkrepacker.security.KeystoreManager;

/** Inputs for one repackage run. */
public final class RepackageRequest {
    public final String originalPackage;
    public final String newPackage;
    public final int minSdk;
    /** When null, the engine uses the app's default AndroidKeyStore identity. */
    public final KeystoreManager.SigningIdentity signingIdentity;
    public final boolean keepIntermediates;

    /** New application display name (android:label). Null/blank = leave unchanged. */
    public final String newLabel;
    /** Whether to stamp a corner-ribbon watermark onto the launcher icon. */
    public final boolean watermarkIcon;
    /** Watermark text (short). Null/blank defaults to "MOD". */
    public final String watermarkText;

    public RepackageRequest(String originalPackage, String newPackage, int minSdk,
                            KeystoreManager.SigningIdentity signingIdentity,
                            boolean keepIntermediates,
                            String newLabel, boolean watermarkIcon, String watermarkText) {
        this.originalPackage = originalPackage;
        this.newPackage = newPackage;
        this.minSdk = minSdk;
        this.signingIdentity = signingIdentity;
        this.keepIntermediates = keepIntermediates;
        this.newLabel = newLabel;
        this.watermarkIcon = watermarkIcon;
        this.watermarkText = watermarkText;
    }
}
