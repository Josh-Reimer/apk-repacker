package com.apkrepacker.apk;

import java.io.File;

/** Outputs of a successful repackage run. */
public final class RepackageResult {
    public final File outputApk;
    /** Signed split APKs to install together with {@link #outputApk}; empty if none. */
    public final java.util.List<File> splitApks;
    public final String newPackage;
    public final String originalPackage;
    public final ApkVerifier.VerifyResult verification;
    public final int manifestStringsChanged;
    public final int dexTypesRemapped;
    public final int resXmlStringsChanged;
    /** Final authorities already owned by an installed app; the install will be refused. */
    public final java.util.List<String> providerConflicts;
    public final String signerDescription;
    public final boolean labelChanged;
    public final String newLabel;
    public final int iconsWatermarked;

    public RepackageResult(File outputApk, java.util.List<File> splitApks, String newPackage, String originalPackage,
                           ApkVerifier.VerifyResult verification,
                           int manifestStringsChanged, int dexTypesRemapped,
                           int resXmlStringsChanged, java.util.List<String> providerConflicts,
                           String signerDescription,
                           boolean labelChanged, String newLabel, int iconsWatermarked) {
        this.outputApk = outputApk;
        this.splitApks = splitApks;
        this.newPackage = newPackage;
        this.originalPackage = originalPackage;
        this.verification = verification;
        this.manifestStringsChanged = manifestStringsChanged;
        this.dexTypesRemapped = dexTypesRemapped;
        this.resXmlStringsChanged = resXmlStringsChanged;
        this.providerConflicts = providerConflicts;
        this.signerDescription = signerDescription;
        this.labelChanged = labelChanged;
        this.newLabel = newLabel;
        this.iconsWatermarked = iconsWatermarked;
    }
}
