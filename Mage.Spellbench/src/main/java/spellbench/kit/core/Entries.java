package spellbench.kit.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The frozen kit entries (A1 result review, change 6): kit-mad-1 (H1), kit-mad-k (H2) and kit-mcts (H3). An entry's
 * identity is its name plus a digest of its whole configuration: the bot and how its decisions are dispatched, K, every
 * budget, the truncation scoring, the horizon policy and the approximation flags policy. The front reports the
 * identity in {@code hello} ({@code bot.name}, {@code bot.version} = kit version + "+" + digest) and logs the
 * configuration at {@code game_start}. Any override of a frozen value (a budget, K, the skill) makes a different
 * configuration: its name gets the suffix {@code -custom} and its digest changes, so a modified configuration can never
 * report a frozen entry's identity.
 */
public final class Entries {

    public static final String KIT_VERSION = "0.3.0";

    private Entries() {
    }

    /** Policies every entry shares (design Sections 3.3, 3.4, 5.1, 5.6, 6.5; E4 outcome; review changes 3 and 4). */
    static Map<String, Object> commonPolicies() {
        return Json.map(
                "dialogs", "ComputerPlayer heuristics (identical in upstream MAD and MCTS)",
                "mulligan", "ComputerPlayer.chooseMulligan on a pregame world",
                "continuation", "saved anchor rebuilt from inputs; earlier dialogs of the resolution replayed from own answers",
                "horizon", "a world with a horizon-flagged stack object or dropped pending triggers is not searched; the "
                        + "front answers the declining candidate (wrapper)",
                "unsupported_states", "a world with an unsupported flag is not searched; the front declines (wrapper)",
                "non_stack_payload", "a chosen action that does not use the stack and asks a dialog while it executes is "
                        + "unsupported; the next ranked candidate answers (wrapper), unless every dialog was a color "
                        + "choice, which the plan carries",
                "color_choice", "choose_color: the plan's color, else ComputerPlayer.choose on the offered colors",
                "approximation_flags", Arrays.asList("approximate:watchers_reset (every world)",
                        "approximate:unexplained_characteristics", "approximate:activation_usage_other_seat",
                        "approximate:first_strike_step_unknown", "approximate:token_characteristics",
                        "approximate:renamed_object (origin from this seat's observed history)"),
                "fallback", "ranked, else declining, else lowest candidate id; always an offered candidate");
    }

    static Map<String, Object> madBudgets() {
        return Json.map("nodes", 5000L, "options", 2000L, "operations", 20000L);
    }

    /** The frozen configuration of h1, h2, h3 or a labelled CP7 fair variant at a shipped skill. */
    public static Map<String, Object> frozen(String entry) {
        if (entry.matches("mad7-s(?:[1-9]|10)")) {
            long skill = Long.parseLong(entry.substring("mad7-s".length()));
            // Keep the existing H1 identity byte-for-byte compatible. New skill
            // entries make their fair wrapper and search settings explicit.
            Map<String, Object> c = frozen("h1");
            c.put("name", "xmage-mad7-fair-s" + skill);
            c.put("skill", skill);
            c.put("source_revision", "fd40ad5c29a92cef824cf12ba6d0e4daa25db975");
            c.put("information", "permitted observation and one sampled hidden world; no live engine state");
            c.put("effective_depth", Math.max(4L, skill));
            c.put("variant", "CP7 with the kit's synchronous node and operation budgets, reconstruction, "
                    + "horizon and fallback policies; upstream wall-clock search is changed");
            return c;
        }
        Map<String, Object> c;
        switch (entry) {
            case "h1":
                c = Json.map("name", "kit-mad-1", "bot", "mad", "worlds", 1L, "skill", 6L,
                        "budgets", madBudgets(), "aggregation", "single world",
                        "priority", "ComputerPlayer7 search in the main phases and declare steps; elsewhere its own pass, "
                                + "answered without a world",
                        "combat", "ComputerPlayer6 declareAttackers/declareBlockers on the world",
                        "truncation", "MAD: a horizon or a budget stop scores the node by GameStateEvaluator2 (a leaf)");
                break;
            case "h2":
                c = Json.map("name", "kit-mad-k", "bot", "mad", "worlds", 4L, "skill", 6L,
                        "budgets", madBudgets(), "aggregation", "vote per priority key, ties by exact score sums, then "
                                + "the lowest candidate id; combat vote per combat key",
                        "priority", "ComputerPlayer7 search in the main phases and declare steps; elsewhere its own pass, "
                                + "answered without a world",
                        "combat", "ComputerPlayer6 declareAttackers/declareBlockers on each world",
                        "truncation", "MAD: a horizon or a budget stop scores the node by GameStateEvaluator2 (a leaf)");
                break;
            case "h3":
                c = Json.map("name", "kit-mcts", "bot", "mcts", "worlds", 1L, "skill", 6L,
                        "budgets", Json.map("iterations", 30L, "rollout", 1000L, "operations", 20000L, "options", 2000L,
                                "combat_options", 128L),
                        "aggregation", "visits per priority key (one world)",
                        "priority", "MCTS at every priority decision with more than one candidate (upstream dispatch)",
                        "combat", "MCTS selectAttackers/selectBlockers (upstream dispatch)",
                        "truncation", "a rollout stopped by the rollout cap, the operation cap or a horizon counts as a "
                                + "completed iteration and scores a win when the decider's GameStateEvaluator2 score "
                                + "exceeds its score at the decision (design 5.6); labelled truncated");
                break;
            case "mz":
                // MageZero's policy-only player (WillWroble/mage spell-bench): no search, so a flagged world is still
                // answered (search_flagged) and nothing is budgeted
                c = Json.map("name", "magezero-policy", "bot", "policy", "worlds", 1L, "skill", 6L,
                        "budgets", Json.map(), "aggregation", "single world", "search_flagged", true,
                        "priority", "the model's highest-scoring offered action at every priority decision",
                        "combat", "ComputerPlayer one-at-a-time attackers and blockers, each pick by the model's target head",
                        "information", "the opponent's hand is not encoded (its size is)");
                break;
            default:
                throw new IllegalArgumentException("unknown entry " + entry);
        }
        c.put("policies", commonPolicies());
        if ("mz".equals(entry)) {
            Map<String, Object> p = Json.obj(c, "policies");
            p.put("dialogs", "targets and yes/no by the model's heads (greedy); other dialogs by ComputerPlayer heuristics");
            p.put("mulligan", "keep (mulligans off, as in training)");
        }
        // the clock policy is part of the entry (second review, item 4)
        c.put("clock", Json.map("grace_ms", 5000L, "overhead_ms", 1500L, "kill_reserve_ms", 300L));
        if ("h3".equals(entry)) {
            // kit-mcts paces its searches by the bank (pauper-kernel panel, 2026-10-06: a fixed 30
            // iterations spent the 600 s bank by about decision 50); kit-mad-1 and kit-mad-k keep their identity
            Json.obj(c, "clock").put("pace_moves", 20L);
            Json.obj(c, "clock").put("pace_floor_ms", 12000L);
        }
        c.put("diagnostics", Json.map("roundtrip", false, "hang_at", -1L));
        return c;
    }

    /**
     * The entry's configuration with the command-line overrides applied: {@code worlds}, {@code skill} and the budgets
     * {@code nodes}, {@code options}, {@code operations}, {@code iterations}, {@code rollout}.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> configure(String entry, Map<String, String> opts) {
        Map<String, Object> c = frozen(entry);
        List<String> overridden = new ArrayList<>();
        if (opts.containsKey("worlds") && Long.parseLong(opts.get("worlds")) != Json.num(c, "worlds", 1)) {
            c.put("worlds", Long.parseLong(opts.get("worlds")));
            overridden.add("worlds");
        }
        if (opts.containsKey("skill") && Long.parseLong(opts.get("skill")) != Json.num(c, "skill", 6)) {
            c.put("skill", Long.parseLong(opts.get("skill")));
            if (c.containsKey("effective_depth")) {
                c.put("effective_depth", Math.max(4L, Json.num(c, "skill", 6)));
            }
            overridden.add("skill");
        }
        Map<String, Object> b = new LinkedHashMap<>((Map<String, Object>) c.get("budgets"));
        for (String k : new String[]{"nodes", "options", "operations", "iterations", "rollout", "combat_options"}) {
            if (opts.containsKey(k) && !Long.valueOf(Long.parseLong(opts.get(k))).equals(b.get(k))) {
                b.put(k, Long.parseLong(opts.get(k)));
                overridden.add(k);
            }
        }
        c.put("budgets", b);
        Map<String, Object> clock = new LinkedHashMap<>((Map<String, Object>) c.get("clock"));
        for (String[] k : new String[][]{{"grace-ms", "grace_ms"}, {"overhead-ms", "overhead_ms"}, {"kill-reserve-ms", "kill_reserve_ms"},
                {"pace-moves", "pace_moves"}, {"pace-floor-ms", "pace_floor_ms"}}) {
            if (opts.containsKey(k[0]) && !Long.valueOf(Long.parseLong(opts.get(k[0]))).equals(clock.get(k[1]))) {
                clock.put(k[1], Long.parseLong(opts.get(k[0])));
                overridden.add(k[1]);
            }
        }
        c.put("clock", clock);
        if ("1".equals(opts.get("roundtrip")) || (opts.containsKey("hang-at") && Long.parseLong(opts.get("hang-at")) >= 0)) {
            // round-trip diagnostics spend the decision's clock; the hang hook is a test: both change behaviour
            c.put("diagnostics", Json.map("roundtrip", "1".equals(opts.get("roundtrip")),
                    "hang_at", Long.parseLong(opts.getOrDefault("hang-at", "-1"))));
            overridden.add("diagnostics");
        }
        if ("1".equals(opts.get("search-flagged"))) {
            c.put("search_flagged", true); // measurement mode (E4): flagged worlds are searched up to the horizon
            overridden.add("search_flagged");
        }
        if (opts.containsKey("model")) {
            c.put("model", opts.get("model")); // the checkpoint's identity, bound into the bot version's digest
        }
        if (!overridden.isEmpty()) {
            c.put("name", c.get("name") + "-custom");
            c.put("overridden", overridden);
        }
        return c;
    }

    /** Twelve hex digits of the configuration's canonical JSON. */
    public static String digest(Map<String, Object> config) {
        return Seeds.hex(Seeds.hmac("spellbench-kit-entry".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                Json.canonical(config))).substring(0, 12);
    }

    public static String version(Map<String, Object> config) {
        return KIT_VERSION + "+" + digest(config);
    }

    /** Prints an entry's identity (name, tab, version) for the game scripts: {@code Entries h1 [--worlds 2 ...]}. */
    public static void main(String[] args) {
        Map<String, String> opts = new LinkedHashMap<>();
        for (int i = 1; i + 1 < args.length; i += 2) {
            opts.put(args[i].replaceFirst("^--", ""), args[i + 1]);
        }
        Map<String, Object> c = configure(args[0], opts);
        System.out.println(c.get("name") + "\t" + version(c));
    }
}
