package mage.player.ai;

import java.io.*;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Represents a single training example:
 */
public class LabeledState implements Serializable {
    private static final long serialVersionUID = 1L;
    /** Is the player PlayerA?*/
    public boolean isPlayer;
    /** the type of decision this state represents (use different heads in network)*/
    public MCTSPlayer.NextAction actionType;
    /** Sparse indices vector */
    public Set<Integer> stateVector;
    /** Raw visit distribution */
    public final double[] actionVector;
    /** AI assigned score for the state*/
    public final double stateScore;
    /** Final blended balue label (-1 to 1). */
    public double resultLabel;



    /**
     * Construct a labeled state.
     * @param stateIndices  indices of active features
     * @param actionVec    vec of the action distribution
     * @param score        scalar outcome label
     */
    public LabeledState(Set<Integer> stateIndices, int[] actionVec, double score, MCTSPlayer.NextAction actionType, boolean isPlayer) {
        // clone to ensure immutability
        this.stateVector = stateIndices;
        this.actionVector= new double[actionVec.length];
        for (int i = 0; i < actionVec.length; i++) {
            this.actionVector[i] = (double) actionVec[i]; // Explicit cast
        }
        this.stateScore = score;
        this.actionType = actionType;
        this.isPlayer = isPlayer;

    }
    /**
     * Persist this labeled state to the given DataOutputStream.
     * Caller must write header (record count, S, wordsPerState) before calling.
     * @param out            DataOutputStream to write to
     * @throws IOException   on I/O error
     */
    public void persist(DataOutputStream out) throws IOException {
        // 1) Write the NUMBER of active indices first.
        out.writeInt(stateVector.size());

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
