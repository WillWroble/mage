package mage.player.cabt;

import mage.MageObject;
import mage.abilities.TriggeredAbility;
import mage.game.Game;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * CABT bridge: builds one option per waiting triggered ability, with the same
 * rule text HumanPlayer shows — ability.getRule(sourceName) with the source
 * object resolved through game.getObject.
 */
public final class CabtTriggeredAbilityOptionFactory {

    private CabtTriggeredAbilityOptionFactory() {
    }

    public static MagicOption triggeredAbilityOption(Game game, TriggeredAbility ability) {
        MageObject sourceObject = ability.getSourceId() == null
                ? null : game.getObject(ability.getSourceId());
        String sourceName = sourceObject == null ? null : sourceObject.getName();
        String rule = ability.getRule(sourceName);
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("abilityId", nullableToString(ability.getId()));
        payload.put("originalId", nullableToString(ability.getOriginalId()));
        payload.put("sourceId", nullableToString(ability.getSourceId()));
        payload.put("sourceName", sourceName);
        payload.put("rule", rule);
        payload.put("abilityClass", ability.getClass().getSimpleName());
        String label = "Put trigger on stack: " + (rule == null ? nullableToString(ability.getId()) : rule);
        return new MagicOption(MagicOptionType.PROMPT_TRIGGERED_ABILITY, label, payload);
    }

    private static String nullableToString(Object value) {
        return value == null ? null : value.toString();
    }
}
