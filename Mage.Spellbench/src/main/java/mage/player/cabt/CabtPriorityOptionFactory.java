package mage.player.cabt;

import mage.abilities.ActivatedAbility;
import mage.constants.AbilityType;
import mage.game.Game;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * CABT bridge: builds one PRIORITY option per playable ability returned by
 * Player.getPlayable — a land play, a castable spell, an activatable ability,
 * or a special action. Any other ability type coming out of getPlayable is a
 * bug and fails closed instead of being bucketed.
 */
public final class CabtPriorityOptionFactory {

    static final String PAYLOAD_PLAYABLE_INDEX = "playableIndex";
    static final String PAYLOAD_ABILITY_TYPE = "abilityType";
    static final String PAYLOAD_ABILITY_ID = "abilityId";
    static final String PAYLOAD_SOURCE_ID = "sourceId";
    static final String PAYLOAD_SOURCE_NAME = "sourceName";
    static final String PAYLOAD_RULE = "rule";
    static final String PAYLOAD_MANA_COST = "manaCost";

    private CabtPriorityOptionFactory() {
    }

    public static MagicOption playableOption(Game game, ActivatedAbility ability, int playableIndex) {
        MagicOptionType type = optionType(ability);
        String sourceName = CabtSourceNames.sourceName(game, ability.getSourceId());
        // payload fields beyond the index are descriptive and must never crash
        // prompt construction, so each one is null-safe on its own
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put(PAYLOAD_PLAYABLE_INDEX, playableIndex);
        payload.put(PAYLOAD_ABILITY_TYPE, ability.getAbilityType().name());
        payload.put(PAYLOAD_ABILITY_ID, ability.getId() == null ? null : ability.getId().toString());
        payload.put(PAYLOAD_SOURCE_ID, ability.getSourceId() == null ? null : ability.getSourceId().toString());
        payload.put(PAYLOAD_SOURCE_NAME, sourceName);
        payload.put(PAYLOAD_RULE, ability.getRule());
        payload.put(PAYLOAD_MANA_COST,
                ability.getManaCostsToPay() == null ? "" : ability.getManaCostsToPay().getText());
        return new MagicOption(type, label(type, ability, sourceName), payload);
    }

    static MagicOptionType optionType(ActivatedAbility ability) {
        AbilityType abilityType = ability.getAbilityType();
        if (abilityType == null) {
            throw new CabtUnhandledDecisionException(
                    "getPlayable returned an ability without an ability type: " + ability.getRule());
        }
        switch (abilityType) {
            case PLAY_LAND:
                return MagicOptionType.PLAY_LAND;
            case SPELL:
                return MagicOptionType.CAST_SPELL;
            case ACTIVATED_NONMANA:
            case ACTIVATED_MANA:
                return MagicOptionType.ACTIVATE_ABILITY;
            case SPECIAL_ACTION:
            case SPECIAL_MANA_PAYMENT:
                return MagicOptionType.SPECIAL_ACTION;
            default:
                throw new CabtUnhandledDecisionException(
                        "getPlayable returned an ability type with no priority option mapping: "
                                + abilityType.name() + " (" + ability.getRule() + ')');
        }
    }

    private static String label(MagicOptionType type, ActivatedAbility ability, String sourceName) {
        String name = sourceName != null ? sourceName : ability.getRule();
        switch (type) {
            case PLAY_LAND:
                return "Play " + name;
            case CAST_SPELL:
                return "Cast " + name;
            case ACTIVATE_ABILITY:
                return name + ": " + ability.getRule();
            case SPECIAL_ACTION:
                return ability.getRule();
            default:
                throw new IllegalStateException("not a priority option type: " + type);
        }
    }
}
