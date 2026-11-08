package mage.player.spellbench;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;

/**
 * HMAC-SHA256 helpers and the secret derivations of Spellbench protocol v2 (Sections 5.3 and 11.6).
 * Every key is raw bytes and every message is ASCII, as the spec states.
 */
public final class Secrets {

    private Secrets() {
    }

    public static Mac newMac(byte[] key) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    public static byte[] hmac(byte[] key, byte[] message) {
        return newMac(key).doFinal(message);
    }

    public static byte[] hmac(byte[] key, String message) {
        return hmac(key, message.getBytes(StandardCharsets.UTF_8));
    }

    public static byte[] sha256(byte[]... parts) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (byte[] part : parts) {
                md.update(part);
            }
            return md.digest();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * Section 11.6: game_secret(i) = HMAC-SHA256(run_secret, "spellbench/v2/game:" + decimal(i)).
     */
    public static byte[] gameSecret(byte[] runSecret, long index) {
        return hmac(runSecret, "spellbench/v2/game:" + index);
    }

    /**
     * Section 11.6 recommended stream seed: HMAC-SHA256(game_secret, "spellbench/v2/rng:scope:purpose:n").
     */
    public static byte[] streamSeed(byte[] gameSecret, String scope, String purpose, long n) {
        return hmac(gameSecret, "spellbench/v2/rng:" + scope + ":" + purpose + ":" + n);
    }

    public static byte[] fromHex(String hex) {
        if (hex.length() % 2 != 0) {
            throw new IllegalArgumentException("odd hex length");
        }
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
        }
        return out;
    }

    public static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
        }
        return sb.toString();
    }
}
