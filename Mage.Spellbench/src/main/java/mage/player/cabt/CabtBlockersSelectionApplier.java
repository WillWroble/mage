package mage.player.cabt;

import mage.game.Game;
import mage.players.Player;

import java.util.UUID;

/**
 * CABT bridge: applies a validated DECLARE_BLOCKERS selection through the
 * engine's own commit API — Player.declareBlocker(defendingPlayerId,
 * blockerId, attackerId, game) per selected pair. An empty selection
 * declares no blocks.
 */
public final class CabtBlockersSelectionApplier {

    public void apply(Player player, Game game, Selection selection, PendingDecision decision) {
        for (Integer index : selection.indices()) {
            MagicOption option = decision.options().get(index);
            UUID blockerId = UUID.fromString((String) option.payload().get("blockerId"));
            UUID attackerId = UUID.fromString((String) option.payload().get("attackerId"));
            Object defendingPlayerId = option.payload().get("defendingPlayerId");
            player.declareBlocker(defendingPlayerId == null
                            ? null : UUID.fromString((String) defendingPlayerId),
                    blockerId, attackerId, game);
        }
    }
}
