package mage.player.cabt;

/**
 * CABT bridge: reads the integer value out of a validated NUMBER selection.
 */
public final class CabtNumberSelectionApplier {

    public int apply(Selection selection, PendingDecision decision) {
        MagicOption option = decision.options().get(selection.indices().get(0));
        Object value = option.payload().get("value");
        if (!(value instanceof Integer)) {
            throw new IllegalStateException("number option has no integer value payload");
        }
        return (Integer) value;
    }
}
