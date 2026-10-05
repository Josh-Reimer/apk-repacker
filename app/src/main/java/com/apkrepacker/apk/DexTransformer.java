package com.apkrepacker.apk;

import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile;
import com.android.tools.smali.dexlib2.iface.DexFile;
import com.android.tools.smali.dexlib2.rewriter.DexRewriter;
import com.android.tools.smali.dexlib2.rewriter.Rewriter;
import com.android.tools.smali.dexlib2.rewriter.RewriterModule;
import com.android.tools.smali.dexlib2.rewriter.Rewriters;
import com.android.tools.smali.dexlib2.writer.pool.DexPool;

import java.io.File;
import java.io.IOException;

/**
 * DEX-aware namespace rewriter built on smali/dexlib2.
 *
 * <p>We rewrite <em>type descriptors</em> (class/field/method references, signatures,
 * annotations) whose package prefix matches the original application package. We do
 * <strong>not</strong> touch string constants — a string that merely happens to contain
 * the old package name (a URL, a DB name, a reflection target, SDK config) is left as-is.
 * This is the conservative behaviour CLAUDE.md requires: DEX is never treated as text.
 */
public final class DexTransformer {

    private final String oldPrefix; // e.g. "Lcom/example/original/"
    private final String newPrefix; // e.g. "Lcom/example/modified/"
    private final int apiLevel;

    public DexTransformer(String oldPackage, String newPackage, int minSdk) {
        this.oldPrefix = "L" + oldPackage.replace('.', '/') + "/";
        this.newPrefix = "L" + newPackage.replace('.', '/') + "/";
        this.apiLevel = minSdk > 0 ? minSdk : 26;
    }

    /**
     * Rewrites one {@code classesN.dex} file in place-to-out.
     *
     * @return the number of type descriptors that were remapped.
     */
    public int rewrite(File inDex, File outDex, BuildLog log) throws PipelineException {
        final int[] remapped = {0};
        try {
            DexBackedDexFile input =
                    DexBackedDexFile.fromInputStream(Opcodes.forApi(apiLevel),
                            new java.io.BufferedInputStream(new java.io.FileInputStream(inDex)));

            DexRewriter rewriter = new DexRewriter(new RewriterModule() {
                @Override
                public Rewriter<String> getTypeRewriter(Rewriters rewriters) {
                    return value -> {
                        String mapped = mapType(value);
                        if (!mapped.equals(value)) {
                            remapped[0]++;
                        }
                        return mapped;
                    };
                }
            });

            DexFile rewritten = rewriter.getDexFileRewriter().rewrite(input);
            DexPool.writeTo(outDex.getAbsolutePath(), rewritten);
            log.info("DEX " + inDex.getName() + ": remapped " + remapped[0] + " type references.");
            return remapped[0];
        } catch (IOException e) {
            throw new PipelineException(Stage.TRANSFORM,
                    "Failed to rewrite " + inDex.getName() + ": " + e.getMessage(), e);
        } catch (RuntimeException e) {
            throw new PipelineException(Stage.TRANSFORM,
                    "DEX rewrite error in " + inDex.getName() + ": " + e.getMessage(), e);
        }
    }

    /** Remap a single type descriptor, handling array ({@code [}) prefixes. */
    private String mapType(String descriptor) {
        int l = descriptor.indexOf('L');
        if (l < 0) {
            return descriptor; // primitive or array-of-primitive
        }
        if (descriptor.startsWith(oldPrefix, l)) {
            return descriptor.substring(0, l)
                    + newPrefix
                    + descriptor.substring(l + oldPrefix.length());
        }
        return descriptor;
    }
}
