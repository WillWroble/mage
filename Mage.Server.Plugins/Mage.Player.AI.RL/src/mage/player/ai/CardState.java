package mage.player.ai;

import java.util.*;

public class CardState {

    private final String cardName;
    private Set<CardState> attachedCards = new HashSet<>();
    private Map<String, Integer> counters = new HashMap<>();
    boolean can_attack;
    boolean can_activate;
    boolean can_block;
    public CardState(String name, Set<CardState> at, Map<String, Integer> cou, boolean att, boolean act, boolean blo) {
        cardName = name;
        attachedCards = at;
        counters = cou;
        can_attack = att;
        can_activate = act;
        can_block = blo;
    }

    public boolean equals(CardState s) {
        return (attachedCards.equals(s.attachedCards) &&
                counters.equals(s.counters) &&
                cardName.equals(s.cardName) &&
                can_attack == s.can_attack &&
                can_activate == s.can_activate &&
                can_block == s.can_block);
    }

    public boolean isChildOf(CardState c) {
        if(!attachedCards.containsAll(c.attachedCards)) return false;
        if(!counters.keySet().containsAll(c.counters.keySet())) return false;
        if(!can_attack && c.can_attack) return false;
        if(!can_activate && c.can_activate) return false;
        if(!can_block && c.can_block) return false;

        return true;
    }

    public static CardState getIntersection(CardState a, CardState b) {
        Map<String, Integer> xCounters = new HashMap<>();
        for(String counterType : a.counters.keySet()) {
            if(b.counters.containsKey(counterType)) {
                xCounters.put(counterType, Math.min(a.counters.get(counterType), b.counters.get(counterType)));
            }
        }
        Set<CardState> xAttackedCards = new HashSet<>(a.attachedCards);
        xAttackedCards.retainAll(b.attachedCards);
        CardState out = new CardState(a.cardName, xAttackedCards, xCounters, a.can_attack && b.can_attack,
                a.can_activate && b.can_activate, a.can_block && b.can_block);
        return out;

    }
    public static CardState getDifference(CardState a, CardState b) {
        Map<String, Integer> dCounters = new HashMap<>();
        for(String counterType : a.counters.keySet()) {
            int countersLeft = a.counters.get(counterType) - b.counters.get(counterType);
            if(countersLeft > 0) {
                dCounters.put(counterType, countersLeft);
            }
        }
        Set<CardState> dAttackedCards = new HashSet<>(a.attachedCards);
        dAttackedCards.removeAll(b.attachedCards);
        CardState out = new CardState(a.cardName, dAttackedCards, dCounters, a.can_attack ^ b.can_attack,
                a.can_activate ^ b.can_activate, a.can_block ^ b.can_block);
        return out;
    }

    /**
     * returns the pairing cost of an intersection between 2 Card States the better they pair, the lower the cost
     * if no attributes are lost (the pairs are identical) the cost is zero. Cost is inversely proportional to intersection
     * @param a
     * @return
     */
    public static int getCost(CardState a, CardState b) {

        CardState intersection = getIntersection(a, b);
        CardState a_diff = getDifference(a, intersection);
        CardState b_diff = getDifference(b, intersection);
        int num = a_diff.attachedCards.size()*10 + b_diff.attachedCards.size()*10;
        num += (a_diff.can_attack ^ b_diff.can_attack) ? 10 : 0;
        num += (a_diff.can_activate ^ b_diff.can_activate) ? 10 : 0;
        num += (a_diff.can_block ^ b_diff.can_block) ? 10 : 0;
        for(String counterType : a_diff.counters.keySet()) {
            num += a_diff.counters.get(counterType)*2;
        }
        for(String counterType : b_diff.counters.keySet()) {
            num += b_diff.counters.get(counterType)*2;
        }
        return num;
    }

    /**
     * uses hungarian algorithm to determine best pairing
     * @param A
     * @param B
     * @return
     */
    public static List<CardState> bestPairing(List<CardState> A, List<CardState> B) {
        List<CardState> out = new ArrayList<>();
        double costs[][] = new double[A.size()][B.size()];

        for(int i = 0; i < A.size(); i++) {
            CardState best = null;
            for(int j = 0; j < B.size(); j++) {
                costs[i][j] = getCost(A.get(i), B.get(j));
            }
        }
        HungarianAlgorithm ha = new HungarianAlgorithm(costs);
        int[] matching = ha.execute();
        for(int i = 0; i<A.size(); i++) {
            if(matching[i] > 0) {
                out.add(getIntersection(A.get(i), B.get(matching[i])));
            }
        }
        return out;
    }
    @Override
    public int hashCode()
    {
        return this.cardName.hashCode();
    }
}
