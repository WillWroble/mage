package mage.player.cabt;

import mage.game.Game;
import mage.players.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Records a whole run — every decision of every bridge player, in the global
 * order the engine asked them — as {@link CabtRunStep}s. Wrap each player's
 * controller with {@link #wrap}; all wrapped controllers append into this one
 * log, so step N+1's observation is the after-state of step N regardless of
 * which player decided. {@link CabtSmokeRunBundleWriter} turns the log into
 * the on-disk artifact bundle.
 */
public final class CabtRunRecorder {

    private final MagicObservationSerializer serializer = new MagicObservationSerializer();
    private final List<CabtRunStep> steps = new ArrayList<CabtRunStep>();

    /**
     * Decorates a controller: observes the prompt, delegates the selection,
     * and appends the (observation, decision, selection) step to this log.
     */
    public CabtBridgeController wrap(final CabtBridgeController delegate) {
        return (game, player, decision) -> {
            MagicObservation observation = serializer.serialize(game, player, decision);
            Selection selection = delegate.requestSelection(game, player, decision);
            steps.add(new CabtRunStep(steps.size(), player.getName(),
                    player.getId().toString(), observation, decision, selection));
            return selection;
        };
    }

    /**
     * Snapshot of the game state after the run, from one player's
     * hidden-information perspective — the "after" of the final step.
     */
    public MagicCurrent finalState(Game game, Player perspective) {
        return serializer.serializeCurrent(game, perspective.getId());
    }

    public List<CabtRunStep> getSteps() {
        return Collections.unmodifiableList(steps);
    }
}
