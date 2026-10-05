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

    public RepackageRequest(String originalPackage, String newPackage, int minSdk,
                            KeystoreManager.SigningIdentity signingIdentity,
                            boolean keepIntermediates) {
        this.originalPackage = originalPackage;
        this.newPackage = newPackage;
        this.minSdk = minSdk;
        this.signingIdentity = signingIdentity;
        this.keepIntermediates = keepIntermediates;
    }
}
