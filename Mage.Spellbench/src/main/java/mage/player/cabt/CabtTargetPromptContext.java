package mage.player.cabt;

import mage.abilities.Ability;
import mage.cards.Cards;
import mage.constants.Outcome;
import mage.target.Target;
import mage.target.TargetCard;

/**
 * CABT bridge: everything a target prompt was built from. {@code cards} and
 * {@code targetCard} are set only for the card-selection callbacks;
 * {@code targeted} distinguishes chooseTarget (real targeting, fires target
 * events) from choose (non-targeted selection).
 */
public final class CabtTargetPromptContext {

    private final Outcome outcome;
    private final Target target;
    private final Cards cards;          // nullable
    private final TargetCard targetCard; // nullable
    private final Ability source;
    private final boolean targeted;

    public CabtTargetPromptContext(Outcome outcome, Target target, Cards cards,
                                   TargetCard targetCard, Ability source, boolean targeted) {
        if (target == null) {
            throw new IllegalArgumentException("target prompt context requires a target");
        }
        this.outcome = outcome;
        this.target = target;
        this.cards = cards;
        this.targetCard = targetCard;
        this.source = source;
        this.targeted = targeted;
    }

    public Outcome getOutcome() {
        return outcome;
    }

    public Target getTarget() {
        return target;
    }

    public Cards getCards() {
        return cards;
    }

    public TargetCard getTargetCard() {
        return targetCard;
    }

    public Ability getSource() {
        return source;
    }

    public boolean isTargeted() {
        return targeted;
    }
}
