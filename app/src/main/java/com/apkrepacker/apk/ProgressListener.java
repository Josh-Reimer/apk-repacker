package com.apkrepacker.apk;

/** Callback for stage transitions during a repackage run. May be called off the UI thread. */
public interface ProgressListener {
    void onStageStart(Stage stage);
    void onStageDone(Stage stage);
}
