package mage.player.spellbench.decide;

import mage.cards.Card;
import mage.cards.Cards;
import mage.constants.Zone;
import mage.game.Game;
import mage.player.spellbench.observe.Look;
import mage.player.spellbench.observe.Observation;
import mage.player.spellbench.observe.ObservationBuilder;
import mage.player.spellbench.observe.ObservationException;
import mage.player.spellbench.observe.PriorityHolder;
import mage.player.spellbench.server.StrictJson;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * The hand-off between the game thread and the protocol thread for one game, and the decision counters of
 * Sections 8 and 9.3 (task X4).
 * <p>
 * The game thread runs XMage. A {@link SeatPlayer} callback builds a {@link Pose} and calls {@link #ask}: the
 * exchange checks the caps, builds the acting seat's observation, turns the pose into a {@code seat_decision},
 * publishes it and parks until the protocol thread answers with a candidate index. It keeps the counters the live
 * validator keeps (V3): {@code seat_step} per seat, {@code group_id} per seat, the partial group, and the completed
 * groups with the ones a rewind abandoned, so {@code decision_count} is exactly what the host counts.
 * <p>
 * Ending a game from the game thread ({@link #halt}, a cap) publishes the terminal first and then throws
 * {@link Closed}, an {@link Error}: XMage's priority loop catches every {@link Exception} and would otherwise roll
 * back and continue the game.
 */
public final class Exchange {

    /** Unwinds the game thread after its game ended or the session closed; never caught by XMage. */
    public static final class Closed extends Error {
        private static final long serialVersionUID = 1L;

        Closed() {
            super("spellbench game closed", null, false, false);
        }
    }

    /** What the protocol thread receives: a decision to forward, or the game's end. */
    public static final class Outcome {
        /** The seat_decision, or null for a terminal. */
        public final Map<String, Object> seatDecision;
        /** Each candidate's canonical semantic, for the echo check. */
        public final List<String> semantics;
        /** outcome, classification, winner, reason; null for a decision. */
        public final Map<String, Object> terminal;
        /** True when the game ended because the game thread returned (a natural end). */
        public final boolean gameOver;

        private Outcome(Map<String, Object> seatDecision, List<String> semantics, Map<String, Object> terminal,
                        boolean gameOver) {
            this.seatDecision = seatDecision;
            this.semantics = semantics;
            this.terminal = terminal;
            this.gameOver = gameOver;
        }
    }

    static final List<String> SEATS = ObservationBuilder.SEATS;

    private final Game game;
    private final ObservationBuilder builder;
    private final PriorityHolder holder = new PriorityHolder();
    private final long maxSteps;
    private final long maxDecisions;
    final Stats stats = new Stats();
    final Set<String> cardNameDomain;
    final boolean mulliganNone;
    final boolean priorityMana;

    private final BlockingQueue<Outcome> outcomes = new ArrayBlockingQueue<>(2);
    private final BlockingQueue<Integer> answers = new ArrayBlockingQueue<>(2);
    private volatile boolean closed;
    private Thread gameThread;

    /** The last combat declaration as posed (attacker -> defender, or blocker -> attacker), until checked. */
    Map<UUID, UUID> declaredAttack;
    Set<String> declaredBlock;

    // Section 8 counters, as the live validator keeps them (host.tracking.GroupTracker)
    private long step;
    private final long[] seatStep = new long[2];
    private final long[] nextGroup = new long[2];
    private final long[][] partial = new long[2][]; // {group_id, substep_index, substep_count} or null
    private final List<Boolean> counted = new ArrayList<>();
    private final Integer[] action = new Integer[2]; // completion index of the seat's last non-pass priority action

    public Exchange(Game game, ObservationBuilder builder, long maxSteps, long maxDecisions,
                    List<String> cardNameDomain, boolean mulliganNone) {
        this(game, builder, maxSteps, maxDecisions, cardNameDomain, mulliganNone, false);
    }

    public Exchange(Game game, ObservationBuilder builder, long maxSteps, long maxDecisions,
                    List<String> cardNameDomain, boolean mulliganNone, boolean priorityMana) {
        this.game = game;
        this.builder = builder;
        this.maxSteps = maxSteps;
        this.maxDecisions = maxDecisions;
        this.cardNameDomain = Collections.unmodifiableSet(new LinkedHashSet<>(cardNameDomain));
        this.mulliganNone = mulliganNone;
        this.priorityMana = priorityMana;
    }

    /**
     * Test hook for the X4h hash-order audit ({@code -Dspellbench.hashWarmup=N}): draws N identity hash codes on the
     * calling thread, which shifts every identity hash the thread assigns afterwards (HotSpot's default generator is
     * per thread). Game results must not change. A no-op unless the property is set.
     */
    public static void hashWarmup() {
        int n = Integer.getInteger("spellbench.hashWarmup", 0);
        int sink = 0;
        for (int i = 0; i < n; i++) {
            sink ^= System.identityHashCode(new Object());
        }
        if (n > 0 && sink == 42) {
            System.err.println("xmage-spellbench: hash warm-up " + n);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // protocol thread

    /** Starts the game thread running {@code body} (the XMage game) and waits for the first outcome. */
    public Outcome start(Runnable body) {
        gameThread = new Thread(() -> {
            try {
                hashWarmup();
                body.run();
                publish(new Outcome(null, null, null, true));
            } catch (Closed e) {
                // the terminal was published before the throw
            } catch (Throwable t) {
                if (!closed) {
                    System.err.println("xmage-spellbench: game thread failed");
                    t.printStackTrace(System.err);
                    publish(terminal("halted", "halted", null, "engine_contract_failure:engine_error"));
                }
            }
        }, mage.util.ThreadUtils.THREAD_PREFIX_GAME + " spellbench");
        gameThread.setDaemon(true);
        gameThread.start();
        return await();
    }

    /** Hands the chosen candidate to the parked game thread and waits for the next outcome. */
    public Outcome answer(int candidateIndex) {
        if (!answers.offer(candidateIndex)) {
            throw new IllegalStateException("the game thread holds an unconsumed answer");
        }
        return await();
    }

    /** Ends the session: the game thread unwinds with {@link Closed} and is joined. */
    public void close() {
        closed = true;
        answers.offer(-1);
        if (gameThread != null && gameThread != Thread.currentThread()) {
            gameThread.interrupt();
            try {
                gameThread.join(TimeUnit.SECONDS.toMillis(10));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (gameThread.isAlive()) {
                System.err.println("xmage-spellbench: game thread still alive after close");
            }
        }
    }

    public long step() {
        return step;
    }

    /** Completed groups that count (Section 8: a rewind abandons groups). */
    public long decisionCount() {
        long n = 0;
        for (Boolean b : counted) {
            if (b) {
                n++;
            }
        }
        return n;
    }

    public Stats stats() {
        return stats;
    }

    private Outcome await() {
        try {
            Outcome o = outcomes.poll(600, TimeUnit.SECONDS);
            if (o == null) {
                closed = true;
                return terminal("halted", "halted", null, "engine_contract_failure:engine_timeout");
            }
            return o;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return terminal("halted", "halted", null, "engine_contract_failure:interrupted");
        }
    }

    private void publish(Outcome o) {
        if (!outcomes.offer(o)) {
            System.err.println("xmage-spellbench: outcome queue full");
        }
    }

    static Outcome terminal(String outcome, String classification, String winner, String reason) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("outcome", outcome);
        t.put("classification", classification);
        t.put("winner", winner);
        t.put("reason", reason);
        return new Outcome(null, null, t, false);
    }

    // ---------------------------------------------------------------------------------------------
    // game thread

    /** Ends the game halted with {@code engine_contract_failure:<cause>} and unwinds the game thread. */
    Closed halt(String cause) {
        if (!closed) {
            stats.add("halt:" + cause);
            closed = true;
            publish(terminal("halted", "halted", null, "engine_contract_failure:" + cause));
        }
        throw new Closed();
    }

    boolean isClosed() {
        return closed;
    }

    /**
     * Poses one decision and returns the index (into {@code pose.cands}) of the chosen candidate. Never returns
     * when the game ends here (a cap, a halt) or the session closes.
     */
    int ask(Pose pose) {
        if (closed) {
            throw new Closed();
        }
        int s = SEATS.indexOf(pose.seat);
        boolean groupStart = pose.substepIndex == 0;
        if (step >= maxSteps || (groupStart && decisionCount() >= maxDecisions)) {
            closed = true;
            stats.add("truncated");
            publish(terminal("truncated", "truncated", null, step >= maxSteps ? "max_steps" : "max_decisions"));
            throw new Closed();
        }
        if (pose.cands.isEmpty()) {
            throw halt("empty_decision:" + pose.tag);
        }
        Observation obs;
        try {
            obs = builder.build(pose.seat, holder.holder(pose.seat, pose.priority), looks(pose));
        } catch (ObservationException e) {
            System.err.println("xmage-spellbench: " + e.getMessage());
            throw halt(e.cause);
        }
        Built built;
        try {
            built = build(pose, obs);
        } catch (Unrepresentable e) {
            System.err.println("xmage-spellbench: unrepresentable " + pose.tag + ": " + e.getMessage());
            throw halt(e.cause);
        }
        if (built.semantics.size() > 4096) {
            throw halt("candidate_limit");
        }

        // Section 8 group bookkeeping (validator V3): a rewind starts the group after a partial one, then abandons
        long groupId;
        if (groupStart) {
            groupId = partial[s] != null ? partial[s][0] + 1 : nextGroup[s];
            if (partial[s] != null && !pose.rewind) {
                throw halt("group_interrupted");
            }
            if (pose.rewind) {
                abandon(s);
            }
        } else {
            if (partial[s] == null || partial[s][1] + 1 != pose.substepIndex || partial[s][2] != pose.substepCount) {
                throw halt("group_out_of_order");
            }
            groupId = partial[s][0];
        }
        int other = 1 - s;
        if (partial[other] != null) {
            throw halt("group_interleaved");
        }

        Map<String, Object> group = new LinkedHashMap<>();
        group.put("group_id", groupId);
        group.put("substep_index", (long) pose.substepIndex);
        group.put("substep_count", (long) pose.substepCount);
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("kind", pose.priority ? "priority" : "choice");
        context.put("source", built.contextSource);
        context.put("purpose", built.contextPurpose);
        context.put("text", null);
        context.put("rewind", pose.rewind);
        List<Object> candidates = new ArrayList<>();
        for (int i = 0; i < built.semantics.size(); i++) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("candidate_id", (long) i);
            c.put("semantic", built.semantics.get(i));
            c.put("display_text", Display.of(built.semantics.get(i)));
            candidates.add(c);
        }
        Map<String, Object> sd = new LinkedHashMap<>();
        sd.put("acting_seat", pose.seat);
        sd.put("seat_step", seatStep[s]);
        sd.put("group", group);
        sd.put("context", context);
        sd.put("observation", obs.json());
        sd.put("candidates", candidates);
        sd.put("extensions", new LinkedHashMap<String, Object>());
        stats.add("posed:" + pose.tag);
        if (built.semantics.size() == 1) {
            stats.add("single_candidate");
        }
        publish(new Outcome(sd, built.canonical, null, false));

        int chosen;
        try {
            Integer a = answers.take();
            chosen = a;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Closed();
        }
        if (closed || chosen < 0) {
            throw new Closed();
        }

        // the answer: counters as the validator records them
        String chosenKind = (String) built.semantics.get(chosen).get("kind");
        step++;
        seatStep[s]++;
        if (pose.priority) {
            action[s] = "pass".equals(chosenKind) ? null : counted.size();
        }
        holder.answered(pose.seat, pose.priority, "pass".equals(chosenKind));
        if (pose.substepIndex + 1 == pose.substepCount) {
            partial[s] = null;
            nextGroup[s] = groupId + 1;
            counted.add(Boolean.TRUE);
        } else {
            partial[s] = new long[]{groupId, pose.substepIndex, pose.substepCount};
        }
        return built.order.get(chosen);
    }

    /** Section 8 rewind: the seat's last priority action's group and every group completed since stop counting. */
    private void abandon(int s) {
        Integer start = action[s];
        if (start == null) {
            throw halt("rewind_without_action");
        }
        for (int i = start; i < counted.size(); i++) {
            counted.set(i, Boolean.FALSE);
        }
        partial[s] = null;
        action[s] = null;
        int other = 1 - s;
        if (action[other] != null && action[other] >= start) {
            action[other] = null;
        }
        stats.add("rewind");
    }

    /** True when the seat's last priority answer was an action, so a rewind is allowed (validator V3). */
    boolean canRewind(String seat) {
        return action[SEATS.indexOf(seat)] != null;
    }

    // ---------------------------------------------------------------------------------------------
    // building a decision

    private static final class Built {
        final List<Map<String, Object>> semantics = new ArrayList<>();
        final List<String> canonical = new ArrayList<>();
        final List<Integer> order = new ArrayList<>(); // wire index -> pose candidate index
        Map<String, Object> contextSource;
        String contextPurpose;
    }

    private Built build(Pose pose, Observation obs) throws Unrepresentable {
        Map<String, Integer> position = positions(obs.json());
        List<Object[]> rows = new ArrayList<>(); // {semantic, canonical, pose index, sort key}
        Set<String> seen = new LinkedHashSet<>();
        for (int i = 0; i < pose.cands.size(); i++) {
            Pose.Cand c = pose.cands.get(i);
            Map<String, Object> semantic = c.fn.build(obs);
            if (semantic == null) {
                continue; // a candidate the observation cannot show (dropped deliberately)
            }
            String canonical = StrictJson.canonicalString(semantic);
            if (!seen.add(canonical)) {
                stats.add("duplicate_candidate:" + pose.tag);
                continue;
            }
            int pos = -1;
            if (c.orderObject != null) {
                Map<String, Object> ref = obs.reference(c.orderObject);
                if (ref != null) {
                    Integer p = position.get((String) ref.get("object_id"));
                    pos = p == null ? -1 : p;
                }
            }
            rows.add(new Object[]{semantic, canonical, i, new long[]{c.rank, pos, c.minor}});
        }
        if (rows.isEmpty()) {
            throw new Unrepresentable("empty_decision:" + pose.tag, "no candidate survived");
        }
        if (pose.sort) {
            rows.sort((a, b) -> {
                long[] x = (long[]) a[3];
                long[] y = (long[]) b[3];
                for (int k = 0; k < x.length; k++) {
                    int c = Long.compare(x[k], y[k]);
                    if (c != 0) {
                        return c;
                    }
                }
                // equal keys: the canonical semantic, so XMage's collection order never decides (X4h)
                return ((String) a[1]).compareTo((String) b[1]);
            });
        }
        // Section 7.1: candidates referencing hidden-zone cards, among themselves, in (card_name, object_id) order
        List<Integer> hiddenSlots = new ArrayList<>();
        List<Object[]> hiddenRows = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            List<String[]> key = HiddenOrder.key(castMap(rows.get(i)[0]), pose.seat);
            if (key != null) {
                hiddenSlots.add(i);
                hiddenRows.add(new Object[]{rows.get(i), key});
            }
        }
        if (hiddenRows.size() > 1) {
            hiddenRows.sort((a, b) -> HiddenOrder.compare(castList(a[1]), castList(b[1])));
            for (int k = 0; k < hiddenSlots.size(); k++) {
                rows.set(hiddenSlots.get(k), (Object[]) hiddenRows.get(k)[0]);
            }
        }
        // Section 7.1: pass is candidate 0
        for (int i = 1; i < rows.size(); i++) {
            if ("pass".equals(castMap(rows.get(i)[0]).get("kind"))) {
                rows.add(0, rows.remove(i));
                break;
            }
        }
        Built b = new Built();
        for (Object[] r : rows) {
            b.semantics.add(castMap(r[0]));
            b.canonical.add((String) r[1]);
            b.order.add((Integer) r[2]);
        }
        // context.source and context.purpose repeat what the candidates share (Section 9.3; validator V9)
        if (!pose.priority) {
            String purpose = null;
            boolean purposeShared = true;
            Map<String, Object> source = null;
            boolean sourceShared = true;
            boolean first = true;
            for (Map<String, Object> s : b.semantics) {
                if (s.containsKey("purpose")) {
                    String p = (String) s.get("purpose");
                    if (purpose == null) {
                        purpose = p;
                    } else if (!purpose.equals(p)) {
                        purposeShared = false;
                    }
                }
                Object src = s.containsKey("source") ? s.get("source") : null;
                if (first) {
                    source = castMapOrNull(src);
                    first = false;
                } else if (src == null ? source != null : !src.equals(source)) {
                    sourceShared = false;
                }
            }
            b.contextPurpose = purposeShared ? purpose : null;
            b.contextSource = sourceShared && source != null ? new LinkedHashMap<>(source) : null;
        }
        return b;
    }

    /** The order of every object the observation holds: zone records seat by seat, then the stack, then known. */
    private static Map<String, Integer> positions(Map<String, Object> obs) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Object p : castList(obs.get("players"))) {
            Map<String, Object> player = castMap(p);
            for (String zone : new String[]{"hand", "battlefield", "graveyard", "exile", "command"}) {
                Object records = player.get(zone);
                if (records == null) {
                    continue;
                }
                for (Object r : castList(records)) {
                    out.putIfAbsent((String) castMap(r).get("object_id"), out.size());
                }
            }
        }
        for (Object e : castList(obs.get("stack"))) {
            out.putIfAbsent((String) castMap(e).get("object_id"), out.size());
        }
        for (Object k : castList(obs.get("known"))) {
            Object id = castMap(k).get("object_id");
            if (id != null) {
                out.putIfAbsent((String) id, out.size());
            }
        }
        return out;
    }

    /** The looks a pose needs: its exact looks, then every hidden-zone card a candidate names (Section 6.7). */
    private List<Look> looks(Pose pose) {
        Map<UUID, Look> out = new LinkedHashMap<>();
        for (Look l : pose.looks) {
            out.put(l.cardId, l);
        }
        UUID viewerId = playerId(pose.seat);
        List<List<UUID>> named = new ArrayList<>();
        for (Pose.Cand c : pose.cands) {
            named.add(c.refs);
        }
        named.add(pose.shown);
        for (List<UUID> ids : named) {
            for (UUID id : ids) {
                if (id == null || out.containsKey(id)) {
                    continue;
                }
                Look l = defaultLook(id, viewerId);
                if (l != null) {
                    out.put(id, l);
                }
            }
        }
        return new ArrayList<>(out.values());
    }

    private Look defaultLook(UUID id, UUID viewerId) {
        Card card = game.getCard(id);
        Zone zone = game.getState().getZone(id);
        if (card == null || zone == null) {
            return null;
        }
        if (zone == Zone.LIBRARY) {
            return Look.searching(id);
        }
        if (zone == Zone.HAND && !card.getOwnerId().equals(viewerId)) {
            boolean revealed = false;
            for (Cards cards : game.getState().getRevealed().values()) {
                if (cards.contains(id)) {
                    revealed = true;
                    break;
                }
            }
            return new Look(id, revealed ? "revealed" : "looked_at", null, null);
        }
        return null;
    }

    UUID playerId(String seat) {
        for (UUID id : game.getPlayers().keySet()) {
            if (seat.equals(game.getPlayer(id).getName())) {
                return id;
            }
        }
        throw new IllegalStateException("no player " + seat);
    }

    String seatOf(UUID playerId) {
        if (playerId == null || game.getPlayer(playerId) == null) {
            return null;
        }
        String name = game.getPlayer(playerId).getName();
        return SEATS.contains(name) ? name : null;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> castMap(Object o) {
        return (Map<String, Object>) o;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> castMapOrNull(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : null;
    }

    @SuppressWarnings("unchecked")
    static <T> List<T> castList(Object o) {
        return (List<T>) o;
    }

    /** Per-game counters of mapping paths, fallbacks and halts, for the X4 evidence (never sent to agents). */
    public static final class Stats {
        private final TreeMap<String, Long> counts = new TreeMap<>();

        public void add(String key) {
            counts.merge(key, 1L, Long::sum);
        }

        public Map<String, Long> snapshot() {
            return new TreeMap<>(counts);
        }
    }
}
