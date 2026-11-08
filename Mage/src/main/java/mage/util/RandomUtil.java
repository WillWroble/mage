package mage.util;

import java.awt.Color;
import java.io.*;
import java.util.Collection;
import java.util.Random;
import java.util.UUID;

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
     * Pluggable randomness for reproducible games (Spellbench, X-P1). An embedding that runs one game per process
     * installs a source that derives every stream from a per-game secret; with none installed, every method here
     * behaves exactly as before.
     */
    public interface Source {

        /** Generator for a draw that names no owner and no purpose. */
        Random untagged();

        /** Generator for draws over one player's objects, for example that player's library shuffle. */
        Random forPlayer(UUID playerId, String purpose);

        /** Generator for draws that belong to no player, for example coin flips. */
        Random shared(String purpose);

        /** Next object id, so that a rerun reproduces every id. */
        UUID newId();
    }

    private static volatile Source source;

    /** Installs a source for every following draw, or restores the thread-local generators with null. */
    public static void setSource(Source newSource) {
        source = newSource;
    }

    public static Source getSource() {
        return source;
    }

    /** Generator for draws over one player's objects (library order, random cards from a zone of theirs). */
    public static Random forPlayer(UUID playerId, String purpose) {
        Source s = source;
        return s == null ? getRandom() : s.forPlayer(playerId, purpose);
    }

    /** Generator for public draws that belong to no player (coin flips, die rolls, the starting player). */
    public static Random shared(String purpose) {
        Source s = source;
        return s == null ? getRandom() : s.shared(purpose);
    }

    /** New id for a game object: from the installed source, else random. */
    public static UUID newId() {
        Source s = source;
        return s == null ? UUID.randomUUID() : s.newId();
    }

    /**
     * Returns the Random object for the current thread.
     *
     * @return The thread-specific Random instance.
     */
    public static Random getRandom() {
        Source s = source;
        return s == null ? threadLocalRandom.get() : s.untagged();
    }

    /**
     * Returns the next pseudorandom, uniformly distributed int value from the
     * current thread's random number generator's sequence.
     *
     * @return The next pseudorandom int.
     */
    public static int nextInt() {
        return getRandom().nextInt();
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
        return getRandom().nextInt(max);
    }

    /**
     * Returns the next pseudorandom, uniformly distributed boolean value from the
     * current thread's random number generator's sequence.
     *
     * @return The next pseudorandom boolean.
     */
    public static boolean nextBoolean() {
        return getRandom().nextBoolean();
    }

    /**
     * Returns the next pseudorandom, uniformly distributed double value between
     * 0.0 and 1.0 from the current thread's random number generator's sequence.
     *
     * @return The next pseudorandom double.
     */
    public static double nextDouble() {
        return getRandom().nextDouble();
    }

    /**
     * Generates a random color using the current thread's Random instance.
     *
     * @return A new random Color.
     */
    public static Color nextColor() {
        // Note: No need to call RandomUtil.nextInt() here, as it would be redundant.
        // We can directly access the thread's Random instance.
        Random r = getRandom();
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
        return randomFromCollection(collection, getRandom());
    }

    public static <T> T randomFromCollection(Collection<T> collection, Random generator) {
        if (collection == null || collection.isEmpty()) {
            return null;
        }
        if (collection.size() == 1) {
            return collection.iterator().next();
        }

        int rand = generator.nextInt(collection.size());
        int count = 0;
        for (T current : collection) {
            if (count == rand) {
                return current;
            }
            count++;
        }
        return null; // Should be unreachable if collection is not empty
    }
    /**
     * Creates a true deep copy of a Random object's internal state.
     * @param original The Random object to copy.
     * @return A new Random object with the identical state.
     */
    public static Random deepCopy(Random original) {
        if (original instanceof CopyableRandom) {
            return ((CopyableRandom) original).copy();
        }
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ObjectOutputStream oos = new ObjectOutputStream(bos);
            oos.writeObject(original);
            oos.flush();

            ByteArrayInputStream bis = new ByteArrayInputStream(bos.toByteArray());
            ObjectInputStream ois = new ObjectInputStream(bis);
            return (Random) ois.readObject();
        } catch (IOException | ClassNotFoundException e) {
            // This should not happen with java.util.Random
            throw new RuntimeException("Failed to deep copy Random object", e);
        }
    }
    public static byte[] serialize(Random original) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ObjectOutputStream oos = new ObjectOutputStream(bos);
            oos.writeObject(original);
            oos.flush();
            return bos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to serialize Random", e);
        }
    }
}
