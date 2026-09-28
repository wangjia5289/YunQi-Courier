package yunqi.courier.encryption.aesgcm;

import yunqi.courier.common.exception.CourierException;
import yunqi.courier.kernel.spi.core.SPI;
import yunqi.courier.kernel.spi.encryption.Encryptor;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/** AES/GCM payload encryption with a per-message random nonce. */
@SPI("aes-gcm")
public final class AesGcmEncryptor implements Encryptor {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int NONCE_LENGTH = 12;
    private static final int TAG_BITS = 128;

    private final SecureRandom random = new SecureRandom();
    private volatile SecretKeySpec key;

    @Override
    public void configure(String encodedKey) {
        if (encodedKey == null || encodedKey.isBlank()) {
            throw new IllegalArgumentException("AES-GCM key must be base64 encoded");
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(encodedKey);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("AES-GCM key must be valid base64", e);
        }
        if (decoded.length != 16 && decoded.length != 24 && decoded.length != 32) {
            throw new IllegalArgumentException("AES-GCM key must decode to 16, 24 or 32 bytes");
        }
        key = new SecretKeySpec(decoded, "AES");
    }

    @Override
    public byte[] encrypt(byte[] rawBytes) {
        SecretKeySpec configuredKey = requireKey();
        byte[] nonce = new byte[NONCE_LENGTH];
        random.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, configuredKey, new GCMParameterSpec(TAG_BITS, nonce));
            byte[] ciphertext = cipher.doFinal(rawBytes == null ? new byte[0] : rawBytes);
            byte[] result = new byte[nonce.length + ciphertext.length];
            System.arraycopy(nonce, 0, result, 0, nonce.length);
            System.arraycopy(ciphertext, 0, result, nonce.length, ciphertext.length);
            return result;
        } catch (GeneralSecurityException e) {
            throw new CourierException("AES-GCM encryption failed", e);
        }
    }

    @Override
    public byte[] decrypt(byte[] encryptedBytes) {
        SecretKeySpec configuredKey = requireKey();
        if (encryptedBytes == null || encryptedBytes.length < NONCE_LENGTH + 16) {
            throw new CourierException("Invalid AES-GCM payload");
        }
        byte[] nonce = new byte[NONCE_LENGTH];
        System.arraycopy(encryptedBytes, 0, nonce, 0, nonce.length);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, configuredKey, new GCMParameterSpec(TAG_BITS, nonce));
            return cipher.doFinal(encryptedBytes, NONCE_LENGTH, encryptedBytes.length - NONCE_LENGTH);
        } catch (GeneralSecurityException e) {
            throw new CourierException("AES-GCM decryption failed", e);
        }
    }

    private SecretKeySpec requireKey() {
        SecretKeySpec configuredKey = key;
        if (configuredKey == null) {
            throw new IllegalStateException("AES-GCM encryptor is not configured");
        }
        return configuredKey;
    }
}
