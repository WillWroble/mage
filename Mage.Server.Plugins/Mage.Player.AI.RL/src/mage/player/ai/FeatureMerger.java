package mage.player.ai;

import java.util.*;

public class FeatureMerger {

    /**
     * Computes an ignore list of feature indices that are nearly always co-occurring.
     *
     * @param stateVectors A list of boolean arrays, each representing the binary feature vector for one state.
     * @param threshold The minimum co-occurrence ratio (e.g., 0.95) above which one feature can be ignored.
     * @return A set of feature indices to ignore.
     */
    public static Set<Integer> computeIgnoreList(List<boolean[]> stateVectors, double threshold) {
        Set<Integer> ignoreList = new HashSet<>();
        if (stateVectors.isEmpty()) {
            return ignoreList;
        }
        int vectorLength = StateEncoder.indexCount;
        int numStates = stateVectors.size();

        // Count how many times each feature is active.
        int[] featureCounts = new int[vectorLength];
        // Count co-occurrence between each pair of features.
        int[][] coOccurrence = new int[vectorLength][vectorLength];

        for (boolean[] vector : stateVectors) {
            for (int i = 0; i < vectorLength; i++) {
                if (vector[i]) {
                    featureCounts[i]++;
                    for (int j = i + 1; j < vectorLength; j++) {
                        if (vector[j]) {
                            coOccurrence[i][j]++;
                            coOccurrence[j][i]++;
                        }
                    }
                }
            }
        }

        // For each pair of features, check if they nearly always appear together.
        // If so, mark the one with the lower frequency as redundant.
        for (int i = 0; i < vectorLength; i++) {
            for (int j = i + 1; j < vectorLength; j++) {
                if (featureCounts[i] > 0 && featureCounts[j] > 0) {
                    double coRatioI = (double) coOccurrence[i][j] / featureCounts[i];
                    double coRatioJ = (double) coOccurrence[i][j] / featureCounts[j];
                    if (coRatioI >= threshold && coRatioJ >= threshold) {
                        if (featureCounts[i] <= featureCounts[j]) {
                            ignoreList.add(i);
                        } else {
                            ignoreList.add(j);
                        }
                    }
                }
            }
        }
        return ignoreList;
    }
}
