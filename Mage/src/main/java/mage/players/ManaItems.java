package mage.players;

import java.util.*;
//deterministic ManaItem List for MCTS AI

public class ManaItems extends ArrayList<ManaPoolItem> {
    private final Comparator<ManaPoolItem> comparator = Comparator.comparing(ManaPoolItem::getSourceId);


    @Override
    public boolean add(ManaPoolItem element) {
        if (element == null) {
            return false;
        }
        int index = Collections.binarySearch(this, element, comparator);
        if (index < 0) {
            index = -index - 1; // Calculate insertion point
        }

        super.add(index, element);
        return true;
    }
    @Override
    public boolean addAll(Collection<? extends ManaPoolItem> c) {
        boolean modified = false;
        for (ManaPoolItem e : c) {
            modified |= add(e);
        }
        return modified;
    }
}