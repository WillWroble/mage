package mage.player.spellbench.decide;

import mage.game.Game;
import mage.game.events.TableEvent;
import mage.player.spellbench.observe.ObservationBuilder;
import mage.players.Player;

import java.util.List;
import java.util.Map;

/**
 * The XMage side of one v2 game, as {@code server.GameSession} builds it: the duel, its two seats (players named
 * {@code p0} and {@code p1}, the order CABT's session created them in), and, once both have their decks, the
 * {@link Exchange} that poses their decisions.
 */
public final class Seats {

    public final Game game;
    private final SeatPlayer[] players = new SeatPlayer[2];
    private final SeatState[] states = new SeatState[2];
    public Exchange exchange;

    private Seats() {
        this.game = new Duel();
        for (int k = 0; k < 2; k++) {
            states[k] = new SeatState(Exchange.SEATS.get(k));
            players[k] = new SeatPlayer(Exchange.SEATS.get(k), states[k]);
        }
    }

    /** The duel and both seat players; create them under the game's stream router. */
    public static Seats create() {
        return new Seats();
    }

    public Player player(int k) {
        return players[k];
    }

    /**
     * Builds the observation builder and the exchange once both players hold their decks, and ends the game halted
     * if XMage reports an internal error: its priority loop would otherwise roll the game back and go on, which
     * no decision stream can represent (Section 9.5).
     */
    public void connect(byte[] gameSecret, Map<String, ?> flags, long maxSteps, long maxDecisions,
                        List<String> cardNameDomain, boolean mulliganNone) {
        connect(gameSecret, flags, maxSteps, maxDecisions, cardNameDomain, mulliganNone, false);
    }

    public void connect(byte[] gameSecret, Map<String, ?> flags, long maxSteps, long maxDecisions,
                        List<String> cardNameDomain, boolean mulliganNone, boolean priorityMana) {
        ObservationBuilder builder = ObservationBuilder.forSession(game, gameSecret, flags);
        exchange = new Exchange(game, builder, maxSteps, maxDecisions, cardNameDomain, mulliganNone, priorityMana);
        for (SeatState s : states) {
            s.exchange = exchange;
        }
        Exchange ex = exchange;
        game.addTableEventListener(event -> {
            if (event.getEventType() == TableEvent.EventType.ERROR && !ex.isClosed()) {
                System.err.println("xmage-spellbench: XMage reported a game error: " + event.getMessage());
                if (event.getException() != null) {
                    event.getException().printStackTrace(System.err);
                }
                throw ex.halt("engine_error");
            }
        });
    }
}
