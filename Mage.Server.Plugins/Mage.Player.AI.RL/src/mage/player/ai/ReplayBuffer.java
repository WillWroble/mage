package mage.player.ai;

import mage.game.Game;
import mage.game.GameState;
import mage.util.RandomUtil;

import java.io.Serializable;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * A fixed-size replay buffer to store experiences for training reinforcement learning models.
 * It acts as a sliding window (FIFO): when the buffer is full, adding a new element
 * removes the oldest one. This class is thread-safe for additions.
 */
public class ReplayBuffer implements Serializable {
    private static final long serialVersionUID = 1L; // For serialization
    private final Deque<Game> buffer;
    private final int capacity;
    private final Random random = new Random();

    public ReplayBuffer(int capacity) {
        this.capacity = capacity;
        // Use a thread-safe Deque to allow additions from multiple game-playing threads if needed in the future.
        this.buffer = new ConcurrentLinkedDeque<>();
    }

    /**
     * Adds a single GameState to the buffer.
     * If the buffer is at capacity, the oldest element is removed.
     *
     * @param state The GameState to add.
     */
    public synchronized void add(Game state) {
        if (buffer.size() >= capacity) {
            buffer.pollFirst(); // Remove the oldest element
        }
        buffer.add(state);
    }

    /**
     * Adds a collection of GameStates to the buffer.
     *
     * @param states The collection of states from a completed game.
     */
    public void addAll(Collection<Game> states) {
        for (Game state : states) {
            add(state); // Use the synchronized add method
        }
    }

    /**
     * Samples a random batch of GameStates from the buffer.
     *
     * @param batchSize The number of states to sample.
     * @return A list containing the sampled GameStates. Returns an empty list if the buffer is empty.
     */
    public List<Game> sample(int batchSize) {
        // Create a temporary list for random access, as Deque doesn't support get(index)
        List<Game> tempList = new ArrayList<>(buffer);
        if (tempList.isEmpty()) {
            return new ArrayList<>();
        }

        List<Game> sampleBatch = new ArrayList<>();
        for (int i = 0; i < batchSize; i++) {
            int randomIndex = RandomUtil.nextInt(tempList.size());
            sampleBatch.add(tempList.get(randomIndex));
        }
        return sampleBatch;
    }

    /**
     * Returns the current number of elements in the buffer.
     *
     * @return The size of the buffer.
     */
    public int size() {
        return buffer.size();
    }

    /**
     * Returns the maximum capacity of the buffer.
     *
     * @return The capacity.
     */
    public int getCapacity() {
        return capacity;
    }

    public boolean isReadyForSampling(int requiredSize) {
        return buffer.size() >= requiredSize;
    }
}