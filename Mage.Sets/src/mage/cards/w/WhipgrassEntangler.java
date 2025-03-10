
package mage.cards.w;

import mage.MageInt;
import mage.abilities.Ability;
import mage.abilities.common.LinkedEffectIdStaticAbility;
import mage.abilities.common.SimpleActivatedAbility;
import mage.abilities.costs.mana.ManaCosts;
import mage.abilities.costs.mana.ManaCostsImpl;
import mage.abilities.dynamicvalue.common.PermanentsOnBattlefieldCount;
import mage.abilities.effects.common.combat.CantAttackBlockUnlessPaysSourceEffect;
import mage.abilities.effects.common.continuous.GainAbilityTargetEffect;
import mage.cards.CardImpl;
import mage.cards.CardSetInfo;
import mage.constants.CardType;
import mage.constants.Duration;
import mage.constants.SubType;
import mage.constants.Zone;
import mage.filter.FilterPermanent;
import mage.game.Game;
import mage.game.events.GameEvent;
import mage.game.permanent.Permanent;
import mage.target.common.TargetCreaturePermanent;

import java.util.UUID;

/**
 * @author Susucr
 */
public final class WhipgrassEntangler extends CardImpl {

    public WhipgrassEntangler(UUID ownerId, CardSetInfo setInfo) {
        super(ownerId, setInfo, new CardType[]{CardType.CREATURE}, "{2}{W}");
        this.subtype.add(SubType.HUMAN);
        this.subtype.add(SubType.CLERIC);
        this.power = new MageInt(1);
        this.toughness = new MageInt(3);

        // {1}{W}: Until end of turn, target creature gains "This creature can't attack or block unless its controller pays {1} for each Cleric on the battlefield."
        Ability gainedAbility = new LinkedEffectIdStaticAbility(new WhipgrassEntanglerCantAttackUnlessYouPayEffect());
        Ability ability = new SimpleActivatedAbility(
                new GainAbilityTargetEffect(gainedAbility, Duration.EndOfTurn).setText("Until end of turn, target creature gains \"This creature can't attack or block unless its controller pays {1} for each Cleric on the battlefield.\""),
                new ManaCostsImpl<>("{1}{W}"));
        ability.addTarget(new TargetCreaturePermanent());
        this.addAbility(ability);

    }

    private WhipgrassEntangler(final WhipgrassEntangler card) {
        super(card);
    }

    @Override
    public WhipgrassEntangler copy() {
        return new WhipgrassEntangler(this);
    }
}

class WhipgrassEntanglerCantAttackUnlessYouPayEffect extends CantAttackBlockUnlessPaysSourceEffect implements LinkedEffectIdStaticAbility.ChildEffect {

    private static final FilterPermanent filter = new FilterPermanent("Cleric on the battlefield");
    private UUID parentLinkHandshake = null;

    static {
        filter.add(SubType.CLERIC.getPredicate());
    }

    WhipgrassEntanglerCantAttackUnlessYouPayEffect() {
        super(new ManaCostsImpl<>("{0}"), RestrictType.ATTACK_AND_BLOCK);
        staticText = "This creature can't attack or block unless its controller pays {1} for each Cleric on the battlefield";
    }

    private WhipgrassEntanglerCantAttackUnlessYouPayEffect(final WhipgrassEntanglerCantAttackUnlessYouPayEffect effect) {
        super(effect);
        this.parentLinkHandshake = effect.parentLinkHandshake;
    }

    @Override
    public boolean applies(GameEvent event, Ability source, Game game) {
        return source.getSourceId().equals(event.getSourceId())
                && source instanceof LinkedEffectIdStaticAbility
                && ((LinkedEffectIdStaticAbility) source).checkLinked(this.parentLinkHandshake);
    }

    @Override
    public ManaCosts getManaCostToPay(GameEvent event, Ability source, Game game) {
        Permanent sourceObject = game.getPermanent(source.getSourceId());
        if (sourceObject != null) {
            int payment = new PermanentsOnBattlefieldCount(filter).calculate(game, source, this);
            if (payment > 0) {
                return new ManaCostsImpl<>("{" + payment + '}');
            }
        }
        return null;
    }

    @Override
    public WhipgrassEntanglerCantAttackUnlessYouPayEffect copy() {
        return new WhipgrassEntanglerCantAttackUnlessYouPayEffect(this);
    }

    @Override
    public void newId() {
        // No id set this way. Parent Ability takes responsability of calling manualNewId()
    }

    public void manualNewId() {
        super.newId();
    }

    public void setParentLinkHandshake(UUID parentLinkHandshake) {
        this.parentLinkHandshake = parentLinkHandshake;
    }
}
