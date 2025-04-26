package mage.player.ai;

import java.io.Serializable;

public class LabeledState implements Serializable {
    public boolean[] stateVector;//state
    public boolean[] actionVector;//policy
    public boolean resultLabel;//win lose



    public LabeledState(boolean[] sVector, boolean[] aVector, boolean label) {
        this.stateVector = sVector;
        this.actionVector = aVector;
        this.resultLabel = label;
    }
}
