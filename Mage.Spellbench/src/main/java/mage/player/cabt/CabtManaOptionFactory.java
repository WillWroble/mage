package mage.player.cabt;

import mage.MageObject;
import mage.abilities.Ability;
import mage.abilities.costs.mana.ManaCost;
import mage.abilities.mana.ActivatedManaAbilityImpl;
import mage.constants.ManaType;
import mage.game.Game;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * CABT bridge: builds PAY_MANA options — a usable mana ability on a
 * producer, mana already in the player's pool, or cancelling the payment.
 */
public final class CabtManaOptionFactory {

    private CabtManaOptionFactory() {
    }

    public static MagicOption manaSourceOption(Game game, MageObject producer,
                                               ActivatedManaAbilityImpl manaAbility,
                                               Ability abilityToCast, ManaCost unpaid,
                                               String promptText) {
        Map<String, Object> payload = commonPayload(
                CabtManaOptionKind.MANA_ABILITY_SOURCE, game, abilityToCast, unpaid, promptText);
        payload.put("objectId", producer.getId() == null ? null : producer.getId().toString());
        payload.put("objectName", producer.getName());
        payload.put("abilityId", manaAbility.getId().toString());
        payload.put("abilityRule", manaAbility.getRule());
        return new MagicOption(MagicOptionType.PROMPT_MANA_SOURCE,
                "Tap " + producer.getName() + " for mana", payload);
    }

    public static MagicOption manaPoolOption(Game game, ManaType manaType, int available,
                                             Ability abilityToCast, ManaCost unpaid,
                                             String promptText) {
        Map<String, Object> payload = commonPayload(
                CabtManaOptionKind.MANA_POOL_COLOR, game, abilityToCast, unpaid, promptText);
        payload.put("manaType", manaType.name());
        payload.put("available", available);
        return new MagicOption(MagicOptionType.PROMPT_MANA_POOL,
                "Pay with " + manaType.name() + " mana from your mana pool", payload);
    }

    public static MagicOption cancelOption(Game game, Ability abilityToCast, ManaCost unpaid,
                                           String promptText) {
        Map<String, Object> payload = commonPayload(
                CabtManaOptionKind.CANCEL, game, abilityToCast, unpaid, promptText);
        return new MagicOption(MagicOptionType.PROMPT_CANCEL_PAYMENT,
                "Cancel payment", payload);
    }

    private static Map<String, Object> commonPayload(CabtManaOptionKind kind, Game game,
                                                     Ability abilityToCast, ManaCost unpaid,
                                                     String promptText) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("manaOptionKind", kind.name());
        payload.put("promptText", promptText);
        payload.put("unpaid", unpaid == null ? null : unpaid.getText());
        payload.put("sourceId", abilityToCast == null || abilityToCast.getSourceId() == null
                ? null : abilityToCast.getSourceId().toString());
        payload.put("sourceName", CabtSourceNames.sourceName(
                game, abilityToCast == null ? null : abilityToCast.getSourceId()));
        return payload;
    }
}
