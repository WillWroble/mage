package mage.player.cabt;

import mage.MageObject;
import mage.cards.Card;
import mage.constants.PhaseStep;
import mage.constants.TurnPhase;
import mage.constants.Zone;
import mage.game.Game;
import mage.game.command.CommandObject;
import mage.game.permanent.Permanent;
import mage.game.stack.StackObject;
import mage.players.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Builds a CABT-style observation (logs + current + select) from an XMage
 * game and a pending prompt. Read-only: never mutates the game, never calls
 * pass()/priority(), never triggers engine advancement.
 * <p>
 * Hidden-information boundary: shared public zones (battlefield, stack,
 * exile, command) and graveyards serialize as object views; hands and
 * libraries stay counts-only, except the selecting player's own hand.
 */
public final class MagicObservationSerializer {

    public MagicObservation serialize(Game game, Player selectingPlayer, PendingDecision decision) {
        MagicCurrent current = serializeCurrent(game, selectingPlayer.getId());
        MagicSelectView select = serializeSelect(game, selectingPlayer, decision);
        // logs stay empty for now; later tasks will fill them
        return new MagicObservation(Collections.emptyList(), current, select);
    }

    /**
     * Bare state snapshot without a pending prompt — same hidden-information
     * rules ({@code perspectivePlayerId}'s own hand is visible, others stay
     * counts-only). Used for the final state of a recorded run.
     */
    public MagicCurrent serializeCurrent(Game game, UUID perspectivePlayerId) {
        List<UUID> playerIds = orderedPlayerIds(game);
        List<MagicPlayerView> players = new ArrayList<MagicPlayerView>();
        for (int i = 0; i < playerIds.size(); i++) {
            players.add(serializePlayer(game, playerIds.get(i), i, perspectivePlayerId));
        }
        List<MagicPermanentView> battlefield = serializeBattlefield(game);
        List<MagicStackObjectView> stack = serializeStack(game);
        return new MagicCurrent(
                game.getTurnNum(),
                nullableToString(game.getActivePlayerId()),
                nullableToString(game.getPriorityPlayerId()),
                enumName(phaseOf(game)),
                enumName(stepOf(game)),
                players,
                stack.size(),
                battlefield.size(),
                game.hasEnded(),
                game.getWinner(),
                battlefield,
                stack,
                serializeExile(game),
                serializeCommand(game));
    }

    private List<MagicPermanentView> serializeBattlefield(Game game) {
        List<MagicPermanentView> views = new ArrayList<MagicPermanentView>();
        if (game.getBattlefield() != null) {
            for (Permanent permanent : game.getBattlefield().getAllActivePermanents()) {
                views.add(MagicObjectViewFactory.permanentView(game, permanent));
            }
        }
        return views;
    }

    private List<MagicStackObjectView> serializeStack(Game game) {
        List<MagicStackObjectView> views = new ArrayList<MagicStackObjectView>();
        if (game.getStack() != null) {
            for (StackObject stackObject : game.getStack()) {
                views.add(MagicObjectViewFactory.stackObjectView(game, stackObject));
            }
        }
        return views;
    }

    private List<MagicObjectView> serializeExile(Game game) {
        List<MagicObjectView> views = new ArrayList<MagicObjectView>();
        if (game.getExile() != null) {
            for (Card card : game.getExile().getAllCards(game)) {
                views.add(MagicObjectViewFactory.objectView(game, card, Zone.EXILED));
            }
        }
        return views;
    }

    private List<MagicObjectView> serializeCommand(Game game) {
        List<MagicObjectView> views = new ArrayList<MagicObjectView>();
        if (game.getState() != null && game.getState().getCommand() != null) {
            for (CommandObject commandObject : game.getState().getCommand()) {
                views.add(MagicObjectViewFactory.objectView(game, commandObject, Zone.COMMAND));
            }
        }
        return views;
    }

    private MagicPlayerView serializePlayer(Game game, UUID playerId, int playerIndex,
                                            UUID selectingPlayerId) {
        Player player = game.getPlayer(playerId);
        if (player == null) {
            return new MagicPlayerView(playerIndex, nullableToString(playerId),
                    null, 0, 0, 0, 0, false, false,
                    null, null, null, null);
        }
        int handCount = player.getHand() == null ? 0 : player.getHand().size();
        int libraryCount = player.getLibrary() == null ? 0 : player.getLibrary().size();
        int graveyardCount = player.getGraveyard() == null ? 0 : player.getGraveyard().size();
        // own hand only: everyone else stays count-only
        List<MagicObjectView> hand = playerId.equals(selectingPlayerId)
                ? serializeCardIds(game, player.getHand(), Zone.HAND)
                : Collections.<MagicObjectView>emptyList();
        return new MagicPlayerView(
                playerIndex,
                nullableToString(playerId),
                player.getName(),
                player.getLife(),
                handCount,
                libraryCount,
                graveyardCount,
                player.isPassed(),
                player.isInGame(),
                serializeCardIds(game, player.getGraveyard(), Zone.GRAVEYARD),
                serializePlayerExile(game, playerId),
                Collections.<MagicObjectView>emptyList(), // revealed-hand tracking comes later
                hand);
    }

    private List<MagicObjectView> serializeCardIds(Game game, Iterable<UUID> cardIds, Zone zone) {
        List<MagicObjectView> views = new ArrayList<MagicObjectView>();
        if (cardIds != null) {
            for (UUID cardId : cardIds) {
                views.add(serializeCardId(game, cardId, zone));
            }
        }
        return views;
    }

    private MagicObjectView serializeCardId(Game game, UUID cardId, Zone zone) {
        MageObject object = game.getCard(cardId);
        if (object == null) {
            object = game.getObject(cardId);
        }
        if (object == null) {
            // id known but object unresolvable: keep the bare reference so the
            // observation stays complete
            return new MagicObjectView(
                    new MagicObjectReference(cardId.toString(), null, zone.name(),
                            null, null, null, null),
                    null, null);
        }
        return MagicObjectViewFactory.objectView(game, object, zone);
    }

    private List<MagicObjectView> serializePlayerExile(Game game, UUID playerId) {
        List<MagicObjectView> views = new ArrayList<MagicObjectView>();
        if (game.getExile() != null) {
            for (Card card : game.getExile().getCardsOwned(game, playerId)) {
                views.add(MagicObjectViewFactory.objectView(game, card, Zone.EXILED));
            }
        }
        return views;
    }

    private MagicSelectView serializeSelect(Game game, Player selectingPlayer, PendingDecision decision) {
        List<MagicOptionView> options = new ArrayList<MagicOptionView>();
        List<MagicOption> decisionOptions = decision.options();
        for (int i = 0; i < decisionOptions.size(); i++) {
            MagicOption option = decisionOptions.get(i);
            options.add(new MagicOptionView(i, option.type().name(), option.label(), option.payload()));
        }
        UUID selectingPlayerId = selectingPlayer.getId();
        return new MagicSelectView(
                indexOf(game, selectingPlayerId),
                nullableToString(selectingPlayerId),
                decision.selectType().name(),
                decision.minCount(),
                decision.maxCount(),
                options);
    }

    private static TurnPhase phaseOf(Game game) {
        TurnPhase phase = game.getTurnPhaseType();
        if (phase != null) {
            return phase;
        }
        return game.getPhase() == null ? null : game.getPhase().getType();
    }

    private static PhaseStep stepOf(Game game) {
        PhaseStep step = game.getTurnStepType();
        if (step != null) {
            return step;
        }
        return game.getStep() == null ? null : game.getStep().getType();
    }

    private static List<UUID> orderedPlayerIds(Game game) {
        List<UUID> ids = new ArrayList<UUID>();
        if (game.getPlayerList() != null) {
            for (UUID playerId : game.getPlayerList()) {
                ids.add(playerId);
            }
        }
        return ids;
    }

    private static int indexOf(Game game, UUID playerId) {
        List<UUID> ids = orderedPlayerIds(game);
        for (int i = 0; i < ids.size(); i++) {
            if (ids.get(i).equals(playerId)) {
                return i;
            }
        }
        return -1;
    }

    private static String nullableToString(Object value) {
        return value == null ? null : value.toString();
    }

    // enum name, not toString: XMage enums override toString with display text
    // ("Precombat Main"), while the bridge wants the stable identifier
    private static String enumName(Enum<?> value) {
        return value == null ? null : value.name();
    }
}
