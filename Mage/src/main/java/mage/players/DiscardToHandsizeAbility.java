package mage.players;

import mage.abilities.AbilityImpl;
import mage.constants.AbilityType;
import mage.constants.Zone;

import java.util.UUID;

/**
 * AI: fake ability to use as a flag for combat decisions
 *
 * @author willwroble
 */
public class DiscardToHandsizeAbility extends AbilityImpl {

    public DiscardToHandsizeAbility() {
        super(AbilityType.SPECIAL_ACTION, Zone.ALL);
        this.usesStack = false;
        this.id = new  UUID(0, this.toString().hashCode());
    }

    protected DiscardToHandsizeAbility(final DiscardToHandsizeAbility ability) {
        super(ability);
    }

    @Override
    public DiscardToHandsizeAbility copy() {
        return new DiscardToHandsizeAbility(this);
    }

    @Override
    public String toString() {
        return "choose cards to discard to hand size";
    }

}
