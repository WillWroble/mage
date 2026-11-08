package mage.player.cabt;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One generic transition: the observation a decision was made in, the prompt
 * (select), the chosen option indices, and the observation that resulted.
 * <p>
 * Deliberately no outcome labels (countered/destroyed/fizzled/succeeded/...):
 * the dataset stores decisions and resulting states, not strategic
 * interpretations — that is what makes it usable for imitation learning, RL,
 * and search analysis alike. Reward is carried as a nullable scalar for
 * terminal transitions only when a caller supplies one.
 */
public final class CabtDatasetRecord {

    private final String gameId;
    private final long sequenceNumber;
    private final String decisionMethod;
    private final Object observation;
    private final Object select;
    private final List<Integer> selectedIndices;
    private final Object nextObservation;
    private final boolean terminal;
    private final Double reward;

    public CabtDatasetRecord(String gameId,
                             long sequenceNumber,
                             String decisionMethod,
                             Object observation,
                             Object select,
                             List<Integer> selectedIndices,
                             Object nextObservation,
                             boolean terminal,
                             Double reward) {
        if (gameId == null || decisionMethod == null || observation == null
                || select == null || selectedIndices == null) {
            throw new IllegalArgumentException(
                    "gameId, decisionMethod, observation, select and selectedIndices must not be null");
        }
        if (sequenceNumber < 0) {
            throw new IllegalArgumentException("sequenceNumber must not be negative");
        }
        this.gameId = gameId;
        this.sequenceNumber = sequenceNumber;
        this.decisionMethod = decisionMethod;
        this.observation = observation;
        this.select = select;
        this.selectedIndices = Collections.unmodifiableList(new ArrayList<Integer>(selectedIndices));
        this.nextObservation = nextObservation;
        this.terminal = terminal;
        this.reward = reward;
    }

    public String getGameId() {
        return gameId;
    }

    public long getSequenceNumber() {
        return sequenceNumber;
    }

    public String getDecisionMethod() {
        return decisionMethod;
    }

    public Object getObservation() {
        return observation;
    }

    public Object getSelect() {
        return select;
    }

    public List<Integer> getSelectedIndices() {
        return selectedIndices;
    }

    public Object getNextObservation() {
        return nextObservation;
    }

    public boolean isTerminal() {
        return terminal;
    }

    public Double getReward() {
        return reward;
    }
}
