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
    public final int actionIndex;
    /** Value label (e.g., -1.0 for loss, +1.0 for win). */
    public final double resultLabel;

    /**
     * Construct a labeled state.
     * @param stateBitset  BitSet of active features
     * @param actionIdx    index of the chosen action
     * @param label        scalar outcome label
     */
    public LabeledState(BitSet stateBitset, int actionIdx, double label) {
        // clone to ensure immutability
        this.stateVector = (BitSet) stateBitset.clone();
        this.actionIndex = actionIdx;
        this.resultLabel = label;
    }

    /**
     * Persist this labeled state to the given DataOutputStream.
     * Caller must write header (record count, S, wordsPerState) before calling.
     * @param out            DataOutputStream to write to
     * @param totalFeatures  total feature count S
     * @throws IOException   on I/O error
     */
    public void persist(DataOutputStream out, int totalFeatures) throws IOException {
        int wordsPerState = (totalFeatures + 63) >>> 6;  // ceil(S/64)

        // pack stateVector into a fixed-size long[]
        long[] raw = stateVector.toLongArray();
        long[] packed = new long[wordsPerState];
        System.arraycopy(raw, 0, packed, 0, raw.length);
        for (long w : packed) {
            out.writeLong(w);
        }

        // write action index
        out.writeInt(actionIndex);
        // write result label
        out.writeDouble(resultLabel);
    }

    /**
     * Load a single LabeledState from the given DataInputStream.
     * Caller must have read header (S, wordsPerState) before calling.
     * @param in             DataInputStream to read from
     * @param totalFeatures  total feature count S
     * @return               a new LabeledState
     * @throws IOException   on I/O error
     */
    public static LabeledState load(DataInputStream in, int totalFeatures) throws IOException {
        int wordsPerState = (totalFeatures + 63) >>> 6;

        long[] packed = new long[wordsPerState];
        for (int i = 0; i < wordsPerState; i++) {
            packed[i] = in.readLong();
        }
        BitSet state = BitSet.valueOf(packed);

        int actionIdx = in.readInt();
        double label  = in.readDouble();

        return new LabeledState(state, actionIdx, label);
    }
}
