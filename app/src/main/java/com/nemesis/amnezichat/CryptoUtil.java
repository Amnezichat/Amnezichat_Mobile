package com.nemesis.amnezichat;

import android.util.Base64;
import android.util.Log;

import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.modes.ChaCha20Poly1305;
import org.bouncycastle.crypto.params.AEADParameters;
import org.bouncycastle.crypto.params.Argon2Parameters;
import org.bouncycastle.crypto.params.KeyParameter;
import org.bouncycastle.crypto.params.ParametersWithIV;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

public class CryptoUtil {

    private static final int NONCE_SIZE = 12;
    private static final int MAC_SIZE_BITS = 128;

    public static String encrypt_data(String plainText, String password) {
        try {
            byte[] salt = new byte[16];
            new SecureRandom().nextBytes(salt);

            String base64Key = new CryptoUtil().deriveKeyFromPasswordWithSalt(password, salt);
            byte[] key = Base64.decode(base64Key, Base64.NO_WRAP);
            if (key.length != 32) throw new IllegalArgumentException("Key must be 32 bytes");

            byte[] nonce = new byte[NONCE_SIZE];
            new SecureRandom().nextBytes(nonce);

            ChaCha20Poly1305 cipher = new ChaCha20Poly1305();
            AEADParameters params = new AEADParameters(new KeyParameter(key), MAC_SIZE_BITS, nonce, null);
            cipher.init(true, params);

            byte[] input = plainText.getBytes(StandardCharsets.UTF_8);
            byte[] output = new byte[cipher.getOutputSize(input.length)];

            int len = cipher.processBytes(input, 0, input.length, output, 0);
            cipher.doFinal(output, len);

            return toHex(salt) + ":" + toHex(nonce) + ":" + toHex(output);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public static String decrypt_data(String encryptedText, String password) {
        try {
            String[] parts = encryptedText.split(":");
            if (parts.length != 3) throw new IllegalArgumentException("Invalid encrypted format");

            byte[] salt = fromHex(parts[0]);
            byte[] nonce = fromHex(parts[1]);
            byte[] cipherText = fromHex(parts[2]);

            String base64Key = new CryptoUtil().deriveKeyFromPasswordWithSalt(password, salt);
            byte[] key = Base64.decode(base64Key, Base64.NO_WRAP);
            if (key.length != 32) throw new IllegalArgumentException("Key must be 32 bytes");

            ChaCha20Poly1305 cipher = new ChaCha20Poly1305();
            AEADParameters params = new AEADParameters(new KeyParameter(key), MAC_SIZE_BITS, nonce, null);
            cipher.init(false, params);

            byte[] output = new byte[cipher.getOutputSize(cipherText.length)];

            int len = cipher.processBytes(cipherText, 0, cipherText.length, output, 0);
            cipher.doFinal(output, len);

            return new String(output, StandardCharsets.UTF_8);
        } catch (Exception e) {
            e.printStackTrace();
            return "DECRYPTION_FAILED";
        }
    }

    private String deriveKeyFromPasswordWithSalt(String password, byte[] salt) {
        try {
            Argon2Parameters params = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                    .withSalt(salt)
                    .withIterations(2)
                    .withMemoryAsKB(19456)
                    .withParallelism(1)
                    .build();

            Argon2BytesGenerator generator = new Argon2BytesGenerator();
            generator.init(params);

            byte[] passwordBytes = password.getBytes(StandardCharsets.UTF_8);
            byte[] key = new byte[32];
            generator.generateBytes(passwordBytes, key);

            return Base64.encodeToString(key, Base64.NO_WRAP);
        } catch (Exception e) {
            Log.e("KeyDerivation", "deriveKeyFromPasswordWithSalt failed", e);
            throw new RuntimeException("Key derivation failed: " + e.getMessage(), e);
        }
    }

    private static String toHex(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) sb.append(String.format("%02x", b & 0xff));
        return sb.toString();
    }

    private static byte[] fromHex(String hex) {
        int len = hex.length();
        if (len % 2 != 0) throw new IllegalArgumentException("Invalid hex string");
        byte[] out = new byte[len / 2];
        for (int i = 0; i < len; i += 2)
            out[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16));
        return out;
    }
}
