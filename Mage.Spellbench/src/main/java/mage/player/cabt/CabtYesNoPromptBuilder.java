package mage.player.cabt;

import mage.MageObject;
import mage.abilities.Ability;
import mage.constants.Outcome;
import mage.game.Game;
import mage.players.Player;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * CABT bridge: builds the YES_NO prompt for chooseUse. Option 0 is always
 * YES, option 1 always NO; the long chooseUse overload's trueText/falseText
 * become the option labels when present.
 */
public final class CabtYesNoPromptBuilder {

    public PendingDecision build(Player player, Game game, Outcome outcome,
                                 String message, Ability source) {
        return build(player, game, outcome, message, null, null, null, source);
    }

    public PendingDecision build(Player player, Game game, Outcome outcome,
                                 String message, String secondMessage,
                                 String trueText, String falseText, Ability source) {
        PendingDecision decision = new PendingDecision(
                MagicSelectType.YES_NO, player.getId(), 1, 1);
        Map<String, Object> payload = payload(game, outcome, message, secondMessage, source);
        decision.addOption(new MagicOption(MagicOptionType.PROMPT_YES,
                trueText == null ? "Yes" : trueText, payload));
        decision.addOption(new MagicOption(MagicOptionType.PROMPT_NO,
                falseText == null ? "No" : falseText, payload));
        return decision;
    }

    private static Map<String, Object> payload(Game game, Outcome outcome, String message,
                                               String secondMessage, Ability source) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("message", message);
        if (secondMessage != null) {
            payload.put("secondMessage", secondMessage);
        }
        payload.put("sourceId", source == null || source.getSourceId() == null
                ? null : source.getSourceId().toString());
        payload.put("sourceName", sourceName(game, source));
        payload.put("outcome", outcome == null ? null : outcome.name());
        return payload;
    }

    private static String sourceName(Game game, Ability source) {
        if (source == null || source.getSourceId() == null) {
            return null;
        }
        MageObject object = game.getObject(source.getSourceId());
        return object == null ? null : object.getName();
    }
}
