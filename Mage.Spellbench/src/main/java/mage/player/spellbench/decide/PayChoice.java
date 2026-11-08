package mage.player.spellbench.decide;

import mage.MageItem;
import mage.abilities.Ability;
import mage.cards.Card;
import mage.cards.Cards;
import mage.constants.Outcome;
import mage.constants.Zone;
import mage.game.Game;
import mage.game.permanent.Permanent;
import mage.player.ai.PossibleTargetsComparator;
import mage.player.ai.PossibleTargetsSelector;
import mage.players.Player;
import mage.target.Target;
import mage.target.common.TargetCardInGraveyardBattlefieldOrStack;
import mage.target.common.TargetDiscard;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Engine autopay's object choices (convoke, delve, improvise and the other costs XMage's planner pays by choosing
 * objects; Section 7.6 {@code engine_autopay}): {@code ComputerPlayer.makeChoice} and its
 * {@code PossibleTargetsSelector}, copied with one change. Their ranking ends in a tie-break by internal object id
 * ({@code PossibleTargetsComparator.BY_ID}); here the last tie-break is a visible order instead (players by seat,
 * then the battlefield, hands, graveyards and exile in XMage's own zone order), so no UUID order reaches this engine
 * default (task X4h).
 */
final class PayChoice {

    private static final Field BY_TYPES = field("BY_TYPES");
    private static final Field BY_BIGGER_SCORE = field("BY_BIGGER_SCORE");
    private static final Field BY_NAME = field("BY_NAME");
    private static final Field BY_LAND = field("BY_LAND");
    private static final Field BY_PLAYABLE = field("BY_PLAYABLE");

    private PayChoice() {
    }

    static boolean choose(Player player, Outcome outcome, Target target, Ability source, Game game, Cards fromCards) {
        if (fromCards != null && fromCards.isEmpty()) {
            return false;
        }
        UUID controller = target.getAffectedAbilityControllerId(player.getId());
        if (target.isChoiceCompleted(controller, source, game, fromCards)) {
            return false;
        }
        List<MageItem> any = new ArrayList<>();
        List<MageItem> me = new ArrayList<>();
        List<MageItem> opponents = new ArrayList<>();
        for (UUID id : target.possibleTargets(controller, source, game, fromCards)) {
            if (target.contains(id)) {
                continue;
            }
            Player p = game.getPlayer(id);
            MageItem item = p != null ? p : game.getObject(id);
            if (item == null) {
                continue;
            }
            any.add(item);
            (PossibleTargetsSelector.isMyItem(controller, item) ? me : opponents).add(item);
        }
        if (any.isEmpty() || target.getMinNumberOfTargets() > 0 && any.size() < target.getMinNumberOfTargets()) {
            return false;
        }
        PossibleTargetsComparator comparators = new PossibleTargetsComparator(controller, game);
        Comparator<MageItem> visible = visibleOrder(game, any);
        Comparator<MageItem> mostValuableFirst = comparator(comparators, BY_TYPES)
                .thenComparing(comparator(comparators, BY_BIGGER_SCORE))
                .thenComparing(comparator(comparators, BY_NAME))
                .thenComparing(visible);
        boolean exileIsGood = outcome == Outcome.Exile
                && (Zone.GRAVEYARD.match(target.getZone()) || Zone.LIBRARY.match(target.getZone()))
                && !(target instanceof TargetCardInGraveyardBattlefieldOrStack);
        boolean anyEffect = outcome.anyTargetHasSameValue() || exileIsGood;
        boolean good = outcome.isGood() || exileIsGood;
        if (target instanceof TargetDiscard) {
            comparators.findPlayableItems();
            Comparator<MageItem> useless = comparator(comparators, BY_LAND).reversed()
                    .thenComparing(comparator(comparators, BY_PLAYABLE).reversed())
                    .thenComparing(mostValuableFirst);
            me.sort(useless);
            opponents.sort(useless);
            any.sort(useless);
        } else if (good) {
            me.sort(mostValuableFirst);
            opponents.sort(mostValuableFirst.reversed());
            any.sort(mostValuableFirst);
        } else {
            me.sort(mostValuableFirst.reversed());
            opponents.sort(mostValuableFirst);
            any.sort(mostValuableFirst.reversed());
        }
        List<MageItem> goodTargets = anyEffect ? any : good ? me : opponents;
        List<MageItem> badTargets = anyEffect ? Collections.<MageItem>emptyList() : good ? opponents : me;
        for (MageItem item : goodTargets) {
            add(target, item, source, game);
            if (target.isChoiceCompleted(controller, source, game, fromCards)) {
                return true;
            }
        }
        for (MageItem item : badTargets) {
            if (target.isChosen(game)) {
                break;
            }
            add(target, item, source, game);
        }
        return target.isChosen(game) && !target.getTargets().isEmpty();
    }

    private static void add(Target target, MageItem item, Ability source, Game game) {
        if (target.isNotTarget()) {
            target.add(item.getId(), game);
        } else {
            target.addTarget(item.getId(), source, game);
        }
    }

    /** Players in turn order, then permanents in battlefield order, then cards by zone in XMage's zone order. */
    private static Comparator<MageItem> visibleOrder(Game game, List<MageItem> items) {
        Map<UUID, Integer> rank = new LinkedHashMap<>();
        for (UUID p : game.getState().getPlayerList()) {
            rank.putIfAbsent(p, rank.size());
        }
        for (Permanent p : game.getBattlefield().getAllPermanents()) {
            rank.putIfAbsent(p.getId(), rank.size());
        }
        for (UUID p : game.getState().getPlayerList()) {
            Player player = game.getPlayer(p);
            if (player == null) {
                continue;
            }
            for (UUID id : player.getHand()) {
                rank.putIfAbsent(id, rank.size());
            }
            for (UUID id : player.getGraveyard()) {
                rank.putIfAbsent(id, rank.size());
            }
        }
        int unranked = Integer.MAX_VALUE;
        for (MageItem item : items) {
            if (!rank.containsKey(item.getId())) {
                // exile and the library have no visible order here: such an item ranks by name only (counted)
                rank.put(item.getId(), unranked);
            }
        }
        return Comparator.comparingInt((MageItem i) -> rank.getOrDefault(i.getId(), unranked))
                .thenComparing(i -> i instanceof Card && ((Card) i).getName() != null ? ((Card) i).getName() : "",
                        HiddenOrder::codePoints);
    }

    @SuppressWarnings("unchecked")
    private static Comparator<MageItem> comparator(PossibleTargetsComparator owner, Field f) {
        try {
            return (Comparator<MageItem>) f.get(owner);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Field field(String name) {
        try {
            Field f = PossibleTargetsComparator.class.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (NoSuchFieldException e) {
            throw new IllegalStateException(e);
        }
    }
}
