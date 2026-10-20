package com.apkrepacker.apk;

import com.apkrepacker.apk.axml.AxmlException;
import com.apkrepacker.apk.axml.AxmlFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Stage 3/4/5 orchestrator: given the decoded manifest and DEX files, produce the set of
 * replacement entries (by ZIP entry name) that carry the new package identity.
 *
 * <p>Binary XML under res/ is rewritten only where a pooled string names a class the DEX
 * step renamed (custom views, fragments, preferences). resources.arsc, assets, and native
 * libraries are intentionally left untouched and copied verbatim at rebuild time.
 */
public final class PackageTransformer {

    public static final class Result {
        /** ZIP entry name -> file containing its new bytes. */
        public final Map<String, File> replacements;
        public final int manifestStringsChanged;
        public final int dexTypesRemapped;
        public final int resXmlStringsChanged;
        public final String detectedOldPackage;
        public final boolean labelChanged;
        /** Every provider authority the output declares differently from the original. */
        public final Map<String, String> authorityRenames;

        Result(Map<String, File> replacements, int manifestStringsChanged,
               int dexTypesRemapped, int resXmlStringsChanged,
               String detectedOldPackage, boolean labelChanged,
               Map<String, String> authorityRenames) {
            this.authorityRenames = authorityRenames;
            this.replacements = replacements;
            this.manifestStringsChanged = manifestStringsChanged;
            this.dexTypesRemapped = dexTypesRemapped;
            this.resXmlStringsChanged = resXmlStringsChanged;
            this.detectedOldPackage = detectedOldPackage;
            this.labelChanged = labelChanged;
        }
    }

    /**
     * @param declaredOldPackage the package the caller believes is original (from analysis);
     *                           used as a cross-check against the manifest.
     */
    public Result transform(ApkDecoder.Decoded decoded,
                            String declaredOldPackage,
                            String newPackage,
                            String newLabel,
                            int minSdk,
                            File outDir,
                            BuildLog log) throws PipelineException {
        return transform(decoded, declaredOldPackage, newPackage, newLabel, minSdk,
                java.util.Collections.emptyList(), outDir, log);
    }

    /**
     * @param installedAuthorities the original app's provider authorities as resolved by
     *                             PackageManager. Needed because a manifest may declare an
     *                             authority by resource reference ({@code @string/...}), whose
     *                             value lives in resources.arsc rather than the manifest.
     */
    public Result transform(ApkDecoder.Decoded decoded,
                            String declaredOldPackage,
                            String newPackage,
                            String newLabel,
                            int minSdk,
                            List<String> installedAuthorities,
                            File outDir,
                            BuildLog log) throws PipelineException {
        Map<String, File> replacements = new HashMap<>();

        // ---- Manifest: read true package, rename pooled strings ----
        String oldPackage;
        int manifestChanged;
        boolean labelChanged = false;
        // Old -> new provider authority, for the manifest's renamed authorities only.
        Map<String, String> authorityRenames = new java.util.LinkedHashMap<>();
        File newManifest = new File(outDir, "AndroidManifest.xml");
        try {
            byte[] bytes = Files.readAllBytes(decoded.manifestFile.toPath());
            AxmlFile axml = AxmlFile.parse(bytes);
            oldPackage = axml.getManifestPackage();
            log.info("Manifest declares package: " + oldPackage);
            if (declaredOldPackage != null && !declaredOldPackage.equals(oldPackage)) {
                log.warn("Analyzer reported '" + declaredOldPackage
                        + "' but manifest says '" + oldPackage + "'; using the manifest value.");
            }
            if (oldPackage.equals(newPackage)) {
                throw new PipelineException(Stage.TRANSFORM,
                        "New package name is identical to the original (" + oldPackage + ").");
            }
            List<Integer> authIdx =
                    axml.getAttrStringIndices("provider", AxmlFile.ATTR_AUTHORITIES);
            List<String> oldAuths = new java.util.ArrayList<>();
            for (int idx : authIdx) {
                oldAuths.add(axml.strings().get(idx));
            }
            manifestChanged = axml.renamePackage(oldPackage, newPackage);
            for (int i = 0; i < authIdx.size(); i++) {
                // android:authorities may list several, separated by ';'.
                String[] before = oldAuths.get(i).split(";");
                String[] after = axml.strings().get(authIdx.get(i)).split(";");
                for (int j = 0; j < before.length && j < after.length; j++) {
                    if (!before[j].equals(after[j])) {
                        authorityRenames.put(before[j], after[j]);
                    }
                }
            }
            if (newLabel != null && !newLabel.trim().isEmpty()) {
                labelChanged = axml.setApplicationLabel(newLabel.trim());
                if (labelChanged) {
                    log.info("Manifest: set application display name to \"" + newLabel.trim()
                            + "\" (components with their own label keep it).");
                } else {
                    log.warn("Manifest: <application> has no label attribute; display name "
                            + "left unchanged.");
                }
            }
            Files.write(newManifest.toPath(), axml.toByteArray());
            log.info("Manifest: rewrote " + manifestChanged + " pooled string(s); "
                    + "relative component names (\".Foo\") resolve under the new package.");
            replacements.put("AndroidManifest.xml", newManifest);
        } catch (AxmlException e) {
            throw new PipelineException(Stage.TRANSFORM,
                    "The APK contains an unsupported binary manifest structure: "
                            + e.getMessage(), e);
        } catch (IOException e) {
            throw new PipelineException(Stage.TRANSFORM,
                    "Failed to read/write the manifest: " + e.getMessage(), e);
        }

        // ---- Authorities defined in resources (android:authorities="@string/...") ----
        Map<String, String> arscRenames = new java.util.LinkedHashMap<>();
        for (String declared : installedAuthorities) {
            for (String a : declared.split(";")) {
                if (authorityRenames.containsKey(a) || authorityRenames.containsValue(a)) {
                    continue; // inline in the manifest; already renamed above
                }
                if (a.equals(oldPackage) || a.startsWith(oldPackage + ".")) {
                    arscRenames.put(a, newPackage + a.substring(oldPackage.length()));
                }
            }
        }
        if (!arscRenames.isEmpty()) {
            renameResourceAuthorities(decoded, arscRenames, outDir, replacements, log);
            authorityRenames.putAll(arscRenames);
        }

        // ---- DEX: conservative type-descriptor remap ----
        // Only run the (heavy) dexlib2 rebuild on DEX files that actually mention the old
        // package. A DEX with no occurrence of the old internal path cannot contain a type
        // to remap, so we skip it and let ApkBuilder copy the original entry verbatim. This
        // avoids rewriting large library DEX files that don't reference the app's namespace.
        int totalRemapped = 0;
        byte[] needle = ("L" + oldPackage.replace('.', '/') + "/")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        // Code that names its own provider (FileProvider.getUriForFile(ctx, AUTHORITY, f),
        // content:// URIs) holds the authority as a string literal. Once the manifest declares
        // the new authority, the old literal points at nothing — or at the original app's
        // provider — so exactly those literals follow the manifest. Nothing else does.
        byte[] dottedNeedle = oldPackage.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        DexTransformer dex = new DexTransformer(oldPackage, newPackage, minSdk, authorityRenames);
        List<File> dexFiles = decoded.dexFiles;
        List<String> dexNames = decoded.dexEntryNames;
        for (int i = 0; i < dexFiles.size(); i++) {
            File in = dexFiles.get(i);
            String name = dexNames.get(i);
            try {
                byte[] bytes = Files.readAllBytes(in.toPath());
                if (!containsBytes(bytes, needle) && (authorityRenames.isEmpty()
                        || !containsBytes(bytes, dottedNeedle))) {
                    log.info("DEX " + name + ": no reference to the old package; copied as-is.");
                    continue;
                }
            } catch (IOException e) {
                throw new PipelineException(Stage.TRANSFORM,
                        "Failed to scan " + name + ": " + e.getMessage(), e);
            }
            File out = new File(outDir, name);
            int n = dex.rewrite(in, out, log);
            totalRemapped += n;
            replacements.put(name, out);
        }

        for (Map.Entry<String, String> a : authorityRenames.entrySet()) {
            log.info("Provider authority: " + a.getKey() + " -> " + a.getValue());
        }
        if (!authorityRenames.isEmpty()) {
            log.info("DEX: renamed " + dex.stringsRenamed() + " string constant(s) that exactly "
                    + "match a renamed provider authority; other strings were left unchanged.");
        }

        if (totalRemapped == 0) {
            log.warn("No DEX type references matched the original package prefix. "
                    + "The app may namespace its classes differently from its applicationId; "
                    + "code references were left unchanged.");
        }

        int resXmlChanged = rewriteResourceXml(decoded, oldPackage, newPackage,
                dex.renamedClasses(), outDir, replacements, log);

        return new Result(replacements, manifestChanged, totalRemapped, resXmlChanged,
                oldPackage, labelChanged, authorityRenames);
    }

    /**
     * Rewrites exact string values in resources.arsc's global pool for authorities that the
     * manifest declares by resource reference. Exact matches only: the pool slot holding
     * "com.example.original.provider" is that value wherever it's used, and nothing that
     * merely starts with the package is touched.
     */
    private static void renameResourceAuthorities(ApkDecoder.Decoded decoded,
                                                  Map<String, String> renames, File outDir,
                                                  Map<String, File> replacements,
                                                  BuildLog log) throws PipelineException {
        if (decoded.resourcesArsc == null) {
            throw new PipelineException(Stage.TRANSFORM, "Provider authorities "
                    + renames.keySet() + " are not inline in the manifest, and the APK has "
                    + "no resources.arsc to rename them in.");
        }
        try {
            com.apkrepacker.apk.axml.ArscFile arsc = com.apkrepacker.apk.axml.ArscFile.parse(
                    Files.readAllBytes(decoded.resourcesArsc.toPath()));
            java.util.Set<String> pool = new java.util.HashSet<>(arsc.strings());
            for (String a : renames.keySet()) {
                if (!pool.contains(a)) {
                    throw new PipelineException(Stage.TRANSFORM, "Provider authority " + a
                            + " is defined by a resource that could not be located in "
                            + "resources.arsc. Renaming it is required to avoid a provider "
                            + "conflict with the original app.");
                }
            }
            int changed = arsc.renameExact(renames);
            File out = new File(outDir, "resources.arsc");
            Files.write(out.toPath(), arsc.toByteArray());
            replacements.put("resources.arsc", out);
            for (Map.Entry<String, String> a : renames.entrySet()) {
                log.info("resources.arsc: authority " + a.getKey() + " -> " + a.getValue());
            }
            log.info("resources.arsc: rewrote " + changed + " pooled string value(s); "
                    + "resource IDs and all other values are unchanged.");
        } catch (AxmlException e) {
            throw new PipelineException(Stage.TRANSFORM,
                    "Unsupported resources.arsc structure: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new PipelineException(Stage.TRANSFORM,
                    "Failed to rewrite resources.arsc: " + e.getMessage(), e);
        }
    }

    /**
     * Layouts, preference screens, navigation graphs, etc. name classes as plain strings
     * ({@code <com.example.original.MyView>}, {@code android:name="com.example.original.F"}).
     * Once the DEX defines those classes under the new package, inflation fails with
     * ClassNotFoundException unless these strings follow. Only exact names of renamed classes
     * are rewritten; any other string that merely starts with the old package is kept.
     */
    private static int rewriteResourceXml(ApkDecoder.Decoded decoded, String oldPackage,
                                          String newPackage, java.util.Set<String> renamed,
                                          File outDir, Map<String, File> replacements,
                                          BuildLog log) throws PipelineException {
        if (renamed.isEmpty() || decoded.resXmlFiles.isEmpty()) {
            return 0;
        }
        Map<String, String> renames = new HashMap<>();
        for (String old : renamed) {
            renames.put(old, newPackage + old.substring(oldPackage.length()));
        }
        // Cheap pre-filter: a file whose pool can't contain the old package is skipped
        // without parsing. Pools are UTF-8 or UTF-16LE, so check both encodings.
        byte[] needle8 = oldPackage.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] needle16 = oldPackage.getBytes(java.nio.charset.StandardCharsets.UTF_16LE);

        int totalChanged = 0;
        int filesChanged = 0;
        for (int i = 0; i < decoded.resXmlFiles.size(); i++) {
            File in = decoded.resXmlFiles.get(i);
            String name = decoded.resXmlEntryNames.get(i);
            try {
                byte[] bytes = Files.readAllBytes(in.toPath());
                if (!containsBytes(bytes, needle8) && !containsBytes(bytes, needle16)) {
                    continue;
                }
                AxmlFile axml;
                try {
                    axml = AxmlFile.parse(bytes);
                } catch (AxmlException e) {
                    continue; // e.g. plain-text XML under res/raw: not ours to rewrite
                }
                if (axml.hasStyles()) {
                    log.warn("Resource " + name + " has a styled string pool; left unchanged.");
                    continue;
                }
                int changed = axml.renameExact(renames);
                if (changed == 0) {
                    continue;
                }
                File out = new File(outDir, name);
                //noinspection ResultOfMethodCallIgnored
                out.getParentFile().mkdirs();
                Files.write(out.toPath(), axml.toByteArray());
                replacements.put(name, out);
                totalChanged += changed;
                filesChanged++;
            } catch (AxmlException e) {
                throw new PipelineException(Stage.TRANSFORM,
                        "Unsupported binary XML structure in " + name + ": " + e.getMessage(), e);
            } catch (IOException e) {
                throw new PipelineException(Stage.TRANSFORM,
                        "Failed to rewrite " + name + ": " + e.getMessage(), e);
            }
        }
        log.info("Resource XML: renamed " + totalChanged + " class reference(s) in "
                + filesChanged + " file(s).");
        return totalChanged;
    }

    /** Naive but adequate substring search over raw bytes (no charset decoding). */
    private static boolean containsBytes(byte[] haystack, byte[] needle) {
        if (needle.length == 0 || haystack.length < needle.length) {
            return false;
        }
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }
}
