package mage.player.ai;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;

public class HashedZone {
    private final HashMap<Integer, List<CardState>> model;
    public static HashedZone getIntersection(HashedZone a, HashedZone b) {
        HashedZone out = new HashedZone();
        for(Integer hash : a.getKeySet()) {
            List<CardState> v2 = b.getCardStatesByKey(hash);
            if(v2 == null) continue;
            List<CardState> v1 = a.getCardStatesByKey(hash);
            if(v1.size() == 1 && v2.size() == 1) {
                out.model.put(hash, new ArrayList<>(v1));
                return out;
            }
            out.model.put(hash, CardState.bestPairing(v1, v2));
        }
        return out;
    }
    public HashedZone() {
        model = new HashMap<>();
    }

    public boolean equals(HashedZone z) {
        //return model.equals(z.model);
        return (model.keySet().equals(z.model.keySet()));
    }

    public HashedZone(HashedZone zone) {
        model = new HashMap<>(zone.model);
    }
    public Set<Integer> getKeySet() {
        return model.keySet();
    }
    public List<CardState> getCardStatesByKey(Integer key) {
        return model.get(key);
    }
    public void addCardState(CardState cardState) {
        Integer key = cardState.hashCode();
        if(model.containsKey(key)) {
            model.get(key).add(cardState);
        } else {
            model.put(key, new ArrayList<>());
            model.get(key).add(cardState);
        }
    }
    public boolean containsAll(HashedZone z) {
        if(z.model.isEmpty()) {
            return true;
        }
        //return getKeySet().containsAll(z.getKeySet());

        for(Integer hash : z.getKeySet()) {
            List<CardState> v1 = model.get(hash);
            if(v1 == null) return false;
            List<CardState> v2 = z.model.get(hash);
            if(v1.size() < v2.size()) return false;

            if(v1.size() == 1 && v2.size() == 1) {
                if(v1.get(0).isChildOf(v2.get(0))) {
                    continue;
                }
            }

            double[][] costs = new double[v1.size()][v2.size()];
            for(int i = 0; i< v1.size(); i++) {
                for(int j = 0; j < v2.size(); j++) {
                    if(v1.get(i).isChildOf(v2.get(j))) {
                        costs[i][j] = 0;
                    } else {
                        costs[i][j] = 1;
                    }
                }
            }
            HungarianAlgorithm ha = new HungarianAlgorithm(costs);
            int[] pairing = ha.execute();
            double totalCost = 0;
            for(int i = 0; i<pairing.length; i++) {
                if(pairing[i] > 0) {
                    totalCost += costs[i][pairing[i]];
                }
            }
            if(totalCost > 0) {
                return false;
            }
        }
        return true;
    }
}
