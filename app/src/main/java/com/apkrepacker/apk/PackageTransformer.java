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
 * Stage 3/4/5 orchestrator: given the decoded APK set (base first, then any splits), produce
 * per-APK replacement entries (by ZIP entry name) that carry the new package identity.
 *
 * <p>A split set is transformed as one unit: every APK's manifest gets the new package, DEX
 * in any APK is remapped with the same rules, and resource XML in any APK follows classes
 * renamed in any other (a base layout may inflate a view defined in a feature split).
 * Binary XML under res/ is rewritten only where a pooled string names a class the DEX step
 * renamed; resources.arsc only for provider authorities declared by reference. Assets and
 * native libraries are copied verbatim at rebuild time.
 */
public final class PackageTransformer {

    public static final class Result {
        /** Base APK: ZIP entry name -> file containing its new bytes. */
        public final Map<String, File> replacements;
        /** Same as {@link #replacements}, for every APK in the set (index 0 = base). */
        public final List<Map<String, File>> replacementsPerApk;
        public final int manifestStringsChanged;
        public final int dexTypesRemapped;
        public final int resXmlStringsChanged;
        public final String detectedOldPackage;
        public final boolean labelChanged;
        /** Every provider authority the output declares differently from the original. */
        public final Map<String, String> authorityRenames;

        Result(List<Map<String, File>> replacementsPerApk, int manifestStringsChanged,
               int dexTypesRemapped, int resXmlStringsChanged,
               String detectedOldPackage, boolean labelChanged,
               Map<String, String> authorityRenames) {
            this.replacementsPerApk = replacementsPerApk;
            this.replacements = replacementsPerApk.get(0);
            this.authorityRenames = authorityRenames;
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

    public Result transform(ApkDecoder.Decoded decoded,
                            String declaredOldPackage,
                            String newPackage,
                            String newLabel,
                            int minSdk,
                            List<String> installedAuthorities,
                            File outDir,
                            BuildLog log) throws PipelineException {
        return transformSet(java.util.Collections.singletonList(decoded),
                java.util.Collections.singletonList("base"), declaredOldPackage, newPackage,
                newLabel, minSdk, installedAuthorities, outDir, log);
    }

    /**
     * @param apks                 base first, then splits
     * @param apkNames             display names for the log ("base", "split_config.arm64_v8a")
     * @param installedAuthorities the original app's provider authorities as resolved by
     *                             PackageManager. Needed because a manifest may declare an
     *                             authority by resource reference ({@code @string/...}), whose
     *                             value lives in resources.arsc rather than the manifest.
     */
    public Result transformSet(List<ApkDecoder.Decoded> apks,
                               List<String> apkNames,
                               String declaredOldPackage,
                               String newPackage,
                               String newLabel,
                               int minSdk,
                               List<String> installedAuthorities,
                               File outDir,
                               BuildLog log) throws PipelineException {
        List<Map<String, File>> replacements = new java.util.ArrayList<>();
        List<File> outDirs = new java.util.ArrayList<>();
        for (int i = 0; i < apks.size(); i++) {
            replacements.add(new HashMap<>());
            // The base keeps outDir itself so single-APK output paths are unchanged.
            File d = i == 0 ? outDir : new File(outDir, "apk-" + i);
            //noinspection ResultOfMethodCallIgnored
            d.mkdirs();
            outDirs.add(d);
        }

        // ---- Manifests: read true package from the base, rename pooled strings in all ----
        String oldPackage = null;
        int manifestChanged = 0;
        boolean labelChanged = false;
        // Old -> new provider authority, for the manifests' renamed authorities only.
        Map<String, String> authorityRenames = new java.util.LinkedHashMap<>();
        for (int a = 0; a < apks.size(); a++) {
            String apkName = apkNames.get(a);
            try {
                AxmlFile axml = AxmlFile.parse(
                        Files.readAllBytes(apks.get(a).manifestFile.toPath()));
                String pkg = axml.getManifestPackage();
                if (a == 0) {
                    oldPackage = pkg;
                    log.info("Manifest declares package: " + oldPackage);
                    if (declaredOldPackage != null && !declaredOldPackage.equals(oldPackage)) {
                        log.warn("Analyzer reported '" + declaredOldPackage + "' but manifest "
                                + "says '" + oldPackage + "'; using the manifest value.");
                    }
                    if (oldPackage.equals(newPackage)) {
                        throw new PipelineException(Stage.TRANSFORM, "New package name is "
                                + "identical to the original (" + oldPackage + ").");
                    }
                } else if (!oldPackage.equals(pkg)) {
                    throw new PipelineException(Stage.TRANSFORM, "Split " + apkName
                            + " declares package '" + pkg + "' but the base declares '"
                            + oldPackage + "'; this is not one app's split set.");
                }
                List<Integer> authIdx =
                        axml.getAttrStringIndices("provider", AxmlFile.ATTR_AUTHORITIES);
                List<String> oldAuths = new java.util.ArrayList<>();
                for (int idx : authIdx) {
                    oldAuths.add(axml.strings().get(idx));
                }
                int changed = axml.renamePackage(oldPackage, newPackage);
                manifestChanged += changed;
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
                if (a == 0 && newLabel != null && !newLabel.trim().isEmpty()) {
                    labelChanged = axml.setApplicationLabel(newLabel.trim());
                    if (labelChanged) {
                        log.info("Manifest: set application display name to \""
                                + newLabel.trim() + "\" (components with their own label keep it).");
                    } else {
                        log.warn("Manifest: <application> has no label attribute; display "
                                + "name left unchanged.");
                    }
                }
                File newManifest = new File(outDirs.get(a), "AndroidManifest.xml");
                Files.write(newManifest.toPath(), axml.toByteArray());
                log.info("Manifest (" + apkName + "): rewrote " + changed + " pooled string(s); "
                        + "relative component names (\".Foo\") resolve under the new package.");
                replacements.get(a).put("AndroidManifest.xml", newManifest);
            } catch (AxmlException e) {
                throw new PipelineException(Stage.TRANSFORM, "The " + apkName + " APK contains "
                        + "an unsupported binary manifest structure: " + e.getMessage(), e);
            } catch (IOException e) {
                throw new PipelineException(Stage.TRANSFORM,
                        "Failed to read/write the " + apkName + " manifest: " + e.getMessage(), e);
            }
        }

        // ---- Authorities defined in resources (android:authorities="@string/...") ----
        Map<String, String> arscRenames = new java.util.LinkedHashMap<>();
        for (String declared : installedAuthorities) {
            for (String a : declared.split(";")) {
                if (authorityRenames.containsKey(a) || authorityRenames.containsValue(a)) {
                    continue; // inline in a manifest; already renamed above
                }
                if (a.equals(oldPackage) || a.startsWith(oldPackage + ".")) {
                    arscRenames.put(a, newPackage + a.substring(oldPackage.length()));
                }
            }
        }
        if (!arscRenames.isEmpty()) {
            renameResourceAuthorities(apks, apkNames, arscRenames, outDirs, replacements, log);
            authorityRenames.putAll(arscRenames);
        }

        // ---- DEX: conservative type-descriptor remap, across the whole set ----
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
        for (int a = 0; a < apks.size(); a++) {
            List<File> dexFiles = apks.get(a).dexFiles;
            List<String> dexNames = apks.get(a).dexEntryNames;
            String prefix = a == 0 ? "" : apkNames.get(a) + "!";
            for (int i = 0; i < dexFiles.size(); i++) {
                File in = dexFiles.get(i);
                String name = dexNames.get(i);
                try {
                    byte[] bytes = Files.readAllBytes(in.toPath());
                    if (!containsBytes(bytes, needle) && (authorityRenames.isEmpty()
                            || !containsBytes(bytes, dottedNeedle))) {
                        log.info("DEX " + prefix + name
                                + ": no reference to the old package; copied as-is.");
                        continue;
                    }
                } catch (IOException e) {
                    throw new PipelineException(Stage.TRANSFORM,
                            "Failed to scan " + prefix + name + ": " + e.getMessage(), e);
                }
                File out = new File(outDirs.get(a), name);
                totalRemapped += dex.rewrite(in, out, log);
                replacements.get(a).put(name, out);
            }
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

        int resXmlChanged = 0;
        for (int a = 0; a < apks.size(); a++) {
            resXmlChanged += rewriteResourceXml(apks.get(a), apkNames.get(a), oldPackage,
                    newPackage, dex.renamedClasses(), outDirs.get(a), replacements.get(a), log);
        }

        return new Result(replacements, manifestChanged, totalRemapped, resXmlChanged,
                oldPackage, labelChanged, authorityRenames);
    }

    /**
     * Rewrites exact string values in resources.arsc's global pool for authorities that a
     * manifest declares by resource reference. Exact matches only: the pool slot holding
     * "com.example.original.provider" is that value wherever it's used, and nothing that
     * merely starts with the package is touched. In a split set, each APK's table is checked;
     * every authority must be found in at least one of them.
     */
    private static void renameResourceAuthorities(List<ApkDecoder.Decoded> apks,
                                                  List<String> apkNames,
                                                  Map<String, String> renames,
                                                  List<File> outDirs,
                                                  List<Map<String, File>> replacements,
                                                  BuildLog log) throws PipelineException {
        java.util.Set<String> found = new java.util.HashSet<>();
        for (int a = 0; a < apks.size(); a++) {
            File arscIn = apks.get(a).resourcesArsc;
            if (arscIn == null) {
                continue;
            }
            try {
                com.apkrepacker.apk.axml.ArscFile arsc = com.apkrepacker.apk.axml.ArscFile
                        .parse(Files.readAllBytes(arscIn.toPath()));
                java.util.Set<String> pool = new java.util.HashSet<>(arsc.strings());
                Map<String, String> here = new java.util.LinkedHashMap<>();
                for (Map.Entry<String, String> r : renames.entrySet()) {
                    if (pool.contains(r.getKey())) {
                        here.put(r.getKey(), r.getValue());
                    }
                }
                if (here.isEmpty()) {
                    continue;
                }
                found.addAll(here.keySet());
                int changed = arsc.renameExact(here);
                File out = new File(outDirs.get(a), "resources.arsc");
                Files.write(out.toPath(), arsc.toByteArray());
                replacements.get(a).put("resources.arsc", out);
                for (Map.Entry<String, String> r : here.entrySet()) {
                    log.info("resources.arsc (" + apkNames.get(a) + "): authority "
                            + r.getKey() + " -> " + r.getValue());
                }
                log.info("resources.arsc (" + apkNames.get(a) + "): rewrote " + changed
                        + " pooled string value(s); resource IDs and all other values are "
                        + "unchanged.");
            } catch (AxmlException e) {
                throw new PipelineException(Stage.TRANSFORM, "Unsupported resources.arsc "
                        + "structure in " + apkNames.get(a) + ": " + e.getMessage(), e);
            } catch (IOException e) {
                throw new PipelineException(Stage.TRANSFORM, "Failed to rewrite "
                        + apkNames.get(a) + " resources.arsc: " + e.getMessage(), e);
            }
        }
        for (String a : renames.keySet()) {
            if (!found.contains(a)) {
                throw new PipelineException(Stage.TRANSFORM, "Provider authority " + a
                        + " is defined by a resource that could not be located in any "
                        + "resources.arsc. Renaming it is required to avoid a provider "
                        + "conflict with the original app.");
            }
        }
    }

    /**
     * Layouts, preference screens, navigation graphs, etc. name classes as plain strings
     * ({@code <com.example.original.MyView>}, {@code android:name="com.example.original.F"}).
     * Once the DEX defines those classes under the new package, inflation fails with
     * ClassNotFoundException unless these strings follow. Only exact names of renamed classes
     * are rewritten; any other string that merely starts with the old package is kept.
     */
    private static int rewriteResourceXml(ApkDecoder.Decoded decoded, String apkName,
                                          String oldPackage,
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
        log.info("Resource XML (" + apkName + "): renamed " + totalChanged
                + " class reference(s) in " + filesChanged + " file(s).");
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
