package com.apkrepacker.apk;

/** The explicit, ordered stages of a repackage run (mirrors the pipeline in CLAUDE.md). */
public enum Stage {
    EXTRACT("Extract APK"),
    VALIDATE("Validate input"),
    DECODE("Decode APK"),
    TRANSFORM("Modify package identity"),
    REBUILD("Rebuild APK"),
    ZIPALIGN("Zipalign"),
    SIGN("Sign APK"),
    VERIFY("Verify APK");

    public final String label;

    Stage(String label) {
        this.label = label;
    }
}
