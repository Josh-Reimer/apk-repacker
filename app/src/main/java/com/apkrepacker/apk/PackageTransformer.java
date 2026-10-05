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
 * <p>Resources (resources.arsc, res/), assets, and native libraries are intentionally
 * left untouched and copied verbatim at rebuild time.
 */
public final class PackageTransformer {

    public static final class Result {
        /** ZIP entry name -> file containing its new bytes. */
        public final Map<String, File> replacements;
        public final int manifestStringsChanged;
        public final int dexTypesRemapped;
        public final String detectedOldPackage;

        Result(Map<String, File> replacements, int manifestStringsChanged,
               int dexTypesRemapped, String detectedOldPackage) {
            this.replacements = replacements;
            this.manifestStringsChanged = manifestStringsChanged;
            this.dexTypesRemapped = dexTypesRemapped;
            this.detectedOldPackage = detectedOldPackage;
        }
    }

    /**
     * @param declaredOldPackage the package the caller believes is original (from analysis);
     *                           used as a cross-check against the manifest.
     */
    public Result transform(ApkDecoder.Decoded decoded,
                            String declaredOldPackage,
                            String newPackage,
                            int minSdk,
                            File outDir,
                            BuildLog log) throws PipelineException {
        Map<String, File> replacements = new HashMap<>();

        // ---- Manifest: read true package, rename pooled strings ----
        String oldPackage;
        int manifestChanged;
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
            manifestChanged = axml.renamePackage(oldPackage, newPackage);
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

        // ---- DEX: conservative type-descriptor remap ----
        // Only run the (heavy) dexlib2 rebuild on DEX files that actually mention the old
        // package. A DEX with no occurrence of the old internal path cannot contain a type
        // to remap, so we skip it and let ApkBuilder copy the original entry verbatim. This
        // avoids rewriting large library DEX files that don't reference the app's namespace.
        int totalRemapped = 0;
        byte[] needle = ("L" + oldPackage.replace('.', '/') + "/")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        DexTransformer dex = new DexTransformer(oldPackage, newPackage, minSdk);
        List<File> dexFiles = decoded.dexFiles;
        List<String> dexNames = decoded.dexEntryNames;
        for (int i = 0; i < dexFiles.size(); i++) {
            File in = dexFiles.get(i);
            String name = dexNames.get(i);
            try {
                if (!containsBytes(Files.readAllBytes(in.toPath()), needle)) {
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

        if (totalRemapped == 0) {
            log.warn("No DEX type references matched the original package prefix. "
                    + "The app may namespace its classes differently from its applicationId; "
                    + "code references were left unchanged.");
        }

        return new Result(replacements, manifestChanged, totalRemapped, oldPackage);
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
