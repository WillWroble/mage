package spellbench.kit.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * The vertical slice's XMage-free cases (design Section 9.1): S8 (keys and aggregation), the sampler accounting
 * fixture of the addendum's change 7 (an unknown face-down battlefield card), the logical-dialog fixes of change 5
 * (two consecutive fixed groups with the same tuple; finish candidates), and stack-source rebinding (N3) on
 * synthetic observations. Prints one JSON line per check and a verdict.
 *
 *   java -cp kit-core.jar spellbench.kit.core.SliceCore
 */
public final class SliceCore {

    private static final List<Map<String, Object>> results = new ArrayList<>();

    private SliceCore() {
    }

    static void check(String id, boolean pass, Object detail) {
        Map<String, Object> m = Json.map("check", id, "pass", pass, "detail", detail);
        results.add(m);
        System.out.println(Json.canonical(m));
    }

    static Map<String, Object> ref(String id, String name, String seat, String zone) {
        return Json.map("object_id", id, "card_name", name, "owner_seat", seat, "controller_seat", seat, "zone", zone);
    }

    public static void main(String[] args) {
        s8Keys();
        s8Aggregation();
        samplerFaceDown();
        samplerPins();
        logicalDialogs();
        rebinding();
        methodNullCasts();
        kernelSmokeFixes();
        clockPacing();
        gateColors();
        boolean all = true;
        for (Map<String, Object> r : results) {
            all &= Boolean.TRUE.equals(r.get("pass"));
        }
        System.out.println(Json.canonical(Json.map("verdict", all ? "PASS" : "FAIL", "checks", (long) results.size())));
    }

    // ------------------------------------------------------------------ S8 keys

    static void s8Keys() {
        Map<String, Object> land = ref("o-1", "Rockface Village", "p0", "battlefield");
        Map<String, Object> manaR = Json.map("kind", "activate_mana_ability", "source", land, "ability_index", 1L,
                "mana_choice", "R", "cost_target", null);
        Map<String, Object> manaG = Json.map("kind", "activate_mana_ability", "source", land, "ability_index", 1L,
                "mana_choice", "G", "cost_target", null);
        boolean oldCollide = Aggregate.oldKey(manaR).equals(Aggregate.oldKey(manaG));
        boolean newDistinct = !Aggregate.key(manaR).equals(Aggregate.key(manaG));
        check("S8.keys.mana_choice", oldCollide && newDistinct,
                Json.map("old_keys_collide", oldCollide, "full_semantic_keys_distinct", newDistinct));
        Map<String, Object> src = ref("o-2", "Youthful Valkyrie", "p0", "battlefield");
        Map<String, Object> t0 = Json.map("kind", "order_pick", "source", null, "purpose", "triggers", "position", 0L, "count", 2L,
                "item", Json.map("trigger", Json.map("source", src, "source_name", "Youthful Valkyrie", "ability_index", 0L,
                        "event_objects", new ArrayList<>(), "instance", 0L, "label", null)));
        Map<String, Object> t1 = Json.map("kind", "order_pick", "source", null, "purpose", "triggers", "position", 0L, "count", 2L,
                "item", Json.map("trigger", Json.map("source", src, "source_name", "Youthful Valkyrie", "ability_index", 0L,
                        "event_objects", new ArrayList<>(), "instance", 1L, "label", null)));
        // the revision-2 trigger key named source and ability_index only
        String old0 = Json.canonical(Json.map("source", src, "ability_index", 0L));
        String old1 = Json.canonical(Json.map("source", src, "ability_index", 0L));
        String k0 = Json.canonical(Json.obj(t0, "item"));
        String k1 = Json.canonical(Json.obj(t1, "item"));
        check("S8.keys.trigger_instances", old0.equals(old1) && !k0.equals(k1),
                Json.map("old_keys_collide", old0.equals(old1), "full_item_keys_distinct", !k0.equals(k1)));
    }

    // ------------------------------------------------------------------ S8 aggregation table: A 45 + 45, B 55, C 55

    static void s8Aggregation() {
        Map<String, Object> a = Json.map("kind", "cast_spell", "source", ref("o-a", "A", "p0", "hand"), "method", "normal");
        Map<String, Object> b = Json.map("kind", "cast_spell", "source", ref("o-b", "B", "p0", "hand"), "method", "normal");
        Map<String, Object> c = Json.map("kind", "cast_spell", "source", ref("o-c", "C", "p0", "hand"), "method", "normal");
        String ka = Aggregate.key(a);
        String kb = Aggregate.key(b);
        String kc = Aggregate.key(c);
        Map<String, Integer> cand = new LinkedHashMap<>();
        cand.put(Aggregate.key(Json.map("kind", "pass")), 0);
        cand.put(ka, 1);
        cand.put(kb, 2);
        cand.put(kc, 3);
        List<Aggregate.WorldVote> votes = new ArrayList<>();
        String[] best = {ka, ka, kb, kc};
        long[] score = {45, 45, 55, 55};
        for (int w = 0; w < 4; w++) {
            Aggregate.WorldVote v = new Aggregate.WorldVote(w, best[w], Json.map("targets", Arrays.asList(), "world", (long) w));
            v.exact.put(best[w], score[w]);
            v.ranked.add(best[w]);
            votes.add(v);
        }
        Aggregate.Result r = Aggregate.vote(votes, cand);
        boolean ok = ka.equals(r.winner) && r.planWorld == 0 && r.planPayload != null
                && Long.valueOf(0).equals(r.planPayload.get("world"));
        // the revision-1 rule (best single score) would pick B or C
        check("S8.aggregation.vote", ok, Json.map("winner_candidate", cand.get(r.winner), "plan_world", (long) r.planWorld,
                "votes", r.votes.values().toString(), "exact_sum_A", r.exactSum.get(ka)));
        // ties by summed exact scores: one vote each, A 40 + B 60 -> B
        List<Aggregate.WorldVote> tie = new ArrayList<>();
        Aggregate.WorldVote v0 = new Aggregate.WorldVote(0, ka, null);
        v0.exact.put(ka, 40L);
        Aggregate.WorldVote v1 = new Aggregate.WorldVote(1, kb, null);
        v1.exact.put(kb, 60L);
        tie.add(v0);
        tie.add(v1);
        Aggregate.Result rt = Aggregate.vote(tie, cand);
        check("S8.aggregation.tie_by_exact_sum", kb.equals(rt.winner) && rt.planWorld == 1,
                Json.map("winner_candidate", cand.get(rt.winner), "plan_world", (long) rt.planWorld));
        // H3: visits summed across worlds; the winner need not be any world's own best
        List<Map<String, Object>> worlds = new ArrayList<>();
        worlds.add(Json.map("index", 0L, "root_stats", Arrays.asList(
                Json.map("semantic", a, "visits", 40L, "wins", 10L, "payload", Json.map("w", 0L)),
                Json.map("semantic", b, "visits", 60L, "wins", 30L, "payload", Json.map("w", 0L)))));
        worlds.add(Json.map("index", 1L, "root_stats", Arrays.asList(
                Json.map("semantic", a, "visits", 70L, "wins", 20L, "payload", Json.map("w", 1L)),
                Json.map("semantic", b, "visits", 30L, "wins", 5L, "payload", Json.map("w", 1L)))));
        Aggregate.Result rv = Aggregate.visits(worlds, cand);
        check("S8.aggregation.mcts_visits", ka.equals(rv.winner) && rv.planWorld == 1
                        && Long.valueOf(1).equals(rv.planPayload.get("w")),
                Json.map("winner_candidate", cand.get(rv.winner), "plan_world", (long) rv.planWorld));
    }

    // ------------------------------------------------------------------ sampler accounting (addendum change 7)

    static Map<String, Object> deck(String name, Object... rows) {
        List<Object> list = new ArrayList<>();
        for (int i = 0; i + 1 < rows.length; i += 2) {
            list.add(Json.map("name", rows[i], "count", ((Number) rows[i + 1]).longValue()));
        }
        return Json.map("name", name, "decklist", list);
    }

    static Map<String, Object> rec(String id, String name, String owner, String zone, boolean faceDown) {
        Map<String, Object> r = ref(id, name, owner, zone);
        r.put("face_down", faceDown);
        r.put("token", false);
        r.put("copy", false);
        return r;
    }

    static Map<String, Object> player(String seat, long hand, long library, List<Object> handRecs, List<Object> bf,
                                      List<Object> gy) {
        return Json.map("seat", seat, "hand_count", hand, "library_count", library, "hand", handRecs,
                "battlefield", bf, "graveyard", gy, "exile", new ArrayList<>(), "command", new ArrayList<>());
    }

    static void samplerFaceDown() {
        Map<String, Object> gs = Json.map("own_deck", deck("own", "Forest", 20L),
                "opponent_deck", deck("opp", "Island", 10L, "Ainok Survivalist", 2L, "Mountain", 8L),
                "rules", Json.map("opponent_decklist", "visible"));
        List<Object> oppBf = new ArrayList<>();
        oppBf.add(rec("o-fd", null, "p1", "battlefield", true));          // face-down: identity hidden
        oppBf.add(rec("o-i1", "Island", "p1", "battlefield", false));
        List<Object> oppGy = new ArrayList<>();
        oppGy.add(rec("o-m1", "Mountain", "p1", "graveyard", false));
        Map<String, Object> obs = Json.map("viewer", "p0", "players", Arrays.asList(
                player("p0", 0, 20, new ArrayList<>(), new ArrayList<>(), new ArrayList<>()),
                player("p1", 5, 12, null, oppBf, oppGy)), "stack", new ArrayList<>(), "known", new ArrayList<>());
        Sampler.Sample s = Sampler.sample(gs, obs, new Random(1));
        Sampler.SeatSample p1 = s.seats.get("p1");
        int u = 5 + 12 + 1;
        int publicCards = 2;
        boolean disjoint = p1.unknownSlots == u && p1.publicCards == publicCards && p1.poolSize == 20 - publicCards
                && p1.deficit == 0 && p1.surplus == 0 && p1.faceDown.containsKey("o-fd") && p1.faceDown.get("o-fd") != null;
        // physical total = U + pinned + public = 18 + 0 + 2 = 20 (the face-down card counted once, as a slot)
        check("addendum7.sampler.face_down_disjoint", disjoint,
                Json.map("unknown_slots", (long) p1.unknownSlots, "public_cards", (long) p1.publicCards,
                        "pool", (long) p1.poolSize, "deficit", (long) p1.deficit, "surplus", (long) p1.surplus,
                        "physical_total", (long) (p1.unknownSlots + p1.pinned + p1.publicCards), "flags", s.flags));
        // multiset: the sampled hidden cards plus the public ones are exactly the list
        Map<String, Integer> all = new LinkedHashMap<>();
        for (Sampler.Slot sl : p1.hand) {
            all.merge(sl.name, 1, Integer::sum);
        }
        for (Sampler.Slot sl : p1.library) {
            all.merge(sl.name, 1, Integer::sum);
        }
        all.merge(p1.faceDown.get("o-fd"), 1, Integer::sum);
        all.merge("Island", 1, Integer::sum);
        all.merge("Mountain", 1, Integer::sum);
        boolean exact = all.getOrDefault("Island", 0) == 10 && all.getOrDefault("Ainok Survivalist", 0) == 2
                && all.getOrDefault("Mountain", 0) == 8 && all.size() == 3;
        check("addendum7.sampler.multiset_exact", exact, all);
    }

    static void samplerPins() {
        Map<String, Object> gs = Json.map("own_deck", deck("own", "Forest", 10L, "Llanowar Elves", 4L, "Plains", 6L),
                "opponent_deck", deck("opp", "Island", 18L, "Refute", 2L), "rules", Json.map("opponent_decklist", "visible"));
        List<Object> known = new ArrayList<>();
        known.add(Json.map("owner_seat", "p0", "zone", "library", "card_name", "Llanowar Elves", "object_id", null,
                "position_from_top", 0L, "position_from_bottom", null, "how", "looked_at"));
        known.add(Json.map("owner_seat", "p1", "zone", "hand", "card_name", "Refute", "object_id", "o-look",
                "position_from_top", null, "position_from_bottom", null, "how", "revealed"));
        Map<String, Object> obs = Json.map("viewer", "p0", "players", Arrays.asList(
                player("p0", 0, 20, new ArrayList<>(), new ArrayList<>(), new ArrayList<>()),
                player("p1", 2, 18, null, new ArrayList<>(), new ArrayList<>())), "stack", new ArrayList<>(), "known", known);
        boolean ok = true;
        for (int seed = 0; seed < 20; seed++) {
            Sampler.Sample s = Sampler.sample(gs, obs, new Random(seed));
            ok &= "Llanowar Elves".equals(s.seats.get("p0").library.get(0).name);
            ok &= "Refute".equals(s.seats.get("p1").hand.get(0).name) && "o-look".equals(s.seats.get("p1").hand.get(0).objectId);
            int refutes = 0;
            for (Sampler.Slot sl : s.seats.get("p1").hand) {
                refutes += "Refute".equals(sl.name) ? 1 : 0;
            }
            for (Sampler.Slot sl : s.seats.get("p1").library) {
                refutes += "Refute".equals(sl.name) ? 1 : 0;
            }
            ok &= refutes == 2;
        }
        check("K1.sampler.pins_honored_20_seeds", ok, null);
    }

    // ------------------------------------------------------------------ logical dialogs (addendum change 5)

    static Map<String, Object> targetDecision(long step, String src, long slot, long sub, long count, String... targets) {
        List<Object> cands = new ArrayList<>();
        for (int i = 0; i < targets.length; i++) {
            Map<String, Object> sem = Json.map("kind", "choose_target", "source", ref(src, "Spell", "p0", "stack"), "slot", slot,
                    "target", Json.map("object", ref(targets[i], "X", "p1", "battlefield")), "selected_count", sub,
                    "minimum", count, "maximum", count);
            cands.add(Json.map("candidate_id", (long) i, "semantic", sem, "display_text", null));
        }
        return Json.map("seat_step", step, "group", Json.map("group_id", step, "substep_index", sub, "substep_count", count),
                "context", Json.map("kind", "choice", "source", ref(src, "Spell", "p0", "stack"), "purpose", null),
                "observation", Json.map("viewer", "p0", "stack", Arrays.asList(Json.map("object_id", src,
                        "card_name", "Spell", "controller_seat", "p0", "stack_kind", "spell", "source", null))),
                "candidates", cands);
    }

    static void logicalDialogs() {
        PlanBook pb = new PlanBook();
        Map<String, Object> payload = Json.map("targets", Arrays.asList(
                Arrays.asList(Json.map("object_id", "o-x"), Json.map("object_id", "o-y")),
                Arrays.asList(Json.map("object_id", "o-z"))));
        Map<String, Object> sem = Json.map("kind", "cast_spell", "source", ref("o-card", "Spell", "p0", "hand"), "method", "normal");
        pb.open(10, sem, Json.map("viewer", "p0", "stack", new ArrayList<>()), payload, null);
        int a = pb.claim(targetDecision(11, "o-s", 0, 0, 2, "o-w", "o-x", "o-y"));
        int b = pb.claim(targetDecision(12, "o-s", 0, 1, 2, "o-w", "o-y"));
        int c = pb.claim(targetDecision(13, "o-s", 1, 0, 1, "o-z", "o-x"));
        boolean bound = "o-s".equals(pb.active.boundStack);
        check("addendum5.logical_dialogs.fixed_groups", a == 1 && b == 1 && c == 0 && bound,
                Json.map("answers", Arrays.asList((long) a, (long) b, (long) c), "bound", pb.active.boundStack));
        // a second fixed group with the same tuple after a completed one opens a new occurrence (no planned value)
        int d = pb.claim(targetDecision(14, "o-s", 1, 0, 1, "o-z", "o-x"));
        check("addendum5.logical_dialogs.second_occurrence", d == -1, Json.map("answer", (long) d));
    }

    // ------------------------------------------------------------------ rebinding (N3)

    static void rebinding() {
        PlanBook pb = new PlanBook();
        Map<String, Object> hart = ref("o-hart", "Burnished Hart", "p0", "battlefield");
        Map<String, Object> sem = Json.map("kind", "activate_ability", "source", hart, "ability_index", 0L);
        pb.open(20, sem, Json.map("viewer", "p0", "stack", new ArrayList<>()), Json.map("targets", new ArrayList<>()), null);
        Map<String, Object> entry = Json.map("object_id", "o-ab", "card_name", "Burnished Hart", "controller_seat", "p0",
                "stack_kind", "activated_ability", "source", null);
        Map<String, Object> d = Json.map("seat_step", 21L, "context", Json.map("kind", "choice", "source",
                        ref("o-ab", "Burnished Hart", "p0", "stack"), "purpose", "search"),
                "observation", Json.map("viewer", "p0", "stack", Arrays.asList(entry)),
                "candidates", Arrays.asList(Json.map("candidate_id", 0L, "semantic",
                        Json.map("kind", "finish_selection", "source", ref("o-ab", "Burnished Hart", "p0", "stack"),
                                "purpose", "search", "selected_count", 0L))));
        pb.claim(d);
        check("S2.rebinding.departed_source", pb.active != null && "o-ab".equals(pb.active.boundStack),
                Json.map("outcome", pb.active == null ? "closed" : pb.active.bindingOutcome));
        // ambiguity: two new entries of that name bind nothing
        PlanBook pb2 = new PlanBook();
        pb2.open(20, sem, Json.map("viewer", "p0", "stack", new ArrayList<>()), Json.map("targets", new ArrayList<>()), null);
        Map<String, Object> entry2 = new LinkedHashMap<>(entry);
        entry2.put("object_id", "o-ab2");
        Map<String, Object> d2 = new LinkedHashMap<>(d);
        d2.put("observation", Json.map("viewer", "p0", "stack", Arrays.asList(entry, entry2)));
        pb2.claim(d2);
        check("S2.rebinding.ambiguous_binds_nothing", pb2.counters.containsKey("rebind_ambiguous"), pb2.counters);
    }

    // ------------------------------------------------------------------ method-null casts (Section 7.4)

    static void methodNullCasts() {
        Map<String, Object> dart = ref("o-dart", "Lava Dart", "p0", "graveyard");
        Map<String, Object> bolt = ref("o-bolt", "Lightning Bolt", "p0", "hand");
        Map<String, Object> offeredDart = Json.map("kind", "cast_spell", "source", dart, "method", null);
        Map<String, Object> offeredBolt = Json.map("kind", "cast_spell", "source", bolt, "method", "normal");
        Map<String, Object> worldDart = Json.map("kind", "cast_spell", "source", dart, "method", "flashback");
        Map<String, Object> worldBoltFlash = Json.map("kind", "cast_spell", "source", bolt, "method", "flashback");
        List<Object> cands = Arrays.asList(
                Json.map("candidate_id", 0L, "semantic", Json.map("kind", "pass"), "display_text", null),
                Json.map("candidate_id", 1L, "semantic", offeredDart, "display_text", null),
                Json.map("candidate_id", 2L, "semantic", offeredBolt, "display_text", null));
        boolean answers = Offers.answers(offeredDart, worldDart) && !Offers.answers(offeredBolt, worldBoltFlash)
                && !Offers.answers(offeredDart, Json.map("kind", "cast_spell", "source", bolt, "method", "normal"))
                && Offers.candidateFor(cands, worldDart) == 1 && Offers.candidateFor(cands, offeredBolt) == 2
                && Offers.candidateFor(cands, worldBoltFlash) == -1;
        check("kernel.method_null.answers", answers, null);

        // the world's flashback key reaches the method-null candidate through the alias, and the plan keeps the method
        Map<String, Object> d = Json.map("candidates", cands);
        Map<String, Integer> candidateOf = Front.candidateKeys(d);
        List<Object> results = Arrays.asList(Json.map("index", 0L, "semantic", worldDart, "root_stats", Arrays.asList(
                Json.map("semantic", worldDart, "bound", "exact", "adjusted", 5L),
                Json.map("semantic", Json.map("kind", "pass"), "bound", "exact", "adjusted", 1L))));
        Front.aliasWorldKeys(cands, results, candidateOf);
        List<Aggregate.WorldVote> votes = Arrays.asList(Aggregate.fromRunner(Json.obj(results.get(0))));
        Aggregate.Result r = Aggregate.vote(votes, candidateOf);
        Integer chosen = candidateOf.get(r.winner);
        String method = Offers.worldMethod(r.winner);
        check("kernel.method_null.vote", chosen != null && chosen == 1 && "flashback".equals(method),
                Json.map("candidate", chosen == null ? null : (long) chosen, "method", method));

        PlanBook pb = new PlanBook();
        pb.open(30, offeredDart, Json.map("viewer", "p0", "stack", new ArrayList<>()), Json.map("targets", new ArrayList<>()), null);
        pb.active.castMethod = method;
        List<Object> methods = new ArrayList<>();
        for (String m : new String[]{"normal", "flashback"}) {
            methods.add(Json.map("candidate_id", (long) methods.size(), "semantic",
                    Json.map("kind", "choose_cast_method", "source", dart, "method", m), "display_text", null));
        }
        Map<String, Object> md = Json.map("seat_step", 31L, "context", Json.map("kind", "choice", "source", dart, "purpose", null),
                "observation", Json.map("viewer", "p0", "stack", new ArrayList<>()), "candidates", methods);
        int claimed = pb.claim(md);
        check("kernel.method_null.plan_answers_method", claimed == 1, Json.map("answer", (long) claimed));

        Map<String, Object> withExt = Json.map("seat_step", 1L, "extensions", Json.map("x_kernel_flat_v4", "payload"));
        Map<String, Object> stripped = Front.withoutExtensions(withExt);
        check("kernel.extensions_stripped", !stripped.containsKey("extensions") && withExt.containsKey("extensions")
                && Front.withoutExtensions(md) == md, null);
    }

    /** The two kit gaps the first pauper-kernel smoke games showed (CawGates' saga back face; priority mana stops). */
    static void kernelSmokeFixes() {
        // a transformed saga shows its back face; the list names its front face; nothing is surplus or negative
        Map<String, Object> gs = Json.map("own_deck", deck("own", "The Modern Age", 4L, "Island", 16L),
                "opponent_deck", deck("opp", "Island", 20L), "rules", Json.map("opponent_decklist", "visible"));
        Map<String, Object> glider = rec("o-vg", "Vector Glider", "p0", "battlefield", false);
        glider.put("full_name", "The Modern Age // Vector Glider");
        Map<String, Object> age = rec("o-ma", "The Modern Age", "p0", "battlefield", false);
        age.put("full_name", "The Modern Age // Vector Glider");
        Map<String, Object> obs = Json.map("viewer", "p0", "players", Arrays.asList(
                player("p0", 0, 18, new ArrayList<>(), new ArrayList<Object>(Arrays.asList(glider, age)), new ArrayList<>()),
                player("p1", 0, 20, null, new ArrayList<>(), new ArrayList<>())), "stack", new ArrayList<>(),
                "known", new ArrayList<>());
        Sampler.Sample sm = Sampler.sample(gs, obs, new Random(3));
        int ages = 0;
        for (Sampler.Slot sl : sm.seats.get("p0").library) {
            ages += "The Modern Age".equals(sl.name) ? 1 : 0;
        }
        java.util.TreeMap<String, Integer> list = new java.util.TreeMap<>();
        list.put("Fire // Ice", 1);
        check("kernel.back_face_counts_against_list", sm.flags.isEmpty() && ages == 2
                && "Fire // Ice".equals(Sampler.listName("Fire", "Fire // Ice", list))
                && "Vector Glider".equals(Sampler.listName("Vector Glider", null, list)),
                Json.map("flags", new ArrayList<Object>(sm.flags), "library_ages", (long) ages));

        // pass beside only mana activations needs no search; any other action does
        Map<String, Object> land = ref("o-land", "Island", "p0", "battlefield");
        Map<String, Object> mana = Json.map("kind", "activate_mana_ability", "source", land, "ability_index", 0L,
                "mana_choice", null, "cost_target", null);
        List<Object> manaOnly = Arrays.asList(
                Json.map("candidate_id", 0L, "semantic", mana, "display_text", null),
                Json.map("candidate_id", 1L, "semantic", Json.map("kind", "pass"), "display_text", null));
        List<Object> withCast = Arrays.asList(
                Json.map("candidate_id", 0L, "semantic", Json.map("kind", "pass"), "display_text", null),
                Json.map("candidate_id", 1L, "semantic", mana, "display_text", null),
                Json.map("candidate_id", 2L, "semantic", Json.map("kind", "cast_spell",
                        "source", ref("o-bolt", "Lightning Bolt", "p0", "hand"), "method", null), "display_text", null));
        check("kernel.mana_only_pass", Front.manaOnlyPass(manaOnly) == 1 && Front.manaOnlyPass(withCast) == -1
                && Front.manaOnlyPass(Arrays.asList(manaOnly.get(0))) == -1, null);
    }

    /** kit-mcts paces its searches by the bank; kit-mad-1 and kit-mad-k keep their frozen configuration. */
    static void clockPacing() {
        boolean math = Front.pacedLimit(90_000, 600_000, 2_000, 30, 12_000) == 22_000
                && Front.pacedLimit(90_000, 120_000, 2_000, 30, 12_000) == 12_000
                && Front.pacedLimit(9_000, 9_000, 2_000, 30, 12_000) == 9_000
                && Front.pacedLimit(90_000, 600_000, 2_000, 0, 12_000) == 90_000;
        Map<String, Object> h3 = Json.obj(Entries.frozen("h3"), "clock");
        boolean entries = Json.num(h3, "pace_moves", 0) == 20 && Json.num(h3, "pace_floor_ms", 0) == 12_000
                && !Json.obj(Entries.frozen("h1"), "clock").containsKey("pace_moves")
                && !Json.obj(Entries.frozen("h2"), "clock").containsKey("pace_moves");
        check("kernel.mcts_clock_pacing", math && entries, Json.map("h1", Entries.version(Entries.frozen("h1")),
                "h3", Entries.version(Entries.frozen("h3"))));
    }

    /** A land that asks a color as it enters: the plan carries the world's color and binds to the played land. */
    static void gateColors() {
        Map<String, Object> obs = Json.map("viewer", "p0", "stack", new ArrayList<>());
        Map<String, Object> play = Json.map("kind", "play_land", "source", ref("o-gate", "Sea Gate", "p0", "hand"), "face", 0L);
        Map<String, Object> onField = ref("o-gate2", "Sea Gate", "p0", "battlefield");
        List<Object> colors = new ArrayList<>();
        for (String c : Arrays.asList("white", "black", "red", "green")) {
            colors.add(Json.map("candidate_id", (long) colors.size(), "semantic",
                    Json.map("kind", "choose_color", "source", onField, "purpose", "effect", "color", c), "display_text", null));
        }
        Map<String, Object> d = Json.map("seat_step", 11L, "context", Json.map("kind", "choice", "source", onField),
                "observation", obs, "candidates", colors);
        PlanBook pb = new PlanBook();
        List<Map<String, Object>> none = new ArrayList<>();
        pb.open(10, play, obs, Json.map("colors", Arrays.asList("red")), none);
        int mcts = pb.claim(d);
        List<Map<String, Object>> mad = new ArrayList<>();
        mad.add(Json.map("family", "choice", "value", "Green"));
        pb.open(10, play, obs, new LinkedHashMap<String, Object>(), mad);
        int madPick = pb.claim(d);
        pb.open(10, play, obs, new LinkedHashMap<String, Object>(), none);
        int deferred = pb.claim(d);
        boolean kept = pb.active != null;
        Map<String, Object> other = Json.map("seat_step", 11L, "context", Json.map("kind", "choice", "source",
                ref("o-cit", "Citadel Gate", "p0", "battlefield")), "observation", obs, "candidates", colors);
        pb.open(10, play, obs, Json.map("colors", Arrays.asList("red")), none);
        int foreign = pb.claim(other);
        boolean only = Front.colorsOnly(Json.map("answers", Arrays.asList(Json.map("family", "choice", "value", "Blue"))))
                && !Front.colorsOnly(Json.map("answers", Arrays.asList(Json.map("family", "choice", "value", "Blue"),
                        Json.map("family", "use", "value", true))))
                && !Front.colorsOnly(Json.map("answers", Arrays.asList(Json.map("family", "choice", "value", "Option A"))))
                && !Front.colorsOnly(Json.map("answers", new ArrayList<>()));
        boolean pick = Front.matchesPick(Json.obj(Json.obj(colors.get(1)), "semantic"), "black")
                && !Front.matchesPick(Json.obj(Json.obj(colors.get(1)), "semantic"), "red");
        check("kernel.gate_color_plan", mcts == 2 && madPick == 3 && deferred == -3 && kept && foreign == -2 && only && pick,
                Json.map("mcts", (long) mcts, "mad", (long) madPick, "deferred", (long) deferred, "kept", kept,
                        "foreign", (long) foreign, "colors_only", only, "pick", pick));
    }
}
