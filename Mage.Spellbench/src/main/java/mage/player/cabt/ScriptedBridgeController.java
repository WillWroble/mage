package mage.player.cabt;

import mage.game.Game;
import mage.players.Player;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;

/**
 * CABT bridge: test-only controller that replays a fixed queue of selections.
 */
public final class ScriptedBridgeController implements CabtBridgeController {

    private final Queue<Selection> selections = new ArrayDeque<>();

    public ScriptedBridgeController(List<Selection> selections) {
        this.selections.addAll(selections);
    }

    @Override
    public Selection requestSelection(Game game, Player player, PendingDecision decision) {
        if (selections.isEmpty()) {
            throw new IllegalStateException("No scripted CABT selection available");
        }
        return selections.remove();
    }
}
