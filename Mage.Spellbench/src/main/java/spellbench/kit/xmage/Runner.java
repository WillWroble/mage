package spellbench.kit.xmage;

import mage.abilities.Ability;
import mage.cards.Card;
import mage.constants.Outcome;
import mage.game.Game;
import mage.game.combat.CombatGroup;
import mage.game.stack.StackObject;
import mage.player.cabt.CardResolver;
import mage.player.spellbench.Warmup;
import mage.players.Player;
import spellbench.kit.core.Json;
import spellbench.kit.core.Offers;
import spellbench.kit.core.ObsIndex;
import spellbench.kit.core.Sampler;
import spellbench.kit.core.Seeds;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The runner process (design Section 2.1): one JVM with XMage, child of the front, building worlds and running bots
 * one world at a time, over a private pipe (its stdin and the stdout it had at start; XMage's own prints go to
 * stderr). It never speaks the protocol. A search that does not return within the safety deadline is interrupted by
 * the watchdog; one that ignores the interrupt is ended by the front killing this process.
 */
public final class Runner {

    private final PrintStream out;
    private Map<String, Object> gameStart;
    private byte[] idSeed;
    private final Thread worldThread = Thread.currentThread();

    private Runner(PrintStream out) {
        this.out = out;
    }

    public static void main(String[] args) throws Exception {
        PrintStream protocol = new PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out), true, "UTF-8");
        System.setOut(System.err); // XMage and log4j print; only this class writes the pipe
        quietLogs();
        Runner r = new Runner(protocol);
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while ((line = in.readLine()) != null) {
            if (line.trim().isEmpty()) {
                continue;
            }
            Map<String, Object> req;
            try {
                req = Json.parseObject(line);
            } catch (RuntimeException e) {
                r.reply(Json.map("ok", false, "error", "malformed request: " + e.getMessage()));
                continue;
            }
            Object seq = req.get("seq");
            try {
                Map<String, Object> res = r.handle(req);
                res.put("seq", seq);
                r.reply(res);
            } catch (Throwable t) {
                System.err.println("kit-runner: request failed");
                t.printStackTrace(System.err);
                r.reply(Json.map("seq", seq, "ok", false, "error", t.toString()));
            }
        }
    }

    static void quietLogs() {
        try {
            org.apache.log4j.Logger.getRootLogger().setLevel(org.apache.log4j.Level.ERROR);
        } catch (Throwable ignored) {
            // no log4j: nothing to quiet
        }
    }

    private synchronized void reply(Map<String, Object> res) {
        out.println(Json.canonical(res));
        out.flush();
    }

    private Map<String, Object> handle(Map<String, Object> req) throws Exception {
        String op = Json.str(req, "op");
        switch (op) {
            case "boot":
                return boot();
            case "game":
                return game(req);
            case "decide":
                return decide(req);
            case "ping":
                return Json.map("ok", true);
            default:
                return Json.map("ok", false, "error", "unknown op " + op);
        }
    }

    // ---------------------------------------------------------------------------------------------

    private Map<String, Object> boot() throws Exception {
        long t0 = System.nanoTime();
        KitRandom.installBoot();
        int[] warm = Warmup.framework();
        // the card database (one copy per process, in the working directory) and the scan
        new CardResolver().resolve("Plains");
        long ms = (System.nanoTime() - t0) / 1_000_000;
        Map<String, Object> res = Json.map("ok", true, "boot_ms", ms, "warmup_ok", warm[0], "warmup_failed", warm[1]);
        try (java.io.InputStream in = Runner.class.getResourceAsStream("/mage/player/spellbench/engine-identity.properties")) {
            if (in != null) {
                java.util.Properties p = new java.util.Properties();
                p.load(in);
                res.put("rules_snapshot_id", p.getProperty("rules_snapshot_id"));
                res.put("card_pool_identity", p.getProperty("card_pool_identity"));
            }
        }
        return res;
    }

    /** game_start: warm every decklist card class under the boot source (class initializers mint ids). */
    private Map<String, Object> game(Map<String, Object> req) {
        long t0 = System.nanoTime();
        gameStart = Json.obj(req, "game_start");
        idSeed = Seeds.unhex(Json.str(req, "id_seed"));
        KitRandom.installBoot();
        CardResolver resolver = new CardResolver();
        List<String> names = new ArrayList<>();
        for (String deck : new String[]{"own_deck", "opponent_deck"}) {
            Map<String, Object> d = Json.obj(gameStart, deck);
            if (d == null) {
                continue;
            }
            for (Object r : Json.arr(d, "decklist")) {
                names.add(Json.str(Json.obj(r), "name"));
            }
        }
        if (gameStart.get("opponent_deck") == null) {
            Map<String, Object> rules = Json.obj(gameStart, "rules");
            if (rules != null && rules.get("card_name_domain") != null) {
                for (Object n : Json.arr(Json.obj(rules, "card_name_domain"), "names")) {
                    names.add((String) n);
                }
            }
        }
        List<String> failed = new ArrayList<>();
        for (String n : names) {
            try {
                Card c = resolver.resolve(n).createCard(UUID.nameUUIDFromBytes(new byte[]{0}));
                c.copy();
            } catch (RuntimeException e) {
                failed.add(n);
            }
        }
        return Json.map("ok", true, "warm_ms", (System.nanoTime() - t0) / 1_000_000, "unresolved", failed);
    }

    // ---------------------------------------------------------------------------------------------
    // decisions

    private Map<String, Object> decide(Map<String, Object> req) {
        String path = Json.str(req, "path");
        Map<String, Object> decision = Json.obj(req, "decision");
        Map<String, Object> budgets = Json.obj(req, "budgets");
        if (budgets != null) {
            KitContext.nodeBudget = (int) Json.num(budgets, "nodes", KitContext.nodeBudget);
            KitContext.optionBudget = (int) Json.num(budgets, "options", KitContext.optionBudget);
            KitContext.opCap = (int) Json.num(budgets, "operations", KitContext.opCap);
            KitContext.mctsIterations = (int) Json.num(budgets, "iterations", KitContext.mctsIterations);
            KitContext.rolloutCap = (int) Json.num(budgets, "rollout", KitContext.rolloutCap);
            KitContext.combatOptionBudget = (int) Json.num(budgets, "combat_options", KitContext.combatOptionBudget);
        }
        long deadline = Json.num(req, "deadline_ms", 0);
        KitContext.hangHook = Json.bool(req, "hang");
        Watchdog dog = deadline > 0 ? new Watchdog(worldThread, deadline) : null;
        long t0 = System.nanoTime();
        List<Object> worlds = new ArrayList<>();
        boolean interrupted = false;
        try {
            List<Object> seeds = Json.arr(req, "world_seeds");
            for (int k = 0; k < seeds.size(); k++) {
                if (Thread.currentThread().isInterrupted()) {
                    interrupted = true;
                    break;
                }
                if ("continuation".equals(path)) {
                    Map<String, Object> bot = Json.obj(req, "bot");
                    Map<String, Object> cw = Continuation.run(gameStart, idSeed, Json.obj(req, "anchor"),
                            Json.arr(req, "earlier"), decision, bot == null ? 6 : (int) Json.num(bot, "skill", 6));
                    cw.put("index", 0L);
                    worlds.add(cw);
                    break;
                }
                worlds.add(oneWorld(path, req, decision, Seeds.unhex((String) seeds.get(k)), k));
            }
        } finally {
            if (dog != null) {
                dog.cancel();
            }
            if (Thread.interrupted()) {
                interrupted = true;
            }
            KitRandom.installBoot();
        }
        return Json.map("ok", true, "worlds", worlds, "ms", (System.nanoTime() - t0) / 1_000_000,
                "interrupted", interrupted || (dog != null && dog.fired));
    }

    /** Interrupts the world thread at the safety deadline (design 5.4 item 2d). */
    static final class Watchdog {
        final Thread target;
        volatile boolean cancelled;
        volatile boolean fired;
        final Thread thread;

        Watchdog(Thread target, long ms) {
            this.target = target;
            this.thread = new Thread(() -> {
                try {
                    Thread.sleep(ms);
                } catch (InterruptedException e) {
                    return;
                }
                if (!cancelled) {
                    fired = true;
                    target.interrupt();
                }
            }, "kit-watchdog");
            thread.setDaemon(true);
            thread.start();
        }

        void cancel() {
            cancelled = true;
            thread.interrupt();
        }
    }

    /** Flags whose world is not searched (E4 outcome; the register, A1 result review change 3). */
    static String skipReason(World w, String path) {
        for (String f : w.flags) {
            if (f.startsWith("unsupported:") && !"dialog".equals(path) && !"roundtrip".equals(path)
                    && !"mulligan_bottom".equals(path)) {
                return "unsupported_state:" + f.substring("unsupported:".length());
            }
            if ("priority".equals(path) && (f.equals("horizon:stack_object") || f.equals("horizon:pending_triggers"))) {
                return "approximate_state_without_search:" + f;
            }
        }
        return null;
    }

    private Map<String, Object> oneWorld(String path, Map<String, Object> req, Map<String, Object> decision,
                                         byte[] worldSeed, int k) {
        KitContext.reset();
        Map<String, Object> obs = Json.obj(Json.copy(decision.get("observation")));
        KitRandom.installBoot();
        List<String> nameFlags = WorldBuilder.restoreVisibleNames(obs, Json.obj(decision, "x_history"));
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("index", (long) k);
        long t0 = System.nanoTime();
        KitRandom random = KitRandom.install(worldSeed, idSeed);
        Sampler.Sample sample = Sampler.sample(gameStart, obs, random.stream("sampler"));
        WorldBuilder.Spec spec = new WorldBuilder.Spec();
        spec.gameStart = gameStart;
        spec.observation = obs;
        spec.sample = sample;
        spec.random = random;
        spec.index = k;
        spec.combatDamageStep = Json.str(req, "combat_damage_step");
        spec.history = Json.obj(decision, "x_history");
        Map<String, Object> bot = Json.obj(req, "bot");
        int skill = bot == null ? 6 : (int) Json.num(bot, "skill", 6);
        final KitPolicy[] decider = new KitPolicy[1];
        final boolean forcing = req.get("force_semantic") != null;
        spec.viewerFactory = seat -> {
            decider[0] = new KitPolicy(seat, skill);
            return decider[0];
        };
        spec.otherFactory = Puppet::new;
        switch (path) {
            case "attack":
                spec.mode = WorldBuilder.Mode.ATTACK;
                break;
            case "block":
                spec.mode = WorldBuilder.Mode.BLOCK;
                break;
            case "mulligan":
            case "mulligan_bottom":
                spec.mode = WorldBuilder.Mode.PREGAME;
                break;
            case "priority":
                spec.mode = WorldBuilder.Mode.PRIORITY;
                break;
            default:
                spec.mode = WorldBuilder.Mode.SNAPSHOT;
        }
        World w = WorldBuilder.build(spec);
        w.flags.addAll(nameFlags);
        if (decider[0] != null) {
            decider[0].attach(w);
        }
        long built = System.nanoTime();
        res.put("build_ms", (built - t0) / 1_000_000);
        res.put("flags", new ArrayList<Object>(w.flags));
        res.put("sample", w.sample);
        ObsIndex index = new ObsIndex(obs);
        KitContext.candidates = Json.arr(decision, "candidates");
        Game game = w.game;
        KitPolicy bot0 = decider[0];
        String skip = forcing || Json.bool(req, "search_flagged") ? null : skipReason(w, path);
        if (skip != null) {
            // the E4 outcome and the register: this world is not searched; the front declines (wrapper)
            res.put("skipped", skip);
            res.put("search_ms", 0L);
            res.put("counters", KitContext.counters());
            return res;
        }
        if ("priority".equals(path) && !forcing) {
            KitContext.rootFilter = offeredFilter(w, decision, index);
        }
        switch (path) {
            case "priority": {
                KitPolicy.PriorityOutcome o;
                if (forcing) {
                    o = forced(w, bot0, Json.obj(req, "force_semantic"), index);
                } else {
                    o = bot0.decidePriority(w, index, true);
                }
                res.put("pass", o.pass);
                res.put("reason", o.reason);
                res.put("semantic", o.semantic);
                res.put("option_payload", o.optionPayload);
                res.put("executed_payload", o.executedPayload);
                res.put("activated", o.activated);
                if (o.chosen != null) {
                    // mapping diagnostics (review change 5): what MAD chose, and why it has no v2 form if it has none
                    res.put("chosen", Mapping.describe(w, game, o.chosen));
                    String why = Mapping.failure(w, game, o.chosen, index);
                    if (why != null || o.semantic == null) {
                        res.put("mapping_failure", why == null ? "no_semantic" : why);
                    }
                }
                List<Object> answers = new ArrayList<>();
                List<Object> families = new ArrayList<>();
                for (KitPolicy.Answer a : o.answers) {
                    answers.add(Json.map("family", a.family, "value", a.value));
                    families.add(a.family);
                }
                res.put("answers", answers);
                if (o.nonStack && !o.answers.isEmpty()) {
                    res.put("non_stack_dialogs", (long) o.answers.size());
                    res.put("non_stack_families", families);
                }
                List<Object> stats = new ArrayList<>();
                for (int i = 0; i < o.stats.size(); i++) {
                    RootStat rs = o.stats.get(i);
                    stats.add(Json.map("index", (long) rs.index, "semantic", o.statSemantics.get(i),
                            "payload", o.statPayloads.get(i), "raw", rs.raw, "adjusted", rs.adjusted,
                            "alpha_before", rs.alphaBefore == null || rs.alphaBefore == Integer.MIN_VALUE ? null : rs.alphaBefore,
                            "beta", rs.beta == null || rs.beta == Integer.MAX_VALUE ? null : rs.beta,
                            "bound", rs.bound, "tie", rs.tie, "reason", rs.reason, "best", rs.best,
                            "horizon_hits", rs.horizonHits));
                }
                res.put("root_stats", stats);
                res.put("nodes", (long) mage.player.ai.KitNodes.count());
                break;
            }
            case "attack": {
                bot0.selectAttackers(game, w.player(w.viewer));
                res.put("pairs", combatPairs(w, game, true));
                break;
            }
            case "block": {
                bot0.selectBlockers(null, game, w.player(w.viewer));
                res.put("pairs", combatPairs(w, game, false));
                break;
            }
            case "mulligan": {
                res.put("keep", !bot0.chooseMulligan(game));
                break;
            }
            case "mulligan_bottom":
            case "dialog": {
                res.put("picks", Dialogs.answer(w, bot0, decision, index));
                break;
            }
            case "roundtrip": {
                res.put("diff", RoundTrip.diff(w, decision));
                break;
            }
            default:
                res.put("error", "unknown path " + path);
        }
        res.put("search_ms", (System.nanoTime() - built) / 1_000_000);
        res.put("counters", KitContext.counters());
        return res;
    }

    /** The root filter of a decision: an action is a root alternative only when its v2 semantic is offered. */
    static java.util.function.BiPredicate<mage.abilities.Ability, Game> offeredFilter(World w, Map<String, Object> decision,
                                                                                 ObsIndex index) {
        final java.util.Set<String> offered = new java.util.HashSet<>();
        final List<Object> candidates = Json.arr(decision, "candidates");
        for (Object c : candidates) {
            offered.add(Json.canonical(Json.obj(Json.obj(c), "semantic")));
        }
        return (a, g) -> {
            Map<String, Object> sem = Mapping.prioritySemantic(w, g, a, index);
            // a method-null cast_spell (Section 7.4) admits every method of that card
            return sem != null && (offered.contains(Json.canonical(sem)) || Offers.candidateFor(candidates, sem) >= 0);
        };
    }

    static List<Object> combatPairs(World w, Game game, boolean attack) {
        List<Object> pairs = new ArrayList<>();
        for (CombatGroup g : game.getCombat().getGroups()) {
            if (attack) {
                for (UUID a : g.getAttackers()) {
                    pairs.add(Json.map("attacker", w.uuidToId.get(a), "defender", Mapping.targetRef(w, g.getDefenderId())));
                }
            } else {
                for (UUID b : g.getBlockers()) {
                    for (UUID a : g.getAttackers()) {
                        pairs.add(Json.map("blocker", w.uuidToId.get(b), "attacker", w.uuidToId.get(a)));
                    }
                }
            }
        }
        return pairs;
    }

    /**
     * Fixture hook (production-path cases): executes the playable action with this v2 semantic on the world, as the
     * priority path executes MAD's pick (live dialogs recorded, executed payload read from the executed object).
     */
    static KitPolicy.PriorityOutcome forced(World w, KitPolicy bot, Map<String, Object> semantic, ObsIndex index) {
        KitPolicy.PriorityOutcome o = new KitPolicy.PriorityOutcome();
        w.game.getState().setPriorityPlayerId(w.player(w.viewer));
        mage.abilities.ActivatedAbility a = Mapping.findPlayable(w, w.viewerPlayer(), semantic, index);
        if (a == null) {
            o.pass = true;
            o.reason = "forced_action_not_playable";
            o.semantic = Json.map("kind", "pass");
            return o;
        }
        o.chosen = a;
        o.semantic = Mapping.prioritySemantic(w, w.game, a, index);
        o.optionPayload = Mapping.payload(w, a, w.game);
        bot.execute(w, a, o);
        return o;
    }
}
