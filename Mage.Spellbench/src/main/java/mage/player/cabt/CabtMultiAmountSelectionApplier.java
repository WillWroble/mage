package mage.player.cabt;

import java.util.ArrayList;
import java.util.List;

/**
 * CABT bridge: reads the amount assignment out of a validated MULTI_AMOUNT
 * selection.
 */
public final class CabtMultiAmountSelectionApplier {

    public List<Integer> apply(Selection selection, PendingDecision decision) {
        MagicOption option = decision.options().get(selection.indices().get(0));
        Object assignment = option.payload().get("assignment");
        if (!(assignment instanceof List)) {
            throw new IllegalStateException("amount option has no assignment payload");
        }
        List<Integer> values = new ArrayList<Integer>();
        for (Object value : (List<?>) assignment) {
            values.add((Integer) value);
        }
        return values;
    }
}
