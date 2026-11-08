package mage.player.cabt;

import mage.choices.Choice;

/**
 * CABT bridge: applies a validated selection back to the XMage {@link Choice}
 * object — setChoiceByKey for key choices, setChoice otherwise — and reports
 * the Choice's own isChosen() answer.
 */
public final class CabtChoiceSelectionApplier {

    public boolean apply(Choice choice, Selection selection, PendingDecision decision) {
        if (selection.indices().isEmpty()) {
            return choice.isChosen();
        }
        MagicOption option = decision.options().get(selection.indices().get(0));
        Object choiceKey = option.payload().get("choiceKey");
        if (choiceKey instanceof String) {
            choice.setChoiceByKey((String) choiceKey);
        } else {
            choice.setChoice((String) option.payload().get("choiceValue"));
        }
        return choice.isChosen();
    }
}
