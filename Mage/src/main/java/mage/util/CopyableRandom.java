package mage.util;

import java.util.Random;

/**
 * java.util.Random with the same sequence (same LCG and gaussian cache), whose state can be copied directly.
 * Random keeps its state private, so copying a plain Random needs a serialization round trip (see RandomUtil.deepCopy).
 */
public class CopyableRandom extends Random {

    private static final long MULTIPLIER = 0x5DEECE66DL;
    private static final long ADDEND = 0xBL;
    private static final long MASK = (1L << 48) - 1;

    // no initializers: Random's constructor sets them through setSeed before this class's initializers would run
    private long seed;
    private double nextNextGaussian;
    private boolean haveNextNextGaussian;

    public CopyableRandom() {
        super();
    }

    private CopyableRandom(final CopyableRandom random) {
        super(0);
        this.seed = random.seed;
        this.nextNextGaussian = random.nextNextGaussian;
        this.haveNextNextGaussian = random.haveNextNextGaussian;
    }

    public CopyableRandom copy() {
        return new CopyableRandom(this);
    }

    @Override
    public void setSeed(long seed) {
        this.seed = (seed ^ MULTIPLIER) & MASK;
        this.haveNextNextGaussian = false;
    }

    @Override
    protected int next(int bits) {
        seed = (seed * MULTIPLIER + ADDEND) & MASK;
        return (int) (seed >>> (48 - bits));
    }

    @Override
    public double nextGaussian() {
        if (haveNextNextGaussian) {
            haveNextNextGaussian = false;
            return nextNextGaussian;
        }
        double v1, v2, s;
        do {
            v1 = 2 * nextDouble() - 1;
            v2 = 2 * nextDouble() - 1;
            s = v1 * v1 + v2 * v2;
        } while (s >= 1 || s == 0);
        double multiplier = StrictMath.sqrt(-2 * StrictMath.log(s) / s);
        nextNextGaussian = v2 * multiplier;
        haveNextNextGaussian = true;
        return v1 * multiplier;
    }
}
