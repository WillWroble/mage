package mage.player.ai;

import java.util.*;

public class FeatureMerger {

    /**
     * Drops only those features that perfectly co-occurred (and fired at least once) across all M states.
     * Leaves zero-count features intact.
     */
    public static Set<Integer> computeIgnoreList(List<BitSet> stateVectors) {
        int M = stateVectors.size();
        if (M == 0) return Collections.emptySet();

        int S = StateEncoder.indexCount;
        BitSet[] patterns = new BitSet[S];
        for (int f = 0; f < S; f++) patterns[f] = new BitSet(M);
        for (int row = 0; row < M; row++) {
            BitSet vec = stateVectors.get(row);
            for (int f = vec.nextSetBit(0); f >= 0; f = vec.nextSetBit(f + 1)) {
                patterns[f].set(row);
            }
        }

        Map<BitSet, Integer> representative = new HashMap<>(S);
        Set<Integer> ignoreList = new HashSet<>();

        for (int f = 0; f < S; f++) {
            BitSet pattern = patterns[f];
            if (pattern.isEmpty()) {
                // never occurred → keep it
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
