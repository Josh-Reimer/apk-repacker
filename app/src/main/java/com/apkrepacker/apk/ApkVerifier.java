package com.apkrepacker.apk;

import java.io.File;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

/** Verifies a signed APK with apksig and summarizes the result. */
public final class ApkVerifier {

    public static final class VerifyResult {
        public final boolean verified;
        public final boolean v1;
        public final boolean v2;
        public final boolean v3;
        public final String signerSubject;
        public final String signerSummary;
        public final List<String> errors;
        public final List<String> warnings;

        VerifyResult(boolean verified, boolean v1, boolean v2, boolean v3,
                     String signerSubject, String signerSummary,
                     List<String> errors, List<String> warnings) {
            this.verified = verified;
            this.v1 = v1;
            this.v2 = v2;
            this.v3 = v3;
            this.signerSubject = signerSubject;
            this.signerSummary = signerSummary;
            this.errors = errors;
            this.warnings = warnings;
        }
    }

    public VerifyResult verify(File apk, int minSdk, BuildLog log) throws PipelineException {
        try {
            com.android.apksig.ApkVerifier.Builder builder =
                    new com.android.apksig.ApkVerifier.Builder(apk);
            if (minSdk > 0) {
                builder.setMinCheckedPlatformVersion(minSdk);
            }
            com.android.apksig.ApkVerifier.Result result = builder.build().verify();

            List<String> errors = new ArrayList<>();
            for (com.android.apksig.ApkVerifier.IssueWithParams i : result.getErrors()) {
                errors.add(i.toString());
            }
            List<String> warnings = new ArrayList<>();
            for (com.android.apksig.ApkVerifier.IssueWithParams i : result.getWarnings()) {
                warnings.add(i.toString());
            }

            String subject = "(none)";
            String summary = "(no signer certificate)";
            List<X509Certificate> certs = result.getSignerCertificates();
            if (certs != null && !certs.isEmpty()) {
                X509Certificate c = certs.get(0);
                subject = c.getSubjectX500Principal().getName();
                summary = subject + "\n"
                        + "Algorithm: " + c.getSigAlgName() + "\n"
                        + "Serial: " + c.getSerialNumber() + "\n"
                        + "Valid: " + c.getNotBefore() + " → " + c.getNotAfter();
            }

            VerifyResult vr = new VerifyResult(
                    result.isVerified(),
                    result.isVerifiedUsingV1Scheme(),
                    result.isVerifiedUsingV2Scheme(),
                    result.isVerifiedUsingV3Scheme(),
                    subject, summary, errors, warnings);

            log.info("Verification: " + (vr.verified ? "VALID" : "INVALID")
                    + " (v1=" + vr.v1 + " v2=" + vr.v2 + " v3=" + vr.v3 + ")");
            for (String w : warnings) {
                log.warn("verify: " + w);
            }
            for (String er : errors) {
                log.error("verify: " + er);
            }
            return vr;
        } catch (Exception e) {
            throw new PipelineException(Stage.VERIFY,
                    "Verification failed: " + e.getMessage(), e);
        }
    }
}
