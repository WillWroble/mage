package mage.player.cabt;

/**
 * CABT bridge: turns a validated MULLIGAN selection into the boolean XMage
 * expects from chooseMulligan — true takes the mulligan, false keeps the
 * hand (the same convention as HumanPlayer's "Mulligan down to N?" dialog).
 */
public final class CabtMulliganSelectionApplier {

    public boolean apply(Selection selection, PendingDecision decision) {
        MagicOption option = decision.options().get(selection.indices().get(0));
        if (option.type() == MagicOptionType.PROMPT_MULLIGAN) {
            return true;
        }
        if (option.type() == MagicOptionType.PROMPT_KEEP) {
            return false;
        }
        throw new IllegalStateException("unexpected mulligan option: " + option.type());
    }
}
