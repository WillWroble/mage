package mage.players;

import mage.abilities.AbilityImpl;
import mage.constants.AbilityType;
import mage.constants.Zone;
import mage.game.Game;

import java.util.UUID;

/**
 * AI: fake ability to use as a flag for combat decisions
 *
 * @author willwroble
 */
public class ChooseCreatureToBlockAbility extends AbilityImpl {

    public ChooseCreatureToBlockAbility() {
        super(AbilityType.SPECIAL_ACTION, Zone.ALL);
        this.usesStack = false;
        this.name = "choose which creature to block";
        this.id = new  UUID(0, this.toString().hashCode());
    }
    public ChooseCreatureToBlockAbility(String message) {
        super(AbilityType.SPECIAL_ACTION, Zone.ALL);
        this.usesStack = false;
        this.name = message;
        this.id = new  UUID(0, this.toString().hashCode());
    }
    public ChooseCreatureToBlockAbility(String message, UUID source) {
        super(AbilityType.SPECIAL_ACTION, Zone.ALL);
        this.usesStack = false;
        this.name = message;
        this.sourceId = source;
        this.id = new UUID(this.sourceId.toString().hashCode(), this.toString().hashCode());
    }

    protected ChooseCreatureToBlockAbility(final ChooseCreatureToBlockAbility ability) {
        super(ability);
    }

    @Override
    public ChooseCreatureToBlockAbility copy() {
        return new ChooseCreatureToBlockAbility(this);
    }

    @Override
    public String toString() {
        return this.name;
    }
    @Override
    public String getGameLogMessage(Game game) {
        return this.name.replace("{this}", game.getEntityName(sourceId));
    }

    @Override
    public String getRule() {
        return this.name;
    }

}
