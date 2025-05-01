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
        long[] fingerprint = new long[S];
        int[]  counts      = new int[S];
        long   token       = 1;

        // 1) Build fingerprint & counts
        for (BitSet vec : stateVectors) {
            long t = token++;
            for (int i = 0; i < S; i++) {
                if (vec.get(i)) {
                    counts[i]++;
                    fingerprint[i] ^= t;
                }
            }
        }

        // 2) Group only the features that actually occurred (counts[i] > 0)
        Map<Long, List<Integer>> groups = new HashMap<>(S);
        for (int i = 0; i < S; i++) {
            if (counts[i] == 0) continue;       // **skip zero‐count features**
            groups
                    .computeIfAbsent(fingerprint[i], __ -> new ArrayList<>())
                    .add(i);
        }

        // 3) Within each non‐zero group, drop duplicates whose counts match
        Set<Integer> ignore = new HashSet<>();
        for (List<Integer> grp : groups.values()) {
            if (grp.size() < 2) continue;
            Collections.sort(grp);
            int keeper = grp.get(0);
            for (int j = 1; j < grp.size(); j++) {
                int idx = grp.get(j);
                if (counts[idx] == counts[keeper]) {
                    ignore.add(idx);
                }
            }
        }

        return ignore;
    }
}
