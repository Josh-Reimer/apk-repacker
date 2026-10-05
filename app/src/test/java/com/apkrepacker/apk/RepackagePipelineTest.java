package com.apkrepacker.apk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.apkrepacker.apk.axml.AxmlFile;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
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
                .transform(decoded, OLD_PKG, NEW_PKG, 26, modDir, log);
        assertEquals(OLD_PKG, transform.detectedOldPackage);
        assertTrue("manifest should have changed strings", transform.manifestStringsChanged >= 1);
        assertTrue("our own app's dex references com.apkrepacker.* types",
                transform.dexTypesRemapped >= 1);

        File unsigned = File.createTempFile("unsigned", ".apk");
        new ApkBuilder().build(apk, transform.replacements, unsigned, log);

        // Confirm the rebuilt (unsigned) APK carries the new package per PackageManager-free parse.
        try (ZipFile zip = new ZipFile(unsigned)) {
            byte[] m = readAll(zip, zip.getEntry("AndroidManifest.xml"));
            assertEquals(NEW_PKG, AxmlFile.parse(m).getManifestPackage());
            assertTrue("resources.arsc preserved", zip.getEntry("resources.arsc") != null);
        }

        // Sign with a key loaded from a PKCS12 keystore (AndroidKeyStore is device-only;
        // this mirrors the KeystoreManager import path).
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = getClass().getResourceAsStream("/test.p12")) {
            ks.load(in, "testpass".toCharArray());
        }
        PrivateKey pk = (PrivateKey) ks.getKey("testkey", "testpass".toCharArray());
        X509Certificate cert = (X509Certificate) ks.getCertificate("testkey");

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
}
