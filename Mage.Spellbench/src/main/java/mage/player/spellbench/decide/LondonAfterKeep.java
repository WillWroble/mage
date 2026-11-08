package mage.player.spellbench.decide;

import mage.cards.CardsImpl;
import mage.constants.Outcome;
import mage.filter.FilterCard;
import mage.game.Game;
import mage.game.mulligan.LondonMulligan;
import mage.players.Player;
import mage.target.Target;
import mage.target.common.TargetCardInHand;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.UUID;

/**
 * The London mulligan in the order of CR 103.5 and spec Section 7.5: a seat that mulligans draws a fresh hand of
 * seven and decides again on all of it; once it keeps after k mulligans, it puts k cards on the bottom (the
 * {@code mulligan_bottom} group). XMage's {@code LondonMulligan} asks for the bottom cards right after each
 * mulligan, before the next keep decision; this subclass moves that step to the keep. Hand sizes stay in
 * {@code LondonMulligan}'s own (private) maps, so observations read {@code mulligans_taken} as before.
 */
final class LondonAfterKeep extends LondonMulligan {

    private static final long serialVersionUID = 1L;
    private static final Field STARTING = field("startingHandSizes");
    private static final Field OPENING = field("openingHandSizes");

    LondonAfterKeep() {
        super(0);
    }

    @Override
    public void mulligan(Game game, UUID playerId) {
        Player player = game.getPlayer(playerId);
        int numCards = sizes(STARTING).get(playerId);
        player.getLibrary().addAll(player.getHand().getCards(game), game);
        player.getHand().clear();
        player.shuffleLibrary(null, game);
        Map<UUID, Integer> opening = sizes(OPENING);
        opening.put(playerId, opening.get(playerId) - 1); // no free mulligans in a duel
        game.informPlayers(player.getLogName() + " mulligans down to " + opening.get(playerId) + " cards");
        drawHand(numCards, player, game);
    }

    @Override
    public void endMulligan(Game game, UUID playerId) {
        Player player = game.getPlayer(playerId);
        int keep = sizes(OPENING).get(playerId);
        // the seat answers all k picks of its mulligan_bottom group at the first prompt (SeatPlayer)
        while (player.canRespond() && player.getHand().size() > keep) {
            Target target = new TargetCardInHand(new FilterCard("card (" + (player.getHand().size() - keep)
                    + " more) to put on the bottom of your library"));
            player.chooseTarget(Outcome.Discard, target, null, game);
            if (target.getTargets().isEmpty()) {
                break;
            }
            player.putCardsOnBottomOfLibrary(new CardsImpl(target.getTargets()), game, null, true);
        }
    }

    @Override
    public LondonAfterKeep copy() {
        LondonAfterKeep c = new LondonAfterKeep();
        c.usedFreeMulligans.putAll(this.usedFreeMulligans);
        c.sizes(STARTING).putAll(this.sizes(STARTING));
        c.sizes(OPENING).putAll(this.sizes(OPENING));
        return c;
    }

    @SuppressWarnings("unchecked")
    private Map<UUID, Integer> sizes(Field f) {
        try {
            return (Map<UUID, Integer>) f.get(this);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("LondonMulligan hand sizes unreadable", e);
        }
    }

    private static Field field(String name) {
        try {
            Field f = LondonMulligan.class.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (NoSuchFieldException e) {
            throw new IllegalStateException("LondonMulligan." + name + " missing at this XMage pin", e);
        }
    }
}
