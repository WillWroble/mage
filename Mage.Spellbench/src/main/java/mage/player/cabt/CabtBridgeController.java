package mage.player.cabt;

import mage.game.Game;
import mage.players.Player;

/**
 * CABT bridge: the decision authority a {@link CabtBridgePlayer} defers to.
 * Task 1 keeps this in-process (see ScriptedBridgeController); a later controller
 * will serialize observations and talk to the Python harness.
 */
public interface CabtBridgeController {

    Selection requestSelection(Game game, Player player, PendingDecision decision);
}
