package mage.player.ai;

import java.io.*;
import java.util.HashSet;
import java.util.List;
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
    public LabeledState(Set<Integer> stateIndices, int[] actionVec, double label) {
        // clone to ensure immutability
        this.stateVector = stateIndices.stream()
                .mapToInt(Integer::intValue)
                .toArray();
        this.actionVector= new double[actionVec.length];
        for (int i = 0; i < actionVec.length; i++) {
            this.actionVector[i] = (double) actionVec[i]; // Explicit cast
        }
        this.resultLabel = label;
    }
    public void compress(Set<Integer> ignoreList) {
        stateVector = FeatureMerger.getCompressedVectorArray(ignoreList, stateVector);
    }

    /**
     * Persist this labeled state to the given DataOutputStream.
     * Caller must write header (record count, S, wordsPerState) before calling.
     * @param out            DataOutputStream to write to
     * @throws IOException   on I/O error
     */
    public void persist(DataOutputStream out) throws IOException {
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
    public void persist(DataOutputStream out, int mIndex) throws IOException {
        // Convert the BitSet to an array of active indices
        int count = 0;
        for(int index : stateVector) {
            if(index < mIndex) count++;
        }
        // 1) Write the NUMBER of active indices first.
        out.writeInt(count);

        // 2) Write only the active indices themselves.
        for (int index : stateVector) {
            if(index < mIndex) out.writeInt(index);
        }
        // --- The rest of the method remains the same ---
        // 3) Write your action-distribution vector
        for (double p : actionVector) {
            out.writeDouble(p);
        }
        // 4) Write result label
        out.writeDouble(resultLabel);
    }
    public static int getUniqueFeaturesFromBatch(List<LabeledState> all) {
        Set<Integer> uniqueFeatures = new HashSet<>();
        for(LabeledState labeledState : all) {
            for(int i : labeledState.stateVector) {
                uniqueFeatures.add(i);
            }
        }
        return uniqueFeatures.size();
    }
}
