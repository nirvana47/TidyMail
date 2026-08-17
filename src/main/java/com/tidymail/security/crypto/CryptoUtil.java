package com.tidymail.security.crypto;

import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import org.springframework.stereotype.Component;

/**
 * Provides AES-256-GCM authenticated encryption and decryption of the OAuth
 * access/refresh tokens stored for linked Gmail and Outlook accounts.
 * <p>
 * Product functionality supported: encrypted secrets at rest for per-owner
 * account linking.
 */
@Component
public class CryptoUtil {

    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH_BITS = 128;
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    private final CryptoProperties cryptoProperties;
    private final SecureRandom secureRandom;

    public CryptoUtil(CryptoProperties cryptoProperties) {
        this.cryptoProperties = cryptoProperties;
        this.secureRandom = new SecureRandom();
    }

    /**
     * Encrypts plaintext with AES-256-GCM and returns the raw 12-byte IV
     * prepended to the ciphertext+tag blob.
     */
    public byte[] encrypt(byte[] plaintext) {
        if (plaintext == null) {
            throw new IllegalArgumentException("Crypto operation failed");
        }
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv);
            cipher.init(Cipher.ENCRYPT_MODE, cryptoProperties.getSecretKey(), gcmSpec);

            byte[] ciphertextWithTag = cipher.doFinal(plaintext);
            byte[] result = new byte[GCM_IV_LENGTH + ciphertextWithTag.length];
            System.arraycopy(iv, 0, result, 0, GCM_IV_LENGTH);
            System.arraycopy(ciphertextWithTag, 0, result, GCM_IV_LENGTH, ciphertextWithTag.length);
            return result;
        } catch (Exception ex) {
            throw new CryptoException("Crypto operation failed", ex);
        }
    }

    /**
     * Decrypts a blob produced by {@link #encrypt}, splitting off the leading
     * IV and authenticating the ciphertext with the GCM tag.
     */
    public byte[] decrypt(byte[] blob) {
        if (blob == null || blob.length < GCM_IV_LENGTH + 1) {
            throw new IllegalArgumentException("Crypto operation failed");
        }
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            System.arraycopy(blob, 0, iv, 0, GCM_IV_LENGTH);

            int ciphertextLength = blob.length - GCM_IV_LENGTH;
            byte[] ciphertextWithTag = new byte[ciphertextLength];
            System.arraycopy(blob, GCM_IV_LENGTH, ciphertextWithTag, 0, ciphertextLength);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv);
            cipher.init(Cipher.DECRYPT_MODE, cryptoProperties.getSecretKey(), gcmSpec);

            return cipher.doFinal(ciphertextWithTag);
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new CryptoException("Crypto operation failed", ex);
        }
    }

    public static class CryptoException extends RuntimeException {
        public CryptoException(String message) {
            super(message);
        }

        public CryptoException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
