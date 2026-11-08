package mage.player.spellbench.rng;

import mage.player.spellbench.Secrets;

import javax.crypto.Mac;
import java.util.Random;

/**
 * A {@link Random} whose bits come from HMAC-SHA256 in counter mode, keyed by a 32-byte stream seed:
 * block k = HMAC-SHA256(seed, k as 8 big-endian bytes). Every inherited method (nextInt(bound), nextLong,
 * nextBoolean, nextDouble, Collections.shuffle) draws through {@link #next(int)}, so the 48-bit state of
 * java.util.Random is never used. Unlike SplitMix64, outputs do not reveal the state, so a seat that sees its own
 * shuffles cannot predict its own later draws.
 */
final class HmacStream extends Random {

    private static final long serialVersionUID = 1L;

    private final transient Mac mac;
    private final byte[] block = new byte[32];
    private final byte[] counterBytes = new byte[8];
    private int pos = 32;
    private long counter;
    private long draws;

    HmacStream(byte[] seed) {
        super(0L);
        this.mac = Secrets.newMac(seed);
    }

    @Override
    public void setSeed(long seed) {
        // streams are keyed by the game secret; reseeding is ignored (also called by Random's constructor)
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

    synchronized long draws() {
        return draws;
    }
}
