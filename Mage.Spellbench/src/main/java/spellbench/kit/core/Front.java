package spellbench.kit.core;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * The kit's front process (design Section 2.1): speaks the protocol v2 agent role over stdio (Section 10), keeps the
 * permitted-input history, plans, saved anchors and the fallback, and never runs a search: every world is built and
 * searched by the runner, a child JVM with XMage. Its stdout carries only protocol lines.
 * <p>
 * Decision paths (Section 5.1, 6.2): the action's plan; single-candidate fast path; MAD's passing steps (for the MAD
 * entries: ComputerPlayer7 passes without thinking outside the main phases and the declare steps, so the kit answers
 * pass there without a world, which is the bot's own answer); priority anchor (K worlds, vote or visits); combat
 * anchors; mulligan; a later decision of a planned logical dialog from its plan; saved-anchor continuation, else
 * current dialog; fallback. Every answer is tagged {@code bot}, {@code wrapper} or {@code cap} (Section 6.5) in the
 * evidence log.
 * <p>
 * Clock (A1 result review, change 2): a {@code choose} gets one absolute answer time, {@code min(max_decision_ms,
 * remaining_ms)} after it arrived minus the response overhead. Every step of the answer (waiting for a replacement
 * runner, the priority search, continuation, the current-dialog search after a failed continuation, diagnostics and
 * the kill of a runner that does not reply) shares it; none gets a fresh budget.
 */
public final class Front {

    static final String PROTOCOL = "spellbench/v2";
    static final List<String> CP7_PASS_STEPS = Arrays.asList("untap", "upkeep", "draw", "beginning_of_combat",
            "combat_damage", "end_of_combat", "end_step", "cleanup");

    // configuration
    final String entry;
    final Map<String, Object> config;
    final int worlds;
    final int skill;
    final String botKind;
    final String botName;
    final String botVersion;
    final long graceMs;
    final long overheadMs;
    /** Reserved at the end of every clock for a kill and the fallback answer. */
    final long killReserveMs;
    /** Clock pacing (0: off): a decision gets at most remaining/paceMoves + increment, never under paceFloorMs. */
    final long paceMoves;
    final long paceFloorMs;
    final Map<String, Object> budgets = new LinkedHashMap<>();
    final long hangAt;
    final boolean roundtrip;
    boolean hangUsed;
    /** Evidence option: priority-anchor decisions and game_start are written here (E7 positions). */
    final String dumpDir;
    final RunnerLink runner;
    PrintStream log;
    /** Evidence option: one log file per game in this directory, named by the game id (soak isolation). */
    final String logDir;
    /** Fixture option: decision lines are kept in memory too. */
    public final List<Map<String, Object>> keptLines = new ArrayList<>();
    boolean keepLines;

    // game state (permitted inputs only)
    Map<String, Object> gameStart;
    byte[] gameKey;
    String gameId;
    final PlanBook plans = new PlanBook();
    /**
     * Saved anchors by stack object id (Section 5.1): the inputs (decision and world seed) of the viewer's last
     * priority decision with that object on top. Kept for every such decision, searched or not, since they are inputs
     * only (a world is built from them at the continuation).
     */
    final Map<String, Map<String, Object>> anchors = new LinkedHashMap<>();
    /**
     * This seat's logical dialogs with a choice source (Section 5.1 step 3), completed, in order, by source object id:
     * {family, group_id, seat_step, picks (the chosen semantics), known (the first decision's known entries)}.
     */
    final Map<String, List<Map<String, Object>>> dialogs = new LinkedHashMap<>();
    /** The logical dialog in progress, or null. */
    Map<String, Object> openDialog;
    /** The plan of the logical dialog in progress: {picks, cursor, path}; null when none was planned. */
    Map<String, Object> dialogPlan;
    /** Combat plan: the winning assignment, the plan of every substep of its group. */
    Map<String, Object> groupPlan;
    long groupPlanGroupId = -1;
    /** Own history (Section 3.3): the last combat_damage observation of this turn. */
    long lastCombatDamageTurn = -1;
    int combatDamageSeen;
    final Map<String, Long> stats = new LinkedHashMap<>();
    /**
     * Own history (Section 3.3): this seat's loyalty activations, each confirmed once a later observation shows the
     * loyalty cost paid. Sent to the runner as {@code x_history.loyalty_used} for the current turn.
     */
    final List<Map<String, Object>> ownActivations = new ArrayList<>();
    final Map<String, String> cardOrigins = new LinkedHashMap<>();
    /** Fixture hook: the next priority decision at this seat step executes this semantic instead of searching. */
    Map<String, Object> forced;
    long forcedAt = -1;

    public Front(Map<String, String> opts, List<String> runnerCmd) throws IOException {
        entry = opts.getOrDefault("entry", "h1");
        config = Entries.configure(entry, opts);
        worlds = (int) Json.num(config, "worlds", 1);
        skill = (int) Json.num(config, "skill", 6);
        botKind = Json.str(config, "bot");
        if (opts.containsKey("name") || opts.containsKey("version")) {
            // the identity is always the configuration's (second review, item 4): no override can advertise it
            throw new IllegalArgumentException("--name and --version are not accepted: the identity follows the configuration");
        }
        botName = Json.str(config, "name");
        botVersion = Entries.version(config);
        Map<String, Object> clockPolicy = Json.obj(config, "clock");
        graceMs = Json.num(clockPolicy, "grace_ms", 5000);
        overheadMs = Json.num(clockPolicy, "overhead_ms", 1500);
        killReserveMs = Json.num(clockPolicy, "kill_reserve_ms", 300);
        paceMoves = Json.num(clockPolicy, "pace_moves", 0);
        paceFloorMs = Json.num(clockPolicy, "pace_floor_ms", 0);
        Map<String, Object> diag = Json.obj(config, "diagnostics");
        hangAt = Json.num(diag, "hang_at", -1);
        roundtrip = Json.bool(diag, "roundtrip");
        keepLines = "1".equals(opts.get("keep-lines"));
        dumpDir = opts.get("dump");
        budgets.putAll(Json.obj(config, "budgets"));
        File work = new File(opts.getOrDefault("work", "."));
        File err = new File(opts.getOrDefault("runner-log", new File(work, "runner-stderr.log").getPath()));
        runner = new RunnerLink(runnerCmd, work, err);
        String logPath = opts.get("log");
        log = logPath == null ? null : new PrintStream(new FileOutputStream(logPath, true), true, "UTF-8");
        logDir = opts.get("log-dir");
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> opts = new LinkedHashMap<>();
        List<String> runnerCmd = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--")) {
                runnerCmd.addAll(Arrays.asList(args).subList(i + 1, args.length));
                break;
            }
            if (args[i].startsWith("--") && i + 1 < args.length) {
                opts.put(args[i].substring(2), args[++i]);
            }
        }
        PrintStream protocol = new PrintStream(new FileOutputStream(java.io.FileDescriptor.out), true, "UTF-8");
        System.setOut(System.err);
        Front f = new Front(opts, runnerCmd);
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        try {
            String line;
            while ((line = in.readLine()) != null) {
                if (line.trim().isEmpty()) {
                    continue;
                }
                Map<String, Object> resp = f.handle(line);
                protocol.println(Json.canonical(resp));
                protocol.flush();
                f.afterAnswer();
            }
        } finally {
            boolean exited = f.runner.close();
            if ("1".equals(opts.get("cleanup-work")) && exited) {
                try {
                    OwnedWork.remove(new File(opts.get("work")).toPath(), new File(opts.get("work-root")).toPath());
                } catch (IOException e) {
                    System.err.println("kit-front: owned work cleanup failed: " + e);
                }
            }
        }
    }

    /**
     * After an answer is out: a killed runner is replaced in the background, so decisions that need no world are
     * served meanwhile, and one that does waits for it within its own clock.
     */
    public void afterAnswer() {
        if (gameId != null && !runner.alive() && runner.kills > 0 && !runner.restarting()) {
            runner.startAsync();
            logLine(Json.map("event", "runner_restart_started", "stale_locks_removed", runner.staleLocksRemoved,
                    "kill_to_exit_ms", new ArrayList<Object>(runner.killToExitMs)));
        }
    }

    public Map<String, Object> handle(String line) {
        Map<String, Object> req;
        try {
            req = Json.parseObject(line);
        } catch (RuntimeException e) {
            return error("", "malformed_json", e.getMessage());
        }
        Object rid = req.get("request_id");
        String requestId = rid instanceof String ? (String) rid : "";
        if (!PROTOCOL.equals(req.get("protocol"))) {
            return error(requestId, "protocol_mismatch", "protocol is not " + PROTOCOL);
        }
        String type = Json.str(req, "request_type");
        try {
            switch (type == null ? "" : type) {
                case "hello":
                    return Json.map("response_type", "hello_ok", "protocol", PROTOCOL, "request_id", requestId,
                            "bot", Json.map("name", botName, "version", botVersion),
                            "requires", Json.map("observation", Arrays.asList("passed_seats", "keywords"), "extensions", new ArrayList<>()),
                            "extensions_accepted", new ArrayList<>());
                case "game_start":
                    return gameStart(req, requestId);
                case "choose":
                    return choose(req, requestId);
                case "game_over":
                    gameOver(req);
                    return Json.map("response_type", "ack", "protocol", PROTOCOL, "request_id", requestId);
                default:
                    return error(requestId, "malformed_request", "unknown request_type " + type);
            }
        } catch (RuntimeException e) {
            e.printStackTrace(System.err);
            return error(requestId, "internal_error", e.toString());
        }
    }

    static Map<String, Object> error(String requestId, String code, String message) {
        return Json.map("response_type", "error", "protocol", PROTOCOL, "request_id", requestId,
                "error", Json.map("code", code, "message", message));
    }

    private void count(String k) {
        stats.merge(k, 1L, Long::sum);
    }

    // =============================================================================================

    Map<String, Object> gameStart(Map<String, Object> req, String requestId) {
        if (gameId != null) {
            return error(requestId, "game_already_active", "a game is active");
        }
        gameStart = req;
        gameId = Json.str(req, "game_id");
        cardOrigins.clear();
        if (logDir != null) {
            try {
                log = new PrintStream(new FileOutputStream(new File(logDir, gameId + "-" + Json.str(req, "seat") + ".jsonl"), true), true, "UTF-8");
            } catch (IOException e) {
                System.err.println("kit-front: per-game log unavailable: " + e);
            }
        }
        long agentSeed = Json.num(req, "agent_seed", 0);
        gameKey = Seeds.gameKey(agentSeed);
        Map<String, Object> game = Json.map("op", "game", "game_start", stripEnvelope(req),
                "id_seed", Seeds.hex(Seeds.hmac(gameKey, "ids")));
        runner.setGame(game);
        // boot the runner now (inside game_start_ms), so the first decision does not pay for it
        try {
            runner.call(Json.map("op", "ping"), 600_000, 10_000);
        } catch (IOException | RunnerLink.Timeout e) {
            System.err.println("kit-front: runner start failed: " + e);
        }
        logLine(Json.map("event", "game_start", "seat", Json.str(req, "seat"), "boot_ms", runner.lastBootMs,
                "restart_ms", runner.lastRestartMs, "entry", Json.map("name", botName, "version", botVersion,
                        "config", config)));
        return Json.map("response_type", "ack", "protocol", PROTOCOL, "request_id", requestId);
    }

    /**
     * The decision without its engine extensions (Section 14): the kit reads none, and an engine may send large native
     * payloads (mtg-kernel's model inputs reach several MiB) that anchors and runner requests would otherwise copy.
     */
    /**
     * The pass candidate of a priority decision whose every other candidate is an ordinary mana activation, else -1.
     * The kit's searches never root on a mana ability (the root filter admits only mapped non-mana actions), so such
     * a stop is a pass however long they search; an engine that offers priority mana (the mtg-kernel v2 engine does at
     * nearly every stop) would otherwise pay a full search for it.
     */
    static int manaOnlyPass(List<Object> cands) {
        int pass = -1;
        for (int i = 0; i < cands.size(); i++) {
            String kind = Json.str(Json.obj(Json.obj(cands.get(i)), "semantic"), "kind");
            if ("pass".equals(kind) && pass < 0) {
                pass = i;
            } else if (!"activate_mana_ability".equals(kind)) {
                return -1;
            }
        }
        return pass;
    }

    /**
     * A decision's time under clock pacing: the decision limit, cut to an even share of the remaining bank
     * ({@code remaining / paceMoves}) plus the increment it earns back, but never under {@code floorMs} (the least a
     * runner search can use after the overhead, grace and kill reserve). The runner's search is interrupted at the
     * clock (RunnerLink's deadline), so a shorter clock is a shorter search, not a kill.
     */
    static long pacedLimit(long limit, long remaining, long increment, long paceMoves, long floorMs) {
        if (paceMoves <= 0) {
            return limit;
        }
        return Math.min(limit, Math.max(floorMs, remaining / paceMoves + increment));
    }

    static Map<String, Object> withoutExtensions(Map<String, Object> d) {
        if (d == null || !d.containsKey("extensions")) {
            return d;
        }
        Map<String, Object> m = new LinkedHashMap<>(d);
        m.remove("extensions");
        return m;
    }

    static Map<String, Object> stripEnvelope(Map<String, Object> req) {
        Map<String, Object> m = new LinkedHashMap<>(req);
        m.remove("request_id");
        m.remove("request_type");
        m.remove("protocol");
        return m;
    }

    void gameOver(Map<String, Object> req) {
        logLine(Json.map("event", "game_over", "terminal", req.get("terminal"), "stats", stats, "plans", plans.counters,
                "runner", Json.map("restarts", runner.restarts, "kills", runner.kills, "late_discarded", runner.lateDiscarded,
                        "kill_to_exit_ms", new ArrayList<Object>(runner.killToExitMs), "last_restart_ms", runner.lastRestartMs,
                        "exits_confirmed_late", runner.exitsConfirmedLate, "busy_refusals", runner.busyRefusals)));
        plans.close();
        anchors.clear();
        dialogs.clear();
        openDialog = null;
        dialogPlan = null;
        gameStart = null;
        gameId = null;
        runner.close();
        if (logDir != null && log != null) {
            log.close(); // the game's own log file is complete
            log = null;
        }
    }

    // =============================================================================================
    // the clock of one choose (review change 2)

    /** One absolute answer time for every step of a choose. */
    public static final class Clock {
        final long start;
        final long answerBy;
        final long limitMs;

        Clock(long start, long limitMs, long overheadMs) {
            this.start = start;
            this.limitMs = limitMs;
            this.answerBy = start + TimeUnit.MILLISECONDS.toNanos(Math.max(0, limitMs - overheadMs));
        }

        long leftMs() {
            return (answerBy - System.nanoTime()) / 1_000_000;
        }

        long elapsedMs() {
            return (System.nanoTime() - start) / 1_000_000;
        }
    }

    /** True when the clock leaves room for a runner request: the grace period, the kill reserve and some work. */
    boolean roomForRunner(Clock clock) {
        return clock.leftMs() >= graceMs + killReserveMs + RunnerLink.MIN_WORK_MS;
    }

    // =============================================================================================
    // decisions

    /** What one answer was, for the response and the evidence log. */
    static final class Answer {
        int candidate;
        String tag = "bot";
        String path;
        Map<String, Object> detail = new LinkedHashMap<>();

        Answer(int candidate, String tag, String path) {
            this.candidate = candidate;
            this.tag = tag;
            this.path = path;
        }
    }

    Map<String, Object> choose(Map<String, Object> req, String requestId) {
        long t0 = System.nanoTime();
        Map<String, Object> d = withoutExtensions(Json.obj(req, "decision"));
        VisibleNames.observe(Json.obj(d, "observation"), cardOrigins);
        Map<String, Object> clockIn = Json.obj(req, "clock");
        long limit = Math.min(clockIn == null ? 60_000 : Json.num(clockIn, "max_decision_ms", 60_000),
                clockIn == null ? 60_000 : Json.num(clockIn, "remaining_ms", 60_000));
        Map<String, Object> tc = gameStart == null ? null : Json.obj(gameStart, "time_control");
        long paced = pacedLimit(limit, clockIn == null ? limit : Json.num(clockIn, "remaining_ms", limit),
                tc == null ? 0 : Json.num(tc, "increment_ms", 0), paceMoves, paceFloorMs);
        Clock clock = new Clock(t0, paced, overheadMs);
        Answer a;
        try {
            a = decide(d, clock);
        } catch (RuntimeException e) {
            e.printStackTrace(System.err);
            a = fallback(d, null, "wrapper", "error:" + e.getClass().getSimpleName());
        }
        List<Object> cands = Json.arr(d, "candidates");
        if (a.candidate < 0 || a.candidate >= cands.size()) {
            a = fallback(d, null, "wrapper", "out_of_range");
        }
        recordAnswer(d, a);
        long ms = clock.elapsedMs();
        if (roundtrip && cands.size() > 1 && clock.leftMs() >= graceMs + killReserveMs + 1000) {
            roundTrip(d, clock); // diagnostics share the clock: they never delay the answer past it
        }
        count("tag:" + a.tag);
        count("path:" + a.path);
        Map<String, Object> line = Json.map("event", "decision", "seat_step", d.get("seat_step"),
                "kind", firstKind(d), "candidates", (long) cands.size(), "candidate", (long) a.candidate,
                "tag", a.tag, "path", a.path, "ms", ms, "limit_ms", limit, "answer_ms", clock.elapsedMs());
        line.putAll(a.detail);
        logLine(line);
        return Json.map("response_type", "choice", "protocol", PROTOCOL, "request_id", requestId,
                "selection", Json.map("candidate_id", (long) a.candidate),
                "x_kit", Json.map("tag", a.tag, "path", a.path));
    }

    /**
     * Diagnostics (design Section 7.2, never a gate): the world built from this decision, projected back through
     * the engine's observation builder, against the received observation modulo ids. Logged, not used.
     */
    void roundTrip(Map<String, Object> d, Clock clock) {
        Map<String, Object> detail = new LinkedHashMap<>();
        Map<String, Object> r = call(request("roundtrip", d, 1), clock, detail);
        if (r == null || Json.arr(r, "worlds").isEmpty()) {
            logLine(Json.map("event", "roundtrip", "seat_step", d.get("seat_step"), "failed", true, "detail", detail));
            return;
        }
        Map<String, Object> w = Json.obj(Json.arr(r, "worlds").get(0));
        List<Object> diff = Json.arr(w, "diff");
        logLine(Json.map("event", "roundtrip", "seat_step", d.get("seat_step"), "kind", firstKind(d),
                "phase_step", Json.str(Json.obj(d, "observation"), "phase_step"), "exact", diff.isEmpty(),
                "diff", diff.size() > 8 ? diff.subList(0, 8) : diff, "diff_count", (long) diff.size(),
                "flags", w.get("flags")));
    }

    static String firstKind(Map<String, Object> d) {
        List<Object> c = Json.arr(d, "candidates");
        String k = null;
        for (Object o : c) {
            String kind = Json.str(Json.obj(Json.obj(o), "semantic"), "kind");
            if (!kind.startsWith("finish_") && !kind.equals("pass")) {
                return kind;
            }
            if (k == null) {
                k = kind;
            }
        }
        return k;
    }

    /** Loyalty counter of a viewer permanent in this observation, or -1. */
    static long loyalty(Map<String, Object> obs, String objectId) {
        String viewer = Json.str(obs, "viewer");
        for (Object p : Json.arr(obs, "players")) {
            Map<String, Object> pm = Json.obj(p);
            if (!viewer.equals(Json.str(pm, "seat"))) {
                continue;
            }
            for (Object o : Json.arr(pm, "battlefield")) {
                Map<String, Object> r = Json.obj(o);
                if (objectId.equals(r.get("object_id"))) {
                    Map<String, Object> perm = Json.obj(r, "permanent");
                    Map<String, Object> c = perm == null ? null : Json.obj(perm, "counters");
                    return c == null ? -1 : Json.num(c, "loyalty", -1);
                }
            }
        }
        return -1;
    }

    private void confirmActivations(Map<String, Object> obs) {
        long turn = Json.num(obs, "turn", -1);
        for (Map<String, Object> m : ownActivations) {
            if (!Json.bool(m, "confirmed") && Json.num(m, "turn", -2) == turn) {
                long now = loyalty(obs, Json.str(m, "object_id"));
                boolean onStack = false; // a loyalty ability costing 0 changes no counter: its stack entry confirms it
                for (Object o : Json.arr(obs, "stack")) {
                    Map<String, Object> e = Json.obj(o);
                    Map<String, Object> src = Json.obj(e, "source");
                    onStack |= "activated_ability".equals(Json.str(e, "stack_kind")) && src != null
                            && Json.str(m, "object_id").equals(Json.str(src, "object_id"));
                }
                if ((now >= 0 && now != Json.num(m, "loyalty_before", -1)) || onStack) {
                    m.put("confirmed", true);
                }
            }
        }
    }

    /** The confirmed own loyalty activations of the current turn (x_history for the runner). */
    List<Object> loyaltyUsed(Map<String, Object> obs) {
        List<Object> out = new ArrayList<>();
        long turn = Json.num(obs, "turn", -1);
        for (Map<String, Object> m : ownActivations) {
            if (Json.bool(m, "confirmed") && Json.num(m, "turn", -2) == turn) {
                out.add(m.get("object_id"));
            }
        }
        return out;
    }

    private void recordAnswer(Map<String, Object> d, Answer a) {
        Map<String, Object> o = Json.obj(d, "observation");
        Map<String, Object> chosen = Json.obj(Json.obj(Json.arr(d, "candidates").get(a.candidate)), "semantic");
        if ("activate_ability".equals(Json.str(chosen, "kind")) && chosen.get("source") != null) {
            String oid = Json.str(Json.obj(chosen, "source"), "object_id");
            long before = loyalty(o, oid);
            if (before >= 0) {
                ownActivations.add(Json.map("seat_step", d.get("seat_step"), "turn", o.get("turn"),
                        "object_id", oid, "loyalty_before", before, "confirmed", false));
            }
        }
        lastWasEmptyPassAtCombatDamage = "combat_damage".equals(Json.str(o, "phase_step"))
                && Json.arr(o, "stack").isEmpty() && "pass".equals(Json.str(chosen, "kind"));
        Map<String, Object> ctx = Json.obj(d, "context");
        if (ctx == null || !"priority".equals(Json.str(ctx, "kind"))) {
            trackDialog(d, chosen);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // logical dialogs (Section 5.1 step 3, Section 6.3)

    static String contextSource(Map<String, Object> d) {
        Map<String, Object> ctx = Json.obj(d, "context");
        Map<String, Object> src = ctx == null ? null : Json.obj(ctx, "source");
        return src == null ? null : Json.str(src, "object_id");
    }

    /** The family of a choice decision: the plan family where the plan has one, else the kind and its purpose. */
    static String dialogFamily(Map<String, Object> d) {
        String f = PlanBook.family(d);
        if (f != null) {
            return f;
        }
        String kind = firstKind(d);
        Map<String, Object> sem = firstSemantic(d, kind);
        String purpose = sem == null ? null : Json.str(sem, "purpose");
        if ("arrange_card".equals(kind) || "order_pick".equals(kind) && !"mulligan_bottom".equals(purpose)) {
            return "arrange"; // an arrangement group: arrange_card substeps, then its order picks
        }
        return kind + (purpose == null ? "" : ":" + purpose);
    }

    /** True when {@code d} continues the logical dialog in progress (same source, same group or family). */
    boolean continuesOpenDialog(Map<String, Object> d) {
        if (openDialog == null) {
            return false;
        }
        String src = contextSource(d);
        if (src == null ? openDialog.get("source") != null : !src.equals(openDialog.get("source"))) {
            return false;
        }
        Map<String, Object> group = fixedGroup(d);
        if (group != null) {
            return Json.num(group, "group_id", -1) == Json.num(openDialog, "group_id", -2)
                    && Json.num(group, "substep_index", 0) > 0;
        }
        return openDialog.get("group_id") == null && dialogFamily(d).equals(openDialog.get("family"));
    }

    /** A decision's answer completes its logical dialog: a finish, a fixed group's last substep, the maximum reached,
     * or a single-step kind. */
    static boolean closesDialog(Map<String, Object> d, Map<String, Object> chosen) {
        String kind = Json.str(chosen, "kind");
        if (kind.startsWith("finish_")) {
            return true;
        }
        Map<String, Object> group = fixedGroup(d);
        if (group != null) {
            return Json.num(group, "substep_index", 0) >= Json.num(group, "substep_count", 1) - 1;
        }
        if (chosen.get("maximum") instanceof Number) {
            return Json.num(chosen, "selected_count", 0) + 1 >= Json.num(chosen, "maximum", 0);
        }
        return true;
    }

    /** The decision's group when it is a fixed group (more than one substep); every decision carries a group. */
    static Map<String, Object> fixedGroup(Map<String, Object> d) {
        Map<String, Object> g = Json.obj(d, "group");
        return g != null && Json.num(g, "substep_count", 1) > 1 ? g : null;
    }

    void trackDialog(Map<String, Object> d, Map<String, Object> chosen) {
        if (!continuesOpenDialog(d)) {
            // the plan made at this decision (its first) belongs to the dialog it opens; any other is stale
            Map<String, Object> plan = dialogPlan != null && Json.num(dialogPlan, "made_at", -1) == Json.num(d, "seat_step", -2)
                    ? dialogPlan : null;
            closeDialog();
            dialogPlan = plan;
            Map<String, Object> group = fixedGroup(d);
            openDialog = Json.map("source", contextSource(d), "family", dialogFamily(d),
                    "group_id", group == null ? null : group.get("group_id"), "seat_step", d.get("seat_step"),
                    "picks", new ArrayList<Object>(), "known", Json.copy(Json.arr(Json.obj(d, "observation"), "known")));
        }
        Json.arr(openDialog, "picks").add(chosen);
        if (closesDialog(d, chosen)) {
            closeDialog();
        }
    }

    void closeDialog() {
        if (openDialog != null) {
            String src = (String) openDialog.get("source");
            if (src != null) {
                List<Map<String, Object>> l = dialogs.get(src);
                if (l == null) {
                    l = new ArrayList<>();
                    dialogs.put(src, l);
                }
                l.add(openDialog);
            }
        }
        openDialog = null;
        dialogPlan = null;
    }

    Answer decide(Map<String, Object> d, Clock clock) {
        Map<String, Object> ctx = Json.obj(d, "context");
        Map<String, Object> obs = Json.obj(d, "observation");
        List<Object> cands = Json.arr(d, "candidates");
        boolean priority = ctx != null && "priority".equals(Json.str(ctx, "kind"));
        long seatStep = Json.num(d, "seat_step", 0);
        dropStaleAnchors(obs);
        trackCombatDamage(obs, priority);
        confirmActivations(obs);

        // rewind (Section 5.1 and 8): roll back every provisional record of the abandoned action
        if (priority && Json.bool(ctx, "rewind")) {
            String abandoned = plans.active == null ? null : plans.active.actionId();
            long since = plans.active == null ? seatStep : plans.active.seatStep;
            plans.rollback();
            int dropped = 0;
            for (String k : new ArrayList<>(anchors.keySet())) {
                if (Json.num(anchors.get(k), "seat_step", 0) >= since) {
                    anchors.remove(k);
                    dropped++;
                }
            }
            int answersDropped = 0;
            if (openDialog != null && Json.num(openDialog, "seat_step", 0) >= since) {
                answersDropped += Json.arr(openDialog, "picks").size();
                openDialog = null;
            }
            for (List<Map<String, Object>> l : dialogs.values()) {
                for (Map<String, Object> m : new ArrayList<>(l)) {
                    if (Json.num(m, "seat_step", 0) >= since) {
                        answersDropped += Json.arr(m, "picks").size();
                        l.remove(m);
                    }
                }
            }
            dialogPlan = null;
            groupPlan = null;
            ownActivations.removeIf(m -> Json.num(m, "seat_step", 0) >= since);
            count("rewinds");
            logLine(Json.map("event", "rewind", "seat_step", seatStep, "abandoned_action", abandoned,
                    "anchors_dropped", (long) dropped, "answers_dropped", (long) answersDropped));
        }

        // the action's plan (Section 6.1)
        if (!priority && plans.active != null) {
            if (cands.size() == 1) {
                plans.forced(d);
                return new Answer(0, "bot", "plan_forced");
            }
            int c = plans.claim(d);
            if (c >= 0) {
                return new Answer(c, "bot", "plan");
            }
            if (c == -1) {
                return fallback(d, null, "wrapper", "plan_missing_value");
            }
        } else if (priority) {
            plans.close();
            closeDialog();
            saveAnchor(d);
        }

        // a later decision of a planned logical dialog: its plan (no new world)
        boolean mid = !priority && continuesOpenDialog(d);
        if (mid && dialogPlan != null) {
            Answer a = answerFromPlan(d, dialogPlan);
            if (cands.size() == 1) {
                return new Answer(0, "bot", "single");
            }
            return a;
        }

        // fast path: one candidate
        if (cands.size() == 1) {
            return new Answer(0, "bot", "single");
        }

        if (priority) {
            if (forced != null && seatStep == forcedAt) {
                return priorityAnchor(d, clock);
            }
            int manaOnlyPass = manaOnlyPass(cands);
            if (manaOnlyPass >= 0) {
                return new Answer(manaOnlyPass, "bot", "mana_only_pass");
            }
            String step = Json.str(obs, "phase_step");
            boolean passFirst = "pass".equals(Json.str(Json.obj(Json.obj(cands.get(0)), "semantic"), "kind"));
            if ("mad".equals(botKind) && passFirst && CP7_PASS_STEPS.contains(step)) {
                return new Answer(0, "bot", "cp7_passes_in_step");
            }
            return priorityAnchor(d, clock);
        }
        String kind = firstKind(d);
        Map<String, Object> group = Json.obj(d, "group");
        long groupId = group == null ? -1 : Json.num(group, "group_id", -1);
        switch (kind) {
            case "declare_attack":
            case "declare_block":
                return combat(d, clock, kind, groupId);
            case "mulligan":
                return mulligan(d, clock);
            default:
                break;
        }
        if ("order_pick".equals(kind) && "mulligan_bottom".equals(Json.str(firstSemantic(d, "order_pick"), "purpose"))) {
            return dialog(d, clock, "mulligan_bottom");
        }
        // saved-anchor continuation at the first decision of a logical dialog, else the current dialog
        String src = contextSource(d);
        Map<String, Object> contDetail = null;
        if (!mid && src != null && anchors.containsKey(src)) {
            contDetail = new LinkedHashMap<>();
            Answer cont = continuation(d, clock, src, contDetail);
            if (cont != null) {
                return cont;
            }
        }
        Answer a = dialog(d, clock, "dialog");
        if (contDetail != null) {
            a.detail.put("continuation_failed", contDetail);
        }
        return a;
    }

    static Map<String, Object> firstSemantic(Map<String, Object> d, String kind) {
        for (Object o : Json.arr(d, "candidates")) {
            Map<String, Object> sem = Json.obj(Json.obj(o), "semantic");
            if (kind.equals(Json.str(sem, "kind"))) {
                return sem;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------------------------------------

    private List<Object> worldSeeds(long seatStep, int k) {
        List<Object> out = new ArrayList<>();
        for (int i = 0; i < k && gameKey != null; i++) {
            out.add(Seeds.hex(Seeds.worldSeed(gameKey, seatStep, i)));
        }
        return out;
    }

    /** Saves the inputs of this priority decision as the anchor of the stack's top object. */
    private void saveAnchor(Map<String, Object> d) {
        List<Object> stack = Json.arr(Json.obj(d, "observation"), "stack");
        if (stack.isEmpty() || gameKey == null) {
            return;
        }
        String top = Json.str(Json.obj(stack.get(stack.size() - 1)), "object_id");
        Map<String, Object> decision = new LinkedHashMap<>(d);
        decision.put("x_history", ownHistory(Json.obj(d, "observation")));
        anchors.put(top, Json.map("decision", decision, "world_seeds", worldSeeds(Json.num(d, "seat_step", 0), 1),
                "seat_step", d.get("seat_step")));
    }

    private Map<String, Object> request(String path, Map<String, Object> d, int k) {
        long seatStep = Json.num(d, "seat_step", 0);
        Map<String, Object> decision = new LinkedHashMap<>(d);
        decision.put("x_history", ownHistory(Json.obj(d, "observation")));
        Map<String, Object> profile = gameStart == null ? null : Json.obj(gameStart, "engine_profile");
        if (profile != null && profile.get("observation") != null) {
            decision.put("x_observation_flags", profile.get("observation"));
        }
        Map<String, Object> r = Json.map("op", "decide", "path", path, "decision", decision,
                "world_seeds", worldSeeds(seatStep, k), "budgets", budgets,
                "bot", Json.map("kind", botKind, "skill", (long) skill),
                "deadline_ms", 0L, "combat_damage_step", combatDamageStep(d));
        if (Json.bool(config, "search_flagged")) {
            r.put("search_flagged", true);
        }
        if (hangAt >= 0 && !hangUsed && seatStep >= hangAt && "priority".equals(path)) {
            r.put("hang", true); // E3 test hook: this request's search never returns
            hangUsed = true;
            logLine(Json.map("event", "hang_hook", "seat_step", seatStep, "path", path));
        }
        return r;
    }

    private Map<String, Object> ownHistory(Map<String, Object> observation) {
        return Json.map("loyalty_used", loyaltyUsed(observation),
                "card_origins", VisibleNames.changed(observation, cardOrigins));
    }

    /** Calls the runner within the clock; null on a timeout (the runner was killed) or a refusal, recorded in detail. */
    private Map<String, Object> call(Map<String, Object> request, Clock clock, Map<String, Object> detail) {
        long waitUntil = clock.answerBy - TimeUnit.MILLISECONDS.toNanos(killReserveMs);
        try {
            Map<String, Object> r = runner.call(request, waitUntil, clock.answerBy, graceMs);
            if (!Json.bool(r, "ok")) {
                detail.put("runner_error", r.get("error"));
                return null;
            }
            if (Json.bool(r, "interrupted")) {
                detail.put("cap", "deadline_interrupt");
            }
            detail.put("runner_deadline_ms", request.get("deadline_ms"));
            return r;
        } catch (RunnerLink.Timeout e) {
            detail.put("cap", "runner_killed");
            detail.put("exit_confirmed", e.exitConfirmed);
            detail.put("waited_ms", e.waitedMs);
            return null;
        } catch (RunnerLink.Busy e) {
            detail.put("cap", e.why);
            detail.put("waited_ms", e.waitedMs);
            return null;
        } catch (IOException e) {
            detail.put("runner_error", e.toString());
            return null;
        }
    }

    /** The world results of a request, or null when the clock had no room or the call failed (detail says which). */
    private Map<String, Object> worldsCall(Map<String, Object> request, Clock clock, Map<String, Object> detail) {
        if (!roomForRunner(clock)) {
            detail.put("cap", "clock_low");
            return null;
        }
        return call(request, clock, detail);
    }

    Answer priorityAnchor(Map<String, Object> d, Clock clock) {
        Map<String, Object> detail = new LinkedHashMap<>();
        long seatStep = Json.num(d, "seat_step", 0);
        Map<String, Object> req = request("priority", d, worlds);
        boolean forcing = forced != null && seatStep == forcedAt;
        if (forcing) {
            req.put("force_semantic", forced); // fixture hook: the runner executes this action instead of searching
            req.put("world_seeds", worldSeeds(seatStep, 1));
            detail.put("forced", true);
            forced = null;
        }
        if (dumpDir != null) {
            try (java.io.Writer wr = new java.io.OutputStreamWriter(new FileOutputStream(new File(dumpDir,
                    "decision-" + gameId + "-" + seatStep + ".json")), StandardCharsets.UTF_8)) {
                // the decision as the runner receives it (with this seat's own history), so a dump replays exactly
                wr.write(Json.canonical(Json.map("game_start", stripEnvelope(gameStart), "decision", req.get("decision"))));
            } catch (IOException e) {
                System.err.println("kit-front: dump failed: " + e);
            }
        }
        Map<String, Object> r = worldsCall(req, clock, detail);
        if (r == null) {
            Answer a = fallback(d, null, detail.containsKey("cap") ? "cap" : "wrapper", "priority_failed");
            a.detail.putAll(detail);
            return a;
        }
        List<Object> results = Json.arr(r, "worlds");
        Map<String, Integer> candidateOf = candidateKeys(d);
        List<Aggregate.WorldVote> votes = new ArrayList<>();
        List<Object> flags = new ArrayList<>();
        long nodes = 0;
        long evaluated = 0;
        long horizonAlternatives = 0;
        List<Object> unmapped = new ArrayList<>();
        boolean skipped = false;
        for (Object o : results) {
            Map<String, Object> w = Json.obj(o);
            votes.add(Aggregate.fromRunner(w));
            flags.addAll(Json.arr(w, "flags"));
            nodes += Json.num(w, "nodes", 0);
            skipped |= w.get("skipped") != null;
            for (Object so : Json.arr(w, "root_stats")) {
                Map<String, Object> s = Json.obj(so);
                if (s.get("adjusted") instanceof Number || s.get("visits") instanceof Number) {
                    evaluated++;
                }
                if (Json.num(s, "horizon_hits", 0) > 0 || Json.bool(s, "truncated")) {
                    horizonAlternatives++;
                }
            }
            if (w.get("mapping_failure") != null) {
                unmapped.add(Json.map("world", w.get("index"), "chosen", w.get("chosen"), "reason", w.get("mapping_failure")));
            }
            mergeCounters(detail, Json.obj(w, "counters"));
        }
        aliasWorldKeys(Json.arr(d, "candidates"), results, candidateOf);
        Aggregate.Result agg;
        if ("mcts".equals(botKind)) {
            List<Map<String, Object>> ws = new ArrayList<>();
            for (Object o : results) {
                ws.add(Json.obj(o));
            }
            agg = Aggregate.visits(ws, candidateOf);
        } else {
            agg = Aggregate.vote(votes, candidateOf);
        }
        detail.put("worlds", (long) results.size());
        detail.put("world_flags", dedupe(flags));
        detail.put("runner_ms", r.get("ms"));
        detail.put("nodes", nodes);
        // E4 accounting (review change 7): root alternatives evaluated, and those whose subtree met a horizon or
        // whose rollouts were truncated; the encounters are the counters (horizon:mad, horizon:mcts_*)
        detail.put("root_alternatives", evaluated);
        detail.put("root_alternatives_horizon", horizonAlternatives);
        if (!results.isEmpty()) {
            Map<String, Object> w0 = Json.obj(results.get(0));
            detail.put("build_ms", w0.get("build_ms"));
            detail.put("search_ms", w0.get("search_ms"));
        }
        String tag = detail.containsKey("cap") ? "cap" : "bot";
        // E4 outcome and the register (review change 3): a world with a horizon-flagged stack object, dropped
        // pending triggers or an unsupported state is not searched; the front declines (wrapper)
        if (skipped) {
            Answer a = fallback(d, null, "wrapper", "approximate_state_without_search");
            a.detail.putAll(detail);
            return a;
        }
        if ("mcts".equals(botKind) && agg.winner != null && agg.votes.getOrDefault(agg.winner, 0) == 0) {
            // interrupted before one completed iteration: the ranking is candidate order, not a search result
            Answer a = fallback(d, null, "cap", "mcts_no_iterations");
            a.detail.putAll(detail);
            return a;
        }
        Integer c = agg.winner == null ? null : candidateOf.get(agg.winner);
        if (c == null) {
            detail.put("unmapped_winner", agg.winner);
            detail.put("unmapped", unmapped);
            count("unmapped");
            Answer a = rankedFallback(d, agg, candidateOf, "wrapper", "priority_unmapped", null);
            a.detail.putAll(detail);
            return a;
        }
        // an action that does not use the stack and asked a dialog while it executed: no executed copy carries its
        // choices, so the state is unsupported (review change 4); the next ranked candidate answers
        if (agg.planWorld >= 0 && Json.num(Json.obj(results.get(agg.planWorld)), "non_stack_dialogs", 0) > 0
                && !colorsOnly(Json.obj(results.get(agg.planWorld)))) {
            detail.put("non_stack_winner", agg.winner);
            Answer a = rankedFallback(d, agg, candidateOf, "wrapper", "unsupported_non_stack_payload", agg.winner);
            a.detail.putAll(detail);
            return a;
        }
        Map<String, Object> sem = Json.obj(Json.obj(Json.arr(d, "candidates").get(c)), "semantic");
        if (!"pass".equals(Json.str(sem, "kind"))) {
            List<Map<String, Object>> answers = new ArrayList<>();
            if (agg.planWorld >= 0) {
                for (Object o : Json.arr(Json.obj(results.get(agg.planWorld)), "answers")) {
                    answers.add(Json.obj(o));
                }
            }
            plans.open(seatStep, sem, Json.obj(d, "observation"), agg.planPayload, answers);
            if ("cast_spell".equals(Json.str(sem, "kind")) && sem.get("method") == null) {
                // a method-null cast (Section 7.4): choose_cast_method then takes the world's method
                plans.active.castMethod = Offers.worldMethod(agg.winner);
                detail.put("cast_method", plans.active.castMethod);
            }
            detail.put("plan_payload", agg.planPayload);
        }
        Answer a = new Answer(c, tag, forcing ? "priority_forced" : "priority_anchor");
        a.detail.putAll(detail);
        return a;
    }

    static List<Object> dedupe(List<Object> l) {
        List<Object> out = new ArrayList<>();
        for (Object o : l) {
            if (!out.contains(o)) {
                out.add(o);
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    static void mergeCounters(Map<String, Object> detail, Map<String, Object> counters) {
        if (counters == null || counters.isEmpty()) {
            return;
        }
        Map<String, Object> m = (Map<String, Object>) detail.get("counters");
        if (m == null) {
            m = new LinkedHashMap<>();
            detail.put("counters", m);
        }
        for (Map.Entry<String, Object> e : counters.entrySet()) {
            long before = m.get(e.getKey()) instanceof Number ? ((Number) m.get(e.getKey())).longValue() : 0;
            m.put(e.getKey(), before + ((Number) e.getValue()).longValue());
        }
    }

    /**
     * Adds every world key that is not itself offered but answers an offered candidate (a method-null cast; see
     * {@link Offers}) to {@code candidateOf}, so votes, visits and the ranked fallback reach that candidate.
     */
    static void aliasWorldKeys(List<Object> cands, List<Object> results, Map<String, Integer> candidateOf) {
        List<Object> sems = new ArrayList<>();
        for (Object o : results) {
            Map<String, Object> w = Json.obj(o);
            sems.add(w.get("semantic"));
            for (Object so : Json.arr(w, "root_stats")) {
                sems.add(Json.obj(so).get("semantic"));
            }
        }
        for (Object s : sems) {
            if (!(s instanceof Map)) {
                continue;
            }
            String k = Aggregate.key(s);
            if (!candidateOf.containsKey(k)) {
                int c = Offers.candidateFor(cands, Json.obj(s));
                if (c >= 0) {
                    candidateOf.put(k, c);
                }
            }
        }
    }

    static Map<String, Integer> candidateKeys(Map<String, Object> d) {
        Map<String, Integer> out = new LinkedHashMap<>();
        List<Object> cands = Json.arr(d, "candidates");
        for (int i = 0; i < cands.size(); i++) {
            out.put(Aggregate.key(Json.obj(Json.obj(cands.get(i)), "semantic")), i);
        }
        return out;
    }

    // ---------------------------------------------------------------------------------------------
    // combat (Section 5.5.4: a vote per combat key; the winning assignment is the plan of every substep)

    Answer combat(Map<String, Object> d, Clock clock, String kind, long groupId) {
        Map<String, Object> detail = new LinkedHashMap<>();
        if (groupPlan == null || groupPlanGroupId != groupId) {
            groupPlan = null;
            Map<String, Object> r = worldsCall(request(kind.equals("declare_attack") ? "attack" : "block", d, worlds),
                    clock, detail);
            boolean skipped = false;
            List<Object> combatFlags = new ArrayList<>();
            for (Object o : r == null ? new ArrayList<>() : Json.arr(r, "worlds")) {
                skipped |= Json.obj(o).get("skipped") != null;
                combatFlags.addAll(Json.arr(Json.obj(o), "flags"));
            }
            detail.put("world_flags", dedupe(combatFlags));
            if (skipped) {
                // an unsupported state: not searched, so no bot plan (second review, item 2); declining, wrapper
                Answer a = fallback(d, null, "wrapper", "approximate_state_without_search");
                a.detail.putAll(detail);
                return a;
            }
            if (r != null) {
                Map<String, Integer> votes = new LinkedHashMap<>();
                Map<String, Object> byKey = new LinkedHashMap<>();
                for (Object o : Json.arr(r, "worlds")) {
                    mergeCounters(detail, Json.obj(Json.obj(o), "counters"));
                    List<Object> pairs = Json.arr(Json.obj(o), "pairs");
                    String key = Json.canonical(sortPairs(pairs));
                    votes.merge(key, 1, Integer::sum);
                    if (!byKey.containsKey(key)) {
                        byKey.put(key, pairs);
                    }
                }
                String best = null;
                for (Map.Entry<String, Integer> e : votes.entrySet()) {
                    if (best == null || e.getValue() > votes.get(best)) {
                        best = e.getKey(); // ties: the lowest world index, which entered first
                    }
                }
                groupPlan = Json.map("pairs", byKey.get(best), "votes", votes.size(),
                        "world_flags", detail.get("world_flags"));
                groupPlanGroupId = groupId;
            }
        }
        if (groupPlan == null) {
            Answer a = fallback(d, null, detail.containsKey("cap") ? "cap" : "wrapper", "combat_failed");
            a.detail.putAll(detail);
            return a;
        }
        detail.put("world_flags", groupPlan.get("world_flags"));
        List<Object> cands = Json.arr(d, "candidates");
        Map<String, Object> s0 = Json.obj(Json.obj(cands.get(0)), "semantic");
        String self = Json.str(Json.obj(s0, kind.equals("declare_attack") ? "attacker" : "blocker"), "object_id");
        Object want = null;
        for (Object p : Json.arr(groupPlan, "pairs")) {
            Map<String, Object> pm = Json.obj(p);
            if (self.equals(pm.get(kind.equals("declare_attack") ? "attacker" : "blocker"))) {
                want = kind.equals("declare_attack") ? pm.get("defender") : pm.get("attacker");
                break;
            }
        }
        for (int i = 0; i < cands.size(); i++) {
            Map<String, Object> sem = Json.obj(Json.obj(cands.get(i)), "semantic");
            if (kind.equals("declare_attack")) {
                Object def = sem.get("defender");
                if (want == null ? def == null : (def != null && PlanBook.sameTarget(Json.obj(def), want))) {
                    Answer a = new Answer(i, "bot", "combat_anchor");
                    a.detail.putAll(detail);
                    return a;
                }
            } else {
                Object atk = sem.get("attacker");
                if (want == null ? atk == null
                        : (atk != null && want.equals(Json.str(Json.obj(atk), "object_id")))) {
                    Answer a = new Answer(i, "bot", "combat_anchor");
                    a.detail.putAll(detail);
                    return a;
                }
            }
        }
        Answer a = fallback(d, null, "wrapper", "combat_pair_not_offered");
        a.detail.putAll(detail);
        return a;
    }

    static List<Object> sortPairs(List<Object> pairs) {
        List<String> keys = new ArrayList<>();
        for (Object p : pairs) {
            keys.add(Json.canonical(p));
        }
        java.util.Collections.sort(keys);
        List<Object> out = new ArrayList<>();
        for (String k : keys) {
            out.add(Json.parse(k));
        }
        return out;
    }

    Answer mulligan(Map<String, Object> d, Clock clock) {
        Map<String, Object> detail = new LinkedHashMap<>();
        Map<String, Object> r = worldsCall(request("mulligan", d, 1), clock, detail);
        if (r == null || Json.arr(r, "worlds").isEmpty()) {
            Answer a = fallback(d, null, detail.containsKey("cap") ? "cap" : "wrapper", "mulligan_failed");
            a.detail.putAll(detail);
            return a;
        }
        boolean keep = Json.bool(Json.obj(Json.arr(r, "worlds").get(0)), "keep");
        List<Object> cands = Json.arr(d, "candidates");
        for (int i = 0; i < cands.size(); i++) {
            Map<String, Object> sem = Json.obj(Json.obj(cands.get(i)), "semantic");
            if (Boolean.valueOf(keep).equals(sem.get("keep"))) {
                return new Answer(i, "bot", "pregame_anchor");
            }
        }
        return fallback(d, null, "wrapper", "mulligan_not_offered");
    }

    /**
     * The current-dialog path (or the London bottom on the pregame anchor) at the first decision of a logical
     * dialog: the runner plans the whole dialog, and this decision is answered from that plan.
     */
    Answer dialog(Map<String, Object> d, Clock clock, String path) {
        Map<String, Object> detail = new LinkedHashMap<>();
        dialogPlan = null;
        Map<String, Object> r = worldsCall(request(path, d, 1), clock, detail);
        Map<String, Object> w = r == null || Json.arr(r, "worlds").isEmpty() ? null : Json.obj(Json.arr(r, "worlds").get(0));
        if (w != null) {
            mergeCounters(detail, Json.obj(w, "counters"));
        }
        if (w == null || w.get("picks") == null) {
            Answer a = fallback(d, null, detail.containsKey("cap") ? "cap" : "wrapper", "no_dialog:" + firstKind(d));
            a.detail.putAll(detail);
            return a;
        }
        dialogPlan = Json.map("picks", w.get("picks"), "cursor", 0L, "path", path, "made_at", d.get("seat_step"));
        Answer a = answerFromPlan(d, dialogPlan);
        a.detail.putAll(detail);
        return a;
    }

    /** Answers {@code d} from a logical dialog's plan; the cursor advances. */
    Answer answerFromPlan(Map<String, Object> d, Map<String, Object> plan) {
        String path = Json.str(plan, "path");
        List<Object> picks = Json.arr(plan, "picks");
        int cursor = (int) Json.num(plan, "cursor", 0);
        plan.put("cursor", (long) (cursor + 1));
        List<Object> cands = Json.arr(d, "candidates");
        if (!picks.isEmpty() && picks.get(0) instanceof Map && Json.bool(Json.obj(picks.get(0)), "arrangement")) {
            // an arrangement (Section 6.3): partition from the plan; the ordering is recomputed from the actual
            // partition, since its candidates are the unplaced cards of the current destination
            Map<String, Object> arrangement = Json.obj(picks.get(0));
            Map<String, Object> dest = Json.obj(arrangement, "dest");
            List<Object> order = Json.arr(arrangement, "order");
            int best = -1;
            int bestRank = Integer.MAX_VALUE;
            for (int i = 0; i < cands.size(); i++) {
                Map<String, Object> sem = Json.obj(Json.obj(cands.get(i)), "semantic");
                if ("arrange_card".equals(Json.str(sem, "kind"))) {
                    String oid = Json.str(Json.obj(sem, "card"), "object_id");
                    if (Json.str(sem, "destination").equals(dest.get(oid))) {
                        return new Answer(i, "bot", path + ":arrangement");
                    }
                } else if ("order_pick".equals(Json.str(sem, "kind"))) {
                    Map<String, Object> item = Json.obj(sem, "item");
                    Map<String, Object> o = item == null ? null : Json.obj(item, "object");
                    int rank = o == null ? -1 : order.indexOf(Json.str(o, "object_id"));
                    if (rank >= 0 && rank < bestRank) {
                        bestRank = rank;
                        best = i;
                    }
                }
            }
            if (best >= 0) {
                return new Answer(best, "bot", path + ":arrangement_order");
            }
            return fallback(d, null, "wrapper", path + "_arrangement_not_offered");
        }
        if (cursor < picks.size()) {
            Object want = picks.get(cursor);
            for (int i = 0; i < cands.size(); i++) {
                Map<String, Object> sem = Json.obj(Json.obj(cands.get(i)), "semantic");
                if (matchesPick(sem, want)) {
                    return new Answer(i, "bot", path);
                }
            }
        } else {
            for (int i = 0; i < cands.size(); i++) {
                Map<String, Object> sem = Json.obj(Json.obj(cands.get(i)), "semantic");
                if (Json.str(sem, "kind").startsWith("finish_")) {
                    return new Answer(i, "bot", path + ":finish");
                }
            }
        }
        Answer a = fallback(d, null, "wrapper", path + "_pick_not_offered");
        a.detail.put("plan_cursor", (long) cursor);
        return a;
    }

    /** A non-stack action whose dialogs were all color choices (a Gate's as-enters color): the plan carries them. */
    public static boolean colorsOnly(Map<String, Object> world) {
        List<Object> answers = Json.arr(world, "answers");
        if (answers.isEmpty()) {
            return false;
        }
        for (Object o : answers) {
            Map<String, Object> a = Json.obj(o);
            if (!"choice".equals(Json.str(a, "family")) || PlanBook.colorName(a.get("value")) == null) {
                return false;
            }
        }
        return true;
    }

    static boolean matchesPick(Map<String, Object> sem, Object want) {
        String kind = Json.str(sem, "kind");
        if (want instanceof Boolean) {
            return want.equals(sem.get("value")) || want.equals(sem.get("pay")) || want.equals(sem.get("keep"));
        }
        if (want instanceof Number) {
            long w = ((Number) want).longValue();
            return (sem.get("value") instanceof Number && ((Number) sem.get("value")).longValue() == w)
                    || ("choose_spell_mode".equals(kind) && Json.num(sem, "mode_index", -1) == w);
        }
        switch (kind) {
            case "choose_color":
                return want instanceof String && want.equals(sem.get("color"));
            case "select_object":
                return PlanBook.sameTarget(Json.obj(sem, "choice"), want);
            case "choose_target":
                return PlanBook.sameTarget(Json.obj(sem, "target"), want);
            case "choose_cost_target":
                return want instanceof Map && Json.str(Json.obj(sem, "candidate"), "object_id")
                        .equals(Json.str(Json.obj(want), "object_id"));
            case "order_pick": {
                Map<String, Object> item = Json.obj(sem, "item");
                Map<String, Object> o = item == null ? null : Json.obj(item, "object");
                return o != null && want instanceof Map && Json.str(o, "object_id").equals(Json.str(Json.obj(want), "object_id"));
            }
            default:
                return false;
        }
    }

    /**
     * Saved-anchor continuation (Section 5.1) at the first decision of a logical dialog: the runner rebuilds the
     * anchor world, replays this resolution's earlier dialogs from this seat's own answers, and plans the current
     * dialog where the world's projected observation equals the received one. Null when it does not apply (then the
     * current dialog answers, within the same clock); {@code why} says why.
     */
    Answer continuation(Map<String, Object> d, Clock clock, String source, Map<String, Object> why) {
        Map<String, Object> anchor = anchors.get(source);
        Map<String, Object> req = request("continuation", d, 1);
        req.put("anchor", anchor);
        // the earlier dialogs of this resolution: this source's dialogs after the anchor (casting-time and
        // trigger-placement choices precede the viewer's last priority decision with the object on top)
        List<Object> earlier = new ArrayList<>();
        long anchorStep = Json.num(anchor, "seat_step", 0);
        for (Map<String, Object> m : dialogs.containsKey(source) ? dialogs.get(source) : new ArrayList<Map<String, Object>>()) {
            if (Json.num(m, "seat_step", 0) > anchorStep) {
                earlier.add(m);
            }
        }
        req.put("earlier", earlier);
        Map<String, Object> r = worldsCall(req, clock, why);
        if (r == null || Json.arr(r, "worlds").isEmpty()) {
            count("continuation_failed");
            return null;
        }
        Map<String, Object> w = Json.obj(Json.arr(r, "worlds").get(0));
        mergeCounters(why, Json.obj(w, "counters"));
        if (!Json.bool(w, "match") || w.get("picks") == null) {
            count("continuation_mismatch");
            why.put("mismatch", w.get("diff"));
            why.put("replayed", w.get("replayed"));
            logLine(Json.map("event", "continuation_mismatch", "seat_step", d.get("seat_step"), "diff", w.get("diff"),
                    "replayed", w.get("replayed"), "earlier_dialogs", (long) Json.arr(req, "earlier").size()));
            return null;
        }
        count("continuation_used");
        dialogPlan = Json.map("picks", w.get("picks"), "cursor", 0L, "path", "continuation", "made_at", d.get("seat_step"));
        Answer a = answerFromPlan(d, dialogPlan);
        a.detail.put("continuation", true);
        a.detail.put("continuation_replayed", w.get("replayed"));
        a.detail.put("continuation_flags", w.get("flags"));
        a.detail.put("picks", w.get("picks"));
        return a;
    }

    // ---------------------------------------------------------------------------------------------
    // fallback (Section 6.5)

    /** The best-ranked offered key other than {@code exclude}, else {@link #fallback}. */
    Answer rankedFallback(Map<String, Object> d, Aggregate.Result agg, Map<String, Integer> candidateOf, String tag,
                          String why, String exclude) {
        if (agg != null) {
            for (String k : agg.ranking) {
                Integer c = candidateOf.get(k);
                if (c != null && !k.equals(exclude)) {
                    return new Answer(c, tag, "fallback_ranked:" + why);
                }
            }
        }
        return fallback(d, null, tag, why);
    }

    /** (1) ranked, (2) the declining candidate, (3) the lowest candidate id. Always an offered candidate. */
    Answer fallback(Map<String, Object> d, Aggregate.Result agg, String tag, String why) {
        List<Object> cands = Json.arr(d, "candidates");
        for (int i = 0; i < cands.size(); i++) {
            Map<String, Object> sem = Json.obj(Json.obj(cands.get(i)), "semantic");
            String kind = Json.str(sem, "kind");
            boolean declining = kind.equals("pass") || kind.startsWith("finish_")
                    || Boolean.FALSE.equals(sem.get("value")) && kind.equals("choose_boolean")
                    || Boolean.FALSE.equals(sem.get("pay")) || Boolean.FALSE.equals(sem.get("cast_it"))
                    || (kind.equals("declare_attack") && sem.get("defender") == null)
                    || (kind.equals("declare_block") && sem.get("attacker") == null);
            if (declining) {
                return new Answer(i, tag, "fallback_declining:" + why);
            }
        }
        return new Answer(0, tag, "fallback_lowest:" + why);
    }

    // ---------------------------------------------------------------------------------------------
    // anchors and own history

    private void dropStaleAnchors(Map<String, Object> obs) {
        List<String> onStack = new ArrayList<>();
        for (Object o : Json.arr(obs, "stack")) {
            onStack.add(Json.str(Json.obj(o), "object_id"));
        }
        for (String k : new ArrayList<>(anchors.keySet())) {
            Map<String, Object> ad = Json.obj(Json.obj(anchors.get(k), "decision"), "observation");
            if (!onStack.contains(k) || Json.num(ad, "turn", -1) != Json.num(obs, "turn", -2)) {
                anchors.remove(k);
            }
        }
    }

    /**
     * Own history for the first-strike damage step (Section 3.3): XMage has two combat damage steps where v2 has one.
     * A combat damage step ends only when both seats pass in succession with an empty stack, and the viewer always
     * acts again before a later object resolves, so: the first combat_damage decision of a turn is in the first
     * damage step, and a combat_damage decision that follows the viewer's own pass with an empty stack at
     * combat_damage is in the next one.
     */
    private void trackCombatDamage(Map<String, Object> obs, boolean priority) {
        long turn = Json.num(obs, "turn", -1);
        if (!"combat_damage".equals(Json.str(obs, "phase_step"))) {
            return;
        }
        if (turn != lastCombatDamageTurn) {
            lastCombatDamageTurn = turn;
            combatDamageSeen = 1;
        } else if (lastWasEmptyPassAtCombatDamage) {
            combatDamageSeen++;
        }
    }

    /** For the slice fixtures (S6): the damage step the front would name for this decision. */
    public String ownHistoryStep(Map<String, Object> d) {
        trackCombatDamage(Json.obj(d, "observation"), true);
        return combatDamageStep(d);
    }

    /** For the slice fixtures: records that this seat answered {@code d} with {@code candidate}. */
    public void answeredForTest(Map<String, Object> d, int candidate) {
        Answer a = new Answer(candidate, "bot", "fixture");
        recordAnswer(d, a);
    }

    /** For the slice fixtures: decides {@code d} as a choose request would, returning {candidate, tag, path}. */
    public Map<String, Object> decideForTest(Map<String, Object> d, long limitMs) {
        Answer a = decide(d, new Clock(System.nanoTime(), limitMs, overheadMs));
        recordAnswer(d, a);
        return Json.map("candidate", (long) a.candidate, "tag", a.tag, "path", a.path);
    }

    /** For the slice fixtures: opens the plan of a priority pick, as the priority path does. */
    public void pickedForTest(Map<String, Object> d, Map<String, Object> semantic, Map<String, Object> payload,
                              List<Map<String, Object>> answers, List<Object> worldSeeds) {
        plans.open(Json.num(d, "seat_step", 0), semantic, Json.obj(d, "observation"), payload, answers);
        List<Object> stack = Json.arr(Json.obj(d, "observation"), "stack");
        if (!stack.isEmpty()) {
            String top = Json.str(Json.obj(stack.get(stack.size() - 1)), "object_id");
            anchors.put(top, Json.map("decision", d, "world_seeds", worldSeeds, "seat_step", d.get("seat_step")));
        }
    }

    /**
     * Fixture hook (production-path cases): at the priority decision with this seat step the runner executes
     * {@code semantic} on world 0 instead of searching; the plan, anchors and everything after are the production
     * path's. Logged as {@code priority_forced}.
     */
    public void forceForTest(long seatStep, Map<String, Object> semantic) {
        forced = semantic;
        forcedAt = seatStep;
    }

    public PlanBook plansForTest() {
        return plans;
    }

    /** For the slice fixtures (S5): the front's provisional records. */
    public Map<String, Object> recordsForTest() {
        long answers = openDialog == null ? 0 : Json.arr(openDialog, "picks").size();
        for (List<Map<String, Object>> l : dialogs.values()) {
            for (Map<String, Object> m : l) {
                answers += Json.arr(m, "picks").size();
            }
        }
        return Json.map("plan", plans.active == null ? null : plans.active.actionId(), "anchors", new ArrayList<Object>(anchors.keySet()),
                "source_answers", answers, "plan_counters", new LinkedHashMap<String, Object>(plans.counters));
    }

    /** Set after each answer: the viewer passed priority with an empty stack at combat_damage. */
    private boolean lastWasEmptyPassAtCombatDamage;

    String combatDamageStep(Map<String, Object> d) {
        Map<String, Object> obs = Json.obj(d, "observation");
        if (!"combat_damage".equals(Json.str(obs, "phase_step"))) {
            return null;
        }
        return combatDamageSeen <= 1 ? "first" : "regular";
    }

    void logLine(Map<String, Object> m) {
        m.put("game_id", gameId);
        if (keepLines) {
            keptLines.add(m);
        }
        if (log != null) {
            log.println(Json.canonical(m));
        }
    }
}
