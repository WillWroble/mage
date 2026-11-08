package mage.player.cabt;

import mage.game.Game;
import mage.players.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * CABT bridge decorator: captures a CABT-style observation of every prompt
 * before delegating the actual selection to the wrapped controller. Keeps
 * CabtBridgePlayer unchanged — observation is a controller concern.
 */
public final class RecordingBridgeController implements CabtBridgeController {

    private final CabtBridgeController delegate;
    private final MagicObservationSerializer serializer;
    private final List<MagicObservation> observations = new ArrayList<MagicObservation>();

    public RecordingBridgeController(CabtBridgeController delegate, MagicObservationSerializer serializer) {
        this.delegate = delegate;
        this.serializer = serializer;
    }

    @Override
    public Selection requestSelection(Game game, Player player, PendingDecision decision) {
        MagicObservation observation = serializer.serialize(game, player, decision);
        observations.add(observation);
        return delegate.requestSelection(game, player, decision);
    }

    public MagicObservation getLastObservation() {
        return observations.isEmpty() ? null : observations.get(observations.size() - 1);
    }

    public List<MagicObservation> getObservations() {
        return Collections.unmodifiableList(observations);
    }
}
