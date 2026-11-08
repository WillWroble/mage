package mage.player.cabt;

/**
 * CABT bridge: reads the original effectsMap index out of a validated
 * REPLACEMENT_EFFECT selection — the int chooseReplacementEffect returns.
 */
public final class CabtReplacementEffectSelectionApplier {

    public int apply(Selection selection, PendingDecision decision) {
        MagicOption option = decision.options().get(selection.indices().get(0));
        Object originalIndex = option.payload().get("originalIndex");
        if (!(originalIndex instanceof Integer)) {
            throw new IllegalStateException("replacement option has no originalIndex payload");
        }
        return (Integer) originalIndex;
    }
}
