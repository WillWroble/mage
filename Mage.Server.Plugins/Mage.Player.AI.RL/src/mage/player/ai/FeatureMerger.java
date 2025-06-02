package mage.player.ai;

import java.util.*;

public class FeatureMerger {
    /**
     * Computes an ignore list for features.
     * Drops features that perfectly co-occurred with another feature (and fired at least once).
     * Also drops features that never occurred at all.
     *
     * @param stateVectorsSet List of Sets of Integers, where each Set contains the active global feature indices for a state.
     * @return A Set of feature indices to ignore.
     */
    public static Set<Integer> computeIgnoreList(List<Set<Integer>> stateVectorsSet) {
        int S = StateEncoder.indexCount;
        int M = stateVectorsSet.size();
        if (M == 0) {
            return Collections.emptySet();
        }

        // patterns[f] will be a BitSet indicating in which of the M stateVectors feature 'f' occurred.
        BitSet[] patterns = new BitSet[S];
        for (int f = 0; f < S; f++) {
            patterns[f] = new BitSet(M);
        }

        for (int row = 0; row < M; row++) {
            Set<Integer> activeIndices = stateVectorsSet.get(row);
            if (activeIndices != null) { // Check for null set if that's possible in your data
                for (Integer activeFeatureIndex : activeIndices) { // Iterating over Integer objects
                    // Ensure the index is within the bounds of our patterns array
                    if (activeFeatureIndex != null && activeFeatureIndex >= 0 && activeFeatureIndex < S) {
                        patterns[activeFeatureIndex].set(row);
                    } else {
                        // Log or handle out-of-bounds or null index if necessary
                        // System.err.println("Warning: Feature index " + activeFeatureIndex + " is out of bounds/null for S=" + S);
                    }
                }
            }
        }

        Map<BitSet, Integer> representative = new HashMap<>(S);
        Set<Integer> ignoreList = new HashSet<>();

        for (int f = 0; f < S; f++) {
            BitSet pattern = patterns[f];
            if (pattern.isEmpty()) {
                // Feature f never occurred in any stateVector - add to ignore list
                ignoreList.add(f);
                continue;
            }

            BitSet key = (BitSet) pattern.clone();
            Integer firstFeatureWithThisPattern = representative.get(key);

            if (firstFeatureWithThisPattern == null) {
                representative.put(key, f);
            } else {
                ignoreList.add(f);
            }
        }
        return ignoreList;
    }

    /**
     * Computes an ignore list for features using LabeledState objects.
     * Assumes LabeledState.activeGlobalIndices is an int[] of active global feature indices.
     *
     * @param labeledStates List of LabeledState objects.
     * @return A Set of feature indices to ignore.
     */
    public static Set<Integer> computeIgnoreListFromLS(List<LabeledState> labeledStates) {
        int M = labeledStates.size();
        int S = StateEncoder.indexCount;
        if (M == 0) {
            return Collections.emptySet();
        }

        BitSet[] patterns = new BitSet[S];
        for (int f = 0; f < S; f++) {
            patterns[f] = new BitSet(M);
        }

        for (int row = 0; row < M; row++) {
            // Assuming LabeledState now has a field like 'activeGlobalIndices' of type int[]
            int[] activeIndices = labeledStates.get(row).stateVector;
            for (int activeFeatureIndex : activeIndices) {
                if (activeFeatureIndex >= 0 && activeFeatureIndex < S) {
                    patterns[activeFeatureIndex].set(row);
                } else {
                    // Log or handle out-of-bounds index
                }
            }
        }

        // The rest of the logic is identical to computeIgnoreList
        Map<BitSet, Integer> representative = new HashMap<>(S);
        Set<Integer> ignoreList = new HashSet<>();

        for (int f = 0; f < S; f++) {
            BitSet pattern = patterns[f];
            if (pattern.isEmpty()) {
                ignoreList.add(f);
                continue;
            }
            BitSet key = (BitSet) pattern.clone();
            Integer first = representative.get(key);
            if (first == null) {
                representative.put(key, f);
            } else {
                ignoreList.add(f);
            }
        }
        return ignoreList;
    }
}