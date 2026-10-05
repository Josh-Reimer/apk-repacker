package com.apkrepacker.apk.axml;

/** Thrown when binary XML cannot be parsed or rewritten. */
public class AxmlException extends RuntimeException {
    public AxmlException(String message) {
        super(message);
    }

    public AxmlException(String message, Throwable cause) {
        super(message, cause);
    }
}
