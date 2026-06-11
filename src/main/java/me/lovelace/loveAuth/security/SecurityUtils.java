package me.lovelace.loveAuth.security;

import de.mkammerer.argon2.Argon2;
import de.mkammerer.argon2.Argon2Factory;
import me.lovelace.loveAuth.config.ConfigManager;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

public final class SecurityUtils {
    private static final Argon2 ARGON2 = Argon2Factory.create(Argon2Factory.Argon2Types.ARGON2id);
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int GCM_IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;

    private SecurityUtils() {}

    public static String hashPassword(String password) {
        return ARGON2.hash(10, 65536, 1, password.toCharArray());
    }

    public static boolean verifyPassword(String raw, String hash) {
        return ARGON2.verify(hash, raw.toCharArray());
    }

    public static String encrypt(String plaintext, SecretKey key) {
        try {
            byte[] iv = new byte[GCM_IV_BYTES];
            SECURE_RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            ByteBuffer buffer = ByteBuffer.allocate(iv.length + encrypted.length);
            buffer.put(iv);
            buffer.put(encrypted);
            return Base64.getEncoder().encodeToString(buffer.array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String decrypt(String ciphertext, SecretKey key) {
        try {
            byte[] decoded = Base64.getDecoder().decode(ciphertext);
            ByteBuffer buffer = ByteBuffer.wrap(decoded);
            byte[] iv = new byte[GCM_IV_BYTES];
            buffer.get(iv);
            byte[] encrypted = new byte[buffer.remaining()];
            buffer.get(encrypted);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String hashIp(String ip, SecretKey key) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(key.getEncoded());
            digest.update(ip.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static SecretKey loadOrGenerateMasterKey(ConfigManager config) {
        String encoded = config.getMasterKey();
        if (encoded == null || encoded.isBlank()) {
            try {
                KeyGenerator kg = KeyGenerator.getInstance("AES");
                kg.init(256, SECURE_RANDOM);
                SecretKey key = kg.generateKey();
                config.setMasterKey(Base64.getEncoder().encodeToString(key.getEncoded()));
                return key;
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }
        return new SecretKeySpec(Base64.getDecoder().decode(encoded), "AES");
    }

    public static String loadOrGeneratePepper(ConfigManager config) {
        String pepper = config.getPepper();
        if (pepper == null || pepper.isBlank()) {
            byte[] bytes = new byte[32];
            SECURE_RANDOM.nextBytes(bytes);
            String p = Base64.getEncoder().encodeToString(bytes);
            config.setPepper(p);
            return p;
        }
        return pepper;
    }
}
