package com.apkrepacker.apk;

import java.io.File;

/** Outputs of a successful repackage run. */
public final class RepackageResult {
    public final File outputApk;
    public final String newPackage;
    public final String originalPackage;
    public final ApkVerifier.VerifyResult verification;
    public final int manifestStringsChanged;
    public final int dexTypesRemapped;
    public final String signerDescription;
    public final boolean labelChanged;
    public final String newLabel;
    public final int iconsWatermarked;

    public RepackageResult(File outputApk, String newPackage, String originalPackage,
                           ApkVerifier.VerifyResult verification,
                           int manifestStringsChanged, int dexTypesRemapped,
                           String signerDescription,
                           boolean labelChanged, String newLabel, int iconsWatermarked) {
        this.outputApk = outputApk;
        this.newPackage = newPackage;
        this.originalPackage = originalPackage;
        this.verification = verification;
        this.manifestStringsChanged = manifestStringsChanged;
        this.dexTypesRemapped = dexTypesRemapped;
        this.signerDescription = signerDescription;
        this.labelChanged = labelChanged;
        this.newLabel = newLabel;
        this.iconsWatermarked = iconsWatermarked;
    }
}
