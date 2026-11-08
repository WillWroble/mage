package spellbench.kit.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Action-scoped plans (design Section 6.1, with the addendum's change 5). An action is identified by the seat_step of
 * the priority decision that started it and the chosen candidate's canonical semantic. The plan holds logical
 * answers, one per XMage dialog of the action, keyed by (dialog family, slot or purpose, occurrence); each wire
 * decision of the action consumes the next value of its logical answer, forced single-candidate steps included.
 * <p>
 * Logical-dialog matching: a decision continues the current logical dialog while its (source, family, slot or
 * purpose) equals the previous decision's, no {@code finish_*} was chosen in between, and the previous decision was
 * not the last substep of a fixed group; otherwise it opens the next occurrence (the addendum's fix: two consecutive
 * fixed groups with the same tuple are two logical dialogs).
 */
public final class PlanBook {

    public static final class Plan {
        public final long seatStep;
        public final String semanticKey;
        public final String kind;
        public final Map<String, Object> pickedSource;
        public final Set<String> pickStackIds;
        public final Map<String, Object> payload;
        public final List<Map<String, Object>> answers;
        public String boundStack;
        public boolean bindingTried;
        public String bindingOutcome;
        /** The world's cast method when the offered cast named none (Section 7.4); answers choose_cast_method. */
        public String castMethod;
        String curKey;
        String curSource;
        boolean closeCurrent;
        int cursor;
        final Map<String, Integer> occurrences = new LinkedHashMap<>();
        final Map<String, Integer> answerCursor = new LinkedHashMap<>();
        public int consumed;
        public int forced;
        public int unplanned;

        Plan(long seatStep, String semanticKey, String kind, Map<String, Object> pickedSource, Set<String> pickStackIds,
             Map<String, Object> payload, List<Map<String, Object>> answers) {
            this.seatStep = seatStep;
            this.semanticKey = semanticKey;
            this.kind = kind;
            this.pickedSource = pickedSource;
            this.pickStackIds = pickStackIds;
            this.payload = payload == null ? new LinkedHashMap<String, Object>() : payload;
            this.answers = answers == null ? new ArrayList<Map<String, Object>>() : answers;
        }

        public String actionId() {
            return seatStep + "|" + semanticKey;
        }
    }

    public Plan active;
    /** Bookkeeping counters for the evidence. */
    public final Map<String, Long> counters = new LinkedHashMap<>();

    private void count(String k) {
        counters.merge(k, 1L, Long::sum);
    }

    /** A priority pick of a non-pass action opens its plan. */
    public void open(long seatStep, Map<String, Object> semantic, Map<String, Object> observation,
                     Map<String, Object> payload, List<Map<String, Object>> answers) {
        Set<String> stackIds = new LinkedHashSet<>();
        for (Object o : Json.arr(observation, "stack")) {
            stackIds.add(Json.str(Json.obj(o), "object_id"));
        }
        active = new Plan(seatStep, Aggregate.key(semantic), Json.str(semantic, "kind"), Json.obj(semantic, "source"),
                stackIds, payload, answers);
        count("plans_opened");
    }

    public void close() {
        active = null;
    }

    /** Rewind (Section 5.1): everything since the abandoned action is rolled back. */
    public void rollback() {
        if (active != null) {
            count("plans_rolled_back");
        }
        active = null;
    }

    /**
     * Stack-source rebinding (N3), at the first decision after the pick: the stack entries new since the pick, with
     * the viewer as controller, the kind of the action, and the picked source's name; a non-null entry source must
     * be the picked object. Exactly one binds; zero or several bind nothing.
     */
    void rebind(Plan p, Map<String, Object> observation) {
        p.bindingTried = true;
        String viewer = Json.str(observation, "viewer");
        String wantKind = "cast_spell".equals(p.kind) ? "spell" : "activate_ability".equals(p.kind) ? "activated_ability" : null;
        if (wantKind == null || p.pickedSource == null) {
            p.bindingOutcome = "no_stack";
            return;
        }
        List<String> found = new ArrayList<>();
        for (Object o : Json.arr(observation, "stack")) {
            Map<String, Object> e = Json.obj(o);
            String id = Json.str(e, "object_id");
            if (p.pickStackIds.contains(id) || !viewer.equals(Json.str(e, "controller_seat"))
                    || !wantKind.equals(Json.str(e, "stack_kind"))) {
                continue;
            }
            String name = Json.str(e, "card_name");
            if (name == null || !name.equals(Json.str(p.pickedSource, "card_name"))) {
                continue;
            }
            Map<String, Object> src = Json.obj(e, "source");
            if (src != null && !Json.str(p.pickedSource, "object_id").equals(Json.str(src, "object_id"))) {
                continue;
            }
            found.add(id);
        }
        if (found.size() == 1) {
            p.boundStack = found.get(0);
            p.bindingOutcome = "bound";
            count("rebind_bound");
        } else {
            p.bindingOutcome = found.isEmpty() ? "none" : "ambiguous";
            count("rebind_" + p.bindingOutcome);
        }
    }

    static String sourceId(Map<String, Object> decision) {
        Map<String, Object> src = sourceRef(decision);
        return src == null ? null : Json.str(src, "object_id");
    }

    static Map<String, Object> sourceRef(Map<String, Object> decision) {
        Map<String, Object> ctx = Json.obj(decision, "context");
        Map<String, Object> src = ctx == null ? null : Json.obj(ctx, "source");
        if (src == null) {
            for (Object c : Json.arr(decision, "candidates")) {
                Map<String, Object> sem = Json.obj(Json.obj(c), "semantic");
                Object s = sem.get("source");
                if (s instanceof Map) {
                    src = Json.obj(s);
                    break;
                }
            }
        }
        return src;
    }

    /**
     * A land play's own dialog (a Gate's as-enters color): the land is a new object once it moves (CR 400.7), so
     * the source is the viewer's object with the played card's name rather than the picked object id.
     */
    static boolean playedLand(Plan p, Map<String, Object> decision) {
        Map<String, Object> src = sourceRef(decision);
        Map<String, Object> obs = Json.obj(decision, "observation");
        return "play_land".equals(p.kind) && p.pickedSource != null && src != null && obs != null
                && Json.str(p.pickedSource, "card_name") != null
                && Json.str(p.pickedSource, "card_name").equals(Json.str(src, "card_name"))
                && Json.str(obs, "viewer") != null && Json.str(obs, "viewer").equals(Json.str(src, "controller_seat"));
    }

    /** The dialog family and slot or purpose of a choice decision (Section 6.2), or null for an unplanned kind. */
    static String family(Map<String, Object> decision) {
        for (Object c : Json.arr(decision, "candidates")) {
            Map<String, Object> sem = Json.obj(Json.obj(c), "semantic");
            String kind = Json.str(sem, "kind");
            switch (kind) {
                case "choose_target":
                case "finish_target_selection":
                    return "target:" + Json.num(sem, "slot", 0);
                case "choose_spell_mode":
                    return "modes";
                case "finish_selection":
                    if ("modes".equals(Json.str(sem, "purpose"))) {
                        return "modes";
                    }
                    continue;
                case "choose_number":
                    return "number:" + Json.str(sem, "purpose");
                case "choose_cost_target":
                    return "cost_target";
                case "optional_cost":
                    return "use";
                case "choose_cast_method":
                    return "cast_method";
                case "choose_color":
                    return "color";
                case "select_object":
                    return "select:" + Json.str(sem, "purpose");
                default:
                    break;
            }
        }
        return null;
    }

    /**
     * The plan's answer to a choice decision: a candidate id, {@code -1} when the plan has no value for it (the
     * caller answers by fallback, tagged wrapper), {@code -2} when the decision is not the plan's (the plan ends), or
     * {@code -3} when the plan has no color for a color decision (the current-dialog path answers; the plan stays).
     */
    public int claim(Map<String, Object> decision) {
        Plan p = active;
        if (p == null) {
            return -2;
        }
        Map<String, Object> ctx = Json.obj(decision, "context");
        if (ctx != null && "priority".equals(Json.str(ctx, "kind"))) {
            close();
            return -2;
        }
        Map<String, Object> obs = Json.obj(decision, "observation");
        if (!p.bindingTried) {
            rebind(p, obs);
        }
        String src = sourceId(decision);
        String picked = p.pickedSource == null ? null : Json.str(p.pickedSource, "object_id");
        if (src != null && !(src.equals(p.boundStack) || src.equals(picked)) && playedLand(p, decision)) {
            count("land_source_by_name");
        } else if (src == null || !(src.equals(p.boundStack) || src.equals(picked))) {
            count("plan_ended_foreign_source");
            close();
            return -2;
        }
        String fam = family(decision);
        if (fam == null) {
            count("plan_unplanned_kind");
            p.unplanned++;
            return -1;
        }
        Map<String, Object> group = Json.obj(decision, "group");
        long sub = group == null ? 0 : Json.num(group, "substep_index", 0);
        long count = group == null ? 1 : Json.num(group, "substep_count", 1);
        if (p.curKey == null || !fam.equals(p.curKey) || !src.equals(p.curSource) || p.closeCurrent
                || (count > 1 && sub == 0)) {
            p.occurrences.merge(fam, 1, Integer::sum);
            p.curKey = fam;
            p.curSource = src;
            p.cursor = 0;
            p.closeCurrent = false;
        }
        int occurrence = p.occurrences.get(fam) - 1;
        List<Object> values = occurrence == 0 ? values(p, fam) : new ArrayList<>();
        if ("color".equals(fam) && p.cursor >= values.size()) {
            // no planned color (an effect the world did not reach): the current-dialog path answers, plan kept
            count("plan_color_deferred");
            return -3;
        }
        int chosen = match(decision, fam, values, p.cursor);
        p.cursor++;
        if (count > 1 && sub == count - 1) {
            p.closeCurrent = true; // a fixed group's last substep completes its logical dialog
        }
        if (chosen >= 0) {
            Map<String, Object> sem = Json.obj(Json.obj(Json.arr(decision, "candidates").get(chosen)), "semantic");
            if (Json.str(sem, "kind").startsWith("finish_")) {
                p.closeCurrent = true;
            }
            // a pick that reaches the maximum completes the logical dialog even without a group (implied completion)
            if (sem.get("maximum") instanceof Number && sem.get("selected_count") instanceof Number
                    && Json.num(sem, "selected_count", 0) + 1 >= Json.num(sem, "maximum", 0)) {
                p.closeCurrent = true;
            }
            p.consumed++;
        } else {
            p.unplanned++;
        }
        return chosen;
    }

    /** A single-candidate step under the plan: it advances the cursor of its logical dialog (forced steps). */
    public void forced(Map<String, Object> decision) {
        Plan p = active;
        if (p == null) {
            return;
        }
        int r = claim(decision);
        if (active != null) {
            active.forced++;
            if (r == -1) {
                active.unplanned--; // forced: not a missing plan value
            }
        }
    }

    @SuppressWarnings("unchecked")
    static List<Object> values(Plan p, String fam) {
        List<Object> out = new ArrayList<>();
        if (fam.startsWith("target:")) {
            int slot = Integer.parseInt(fam.substring(7));
            List<Object> slots = Json.arr(p.payload, "targets");
            if (slot < slots.size()) {
                out.addAll(Json.arr(slots.get(slot)));
            } else {
                // no executed object (an activation that failed in the world): the recorded live target dialogs
                int k = 0;
                for (Map<String, Object> a : p.answers) {
                    if ("target".equals(a.get("family")) && k++ == slot) {
                        out.addAll(Json.arr(a.get("value")));
                    }
                }
            }
        } else if (fam.equals("modes")) {
            out.addAll(Json.arr(p.payload, "modes"));
        } else if (fam.equals("number:x_value")) {
            if (p.payload.get("x") != null) {
                out.add(p.payload.get("x"));
            }
        } else if (fam.equals("cost_target")) {
            out.addAll(Json.arr(p.payload, "cost_targets"));
        } else if (fam.equals("use")) {
            for (Map<String, Object> a : p.answers) {
                if ("use".equals(a.get("family"))) {
                    out.add(a.get("value"));
                }
            }
        } else if (fam.equals("cast_method")) {
            if (p.castMethod != null) {
                out.add(p.castMethod);
            }
        } else if (fam.equals("color")) {
            // the search's colors (the MCTS payload) else the world's recorded color choices (MAD answers)
            if (p.payload.get("colors") != null) {
                out.addAll(Json.arr(p.payload, "colors"));
            } else {
                for (Map<String, Object> a : p.answers) {
                    if ("choice".equals(a.get("family")) && colorName(a.get("value")) != null) {
                        out.add(colorName(a.get("value")));
                    }
                }
            }
        } else if (fam.startsWith("select:")) {
            for (Map<String, Object> a : p.answers) {
                if ("select".equals(a.get("family"))) {
                    out.addAll(Json.arr(a.get("value")));
                }
            }
        }
        return out;
    }

    /** The candidate whose value equals the plan's value at the cursor; a finish candidate once the values run out. */
    static int match(Map<String, Object> decision, String fam, List<Object> values, int cursor) {
        List<Object> cands = Json.arr(decision, "candidates");
        if (cursor >= values.size()) {
            for (int i = 0; i < cands.size(); i++) {
                Map<String, Object> sem = Json.obj(Json.obj(cands.get(i)), "semantic");
                if (Json.str(sem, "kind").startsWith("finish_")) {
                    return i;
                }
            }
            return -1;
        }
        Object want = values.get(cursor);
        for (int i = 0; i < cands.size(); i++) {
            Map<String, Object> sem = Json.obj(Json.obj(cands.get(i)), "semantic");
            String kind = Json.str(sem, "kind");
            switch (kind) {
                case "choose_target":
                    if (sameTarget(Json.obj(sem, "target"), want)) {
                        return i;
                    }
                    break;
                case "choose_cost_target":
                    if (want instanceof Map && Json.str(Json.obj(sem, "candidate"), "object_id")
                            .equals(Json.str(Json.obj(want), "object_id"))) {
                        return i;
                    }
                    break;
                case "select_object":
                    if (sameTarget(Json.obj(sem, "choice"), want)) {
                        return i;
                    }
                    break;
                case "choose_spell_mode":
                    if (want instanceof Number && Json.num(sem, "mode_index", -1) == ((Number) want).longValue()) {
                        return i;
                    }
                    break;
                case "choose_number":
                    if (want instanceof Number && Json.num(sem, "value", Long.MIN_VALUE) == ((Number) want).longValue()) {
                        return i;
                    }
                    break;
                case "optional_cost":
                    if (want instanceof Boolean && want.equals(sem.get("pay"))) {
                        return i;
                    }
                    break;
                case "choose_cast_method":
                    if (want instanceof String && want.equals(sem.get("method"))) {
                        return i;
                    }
                    break;
                case "choose_color":
                    if (want instanceof String && want.equals(sem.get("color"))) {
                        return i;
                    }
                    break;
                default:
                    break;
            }
        }
        return -1;
    }

    static final java.util.List<String> COLORS = java.util.Arrays.asList("white", "blue", "black", "red", "green");

    /** A recorded choice as a protocol color name (Section 6.10), or null when it names no color. */
    public static String colorName(Object value) {
        if (!(value instanceof String)) {
            return null;
        }
        String c = ((String) value).toLowerCase(java.util.Locale.ROOT);
        return COLORS.contains(c) ? c : null;
    }

    /** A candidate target reference against a plan value ({"player": seat} or {"object_id": id}). */
    static boolean sameTarget(Map<String, Object> target, Object want) {
        if (target == null || !(want instanceof Map)) {
            return false;
        }
        Map<String, Object> w = Json.obj(want);
        if (target.get("player") != null) {
            return target.get("player").equals(w.get("player"));
        }
        Map<String, Object> o = Json.obj(target, "object");
        return o != null && w.get("object_id") != null && w.get("object_id").equals(o.get("object_id"));
    }
}
