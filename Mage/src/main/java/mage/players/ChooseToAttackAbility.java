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
public class ChooseToAttackAbility extends AbilityImpl {

    public ChooseToAttackAbility() {
        super(AbilityType.SPECIAL_ACTION, Zone.ALL);
        this.usesStack = false;
        this.name = "attack with: {this} ?";
        this.id = new  UUID(0, this.toString().hashCode());
    }
    public ChooseToAttackAbility(String message) {
        super(AbilityType.SPECIAL_ACTION, Zone.ALL);
        this.usesStack = false;
        this.name = message;
        this.id = new  UUID(0, this.toString().hashCode());
    }
    public ChooseToAttackAbility(String message, UUID source) {
        super(AbilityType.SPECIAL_ACTION, Zone.ALL);
        this.usesStack = false;
        this.name = message;
        this.sourceId = source;
        this.id = new UUID(this.sourceId.toString().hashCode(), this.toString().hashCode());
    }

    protected ChooseToAttackAbility(final ChooseToAttackAbility ability) {
        super(ability);
    }

    @Override
    public ChooseToAttackAbility copy() {
        return new ChooseToAttackAbility(this);
    }

    @Override
    public String toString() {
        return this.name;
    }

    @Override
    public String getRule() {
        return this.name;
    }

}
