package mage.player.cabt;

import mage.MageObject;
import mage.abilities.mana.ActivatedManaAbilityImpl;
import mage.constants.ManaType;
import mage.game.Game;

import java.util.UUID;

/**
 * CABT bridge: applies a validated PAY_MANA selection through XMage's own
 * payment paths — playManaAbility for a mana source (like HumanPlayer's
 * playManaAbilities response) and ManaPool.unlockManaType for floating mana.
 * Returns true to continue the engine's payment loop, false to cancel it.
 */
public final class CabtManaSelectionApplier {

    public boolean apply(CabtBridgePlayer player, Game game,
                         Selection selection, PendingDecision decision) {
        MagicOption option = decision.options().get(selection.indices().get(0));
        Object kindValue = option.payload().get("manaOptionKind");
        if (!(kindValue instanceof String)) {
            throw new IllegalStateException("mana option has no manaOptionKind payload");
        }
        CabtManaOptionKind kind = CabtManaOptionKind.valueOf((String) kindValue);
        switch (kind) {
            case MANA_ABILITY_SOURCE:
                activateManaSource(player, game, option);
                return true;
            case MANA_POOL_COLOR:
                player.getManaPool().unlockManaType(
                        ManaType.valueOf((String) option.payload().get("manaType")));
                return true;
            case SPECIAL_MANA_ACTION:
                // not discovered by the builder yet; selecting one is a bug
                throw new CabtUnhandledDecisionException(
                        "special mana actions are not surfaced yet; failing closed");
            case CANCEL:
                return false;
            default:
                throw new IllegalStateException("unhandled mana option kind: " + kind);
        }
    }

    private static void activateManaSource(CabtBridgePlayer player, Game game, MagicOption option) {
        UUID objectId = UUID.fromString((String) option.payload().get("objectId"));
        UUID abilityId = UUID.fromString((String) option.payload().get("abilityId"));
        MageObject object = game.getObject(objectId);
        if (object == null) {
            throw new IllegalStateException("mana source " + objectId + " is no longer available");
        }
        ActivatedManaAbilityImpl manaAbility = player.cabtManaAbilities(object, game).get(abilityId);
        if (manaAbility == null) {
            throw new IllegalStateException(
                    "mana ability " + abilityId + " is no longer usable on " + object.getName());
        }
        player.cabtActivateManaAbility(manaAbility, game);
    }
}
