package mage.player.cabt;

import mage.game.Game;
import mage.players.Player;

import java.util.UUID;

/**
 * CABT bridge: applies a validated DECLARE_ATTACKERS selection through the
 * engine's own commit API — Player.declareAttacker(attackerId, defenderId,
 * game, false) per selected pair. An empty selection declares no attackers.
 */
public final class CabtAttackersSelectionApplier {

    public void apply(Player player, Game game, Selection selection, PendingDecision decision) {
        for (Integer index : selection.indices()) {
            MagicOption option = decision.options().get(index);
            UUID attackerId = UUID.fromString((String) option.payload().get("attackerId"));
            UUID defenderId = UUID.fromString((String) option.payload().get("defenderId"));
            player.declareAttacker(attackerId, defenderId, game, false);
        }
    }
}
