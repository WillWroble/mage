package mage.player.cabt;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One recorded decision of a run: the observation the deciding player saw,
 * the prompt, and the answer — globally sequenced across all players by
 * {@link CabtRunRecorder}, so consecutive steps double as before/after state
 * pairs for transition artifacts.
 */
public final class CabtRunStep {

    private final long sequence;
    private final String playerName;
    private final String playerId;
    private final MagicObservation observation;
    private final PendingDecision decision;
    private final Selection selection;

    CabtRunStep(long sequence, String playerName, String playerId,
                MagicObservation observation, PendingDecision decision, Selection selection) {
        this.sequence = sequence;
        this.playerName = playerName;
        this.playerId = playerId;
        this.observation = observation;
        this.decision = decision;
        this.selection = selection;
    }

    public long getSequence() {
        return sequence;
    }

    public String getPlayerName() {
        return playerName;
    }

    public String getPlayerId() {
        return playerId;
    }

    public MagicObservation getObservation() {
        return observation;
    }

    public PendingDecision getDecision() {
        return decision;
    }

    public Selection getSelection() {
        return selection;
    }

    /**
     * The options the selection picked, resolved against the prompt.
     */
    public List<MagicOption> getSelectedOptions() {
        List<MagicOption> selected = new ArrayList<MagicOption>();
        for (int index : selection.indices()) {
            selected.add(decision.options().get(index));
        }
        return Collections.unmodifiableList(selected);
    }
}
