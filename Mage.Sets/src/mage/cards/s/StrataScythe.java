
package mage.cards.s;

import java.util.UUID;
import mage.abilities.Ability;
import mage.abilities.common.EntersBattlefieldTriggeredAbility;
import mage.abilities.common.SimpleStaticAbility;
import mage.abilities.costs.mana.GenericManaCost;
import mage.abilities.dynamicvalue.DynamicValue;
import mage.abilities.effects.Effect;
import mage.abilities.effects.OneShotEffect;
import mage.abilities.effects.common.continuous.BoostEquippedEffect;
import mage.abilities.keyword.EquipAbility;
import mage.cards.Card;
import mage.cards.CardImpl;
import mage.cards.CardSetInfo;
import mage.constants.*;
import mage.filter.FilterPermanent;
import mage.filter.common.FilterLandCard;
import mage.filter.predicate.mageobject.NamePredicate;
import mage.game.Game;
import mage.game.permanent.Permanent;
import mage.players.Player;
import mage.target.common.TargetCardInLibrary;

/**
 *
 * @author Loki
 */
public final class StrataScythe extends CardImpl {

    public StrataScythe(UUID ownerId, CardSetInfo setInfo) {
        super(ownerId, setInfo, new CardType[]{CardType.ARTIFACT}, "{3}");
        this.subtype.add(SubType.EQUIPMENT);

        // Imprint — When Strata Scythe enters the battlefield, search your library for a land card, exile it, then shuffle.
        this.addAbility(new EntersBattlefieldTriggeredAbility(new StrataScytheImprintEffect()).setAbilityWord(AbilityWord.IMPRINT));

        // Equipped creature gets +1/+1 for each land on the battlefield with the same name as the exiled card.
        this.addAbility(new SimpleStaticAbility(new BoostEquippedEffect(SameNameAsExiledCountValue.getInstance(), SameNameAsExiledCountValue.getInstance())));

        // Equip {3}
        this.addAbility(new EquipAbility(Outcome.BoostCreature, new GenericManaCost(3), false));
    }

    private StrataScythe(final StrataScythe card) {
        super(card);
    }

    @Override
    public StrataScythe copy() {
        return new StrataScythe(this);
    }

}

class StrataScytheImprintEffect extends OneShotEffect {

    StrataScytheImprintEffect() {
        super(Outcome.Exile);
        staticText = "search your library for a land card, exile it, then shuffle";
    }

    private StrataScytheImprintEffect(final StrataScytheImprintEffect effect) {
        super(effect);
    }

    @Override
    public boolean apply(Game game, Ability source) {
        Player player = game.getPlayer(source.getControllerId());
        if (player == null) {
            return false;
        }
        TargetCardInLibrary target = new TargetCardInLibrary(new FilterLandCard());
        if (player.searchLibrary(target, source, game)) {
            if (!target.getTargets().isEmpty()) {
                UUID cardId = target.getTargets().get(0);
                Card card = player.getLibrary().remove(cardId, game);
                if (card != null) {
                    card.moveToExile(source.getSourceId(), "Strata Scythe", source, game);
                    Permanent permanent = game.getPermanent(source.getSourceId());
                    if (permanent != null) {
                        permanent.imprint(card.getId(), game);
                    }
                }
            }
        }
        player.shuffleLibrary(source, game);
        return true;
    }

    @Override
    public StrataScytheImprintEffect copy() {
        return new StrataScytheImprintEffect(this);
    }

}

class SameNameAsExiledCountValue implements DynamicValue {

    private static final SameNameAsExiledCountValue instance = new SameNameAsExiledCountValue();

    public static SameNameAsExiledCountValue getInstance() {
        return instance;
    }

    private SameNameAsExiledCountValue() {
    }

    @Override
    public int calculate(Game game, Ability sourceAbility, Effect effect) {
        int value = 0;
        Permanent permanent = game.getPermanent(sourceAbility.getSourceId());
        if (permanent != null && !permanent.getImprinted().isEmpty()) {
            FilterPermanent filterPermanent = new FilterPermanent();
            filterPermanent.add(new NamePredicate(game.getCard(permanent.getImprinted().get(0)).getName()));
            value = game.getBattlefield().count(filterPermanent, sourceAbility.getControllerId(), sourceAbility, game);
        }
        return value;
    }

    @Override
    public DynamicValue copy() {
        return instance;
    }

    @Override
    public String toString() {
        return "1";
    }

    @Override
    public String getMessage() {
        return "land on the battlefield with the same name as the exiled card";
    }
}
