package com.apkrepacker.apk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.apkrepacker.apk.axml.AxmlFile;

import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.asn1.x500.X500Name;
import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Exercises the real on-device pipeline classes end-to-end on the JVM (everything except
 * the Android-only glue: PackageManager, AndroidKeyStore, Context). Input is a real APK
 * fixture; output is re-signed and verified with Google's apksig.
 */
public final class RepackagePipelineTest {

    private static final String OLD_PKG = "com.apkrepacker";
    private static final String NEW_PKG = "com.example.renamed";

    private File fixture() throws Exception {
        File out = File.createTempFile("fixture", ".apk");
        try (InputStream in = getClass().getResourceAsStream("/fixture.apk");
             OutputStream os = new FileOutputStream(out)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
        }
        return out;
    }

    @Test
    public void packageNameValidation() {
        assertTrue(PackageNames.isValid("com.example.app"));
        assertNotEquals(null, PackageNames.validate("com"));          // single segment
        assertNotEquals(null, PackageNames.validate("com.1bad.app")); // digit start
        assertNotEquals(null, PackageNames.validate("com.new.app"));  // keyword
        assertNotEquals(null, PackageNames.validate(".com.app"));     // leading dot
    }

    @Test
    public void manifestPackageRoundTrip() throws Exception {
        File apk = fixture();
        byte[] manifest;
        try (ZipFile zip = new ZipFile(apk)) {
            ZipEntry e = zip.getEntry("AndroidManifest.xml");
            manifest = readAll(zip, e);
        }
        AxmlFile axml = AxmlFile.parse(manifest);
        assertEquals(OLD_PKG, axml.getManifestPackage());

        int changed = axml.renamePackage(OLD_PKG, NEW_PKG);
        assertTrue("expected at least the package string to change", changed >= 1);

        byte[] rebuilt = axml.toByteArray();
        AxmlFile reparsed = AxmlFile.parse(rebuilt);
        assertEquals(NEW_PKG, reparsed.getManifestPackage());
    }

    @Test
    public void fullTransformRebuildSignVerify() throws Exception {
        File apk = fixture();
        BuildLog log = new BuildLog();

        File decodeDir = Files.createTempDirectory("decode").toFile();
        File modDir = Files.createTempDirectory("mod").toFile();

        ApkDecoder.Decoded decoded = new ApkDecoder().decode(apk, decodeDir, log);
        assertTrue("fixture must contain a dex", decoded.dexFiles.size() >= 1);

        PackageTransformer.Result transform = new PackageTransformer()
                .transform(decoded, OLD_PKG, NEW_PKG, "Renamed App", 26, modDir, log);
        assertEquals(OLD_PKG, transform.detectedOldPackage);
        assertTrue("manifest should have changed strings", transform.manifestStringsChanged >= 1);
        assertTrue("application label should have been set", transform.labelChanged);
        assertTrue("our own app's dex references com.apkrepacker.* types",
                transform.dexTypesRemapped >= 1);

        File unsigned = File.createTempFile("unsigned", ".apk");
        new ApkBuilder().build(apk, transform.replacements, unsigned, log);

        // Confirm the rebuilt (unsigned) APK carries the new package per PackageManager-free parse.
        try (ZipFile zip = new ZipFile(unsigned)) {
            byte[] m = readAll(zip, zip.getEntry("AndroidManifest.xml"));
            AxmlFile parsed = AxmlFile.parse(m);
            assertEquals(NEW_PKG, parsed.getManifestPackage());
            assertTrue("inline display name present in manifest",
                    parsed.strings().contains("Renamed App"));
            assertTrue("resources.arsc preserved", zip.getEntry("resources.arsc") != null);
        }

        // Sign with an ephemeral self-signed key generated at runtime (AndroidKeyStore is
        // device-only). Nothing is committed to the repo and nothing ships in the app.
        KeyPair kp = genKeyPair();
        X509Certificate cert = selfSignedCert(kp);
        PrivateKey pk = kp.getPrivate();

        File signed = File.createTempFile("signed", ".apk");
        com.android.apksig.ApkSigner.SignerConfig cfg =
                new com.android.apksig.ApkSigner.SignerConfig.Builder(
                        "test", pk, java.util.Collections.singletonList(cert)).build();
        new com.android.apksig.ApkSigner.Builder(java.util.Collections.singletonList(cfg))
                .setInputApk(unsigned)
                .setOutputApk(signed)
                .setV1SigningEnabled(true)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(true)
                .setMinSdkVersion(26)
                .build()
                .sign();

        com.android.apksig.ApkVerifier.Result result =
                new com.android.apksig.ApkVerifier.Builder(signed)
                        .setMinCheckedPlatformVersion(26)
                        .build()
                        .verify();
        assertTrue("signed APK must verify. errors=" + result.getErrors(), result.isVerified());
        assertTrue(result.isVerifiedUsingV2Scheme());
    }

    /** minSdk 36+ has no dexlib2 API→DEX-version mapping; opcodes must come from the header. */
    @Test
    public void dexRewriteWithMinSdkNewerThanDexlib2Knows() throws Exception {
        File apk = fixture();
        BuildLog log = new BuildLog();
        ApkDecoder.Decoded decoded = new ApkDecoder().decode(
                apk, Files.createTempDirectory("decode").toFile(), log);

        DexTransformer dex = new DexTransformer(OLD_PKG, NEW_PKG, 37);
        int remapped = 0;
        for (File in : decoded.dexFiles) {
            remapped += dex.rewrite(in, File.createTempFile("out", ".dex"), log);
        }
        assertTrue("type references should be remapped", remapped >= 1);
    }

    /** Resource XML renames only exact class names, never mere package-prefixed strings. */
    @Test
    public void renameExactIgnoresPrefixMatches() throws Exception {
        File apk = fixture();
        byte[] manifest;
        try (ZipFile zip = new ZipFile(apk)) {
            manifest = readAll(zip, zip.getEntry("AndroidManifest.xml"));
        }
        AxmlFile axml = AxmlFile.parse(manifest);
        String cls = OLD_PKG + ".ui.MainActivity";
        int clsIndex = axml.strings().indexOf(cls);
        assertTrue("fixture manifest names " + cls, clsIndex >= 0);
        int pkgIndex = axml.strings().indexOf(OLD_PKG);

        int changed = axml.renameExact(java.util.Collections.singletonMap(
                cls, NEW_PKG + ".ui.MainActivity"));
        assertEquals(1, changed);

        AxmlFile reparsed = AxmlFile.parse(axml.toByteArray());
        assertEquals(NEW_PKG + ".ui.MainActivity", reparsed.strings().get(clsIndex));
        if (pkgIndex >= 0) {
            assertEquals("bare package string must not change",
                    OLD_PKG, reparsed.strings().get(pkgIndex));
        }
    }

    /**
     * Apps with minSdk < 18 made apksig sign v1 with SHA-1, which the AndroidKeyStore key
     * (SHA-256/512 only) rejects. Signing must go through signingMinSdk() and avoid SHA-1.
     */
    @Test
    public void lowMinSdkAppIsSignedWithoutSha1() throws Exception {
        File unsigned = File.createTempFile("unsigned", ".apk");
        new ApkBuilder().build(fixture(), java.util.Collections.emptyMap(), unsigned,
                new BuildLog());

        KeyPair kp = genKeyPair();
        com.apkrepacker.security.KeystoreManager.SigningIdentity id =
                new com.apkrepacker.security.KeystoreManager.SigningIdentity(kp.getPrivate(),
                        java.util.Collections.singletonList(selfSignedCert(kp)), "test");
        int signingMinSdk = ApkSigner.signingMinSdk(14);
        File signed = File.createTempFile("signed", ".apk");
        new ApkSigner().sign(unsigned, signed, id, signingMinSdk, new BuildLog());

        try (ZipFile zip = new ZipFile(signed)) {
            ZipEntry sf = java.util.Collections.list(zip.entries()).stream()
                    .filter(e -> e.getName().startsWith("META-INF/")
                            && e.getName().endsWith(".SF"))
                    .findFirst().orElseThrow(AssertionError::new);
            String sfText = new String(readAll(zip, sf), java.nio.charset.StandardCharsets.UTF_8);
            assertTrue("v1 must use SHA-256", sfText.contains("SHA-256-Digest"));
            assertTrue("v1 must not use SHA-1", !sfText.contains("SHA1-Digest"));
        }
        assertTrue(new com.android.apksig.ApkVerifier.Builder(signed)
                .setMinCheckedPlatformVersion(signingMinSdk).build().verify().isVerified());
    }

    /**
     * Code holding its own provider authority as a literal must follow the manifest rename
     * (FileProvider crashed otherwise), while the bare package and other package-prefixed
     * strings stay untouched.
     */
    @Test
    public void dexRenamesOnlyExactAuthorityStrings() throws Exception {
        String oldAuth = OLD_PKG + ".fileprovider";
        String newAuth = NEW_PKG + ".fileprovider";
        String prefs = OLD_PKG + ".prefs";
        String cls = "L" + OLD_PKG.replace('.', '/') + "/Holder;";

        java.util.List<com.android.tools.smali.dexlib2.iface.instruction.Instruction> code =
                java.util.Arrays.asList(
                        constString(0, oldAuth), constString(0, OLD_PKG),
                        constString(0, prefs),
                        new com.android.tools.smali.dexlib2.immutable.instruction
                                .ImmutableInstruction10x(
                                com.android.tools.smali.dexlib2.Opcode.RETURN_VOID));
        com.android.tools.smali.dexlib2.immutable.ImmutableMethod method =
                new com.android.tools.smali.dexlib2.immutable.ImmutableMethod(cls, "run",
                        null, "V", com.android.tools.smali.dexlib2.AccessFlags.STATIC.getValue(),
                        null, null,
                        new com.android.tools.smali.dexlib2.immutable
                                .ImmutableMethodImplementation(1, code, null, null));
        com.android.tools.smali.dexlib2.immutable.ImmutableField field =
                new com.android.tools.smali.dexlib2.immutable.ImmutableField(cls, "AUTHORITY",
                        "Ljava/lang/String;",
                        com.android.tools.smali.dexlib2.AccessFlags.STATIC.getValue()
                                | com.android.tools.smali.dexlib2.AccessFlags.FINAL.getValue(),
                        new com.android.tools.smali.dexlib2.immutable.value
                                .ImmutableStringEncodedValue(oldAuth), null, null);
        com.android.tools.smali.dexlib2.immutable.ImmutableClassDef classDef =
                new com.android.tools.smali.dexlib2.immutable.ImmutableClassDef(cls,
                        com.android.tools.smali.dexlib2.AccessFlags.PUBLIC.getValue(),
                        "Ljava/lang/Object;", null, null, null,
                        java.util.Collections.singletonList(field),
                        java.util.Collections.singletonList(method));
        File in = File.createTempFile("input", ".dex");
        com.android.tools.smali.dexlib2.writer.pool.DexPool.writeTo(in.getAbsolutePath(),
                new com.android.tools.smali.dexlib2.immutable.ImmutableDexFile(
                        com.android.tools.smali.dexlib2.Opcodes.getDefault(),
                        java.util.Collections.singletonList(classDef)));

        File out = File.createTempFile("out", ".dex");
        DexTransformer dex = new DexTransformer(OLD_PKG, NEW_PKG, 26,
                java.util.Collections.singletonMap(oldAuth, newAuth));
        dex.rewrite(in, out, new BuildLog());
        assertEquals("one const-string + one field value", 2, dex.stringsRenamed());

        java.util.Set<String> strings = new java.util.HashSet<>();
        com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile result =
                com.android.tools.smali.dexlib2.DexFileFactory.loadDexFile(out,
                        com.android.tools.smali.dexlib2.Opcodes.getDefault());
        for (com.android.tools.smali.dexlib2.iface.ClassDef c : result.getClasses()) {
            for (com.android.tools.smali.dexlib2.iface.Field f : c.getStaticFields()) {
                strings.add(((com.android.tools.smali.dexlib2.iface.value.StringEncodedValue)
                        f.getInitialValue()).getValue());
            }
            for (com.android.tools.smali.dexlib2.iface.Method m : c.getMethods()) {
                for (com.android.tools.smali.dexlib2.iface.instruction.Instruction i
                        : m.getImplementation().getInstructions()) {
                    if (i instanceof com.android.tools.smali.dexlib2.iface.instruction
                            .ReferenceInstruction) {
                        strings.add(((com.android.tools.smali.dexlib2.iface.reference
                                .StringReference) ((com.android.tools.smali.dexlib2.iface
                                .instruction.ReferenceInstruction) i).getReference())
                                .getString());
                    }
                }
            }
        }
        assertTrue("authority renamed", strings.contains(newAuth));
        assertTrue("old authority gone", !strings.contains(oldAuth));
        assertTrue("bare package untouched", strings.contains(OLD_PKG));
        assertTrue("prefixed non-authority untouched", strings.contains(prefs));
    }

    /**
     * Authorities declared as @string/... live in resources.arsc. Renaming one pooled value
     * must leave every other string and every byte after the pool intact, and the rebuilt
     * table must stay STORED and 4-byte aligned (required for targetSdk 30+).
     */
    @Test
    public void arscExactRenameKeepsTableIntactAndStored() throws Exception {
        File apk = fixture();
        byte[] original;
        try (ZipFile zip = new ZipFile(apk)) {
            original = readAll(zip, zip.getEntry("resources.arsc"));
        }
        com.apkrepacker.apk.axml.ArscFile arsc =
                com.apkrepacker.apk.axml.ArscFile.parse(original);
        java.util.List<String> before = new java.util.ArrayList<>(arsc.strings());
        int idx = before.indexOf("APK Repacker");
        assertTrue("fixture pool holds the app name", idx >= 0);
        String renamed = "APK Repacker — renamed ✓"; // non-ASCII exercises UTF-8 lengths

        assertEquals(1, arsc.renameExact(java.util.Collections.singletonMap(
                "APK Repacker", renamed)));
        byte[] rewritten = arsc.toByteArray();

        java.util.List<String> after =
                com.apkrepacker.apk.axml.ArscFile.parse(rewritten).strings();
        assertEquals(before.size(), after.size());
        for (int i = 0; i < before.size(); i++) {
            assertEquals(i == idx ? renamed : before.get(i), after.get(i));
        }
        int oldPoolEnd = 12 + u32(original, 16);
        int newPoolEnd = 12 + u32(rewritten, 16);
        assertEquals("table body after the pool is byte-identical",
                java.nio.ByteBuffer.wrap(original, oldPoolEnd, original.length - oldPoolEnd),
                java.nio.ByteBuffer.wrap(rewritten, newPoolEnd, rewritten.length - newPoolEnd));

        File arscFile = File.createTempFile("resources", ".arsc");
        Files.write(arscFile.toPath(), rewritten);
        File unsigned = File.createTempFile("unsigned", ".apk");
        new ApkBuilder().build(apk, java.util.Collections.singletonMap("resources.arsc",
                arscFile), unsigned, new BuildLog());
        try (ZipFile zip = new ZipFile(unsigned)) {
            ZipEntry e = zip.getEntry("resources.arsc");
            assertEquals("resources.arsc stays STORED", ZipEntry.STORED, e.getMethod());
        }
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(unsigned, "r")) {
            long dataOffset = localDataOffset(raf, "resources.arsc");
            assertEquals("resources.arsc data is 4-byte aligned", 0, dataOffset % 4);
        }
    }

    /**
     * A split set is renamed as one unit: every manifest gets the new package, and an APK
     * from a different package is rejected rather than silently mixed in.
     */
    @Test
    public void splitSetRenamesEveryManifestAndRejectsForeignSplit() throws Exception {
        File apk = fixture();
        BuildLog log = new BuildLog();
        ApkDecoder.Decoded base = new ApkDecoder().decode(
                apk, Files.createTempDirectory("base").toFile(), log);
        ApkDecoder.Decoded split = new ApkDecoder().decode(
                apk, Files.createTempDirectory("split").toFile(), log);

        PackageTransformer.Result r = new PackageTransformer().transformSet(
                java.util.Arrays.asList(base, split),
                java.util.Arrays.asList("base", "split_config.test"),
                OLD_PKG, NEW_PKG, null, 26, java.util.Collections.emptyList(),
                Files.createTempDirectory("mod").toFile(), log);
        assertEquals(2, r.replacementsPerApk.size());
        for (java.util.Map<String, File> repl : r.replacementsPerApk) {
            AxmlFile m = AxmlFile.parse(Files.readAllBytes(
                    repl.get("AndroidManifest.xml").toPath()));
            assertEquals(NEW_PKG, m.getManifestPackage());
        }
        assertNotEquals("each APK gets its own output files",
                r.replacementsPerApk.get(0).get("AndroidManifest.xml"),
                r.replacementsPerApk.get(1).get("AndroidManifest.xml"));

        // A "split" whose manifest belongs to another package.
        AxmlFile foreign = AxmlFile.parse(Files.readAllBytes(split.manifestFile.toPath()));
        foreign.renamePackage(OLD_PKG, "org.someone.else");
        Files.write(split.manifestFile.toPath(), foreign.toByteArray());
        try {
            new PackageTransformer().transformSet(java.util.Arrays.asList(base, split),
                    java.util.Arrays.asList("base", "split_config.test"), OLD_PKG, NEW_PKG,
                    null, 26, java.util.Collections.emptyList(),
                    Files.createTempDirectory("mod2").toFile(), log);
            throw new AssertionError("foreign split must be rejected");
        } catch (PipelineException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("not one app's"));
        }
    }

    private static int u32(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8) | ((b[off + 2] & 0xFF) << 16)
                | ((b[off + 3] & 0xFF) << 24);
    }

    /**
     * Finds {@code name} via the central directory (local sizes can be 0 when a data
     * descriptor follows) and returns where its data starts.
     */
    private static long localDataOffset(java.io.RandomAccessFile raf, String name)
            throws Exception {
        byte[] all = new byte[(int) raf.length()];
        raf.seek(0);
        raf.readFully(all);
        int eocd = all.length - 22;
        while (eocd >= 0 && u32(all, eocd) != 0x06054b50) {
            eocd--;
        }
        int pos = u32(all, eocd + 16);
        while (u32(all, pos) == 0x02014b50) {
            int nameLen = u16(all, pos + 28);
            String n = new String(all, pos + 46, nameLen,
                    java.nio.charset.StandardCharsets.UTF_8);
            if (n.equals(name)) {
                int local = u32(all, pos + 42);
                return local + 30 + u16(all, local + 26) + u16(all, local + 28);
            }
            pos += 46 + nameLen + u16(all, pos + 30) + u16(all, pos + 32);
        }
        throw new AssertionError(name + " not found");
    }

    private static int u16(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
    }

    private static com.android.tools.smali.dexlib2.iface.instruction.Instruction constString(
            int reg, String s) {
        return new com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c(
                com.android.tools.smali.dexlib2.Opcode.CONST_STRING, reg,
                new com.android.tools.smali.dexlib2.immutable.reference
                        .ImmutableStringReference(s));
    }

    // ---- helpers -----------------------------------------------------------

    private static byte[] readAll(ZipFile zip, ZipEntry e) throws Exception {
        try (InputStream is = zip.getInputStream(e)) {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        }
    }

    private static KeyPair genKeyPair() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        return kpg.generateKeyPair();
    }

    /** Builds a self-signed X.509 certificate for the key pair using BouncyCastle. */
    private static X509Certificate selfSignedCert(KeyPair kp) throws Exception {
        X500Name dn = new X500Name("CN=Test Signer, O=APK Repacker Test");
        long now = System.currentTimeMillis();
        Date from = new Date(now);
        Date to = new Date(now + 3650L * 24 * 60 * 60 * 1000);
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                dn, BigInteger.valueOf(now), from, to, dn, kp.getPublic());
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA").build(kp.getPrivate());
        return new JcaX509CertificateConverter().getCertificate(builder.build(signer));
    }
}
