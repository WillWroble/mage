package mage.player.cabt;

import mage.cards.Card;
import mage.cards.decks.Deck;
import mage.constants.RangeOfInfluence;
import mage.game.Game;
import mage.game.GameOptions;
import mage.players.Player;
import mage.util.RandomUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * One live protocol-driven game: builds a real {@link CabtLiveDuel} from two
 * decklists, runs the engine loop on a background thread through
 * {@link CabtBlockingBridgeController}, and exposes the CABT-style
 * request/response surface — {@link #start()} and {@link #select(List)} each
 * return the next {@link Event} (a decision to answer, the game's end, or an
 * engine error).
 * <p>
 * Selections are validated here, before the game thread is woken: an invalid
 * selection throws {@link InvalidSelectionException} and leaves the pending
 * decision untouched, so the protocol can report a structured error without
 * corrupting or advancing the game.
 * <p>
 * Thread-safety contract: between events the game thread is parked inside
 * requestSelection (or exited), so reading game state from the protocol
 * thread while a decision is pending or the game is over is safe.
 */
public final class CabtGameSession {

    /** What the engine surfaced next: a decision, game over, or a failure. */
    public static final class Event {

        public enum Kind {
            DECISION, GAME_OVER, GAME_ERROR
        }

        private final Kind kind;
        private final int sequence;
        private final String playerName;
        private final String playerId;
        private final PendingDecision decision;
        private final MagicObservation observation;
        private final MagicCurrent finalState;
        private final String winner;
        private final String errorMessage;

        private Event(Kind kind, int sequence, String playerName, String playerId,
                      PendingDecision decision, MagicObservation observation,
                      MagicCurrent finalState, String winner, String errorMessage) {
            this.kind = kind;
            this.sequence = sequence;
            this.playerName = playerName;
            this.playerId = playerId;
            this.decision = decision;
            this.observation = observation;
            this.finalState = finalState;
            this.winner = winner;
            this.errorMessage = errorMessage;
        }

        static Event decision(int sequence, Player player, PendingDecision decision,
                              MagicObservation observation) {
            return new Event(Kind.DECISION, sequence, player.getName(),
                    player.getId().toString(), decision, observation, null, null, null);
        }

        static Event gameOver(String winner, MagicCurrent finalState) {
            return new Event(Kind.GAME_OVER, -1, null, null, null, null,
                    finalState, winner, null);
        }

        static Event gameError(Throwable error) {
            String message = error.getClass().getSimpleName()
                    + (error.getMessage() == null ? "" : ": " + error.getMessage());
            return new Event(Kind.GAME_ERROR, -1, null, null, null, null,
                    null, null, message);
        }

        public Kind kind() {
            return kind;
        }

        public int sequence() {
            return sequence;
        }

        public String playerName() {
            return playerName;
        }

        public String playerId() {
            return playerId;
        }

        public PendingDecision decision() {
            return decision;
        }

        public MagicObservation observation() {
            return observation;
        }

        public MagicCurrent finalState() {
            return finalState;
        }

        public String winner() {
            return winner;
        }

        public String errorMessage() {
            return errorMessage;
        }
    }

    /** Optional game_start knobs; every field may stay null for defaults. */
    public static final class Config {
        private String playerName0 = "Player0";
        private String playerName1 = "Player1";
        private Long seed;
        private Integer maxTurns;
        private long decisionTimeoutSeconds = 120;

        public Config playerNames(String name0, String name1) {
            if (name0 != null) {
                this.playerName0 = name0;
            }
            if (name1 != null) {
                this.playerName1 = name1;
            }
            return this;
        }

        public Config seed(Long seed) {
            this.seed = seed;
            return this;
        }

        public Config maxTurns(Integer maxTurns) {
            this.maxTurns = maxTurns;
            return this;
        }

        public Config decisionTimeoutSeconds(long seconds) {
            this.decisionTimeoutSeconds = seconds;
            return this;
        }
    }

    private final CabtLiveDuel game;
    private final CabtBridgePlayer player0;
    private final CabtBridgePlayer player1;
    private final CabtBlockingBridgeController controller;
    private final BlockingQueue<Event> events = new ArrayBlockingQueue<Event>(4);
    private final MagicObservationSerializer serializer = new MagicObservationSerializer();
    private final List<Card> deckCards = new ArrayList<Card>();
    private final long decisionTimeoutSeconds;

    private Thread gameThread;
    private Event currentEvent;
    private volatile boolean closed;

    public CabtGameSession(List<CabtDeckFactory.Entry> deck0, List<CabtDeckFactory.Entry> deck1,
                           Config config) {
        this(deck0, deck1, config, new CardResolver());
    }

    /**
     * Builds the game with an explicit {@link CardResolver}, so a caller that
     * already validated the decks (e.g. the protocol server) can reuse the
     * same repository-backed resolver and its resolution cache.
     */
    public CabtGameSession(List<CabtDeckFactory.Entry> deck0, List<CabtDeckFactory.Entry> deck1,
                           Config config, CardResolver resolver) {
        if (config == null) {
            config = new Config();
        }
        this.decisionTimeoutSeconds = config.decisionTimeoutSeconds;
        if (config.seed != null) {
            // engine-global randomness (shuffles, coin flips): best-effort
            // determinism for single-session processes
            RandomUtil.setSeed(config.seed);
        }

        this.game = new CabtLiveDuel();
        this.controller = new CabtBlockingBridgeController(events);
        this.player0 = new CabtBridgePlayer(config.playerName0, RangeOfInfluence.ALL, controller);
        this.player1 = new CabtBridgePlayer(config.playerName1, RangeOfInfluence.ALL, controller);

        addPlayer(player0, resolver.buildDeck(player0.getId(), deck0));
        addPlayer(player1, resolver.buildDeck(player1.getId(), deck1));

        GameOptions options = new GameOptions();
        if (config.maxTurns != null) {
            options.stopOnTurn = config.maxTurns;
        }
        game.setGameOptions(options);
    }

    private void addPlayer(CabtBridgePlayer player, List<Card> cards) {
        Deck deck = new Deck();
        deck.getCards().addAll(cards);
        deckCards.addAll(cards);
        game.loadCards(deck.getCards(), player.getId());
        game.addPlayer(player, deck);
    }

    /**
     * Starts the engine loop on a background thread and blocks until the
     * first event (normally the pregame starting-player or mulligan prompt).
     */
    public Event start() {
        if (gameThread != null) {
            throw new IllegalStateException("session already started");
        }
        gameThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    game.start(player0.getId());
                    events.put(Event.gameOver(game.getWinner(),
                            serializer.serializeCurrent(game, null)));
                } catch (CabtSessionClosedException e) {
                    // deliberate shutdown via finish(): no event
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Throwable t) {
                    if (!closed) {
                        events.offer(Event.gameError(t));
                    }
                }
            }
            // the name must carry XMage's GAME prefix: ThreadUtils.ensureRunInGameThread
            // rejects engine work on unrecognized threads
        }, mage.util.ThreadUtils.THREAD_PREFIX_GAME + " cabt");
        gameThread.setDaemon(true);
        gameThread.start();
        return awaitEvent();
    }

    /**
     * Validates and applies one selection for the pending decision, then
     * blocks until the engine surfaces the next event. Invalid selections
     * throw {@link InvalidSelectionException} and leave the game untouched.
     * <p>
     * The pending decision is single-use: it is cleared <em>before</em> the
     * answer is handed to the game thread, so if {@link #awaitEvent()} times
     * out the old decision can never be re-validated or re-answered. On
     * timeout the session is closed (fail-closed) so no stale state survives.
     */
    public Event select(List<Integer> indices) {
        Event answered = currentEvent;
        if (answered == null || answered.kind() != Event.Kind.DECISION) {
            throw new IllegalStateException("NO_PENDING_DECISION");
        }
        Selection selection = new Selection(indices);
        SelectionValidator.validate(answered.decision(), selection);
        currentEvent = null; // no longer answerable
        controller.answer(selection);
        try {
            return awaitEvent();
        } catch (RuntimeException e) {
            finish(); // fail-closed: never leave an answered decision dangling
            throw e;
        }
    }

    /**
     * Closes the session: unparks the game thread with a poison pill so the
     * engine loop unwinds via {@link CabtSessionClosedException}.
     */
    public void finish() {
        closed = true;
        controller.close();
        if (gameThread != null) {
            gameThread.interrupt();
            try {
                gameThread.join(TimeUnit.SECONDS.toMillis(5));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** The event returned by the latest start()/select() call. */
    public Event currentEvent() {
        return currentEvent;
    }

    public boolean isFinished() {
        return currentEvent != null && currentEvent.kind() != Event.Kind.DECISION;
    }

    /**
     * Every card constructed for either deck — the game-scoped card pool the
     * {@code all_card_data} command exports.
     */
    public List<Card> allDeckCards() {
        return Collections.unmodifiableList(deckCards);
    }

    /**
     * Live game, for state rendering. Only safe to read while a decision is
     * pending or the game is over (the game thread is parked or exited).
     */
    public Game game() {
        return game;
    }

    /**
     * Public-information state snapshot for visualize_data, from the pending
     * player's perspective (or fully hidden hands when the game is over).
     */
    public MagicCurrent snapshotCurrent() {
        Event event = currentEvent;
        java.util.UUID perspective = null;
        if (event != null && event.kind() == Event.Kind.DECISION) {
            perspective = java.util.UUID.fromString(event.playerId());
        }
        return serializer.serializeCurrent(game, perspective);
    }

    private Event awaitEvent() {
        try {
            Event event = events.poll(decisionTimeoutSeconds, TimeUnit.SECONDS);
            if (event == null) {
                throw new IllegalStateException(
                        "ENGINE_TIMEOUT: no decision or game end within "
                                + decisionTimeoutSeconds + "s");
            }
            currentEvent = event;
            return event;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for the engine");
        }
    }
}
