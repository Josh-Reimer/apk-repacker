package com.apkrepacker.security;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.io.InputStream;
import java.math.BigInteger;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Enumeration;
import java.util.List;

import javax.security.auth.x500.X500Principal;

/**
 * Manages the signing identity used to re-sign repackaged APKs.
 *
 * <p>The default key is generated in the <b>AndroidKeyStore</b>. The private key is
 * non-exportable and lives in the platform's hardware-backed store, which is the
 * strongest possible answer to "never transmit private keys": the bytes never exist in
 * our address space, let alone on a network.
 *
 * <p>An advanced path lets the user import their own PKCS12/JKS keystore (for example to
 * update an app they already own with its real key).
 */
public final class KeystoreManager {

    public static final String DEFAULT_ALIAS = "apkrepacker-default";
    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";

    /** A resolved signing identity ready to hand to {@link com.apkrepacker.apk.ApkSigner}. */
    public static final class SigningIdentity {
        public final PrivateKey privateKey;
        public final List<X509Certificate> certificateChain;
        public final String description;

        public SigningIdentity(PrivateKey privateKey,
                               List<X509Certificate> chain, String description) {
            this.privateKey = privateKey;
            this.certificateChain = chain;
            this.description = description;
        }

        public X509Certificate leaf() {
            return certificateChain.get(0);
        }
    }

    private final Context context;

    public KeystoreManager(Context context) {
        this.context = context.getApplicationContext();
    }

    /** Returns the app's default signing identity, generating it on first use. */
    public SigningIdentity getOrCreateDefault() throws Exception {
        KeyStore ks = KeyStore.getInstance(ANDROID_KEYSTORE);
        ks.load(null);
        if (!ks.containsAlias(DEFAULT_ALIAS)) {
            generateDefaultKey();
            ks.load(null);
        }
        PrivateKey key = (PrivateKey) ks.getKey(DEFAULT_ALIAS, null);
        Certificate cert = ks.getCertificate(DEFAULT_ALIAS);
        if (key == null || !(cert instanceof X509Certificate)) {
            throw new IllegalStateException("Default signing key is missing or malformed.");
        }
        List<X509Certificate> chain = new ArrayList<>();
        chain.add((X509Certificate) cert);
        return new SigningIdentity(key, chain, describe((X509Certificate) cert));
    }

    private void generateDefaultKey() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_RSA, ANDROID_KEYSTORE);

        Calendar start = Calendar.getInstance();
        Calendar end = Calendar.getInstance();
        end.add(Calendar.YEAR, 40); // APKs commonly use very long-lived signing certs

        KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(
                DEFAULT_ALIAS, KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
                .setKeySize(2048)
                .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
                .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                .setCertificateSubject(new X500Principal(
                        "CN=APK Repacker, OU=Local Signing, O=APK Repacker"))
                .setCertificateSerialNumber(BigInteger.valueOf(System.currentTimeMillis()))
                .setCertificateNotBefore(start.getTime())
                .setCertificateNotAfter(end.getTime())
                .build();

        kpg.initialize(spec);
        kpg.generateKeyPair(); // stored under DEFAULT_ALIAS; private key is non-exportable
    }

    /**
     * Loads a user-provided keystore (advanced). The stream and passwords stay in memory
     * and are never persisted or transmitted.
     *
     * @param type "PKCS12" or "JKS"/"BKS" as appropriate.
     */
    public SigningIdentity importFromKeystore(InputStream keystoreStream, String type,
                                              char[] storePassword, String alias,
                                              char[] keyPassword) throws Exception {
        KeyStore ks = KeyStore.getInstance(type);
        ks.load(keystoreStream, storePassword);

        String useAlias = alias;
        if (useAlias == null || useAlias.isEmpty()) {
            Enumeration<String> aliases = ks.aliases();
            if (!aliases.hasMoreElements()) {
                throw new IllegalArgumentException("Keystore contains no entries.");
            }
            useAlias = aliases.nextElement();
        }

        PrivateKey key = (PrivateKey) ks.getKey(useAlias,
                keyPassword != null ? keyPassword : storePassword);
        if (key == null) {
            throw new IllegalArgumentException("No private key under alias: " + useAlias);
        }
        Certificate[] chain = ks.getCertificateChain(useAlias);
        if (chain == null || chain.length == 0) {
            throw new IllegalArgumentException("No certificate chain under alias: " + useAlias);
        }
        List<X509Certificate> certs = new ArrayList<>();
        for (Certificate c : chain) {
            certs.add((X509Certificate) c);
        }
        return new SigningIdentity(key, certs,
                "Imported: " + describe(certs.get(0)));
    }

    private static String describe(X509Certificate cert) {
        return cert.getSubjectX500Principal().getName()
                + " · " + cert.getSigAlgName()
                + " · serial " + cert.getSerialNumber();
    }
}
