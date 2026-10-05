package com.apkrepacker.ui;

import com.apkrepacker.security.KeystoreManager;

/**
 * Process-scoped holder for an optionally-imported signing identity. A
 * {@link KeystoreManager.SigningIdentity} wraps a non-serializable PrivateKey, so it is
 * passed between activities in-process rather than through an Intent. Null means "use the
 * app's default AndroidKeyStore key".
 */
public final class SigningHolder {
    private static volatile KeystoreManager.SigningIdentity imported;

    private SigningHolder() {}

    public static void set(KeystoreManager.SigningIdentity identity) {
        imported = identity;
    }

    public static KeystoreManager.SigningIdentity get() {
        return imported;
    }

    public static void clear() {
        imported = null;
    }
}
