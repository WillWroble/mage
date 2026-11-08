package spellbench.kit.core;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Random;

/**
 * The kit's seeds (design Section 4.5). Nothing else seeds the kit: not {@code game_id}, {@code request_id}, object
 * ids, time or the environment.
 *
 * <pre>
 * game_key   = HMAC-SHA256(key = agent_seed as 8 big-endian bytes, msg = "spellbench-xmage-kit/v1/game")
 * world_seed = HMAC-SHA256(key = game_key, msg = "world:" + seat_step + ":" + k)
 * stream     = HMAC-SHA256(key = world_seed, msg = "&lt;purpose&gt;:&lt;n&gt;")
 * </pre>
 */
public final class Seeds {

    private Seeds() {
    }

    public static byte[] hmac(byte[] key, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static byte[] gameKey(long agentSeed) {
        byte[] k = new byte[8];
        for (int i = 7; i >= 0; i--) {
            k[i] = (byte) agentSeed;
            agentSeed >>>= 8;
        }
        return hmac(k, "spellbench-xmage-kit/v1/game");
    }

    public static byte[] worldSeed(byte[] gameKey, long seatStep, int k) {
        return hmac(gameKey, "world:" + seatStep + ":" + k);
    }

    /** The seed of stream {@code purpose:n} of a world. */
    public static byte[] streamSeed(byte[] worldSeed, String purpose, long n) {
        return hmac(worldSeed, purpose + ":" + n);
    }

    public static Random stream(byte[] worldSeed, String purpose, long n) {
        return new Stream(streamSeed(worldSeed, purpose, n));
    }

    public static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) {
            sb.append(String.format("%02x", x & 0xff));
        }
        return sb.toString();
    }

    public static byte[] unhex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        }
        return out;
    }

    /**
     * A {@link Random} whose bits come from HMAC-SHA256 in counter mode (block k = HMAC(seed, k as 8 big-endian
     * bytes)). Every inherited method draws through {@link #next(int)}, so java.util.Random's 48-bit state is never
     * used.
     */
    public static final class Stream extends Random {
        private static final long serialVersionUID = 1L;

        private final transient Mac mac;
        private final byte[] block = new byte[32];
        private final byte[] counterBytes = new byte[8];
        private int pos = 32;
        private long counter;
        private long draws;

        public Stream(byte[] seed) {
            super(0L);
            try {
                mac = Mac.getInstance("HmacSHA256");
                mac.init(new SecretKeySpec(seed, "HmacSHA256"));
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public void setSeed(long seed) {
            // keyed streams ignore reseeding (also called by Random's constructor)
        }

        @Override
        protected synchronized int next(int bits) {
            if (pos == block.length) {
                long c = counter++;
                for (int i = 7; i >= 0; i--) {
                    counterBytes[i] = (byte) c;
                    c >>>= 8;
                }
                mac.update(counterBytes);
                try {
                    mac.doFinal(block, 0);
                } catch (javax.crypto.ShortBufferException e) {
                    throw new IllegalStateException(e);
                }
                pos = 0;
            }
            int v = ((block[pos] & 0xff) << 24) | ((block[pos + 1] & 0xff) << 16)
                    | ((block[pos + 2] & 0xff) << 8) | (block[pos + 3] & 0xff);
            pos += 4;
            draws++;
            return v >>> (32 - bits);
        }

        public synchronized long draws() {
            return draws;
        }
    }
}
