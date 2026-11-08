package mage.player.cabt;

import mage.abilities.TriggeredAbility;
import mage.game.Game;
import mage.players.Player;

import java.util.List;

/**
 * CABT bridge: builds the TRIGGER_ORDER prompt when GameImpl.checkTriggered()
 * asks which waiting triggered ability goes on the stack first. Option index
 * i corresponds to abilities.get(i) — the input list's order is the prompt's
 * order.
 */
public final class CabtTriggeredAbilityPromptBuilder {

    public PendingDecision build(Player player, Game game, List<TriggeredAbility> abilities) {
        PendingDecision decision = new PendingDecision(
                MagicSelectType.TRIGGER_ORDER, player.getId(), 1, 1);
        for (TriggeredAbility ability : abilities) {
            decision.addOption(CabtTriggeredAbilityOptionFactory.triggeredAbilityOption(game, ability));
        }
        return decision;
    }
}
