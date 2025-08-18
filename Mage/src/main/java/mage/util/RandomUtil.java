package mage.util;

import java.awt.Color;
import java.util.Collection;
import java.util.Random;

/**
 * A thread-safe utility class for random number generation.
 *
 * This class provides a drop-in replacement for the original RandomUtil but uses a
 * ThreadLocal variable to ensure that each thread has its own independent instance
 * of a Random object. This prevents race conditions and ensures that setting a seed
 * in one thread does not affect the random number generation in any other thread,
 * which is critical for deterministic, parallel simulations.
 */
public final class RandomUtil {

    /**
     * A ThreadLocal storage for Random instances.
     *
     * By using ThreadLocal.withInitial, we ensure that a new Random object is
     * created for each thread the first time it accesses this variable. Subsequent
     * calls to .get() on the same thread will return the same instance.
     */
    private static final ThreadLocal<Random> threadLocalRandom = ThreadLocal.withInitial(Random::new);

    /**
     * Private constructor to prevent instantiation of this utility class.
     */
    private RandomUtil() {
    }

    /**
     * Returns the Random object for the current thread.
     *
     * @return The thread-specific Random instance.
     */
    public static Random getRandom() {
        return threadLocalRandom.get();
    }

    /**
     * Returns the next pseudorandom, uniformly distributed int value from the
     * current thread's random number generator's sequence.
     *
     * @return The next pseudorandom int.
     */
    public static int nextInt() {
        return threadLocalRandom.get().nextInt();
    }

    /**
     * Returns a pseudorandom, uniformly distributed int value between 0 (inclusive)
     * and the specified value (exclusive), drawn from the current thread's
     * random number generator's sequence.
     *
     * @param max The upper bound (exclusive). Must be positive.
     * @return The next pseudorandom int within the specified bound.
     */
    public static int nextInt(int max) {
        return threadLocalRandom.get().nextInt(max);
    }

    /**
     * Returns the next pseudorandom, uniformly distributed boolean value from the
     * current thread's random number generator's sequence.
     *
     * @return The next pseudorandom boolean.
     */
    public static boolean nextBoolean() {
        return threadLocalRandom.get().nextBoolean();
    }

    /**
     * Returns the next pseudorandom, uniformly distributed double value between
     * 0.0 and 1.0 from the current thread's random number generator's sequence.
     *
     * @return The next pseudorandom double.
     */
    public static double nextDouble() {
        return threadLocalRandom.get().nextDouble();
    }

    /**
     * Generates a random color using the current thread's Random instance.
     *
     * @return A new random Color.
     */
    public static Color nextColor() {
        // Note: No need to call RandomUtil.nextInt() here, as it would be redundant.
        // We can directly access the thread's Random instance.
        Random r = threadLocalRandom.get();
        return new Color(r.nextInt(256), r.nextInt(256), r.nextInt(256));
    }

    /**
     * Sets the seed of the current thread's random number generator.
     * This is the key method for ensuring deterministic parallel tests.
     *
     * @param newSeed The initial seed.
     */
    public static void setSeed(long newSeed) {
        threadLocalRandom.get().setSeed(newSeed);
    }

    /**
     * Selects a random element from a given collection using the current thread's
     * Random instance.
     *
     * @param collection The collection to select an element from.
     * @param <T> The type of the elements in the collection.
     * @return A randomly selected element, or null if the collection is empty.
     */
    public static <T> T randomFromCollection(Collection<T> collection) {
        if (collection == null || collection.isEmpty()) {
            return null;
        }
        if (collection.size() == 1) {
            return collection.iterator().next();
        }

        // Use the thread-local random instance for the selection
        int rand = nextInt(collection.size());
        int count = 0;
        for (T current : collection) {
            if (count == rand) {
                return current;
            }
            count++;
        }
        return null; // Should be unreachable if collection is not empty
    }
}
