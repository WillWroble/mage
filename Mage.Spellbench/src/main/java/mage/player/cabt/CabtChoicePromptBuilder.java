package mage.player.cabt;

import mage.choices.Choice;
import mage.players.Player;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * CABT bridge: builds the CHOICE prompt from an XMage {@link Choice} object —
 * the same key/value source HumanPlayer presents via fireChooseChoiceEvent.
 * The Choice object is the source of truth; options keep its iteration order.
 */
public final class CabtChoicePromptBuilder {

    public PendingDecision build(Player player, Choice choice) {
        PendingDecision decision = new PendingDecision(
                MagicSelectType.CHOICE, player.getId(), 1, 1);
        if (choice.isKeyChoice()) {
            for (Map.Entry<String, String> entry : choice.getKeyChoices().entrySet()) {
                decision.addOption(option(entry.getKey(), entry.getValue()));
            }
        } else if (choice.getChoices() != null) {
            for (String value : choice.getChoices()) {
                decision.addOption(option(null, value));
            }
        }
        return decision;
    }

    private static MagicOption option(String choiceKey, String choiceValue) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("choiceKey", choiceKey);
        payload.put("choiceValue", choiceValue);
        payload.put("choiceLabel", choiceValue);
        return new MagicOption(MagicOptionType.PROMPT_CHOICE, choiceValue, payload);
    }
}
