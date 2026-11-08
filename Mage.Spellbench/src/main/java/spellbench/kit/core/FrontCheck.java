package spellbench.kit.core;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Front-level checks with a scripted runner (this class's {@code fake} mode), XMage-free, so the timings are the
 * front's own (A1 result review, changes 1 and 2):
 * <ul>
 * <li>continuation: the picks a continuation returns answer the dialog's first decision and its later substeps
 * without another runner request; a forced single-candidate substep advances the plan; the plan's end selects the
 * finish candidate; a later dialog of the same resolution sends the earlier dialogs (this seat's own answers after
 * the anchor) for replay;</li>
 * <li>clock: one answer time per choose under each limit ({@code max_decision_ms}, {@code remaining_ms}); a decision
 * arriving while a replacement runner boots is answered in time without a kill; a slow continuation followed by the
 * current dialog, and diagnostics, stay inside the same clock.</li>
 * </ul>
 * The scripted runner reads {@code fake.json} in its working directory and appends every request to
 * {@code requests.log} there.
 *
 *   java -cp kit-core.jar spellbench.kit.core.FrontCheck WORKDIR
 */
public final class FrontCheck {

    private static final List<Map<String, Object>> results = new ArrayList<>();
    private static String cp;

    private FrontCheck() {
    }

    static void check(String id, boolean pass, Object detail) {
        Map<String, Object> m = Json.map("check", id, "pass", pass, "detail", detail);
        results.add(m);
        System.out.println(Json.canonical(m));
    }

    // =============================================================================================
    // the scripted runner

    static void fake() throws Exception {
        PrintStream out = new PrintStream(new FileOutputStream(java.io.FileDescriptor.out), true, "UTF-8");
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        File cfgFile = new File("fake.json");
        Map<String, Object> cfg = cfgFile.exists()
                ? Json.parseObject(new String(Files.readAllBytes(cfgFile.toPath()), StandardCharsets.UTF_8)) : new LinkedHashMap<>();
        String line;
        while ((line = in.readLine()) != null) {
            Map<String, Object> req = Json.parseObject(line);
            long seq = Json.num(req, "seq", 0);
            String op = Json.str(req, "op");
            String path = Json.str(req, "path");
            try (Writer w = new OutputStreamWriter(new FileOutputStream("requests.log", true), StandardCharsets.UTF_8)) {
                w.write(Json.canonical(Json.map("op", op, "path", path, "deadline_ms", req.get("deadline_ms"),
                        "earlier", req.get("earlier") == null ? null : (long) Json.arr(req, "earlier").size(),
                        "seat_step", req.get("decision") == null ? null : Json.obj(req, "decision").get("seat_step"))));
                w.write('\n');
            }
            Map<String, Object> res = Json.map("seq", seq, "ok", true);
            if ("boot".equals(op)) {
                File booted = new File("booted.once");
                long ms = booted.exists() ? Json.num(cfg, "reboot_ms", 0) : Json.num(cfg, "boot_ms", 0);
                booted.createNewFile();
                Thread.sleep(ms);
                res.put("boot_ms", ms);
            } else if ("decide".equals(op)) {
                Map<String, Object> slow = Json.obj(cfg, "slow");
                if (slow != null && slow.get(path) instanceof Number) {
                    Thread.sleep(((Number) slow.get(path)).longValue());
                }
                File hung = new File("hung-" + path);
                if (Json.arr(cfg, "hang").contains(path) && !hung.exists()) {
                    hung.createNewFile();
                    Thread.sleep(3_600_000L); // ignores everything; only a kill ends it
                }
                Map<String, Object> w = Json.map("index", 0L, "flags", new ArrayList<>(), "counters", new LinkedHashMap<>());
                if (Json.arr(cfg, "skip").contains(path)) {
                    w.put("skipped", "unsupported_state:test");
                }
                switch (path) {
                    case "priority":
                        w.put("semantic", Json.map("kind", "pass"));
                        w.put("root_stats", new ArrayList<>());
                        break;
                    case "continuation":
                        w.put("match", true);
                        w.put("picks", cfg.get("cont_picks_" + Json.arr(req, "earlier").size()));
                        w.put("replayed", (long) Json.arr(req, "earlier").size());
                        break;
                    case "dialog":
                        w.put("picks", cfg.get("dialog_picks"));
                        break;
                    case "roundtrip":
                        w.put("diff", new ArrayList<>());
                        break;
                    default:
                        w.put("pairs", new ArrayList<>());
                        w.put("keep", true);
                }
                res.put("worlds", new ArrayList<Object>(Arrays.asList(w)));
                res.put("ms", 0L);
            }
            out.println(Json.canonical(res));
        }
    }

    // =============================================================================================
    // decisions

    static Map<String, Object> ref(String id) {
        return Json.map("object_id", id, "card_name", "Card " + id, "owner_seat", "p0", "controller_seat", "p0", "zone", "library");
    }

    static Map<String, Object> observation(List<Object> stack) {
        return Json.map("viewer", "p0", "turn", 3L, "active_seat", "p0", "phase_step", "precombat_main", "stack", stack,
                "players", new ArrayList<>(), "known", new ArrayList<>());
    }

    static Map<String, Object> cand(long id, Map<String, Object> semantic) {
        return Json.map("candidate_id", id, "semantic", semantic);
    }

    static Map<String, Object> priority(long step, List<Object> stack) {
        return Json.map("seat_step", step, "acting_seat", "p0", "context", Json.map("kind", "priority"),
                "observation", observation(stack),
                "candidates", Arrays.asList(cand(0, Json.map("kind", "pass")),
                        cand(1, Json.map("kind", "cast_spell", "source", ref("o-x"), "method", "normal"))));
    }

    static Map<String, Object> select(long step, String source, Map<String, Object> group, long selected, Long maximum,
                                      boolean finish, String... ids) {
        List<Object> cands = new ArrayList<>();
        long i = 0;
        for (String id : ids) {
            Map<String, Object> sem = Json.map("kind", "select_object", "purpose", "search",
                    "choice", Json.map("object", ref(id)), "selected_count", selected);
            if (maximum != null) {
                sem.put("maximum", maximum);
            }
            cands.add(cand(i++, sem));
        }
        if (finish) {
            cands.add(cand(i, Json.map("kind", "finish_selection", "purpose", "search")));
        }
        Map<String, Object> d = Json.map("seat_step", step, "acting_seat", "p0",
                "context", Json.map("kind", "choice", "source", Json.map("object_id", source)),
                "observation", observation(Arrays.asList(Json.map("object_id", source, "stack_kind", "spell"))),
                "candidates", cands);
        if (group != null) {
            d.put("group", group);
        }
        return d;
    }

    static Map<String, Object> bool(long step, String source) {
        return Json.map("seat_step", step, "acting_seat", "p0",
                "context", Json.map("kind", "choice", "source", Json.map("object_id", source)),
                "observation", observation(Arrays.asList(Json.map("object_id", source, "stack_kind", "spell"))),
                "candidates", Arrays.asList(cand(0, Json.map("kind", "choose_boolean", "value", true)),
                        cand(1, Json.map("kind", "choose_boolean", "value", false))));
    }

    static Map<String, Object> choose(Map<String, Object> d, long maxDecision, long remaining, String rid) {
        return Json.map("protocol", Front.PROTOCOL, "request_type", "choose", "request_id", rid, "game_id", "g-check",
                "decision", d, "clock", Json.map("max_decision_ms", maxDecision, "remaining_ms", remaining));
    }

    // =============================================================================================

    static final class Agent {
        final Front front;
        final File work;
        int n;

        Agent(File root, String name, Map<String, Object> fake, String... extra) throws Exception {
            work = new File(root, name);
            if (work.exists()) {
                for (File f : work.listFiles()) {
                    f.delete();
                }
            }
            work.mkdirs();
            Files.write(new File(work, "fake.json").toPath(), Json.canonical(fake).getBytes(StandardCharsets.UTF_8));
            Map<String, String> opts = new LinkedHashMap<>();
            opts.put("work", work.getPath());
            opts.put("grace-ms", "1000");
            opts.put("overhead-ms", "300");
            opts.put("kill-reserve-ms", "300");
            opts.put("keep-lines", "1");
            for (int i = 0; i + 1 < extra.length; i += 2) {
                opts.put(extra[i], extra[i + 1]);
            }
            front = new Front(opts, Arrays.asList("java", "-cp", cp, "spellbench.kit.core.FrontCheck", "fake"));
            front.handle(Json.canonical(Json.map("protocol", Front.PROTOCOL, "request_type", "game_start", "request_id", "gs",
                    "game_id", "g-check", "seat", "p0", "agent_seed", 7L, "engine_profile", Json.map())));
        }

        /** Sends one choose; returns {candidate, path, tag, ms, line}. */
        Map<String, Object> choose(Map<String, Object> d, long maxDecision, long remaining) {
            long t0 = System.nanoTime();
            Map<String, Object> resp = front.handle(Json.canonical(FrontCheck.choose(d, maxDecision, remaining, "r" + (n++))));
            long ms = (System.nanoTime() - t0) / 1_000_000;
            front.afterAnswer();
            Map<String, Object> line = front.keptLines.isEmpty() ? null : front.keptLines.get(front.keptLines.size() - 1);
            for (int i = front.keptLines.size() - 1; i >= 0; i--) {
                if ("decision".equals(front.keptLines.get(i).get("event"))) {
                    line = front.keptLines.get(i);
                    break;
                }
            }
            Map<String, Object> sel = Json.obj(resp, "selection");
            return Json.map("candidate", sel == null ? null : sel.get("candidate_id"), "path", line == null ? null : line.get("path"),
                    "tag", line == null ? null : line.get("tag"), "ms", ms, "line", line);
        }

        List<Map<String, Object>> requests() throws Exception {
            List<Map<String, Object>> out = new ArrayList<>();
            File f = new File(work, "requests.log");
            if (f.exists()) {
                for (String l : Files.readAllLines(f.toPath(), StandardCharsets.UTF_8)) {
                    if (!l.trim().isEmpty()) {
                        out.add(Json.parseObject(l));
                    }
                }
            }
            return out;
        }

        long decides(String path) throws Exception {
            long k = 0;
            for (Map<String, Object> r : requests()) {
                if ("decide".equals(r.get("op")) && path.equals(r.get("path"))) {
                    k++;
                }
            }
            return k;
        }

        void close() {
            front.handle(Json.canonical(Json.map("protocol", Front.PROTOCOL, "request_type", "game_over", "request_id", "go")));
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("fake")) {
            fake();
            return;
        }
        File root = new File(args.length > 0 ? args[0] : ".");
        root.mkdirs();
        cp = FrontCheck.class.getProtectionDomain().getCodeSource().getLocation().getPath();
        if (cp.matches("^/[A-Za-z]:/.*")) {
            cp = cp.substring(1);
        }
        continuationPlan(root);
        laterDialogAndFinish(root);
        clockMaxDecision(root);
        clockRemainingAndReboot(root);
        clockContinuationThenDialog(root);
        clockDiagnostics(root);
        exitLatch(root);
        identities();
        skippedCombat(root);
        boolean all = true;
        for (Map<String, Object> r : results) {
            all &= Boolean.TRUE.equals(r.get("pass"));
        }
        System.out.println(Json.canonical(Json.map("verdict", all ? "PASS" : "FAIL", "checks", (long) results.size())));
        System.exit(0);
    }

    static Map<String, Object> group(long id, long index, long count) {
        return Json.map("group_id", id, "substep_index", index, "substep_count", count);
    }

    /** Change 1: the continuation's picks answer the group's first decision and its later substeps. */
    static void continuationPlan(File root) throws Exception {
        Map<String, Object> fake = Json.map("cont_picks_0", Arrays.asList(Json.map("object_id", "o-c"),
                Json.map("object_id", "o-a"), Json.map("object_id", "o-b")));
        Agent a = new Agent(root, "continuation-plan", fake);
        List<Object> stack = Arrays.asList(Json.map("object_id", "o-s", "stack_kind", "spell"));
        Map<String, Object> p = a.choose(priority(10, stack), 20_000, 600_000);
        Map<String, Object> r0 = a.choose(select(12, "o-s", group(5, 0, 3), 0, null, false, "o-a", "o-b", "o-c"), 20_000, 600_000);
        Map<String, Object> r1 = a.choose(select(13, "o-s", group(5, 1, 3), 1, null, false, "o-a"), 20_000, 600_000);
        Map<String, Object> r2 = a.choose(select(14, "o-s", group(5, 2, 3), 2, null, false, "o-a", "o-b"), 20_000, 600_000);
        long contCalls = a.decides("continuation");
        long dialogCalls = a.decides("dialog");
        check("FRONT.continuation.first_decision_from_picks", Long.valueOf(2).equals(r0.get("candidate"))
                        && "continuation".equals(r0.get("path")),
                Json.map("priority", p.get("path"), "answer", r0.get("candidate"), "path", r0.get("path")));
        check("FRONT.continuation.forced_substep_advances_plan", "single".equals(r1.get("path"))
                        && Long.valueOf(1).equals(r2.get("candidate")) && "continuation".equals(r2.get("path")),
                Json.map("forced", r1.get("path"), "third", r2.get("candidate"), "third_path", r2.get("path")));
        check("FRONT.continuation.no_second_request", contCalls == 1 && dialogCalls == 0,
                Json.map("continuation_requests", contCalls, "dialog_requests", dialogCalls));
        a.close();
    }

    /** Change 1: the plan's end selects finish; a later dialog of the resolution sends the earlier one for replay. */
    static void laterDialogAndFinish(File root) throws Exception {
        Map<String, Object> fake = Json.map("cont_picks_0", Arrays.asList(Json.map("object_id", "o-a")),
                "cont_picks_1", Arrays.asList(false));
        Agent a = new Agent(root, "later-dialog", fake);
        List<Object> stack = Arrays.asList(Json.map("object_id", "o-s", "stack_kind", "spell"));
        a.choose(priority(20, stack), 20_000, 600_000);
        Map<String, Object> r0 = a.choose(select(22, "o-s", null, 0, 2L, true, "o-a", "o-b"), 20_000, 600_000);
        Map<String, Object> r1 = a.choose(select(23, "o-s", null, 1, 2L, true, "o-b"), 20_000, 600_000);
        Map<String, Object> r2 = a.choose(bool(24, "o-s"), 20_000, 600_000);
        List<Map<String, Object>> reqs = a.requests();
        Long earlierSent = null;
        for (Map<String, Object> q : reqs) {
            if ("continuation".equals(q.get("path")) && Long.valueOf(24).equals(q.get("seat_step"))) {
                earlierSent = (Long) q.get("earlier");
            }
        }
        check("FRONT.continuation.finish_at_plan_end", Long.valueOf(0).equals(r0.get("candidate"))
                        && Long.valueOf(1).equals(r1.get("candidate")) && "continuation:finish".equals(r1.get("path")),
                Json.map("first", r0, "second", Json.map("candidate", r1.get("candidate"), "path", r1.get("path"))));
        check("FRONT.continuation.later_dialog_sends_earlier", Long.valueOf(1).equals(earlierSent)
                        && Long.valueOf(1).equals(r2.get("candidate")) && "continuation".equals(r2.get("path")),
                Json.map("earlier_sent", earlierSent, "answer", r2.get("candidate"), "path", r2.get("path")));
        a.close();
    }

    /** Change 2: max_decision_ms binds; a hung search is answered inside it, the runner killed. */
    static void clockMaxDecision(File root) throws Exception {
        Agent a = new Agent(root, "clock-max", Json.map("hang", Arrays.asList("priority")));
        Map<String, Object> r = a.choose(priority(30, new ArrayList<>()), 4000, 600_000);
        check("FRONT.clock.max_decision_binds", ((Number) r.get("ms")).longValue() <= 4000 && "cap".equals(r.get("tag"))
                        && a.front.runner.kills == 1,
                Json.map("answer_ms", r.get("ms"), "limit_ms", 4000L, "tag", r.get("tag"), "path", r.get("path"),
                        "kills", a.front.runner.kills, "line", r.get("line")));
        a.close();
    }

    /**
     * Change 2: remaining_ms binds; then a decision that arrives while the replacement runner boots is answered in
     * time without another kill, and once it has booted the runner answers again.
     */
    static void clockRemainingAndReboot(File root) throws Exception {
        Agent a = new Agent(root, "clock-remaining", Json.map("hang", Arrays.asList("priority"), "reboot_ms", 6000L));
        Map<String, Object> r = a.choose(priority(40, new ArrayList<>()), 600_000, 4000);
        check("FRONT.clock.remaining_binds", ((Number) r.get("ms")).longValue() <= 4000 && "cap".equals(r.get("tag")),
                Json.map("answer_ms", r.get("ms"), "limit_ms", 4000L, "tag", r.get("tag"), "path", r.get("path")));
        boolean restarting = a.front.runner.restarting();
        Map<String, Object> r2 = a.choose(priority(42, new ArrayList<>()), 3000, 600_000);
        Map<String, Object> cap = Json.obj(r2, "line");
        check("FRONT.clock.decision_during_reboot", restarting && ((Number) r2.get("ms")).longValue() <= 3000
                        && "runner_restarting".equals(cap.get("cap")) && a.front.runner.kills == 1,
                Json.map("restart_in_progress", restarting, "answer_ms", r2.get("ms"), "limit_ms", 3000L,
                        "cap", cap.get("cap"), "kills", a.front.runner.kills));
        Thread.sleep(7000);
        Map<String, Object> r3 = a.choose(priority(44, new ArrayList<>()), 20_000, 600_000);
        check("FRONT.clock.runner_serves_after_reboot", "priority_anchor".equals(r3.get("path")) && "bot".equals(r3.get("tag"))
                        && a.front.runner.restarts == 1,
                Json.map("path", r3.get("path"), "tag", r3.get("tag"), "restarts", a.front.runner.restarts,
                        "restart_ms", a.front.runner.lastRestartMs));
        a.close();
    }

    /** Change 2: a slow continuation and the current dialog after it share one clock. */
    static void clockContinuationThenDialog(File root) throws Exception {
        Map<String, Object> slow = Json.map("continuation", 2500L, "dialog", 2500L);
        Agent a = new Agent(root, "clock-continuation", Json.map("slow", slow, "cont_picks_0", null,
                "dialog_picks", Arrays.asList(Json.map("object_id", "o-a"))));
        List<Object> stack = Arrays.asList(Json.map("object_id", "o-s", "stack_kind", "spell"));
        a.choose(priority(50, stack), 20_000, 600_000);
        Map<String, Object> r = a.choose(select(52, "o-s", group(9, 0, 1), 0, null, false, "o-a", "o-b"), 4000, 600_000);
        check("FRONT.clock.continuation_then_dialog_share_clock", ((Number) r.get("ms")).longValue() <= 4000,
                Json.map("answer_ms", r.get("ms"), "limit_ms", 4000L, "path", r.get("path"), "tag", r.get("tag"),
                        "continuation_requests", a.decides("continuation"), "dialog_requests", a.decides("dialog"),
                        "line", r.get("line")));
        a.close();
    }

    /**
     * Second review, item 1: a killed runner whose exit is not confirmed latches the link: no replacement starts and
     * decisions are answered by fallback in time; once the exit is seen, the latch clears and a runner serves again.
     */
    static void exitLatch(File root) throws Exception {
        Agent a = new Agent(root, "exit-latch", Json.map("hang", Arrays.asList("priority")));
        a.front.runner.simulateUnconfirmedExit = true;
        Map<String, Object> r1 = a.choose(priority(70, new ArrayList<>()), 4000, 600_000);
        boolean latched = a.front.runner.exitLatched();
        Map<String, Object> r2 = a.choose(priority(72, new ArrayList<>()), 4000, 600_000);
        Map<String, Object> l2 = Json.obj(r2, "line");
        boolean noRestart = !a.front.runner.alive() && !a.front.runner.restarting() && a.front.runner.restarts == 0;
        check("FRONT.latch.unconfirmed_exit_blocks_restart", latched && noRestart && "runner_exit_unconfirmed".equals(l2.get("cap"))
                        && ((Number) r2.get("ms")).longValue() <= 4000,
                Json.map("first", Json.map("tag", r1.get("tag"), "path", r1.get("path")), "latched", latched,
                        "second_cap", l2.get("cap"), "second_ms", r2.get("ms"), "restarts", a.front.runner.restarts,
                        "alive", a.front.runner.alive()));
        a.front.runner.simulateUnconfirmedExit = false; // the exit is now observable
        Map<String, Object> r3 = a.choose(priority(74, new ArrayList<>()), 20_000, 600_000);
        check("FRONT.latch.clears_after_confirmed_exit", !a.front.runner.exitLatched() && "priority_anchor".equals(r3.get("path"))
                        && a.front.runner.restarts == 1,
                Json.map("path", r3.get("path"), "restarts", a.front.runner.restarts, "exits_confirmed_late",
                        a.front.runner.exitsConfirmedLate));
        a.close();
    }

    /** Second review, item 4: the clock policy and diagnostics are in the identity; names cannot be overridden. */
    static void identities() {
        Map<String, Object> frozen = Entries.configure("h1", new LinkedHashMap<String, String>());
        Map<String, String> g = new LinkedHashMap<>();
        g.put("grace-ms", "1000");
        Map<String, Object> grace = Entries.configure("h1", g);
        Map<String, String> rt = new LinkedHashMap<>();
        rt.put("roundtrip", "1");
        Map<String, Object> diag = Entries.configure("h1", rt);
        boolean refused = false;
        try {
            Map<String, String> o = new LinkedHashMap<>();
            o.put("name", "kit-mad-1");
            o.put("grace-ms", "1000");
            new Front(o, Arrays.asList("none"));
        } catch (IllegalArgumentException | java.io.IOException e) {
            refused = true;
        }
        check("FRONT.identity.full_configuration", "kit-mad-1".equals(frozen.get("name"))
                        && "kit-mad-1-custom".equals(grace.get("name")) && "kit-mad-1-custom".equals(diag.get("name"))
                        && !Entries.digest(frozen).equals(Entries.digest(grace)) && !Entries.digest(frozen).equals(Entries.digest(diag))
                        && refused,
                Json.map("frozen", Entries.version(frozen), "grace_override", grace.get("name") + " " + Entries.version(grace),
                        "roundtrip_override", diag.get("name") + " " + Entries.version(diag), "name_override_refused", refused));
        List<String> skillNames = new ArrayList<>();
        boolean allDistinct = true;
        for (int skill = 1; skill <= 10; skill++) {
            Map<String, Object> c = Entries.configure("mad7-s" + skill, new LinkedHashMap<String, String>());
            String name = "xmage-mad7-fair-s" + skill;
            allDistinct &= name.equals(c.get("name")) && Json.num(c, "skill", 0) == skill
                    && Json.num(c, "effective_depth", 0) == Math.max(4, skill)
                    && !Entries.digest(frozen).equals(Entries.digest(c));
            skillNames.add(name + " " + Entries.version(c));
        }
        Map<String, String> overrides = new LinkedHashMap<>();
        overrides.put("nodes", "100");
        Map<String, Object> changed = Entries.configure("mad7-s7", overrides);
        Map<String, String> skillOverride = new LinkedHashMap<>();
        skillOverride.put("skill", "3");
        Map<String, Object> shallower = Entries.configure("mad7-s7", skillOverride);
        boolean invalidSkillsRefused = true;
        for (String entry : Arrays.asList("mad7-s0", "mad7-s11", "mad7-s07")) {
            try {
                Entries.frozen(entry);
                invalidSkillsRefused = false;
            } catch (IllegalArgumentException expected) {
                // These are not the shipped skills and must not acquire a frozen identity.
            }
        }
        check("FRONT.identity.shipped_cp7_skills_are_labelled_fair_variants",
                allDistinct && invalidSkillsRefused && "xmage-mad7-fair-s7-custom".equals(changed.get("name"))
                        && Json.num(shallower, "effective_depth", 0) == 4
                        && !Entries.digest(changed).equals(Entries.digest(Entries.frozen("mad7-s7")))
                        && Entries.version(frozen).startsWith("0.3.0+"),
                Json.map("entries", skillNames, "invalid_skills_refused", invalidSkillsRefused,
                        "budget_override", changed.get("name"), "existing_h1", Entries.version(frozen)));
    }

    /** Second review, item 2: a combat world skipped as unsupported is a wrapper answer (declining), not a bot plan. */
    static void skippedCombat(File root) throws Exception {
        Agent a = new Agent(root, "skipped-combat", Json.map("skip", Arrays.asList("attack")));
        Map<String, Object> d = Json.map("seat_step", 80L, "acting_seat", "p0", "context", Json.map("kind", "choice"),
                "group", group(11, 0, 1), "observation", observation(new ArrayList<>()),
                "candidates", Arrays.asList(cand(0, Json.map("kind", "declare_attack", "attacker", ref("o-c"), "defender", Json.map("player", "p1"))),
                        cand(1, Json.map("kind", "declare_attack", "attacker", ref("o-c"), "defender", null))));
        Map<String, Object> r = a.choose(d, 20_000, 600_000);
        check("FRONT.skip.combat_is_wrapper", "wrapper".equals(r.get("tag")) && Long.valueOf(1).equals(r.get("candidate")),
                Json.map("tag", r.get("tag"), "path", r.get("path"), "candidate", r.get("candidate")));
        a.close();
    }

    /** Change 2: diagnostics after the decision stay inside the decision's clock. */
    static void clockDiagnostics(File root) throws Exception {
        Agent a = new Agent(root, "clock-diagnostics", Json.map("slow", Json.map("roundtrip", 5000L)), "roundtrip", "1");
        Map<String, Object> r = a.choose(priority(60, new ArrayList<>()), 4000, 600_000);
        check("FRONT.clock.diagnostics_share_clock", ((Number) r.get("ms")).longValue() <= 4000 && "bot".equals(r.get("tag")),
                Json.map("answer_ms", r.get("ms"), "limit_ms", 4000L, "path", r.get("path"), "tag", r.get("tag")));
        a.close();
    }
}
