package mage.player.ai.encoder;


import java.io.DataOutputStream;
import java.io.IOException;
import java.io.Serializable;
import java.util.*;

/**
 * Represents a single training example:
 */
public class LabeledState implements Serializable {
    private static final long serialVersionUID = 2L;
    /** Is the player PlayerA?*/
    public boolean isPlayer;
    /** the type of decision this state represents (use different heads in network)*/
    public ActionEncoder.ActionType actionType;
    /** Sparse indices vector */
    public FeatureGraph stateGraph;
    /** Raw visit distribution */
    public Map<UUID, Integer> actionMap;
    /** AI assigned score for the state*/
    public final double stateScore;
    /** Final blended balue label (-1 to 1). */
    public double resultLabel;



    /**
     * Construct a labeled state.
     * @param state  Graph of active features
     * @param actionMap    map from UUID to visits of associated action
     * @param score        scalar outcome label
     */
    public LabeledState(FeatureGraph state, Map<UUID, Integer> actionMap, double score, ActionEncoder.ActionType actionType, boolean isPlayer) {
        // clone to ensure immutability
        this.stateGraph = new FeatureGraph(state);
        this.actionMap= new HashMap<>(actionMap);
        this.stateScore = score;
        this.actionType = actionType;
        this.isPlayer = isPlayer;

    }

}
