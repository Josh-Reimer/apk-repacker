package com.apkrepacker.apk;

/**
 * A stage-aware failure. Carries the stage that failed and a specific, user-facing
 * reason so the UI never has to fall back to "something went wrong".
 */
public class PipelineException extends Exception {
    public final Stage stage;

    public PipelineException(Stage stage, String reason) {
        super(reason);
        this.stage = stage;
    }

    public PipelineException(Stage stage, String reason, Throwable cause) {
        super(reason, cause);
        this.stage = stage;
    }
}
