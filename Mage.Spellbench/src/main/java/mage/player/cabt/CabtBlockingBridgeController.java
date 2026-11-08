package mage.player.cabt;

import mage.game.Game;
import mage.players.Player;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * CABT bridge controller for protocol-driven games: serializes each decision
 * into an observation, publishes it as a {@link CabtGameSession.Event} on the
 * session's event queue, and parks the game thread until the protocol side
 * answers with a validated {@link Selection} (or the session closes).
 * <p>
 * One instance serves both players of a duel: the engine loop is single
 * threaded, so decisions arrive strictly one at a time.
 */
final class CabtBlockingBridgeController implements CabtBridgeController {

    private static final Object POISON = new Object();

    private final MagicObservationSerializer serializer = new MagicObservationSerializer();
    private final BlockingQueue<CabtGameSession.Event> events;
    // capacity 2 so close() can always offer POISON without blocking, even if
    // a stale answer was never consumed
    private final BlockingQueue<Object> answers = new ArrayBlockingQueue<Object>(2);
    private volatile boolean closed;
    private int sequence;

    CabtBlockingBridgeController(BlockingQueue<CabtGameSession.Event> events) {
        this.events = events;
    }

    @Override
    public Selection requestSelection(Game game, Player player, PendingDecision decision) {
        if (closed) {
            throw new CabtSessionClosedException();
        }
        MagicObservation observation = serializer.serialize(game, player, decision);
        try {
            events.put(CabtGameSession.Event.decision(sequence++, player, decision, observation));
            Object answer = answers.take();
            if (answer == POISON || closed) {
                throw new CabtSessionClosedException();
            }
            return (Selection) answer;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CabtSessionClosedException();
        }
    }

    /**
     * Hands the protocol side's validated selection to the parked game
     * thread. Called only while that thread waits in requestSelection.
     */
    void answer(Selection selection) {
        if (!answers.offer(selection)) {
            throw new IllegalStateException("game thread has an unconsumed answer already");
        }
    }

    void close() {
        closed = true;
        answers.offer(POISON);
    }
}
