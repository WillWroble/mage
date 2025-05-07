package mage.player.ai;

import java.io.*;
import java.util.BitSet;

/**
 * Represents a single training example: a bitset-encoded game state,
 * an integer action index (one-hot), and a scalar result label.
 */
public class LabeledState implements Serializable {
    private static final long serialVersionUID = 1L;

    /** Bit-packed state vector of length S. */
    public final BitSet stateVector;
    /** Index of the chosen action (one-hot). */
    public final double[] actionVector;
    /** Value label (e.g., -1.0 for loss, +1.0 for win). */
    public final double resultLabel;

    /**
     * Construct a labeled state.
     * @param stateBitset  BitSet of active features
     * @param actionVec    vec of the action distribution
     * @param label        scalar outcome label
     */
    public LabeledState(BitSet stateBitset, double[] actionVec, double label) {
        // clone to ensure immutability
        this.stateVector = (BitSet) stateBitset.clone();
        this.actionVector = actionVec;
        this.resultLabel = label;
    }

    /**
     * Persist this labeled state to the given DataOutputStream.
     * Caller must write header (record count, S, wordsPerState) before calling.
     * @param out            DataOutputStream to write to
     * @throws IOException   on I/O error
     */
    public void persist(DataOutputStream out) throws IOException {
        int wordsPerState = (StateEncoder.COMPRESSED_VECTOR_SIZE + 63) >>> 6;  // ceil(S/64)

        // pack stateVector into a fixed-size long[]
        long[] raw = stateVector.toLongArray();
        long[] packed = new long[wordsPerState];
        System.arraycopy(raw, 0, packed, 0, raw.length);
        for (long w : packed) {
            out.writeLong(w);
        }

        // write your action‐distribution vector instead of a single int
        for (double p : actionVector) {
            out.writeDouble(p);
        }
        // write result label
        out.writeDouble(resultLabel);
    }
}
