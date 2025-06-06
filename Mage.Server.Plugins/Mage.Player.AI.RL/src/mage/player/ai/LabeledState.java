package mage.player.ai;

import java.io.*;
import java.util.BitSet;
import java.util.HashSet;
import java.util.Set;

/**
 * Represents a single training example: a bitset-encoded game state,
 * an integer action index (one-hot), and a scalar result label.
 */
public class LabeledState implements Serializable {
    private static final long serialVersionUID = 1L;

    /** Bit-packed state vector of length S. */
    public int[] stateVector;
    /** Index of the chosen action (one-hot). */
    public final double[] actionVector;
    /** Value label (e.g., -1.0 for loss, +1.0 for win). */
    public final double resultLabel;

    /**
     * Construct a labeled state.
     * @param stateIndices  indices of active features
     * @param actionVec    vec of the action distribution
     * @param label        scalar outcome label
     */
    public LabeledState(Set<Integer> stateIndices, double[] actionVec, double label) {
        // clone to ensure immutability
        this.stateVector = stateIndices.stream()                       // 1. Get a Stream<Integer>
                .mapToInt(Integer::intValue)    // 2. Convert to IntStream (unboxes Integer to int)
                .toArray();
        this.actionVector = actionVec;
        this.resultLabel = label;
    }
    public void compress(StateEncoder encoder) {
        stateVector = encoder.getCompressedVectorArray(stateVector);
    }
    /**
     * Persist this labeled state to the given DataOutputStream.
     * Caller must write header (record count, S, wordsPerState) before calling.
     * @param out            DataOutputStream to write to
     * @throws IOException   on I/O error
     */
    public void persist(DataOutputStream out) throws IOException {
        // Convert the BitSet to an array of active indices

        // 1) Write the NUMBER of active indices first.
        out.writeInt(stateVector.length);

        // 2) Write only the active indices themselves.
        for (int index : stateVector) {
            out.writeInt(index);
        }
        // --- The rest of the method remains the same ---
        // 3) Write your action-distribution vector
        for (double p : actionVector) {
            out.writeDouble(p);
        }
        // 4) Write result label
        out.writeDouble(resultLabel);
    }
}
