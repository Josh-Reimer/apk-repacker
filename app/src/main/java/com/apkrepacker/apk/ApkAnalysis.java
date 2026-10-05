package com.apkrepacker.apk;

import java.util.ArrayList;
import java.util.List;

/** Immutable result of {@link ApkAnalyzer}. */
public final class ApkAnalysis {
    public final String packageName;
    public final String versionName;
    public final long versionCode;
    public final int minSdk;
    public final int targetSdk;
    public final int dexCount;
    public final List<String> nativeAbis;
    public final boolean hasSplits;
    public final List<String> potentialIssues;

    public ApkAnalysis(String packageName, String versionName, long versionCode,
                       int minSdk, int targetSdk, int dexCount,
                       List<String> nativeAbis, boolean hasSplits,
                       List<String> potentialIssues) {
        this.packageName = packageName;
        this.versionName = versionName;
        this.versionCode = versionCode;
        this.minSdk = minSdk;
        this.targetSdk = targetSdk;
        this.dexCount = dexCount;
        this.nativeAbis = new ArrayList<>(nativeAbis);
        this.hasSplits = hasSplits;
        this.potentialIssues = new ArrayList<>(potentialIssues);
    }
}
