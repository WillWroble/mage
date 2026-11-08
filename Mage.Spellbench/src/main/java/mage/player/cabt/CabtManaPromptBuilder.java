package mage.player.cabt;

import mage.MageObject;
import mage.abilities.Ability;
import mage.abilities.costs.mana.ManaCost;
import mage.abilities.mana.ActivatedManaAbilityImpl;
import mage.constants.ManaType;
import mage.game.Game;

/**
 * CABT bridge: builds the PAY_MANA prompt for one step of XMage's payment
 * loop — the engine calls playMana repeatedly while the cost is unpaid, so
 * each prompt picks exactly one mana source, pool mana, or cancel.
 * <p>
 * Discovery reuses the inherited player's own mana path (the protected
 * PlayerImpl helpers getAvailableManaProducers and getUseableManaAbilities,
 * exposed package-privately by CabtBridgePlayer), plus the public
 * ManaPool.get(ManaType) for mana already floating. Special mana actions are
 * not discovered yet; the option kind exists so they can be added without a
 * schema change.
 */
public final class CabtManaPromptBuilder {

    public PendingDecision build(CabtBridgePlayer player, Game game, Ability abilityToCast,
                                 ManaCost unpaid, String promptText) {
        PendingDecision decision = new PendingDecision(
                MagicSelectType.PAY_MANA, player.getId(), 1, 1);
        for (MageObject producer : player.cabtManaProducers(game)) {
            for (ActivatedManaAbilityImpl manaAbility : player.cabtManaAbilities(producer, game).values()) {
                decision.addOption(CabtManaOptionFactory.manaSourceOption(
                        game, producer, manaAbility, abilityToCast, unpaid, promptText));
            }
        }
        for (ManaType manaType : ManaType.values()) {
            int available = player.getManaPool().get(manaType);
            if (available > 0) {
                decision.addOption(CabtManaOptionFactory.manaPoolOption(
                        game, manaType, available, abilityToCast, unpaid, promptText));
            }
        }
        decision.addOption(CabtManaOptionFactory.cancelOption(game, abilityToCast, unpaid, promptText));
        return decision;
    }
}
