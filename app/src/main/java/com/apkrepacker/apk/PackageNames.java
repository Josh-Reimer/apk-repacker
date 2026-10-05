package com.apkrepacker.apk;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/** Validation of Android application IDs / Java package names (Stage 1). */
public final class PackageNames {

    // Each label: letter/underscore start, then letters/digits/underscore.
    private static final Pattern LABEL = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private static final Set<String> JAVA_KEYWORDS = new HashSet<>();
    static {
        for (String k : new String[]{
                "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char",
                "class", "const", "continue", "default", "do", "double", "else", "enum",
                "extends", "final", "finally", "float", "for", "goto", "if", "implements",
                "import", "instanceof", "int", "interface", "long", "native", "new", "package",
                "private", "protected", "public", "return", "short", "static", "strictfp",
                "super", "switch", "synchronized", "this", "throw", "throws", "transient",
                "try", "void", "volatile", "while", "true", "false", "null"}) {
            JAVA_KEYWORDS.add(k);
        }
    }

    private PackageNames() {}

    /**
     * Returns null if {@code name} is a valid Android application ID, otherwise a specific
     * human-readable reason why it is not.
     *
     * <p>Android additionally requires at least two segments and that no segment is a Java
     * keyword, matching the AGP applicationId rules.
     */
    public static String validate(String name) {
        if (name == null || name.isEmpty()) {
            return "Package name is empty.";
        }
        if (name.startsWith(".") || name.endsWith(".")) {
            return "Package name cannot start or end with '.'";
        }
        if (name.contains("..")) {
            return "Package name cannot contain an empty segment ('..').";
        }
        String[] parts = name.split("\\.", -1);
        if (parts.length < 2) {
            return "Package name must have at least two segments (e.g. com.example.app).";
        }
        for (String p : parts) {
            if (!LABEL.matcher(p).matches()) {
                return "Invalid segment \"" + p + "\": must start with a letter or '_' "
                        + "and contain only letters, digits, or '_'.";
            }
            if (JAVA_KEYWORDS.contains(p)) {
                return "Segment \"" + p + "\" is a reserved Java keyword.";
            }
        }
        return null;
    }

    public static boolean isValid(String name) {
        return validate(name) == null;
    }
}
