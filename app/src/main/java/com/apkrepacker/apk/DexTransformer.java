package com.apkrepacker.apk;

import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile;
import com.android.tools.smali.dexlib2.Opcode;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.instruction.Instruction;
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction;
import com.android.tools.smali.dexlib2.iface.reference.StringReference;
import com.android.tools.smali.dexlib2.iface.value.EncodedValue;
import com.android.tools.smali.dexlib2.iface.value.StringEncodedValue;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction31c;
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference;
import com.android.tools.smali.dexlib2.immutable.value.ImmutableStringEncodedValue;
import com.android.tools.smali.dexlib2.rewriter.EncodedValueRewriter;
import com.android.tools.smali.dexlib2.rewriter.InstructionRewriter;
import com.android.tools.smali.dexlib2.iface.DexFile;
import com.android.tools.smali.dexlib2.rewriter.DexRewriter;
import com.android.tools.smali.dexlib2.rewriter.Rewriter;
import com.android.tools.smali.dexlib2.rewriter.RewriterModule;
import com.android.tools.smali.dexlib2.rewriter.Rewriters;
import com.android.tools.smali.dexlib2.writer.pool.DexPool;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * DEX-aware namespace rewriter built on smali/dexlib2.
 *
 * <p>We rewrite <em>type descriptors</em> (class/field/method references, signatures,
 * annotations) whose package prefix matches the original application package. String
 * constants are left as-is — a string that merely happens to contain the old package name
 * (a URL, a DB name, a reflection target, SDK config) keeps its value. The one exception is
 * an explicit {@code stringRenames} map of strings whose meaning is certain (provider
 * authorities the manifest declares and was rewritten to), matched exactly, never by prefix.
 * This is the conservative behaviour CLAUDE.md requires: DEX is never treated as text.
 */
public final class DexTransformer {

    private final String oldPrefix; // e.g. "Lcom/example/original/"
    private final String newPrefix; // e.g. "Lcom/example/modified/"
    private final int apiLevel;
    /** Original binary names ("com.example.original.Foo$Bar") of classes defined and renamed. */
    // Shared across threads: one DexTransformer rewrites many DEX files in parallel.
    private final Set<String> renamedClasses = ConcurrentHashMap.newKeySet();
    /** Exact string constant -> replacement (see class doc); empty means strings untouched. */
    private final Map<String, String> stringRenames;
    private final AtomicInteger stringsRenamed = new AtomicInteger();

    public DexTransformer(String oldPackage, String newPackage, int minSdk) {
        this(oldPackage, newPackage, minSdk, Collections.emptyMap());
    }

    public DexTransformer(String oldPackage, String newPackage, int minSdk,
                          Map<String, String> stringRenames) {
        this.stringRenames = stringRenames;
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
            Opcodes opcodes = opcodesFor(inDex);
            log.info("DEX " + inDex.getName() + ": using API " + opcodes.api + " opcodes.");
            DexBackedDexFile input =
                    DexBackedDexFile.fromInputStream(opcodes,
                            new java.io.BufferedInputStream(new java.io.FileInputStream(inDex)));
            for (ClassDef c : input.getClasses()) {
                String type = c.getType();
                if (!mapType(type).equals(type)) {
                    renamedClasses.add(type.substring(1, type.length() - 1).replace('/', '.'));
                }
                if (!stringRenames.isEmpty()) {
                    stringsRenamed.addAndGet(countStringRenames(c));
                }
            }

            DexRewriter rewriter = new DexRewriter(new RewriterModule() {
                @Override
                public Rewriter<Instruction> getInstructionRewriter(Rewriters rewriters) {
                    InstructionRewriter base = new InstructionRewriter(rewriters);
                    return insn -> renameConstString(base.rewrite(insn));
                }

                @Override
                public Rewriter<EncodedValue> getEncodedValueRewriter(Rewriters rewriters) {
                    EncodedValueRewriter base = new EncodedValueRewriter(rewriters);
                    return value -> {
                        EncodedValue v = base.rewrite(value);
                        if (v instanceof StringEncodedValue) {
                            String to = stringRenames.get(((StringEncodedValue) v).getValue());
                            if (to != null) {
                                return new ImmutableStringEncodedValue(to);
                            }
                        }
                        return v;
                    };
                }

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

    /** Number of string constants replaced via {@code stringRenames} so far. */
    public int stringsRenamed() {
        return stringsRenamed.get();
    }

    /**
     * Counts the const-strings and static-field values in {@code c} that the rewriters will
     * replace. Counted here, once, because dexlib2's rewritten views are lazy and get
     * re-evaluated several times while DexPool writes them.
     */
    private int countStringRenames(ClassDef c) {
        int n = 0;
        for (com.android.tools.smali.dexlib2.iface.Field f : c.getStaticFields()) {
            EncodedValue v = f.getInitialValue();
            if (v instanceof StringEncodedValue
                    && stringRenames.containsKey(((StringEncodedValue) v).getValue())) {
                n++;
            }
        }
        for (com.android.tools.smali.dexlib2.iface.Method m : c.getMethods()) {
            if (m.getImplementation() == null) {
                continue;
            }
            for (Instruction insn : m.getImplementation().getInstructions()) {
                Opcode op = insn.getOpcode();
                if ((op == Opcode.CONST_STRING || op == Opcode.CONST_STRING_JUMBO)
                        && stringRenames.containsKey(((StringReference)
                        ((ReferenceInstruction) insn).getReference()).getString())) {
                    n++;
                }
            }
        }
        return n;
    }

    /**
     * Swaps the literal of a {@code const-string}/{@code const-string/jumbo} if it is exactly
     * a key of {@code stringRenames}. The opcode (and so the instruction size and every
     * branch offset) is unchanged.
     */
    private Instruction renameConstString(Instruction insn) {
        Opcode op = insn.getOpcode();
        if (stringRenames.isEmpty()
                || (op != Opcode.CONST_STRING && op != Opcode.CONST_STRING_JUMBO)) {
            return insn;
        }
        String to = stringRenames.get(
                ((StringReference) ((ReferenceInstruction) insn).getReference()).getString());
        if (to == null) {
            return insn;
        }
        int reg = ((OneRegisterInstruction) insn).getRegisterA();
        ImmutableStringReference ref = new ImmutableStringReference(to);
        return op == Opcode.CONST_STRING
                ? new ImmutableInstruction21c(op, reg, ref)
                : new ImmutableInstruction31c(op, reg, ref);
    }

    /**
     * Binary names of every class this transformer has renamed so far, across all DEX files.
     * Resource XML may name these classes (custom views, fragments, preferences), and only
     * those exact names are safe to follow the rename there.
     */
    public Set<String> renamedClasses() {
        return Collections.unmodifiableSet(renamedClasses);
    }

    /** Highest API level dexlib2 3.0.9's VersionMap can map to a DEX version. */
    private static final int MAX_MAPPED_API = 35;

    /**
     * Picks opcodes from the DEX header's own version rather than from minSdk. dexlib2 has
     * no DEX-version mapping for API levels newer than it knows (minSdk 36+ yields -1 and a
     * "dexVersion must be within [0, 999]" failure), and the header is authoritative anyway.
     * DexPool writes with the input's opcodes, so the output keeps the original DEX version.
     */
    private Opcodes opcodesFor(File inDex) throws IOException {
        int version = readDexVersion(inDex);
        if (version > 0) {
            try {
                return Opcodes.forDexVersion(version);
            } catch (RuntimeException ignored) {
                // Unknown to dexlib2; fall back to the API-derived opcode set below.
            }
        }
        return Opcodes.forApi(Math.min(apiLevel, MAX_MAPPED_API));
    }

    /** Parses the "dex\n0NN\0" magic; returns -1 if the header is not a recognizable DEX. */
    private static int readDexVersion(File inDex) throws IOException {
        byte[] magic = new byte[8];
        try (java.io.DataInputStream in =
                     new java.io.DataInputStream(new java.io.FileInputStream(inDex))) {
            in.readFully(magic);
        } catch (java.io.EOFException e) {
            return -1;
        }
        if (magic[0] != 'd' || magic[1] != 'e' || magic[2] != 'x' || magic[3] != '\n'
                || magic[7] != 0) {
            return -1;
        }
        int v = 0;
        for (int i = 4; i < 7; i++) {
            if (magic[i] < '0' || magic[i] > '9') {
                return -1;
            }
            v = v * 10 + (magic[i] - '0');
        }
        return v;
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
