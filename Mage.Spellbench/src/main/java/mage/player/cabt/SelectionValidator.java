package mage.player.cabt;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * CABT bridge: validates a {@link Selection} against its {@link PendingDecision}
 * before any dispatch into XMage — selection count within min/max, every index
 * in range, no duplicates (CABT's checkPlayerSelect, with named errors).
 */
public final class SelectionValidator {

    private SelectionValidator() {
    }

    public static void validate(PendingDecision decision, Selection selection) {
        List<Integer> indices = selection.indices();
        if (indices.size() < decision.minCount() || indices.size() > decision.maxCount()) {
            throw new InvalidSelectionException("INVALID_SELECTION_COUNT");
        }
        Set<Integer> seen = new HashSet<>();
        int optionCount = decision.options().size();
        for (int index : indices) {
            if (index < 0 || index >= optionCount) {
                throw new InvalidSelectionException("OPTION_INDEX_OUT_OF_RANGE");
            }
            if (!seen.add(index)) {
                throw new InvalidSelectionException("DUPLICATE_SELECTION");
            }
        }
    }
}
