package app.airnavig;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import java.security.KeyStore;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Chiffrement d'AIR Navig.
 *  - Données de l'appareil (mots de passe, phrase de synchro) : clé AES-256 rangée dans le
 *    coffre matériel d'Android (AndroidKeyStore), jamais lisible par l'appli elle-même.
 *  - Fichier de synchronisation : clé dérivée de ta phrase secrète (PBKDF2-SHA256, 210 000 tours),
 *    chiffrement AES-256-GCM (confidentialité + détection de toute modification).
 */
final class Crypto {
    private static final String KS = "AndroidKeyStore";
    static final int PBKDF2_ROUNDS = 210_000;
    private static final SecureRandom RNG = new SecureRandom();

    private Crypto() {}

    static byte[] random(int n) {
        byte[] b = new byte[n];
        RNG.nextBytes(b);
        return b;
    }

    /** Clé de l'appareil (créée au premier usage). */
    static synchronized SecretKey deviceKey(String alias) throws Exception {
        KeyStore ks = KeyStore.getInstance(KS);
        ks.load(null);
        if (ks.containsAlias(alias)) {
            KeyStore.Entry e = ks.getEntry(alias, null);
            if (e instanceof KeyStore.SecretKeyEntry) return ((KeyStore.SecretKeyEntry) e).getSecretKey();
        }
        KeyGenerator g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KS);
        g.init(new KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return g.generateKey();
    }

    /** Chiffre avec une clé du coffre : IV(12) + texte chiffré. */
    static byte[] sealDevice(SecretKey k, byte[] plain) throws Exception {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, k);
        byte[] iv = c.getIV();
        byte[] ct = c.doFinal(plain);
        byte[] out = new byte[iv.length + ct.length];
        System.arraycopy(iv, 0, out, 0, iv.length);
        System.arraycopy(ct, 0, out, iv.length, ct.length);
        return out;
    }

    static byte[] openDevice(SecretKey k, byte[] blob) throws Exception {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, k, new GCMParameterSpec(128, blob, 0, 12));
        return c.doFinal(blob, 12, blob.length - 12);
    }

    /** Clé dérivée d'une phrase secrète. */
    static SecretKey derive(char[] pass, byte[] salt) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(pass, salt, PBKDF2_ROUNDS, 256);
        try {
            byte[] k = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            return new SecretKeySpec(k, "AES");
        } finally {
            spec.clearPassword();
        }
    }

    static byte[] seal(SecretKey k, byte[] plain, byte[] aad) throws Exception {
        byte[] iv = random(12);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, k, new GCMParameterSpec(128, iv));
        if (aad != null) c.updateAAD(aad);
        byte[] ct = c.doFinal(plain);
        byte[] out = new byte[12 + ct.length];
        System.arraycopy(iv, 0, out, 0, 12);
        System.arraycopy(ct, 0, out, 12, ct.length);
        return out;
    }

    static byte[] open(SecretKey k, byte[] blob, int off, int len, byte[] aad) throws Exception {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, k, new GCMParameterSpec(128, blob, off, 12));
        if (aad != null) c.updateAAD(aad);
        return c.doFinal(blob, off + 12, len - 12);
    }
}
