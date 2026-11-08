package mage.player.spellbench.decide;

import mage.player.ai.ComputerPlayer;
import mage.players.Player;
import mage.players.PlayerImpl;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Task X4c: no XMage built-in AI fallback is reachable from a seat. Lists every method a {@link SeatPlayer} runs,
 * where its implementation comes from, and how a decision it makes reaches the seat:
 * <ul>
 * <li>{@code posed}: overridden in {@link SeatPlayer}, which poses it as v2 decisions (or halts the game where it
 * names a halt cause);</li>
 * <li>{@code engine_autopay}: XMage's mana planner under the declared {@code mana_payment: engine_autopay}
 * (Section 7.6), in {@link AutoPayPlayer} and, inside a payment, {@link PayChoice};</li>
 * <li>{@code not_a_decision}: a {@code ComputerPlayer} method that decides nothing in a game (bookkeeping, draft
 * and tournament hooks no Spellbench game calls);</li>
 * <li>{@code rules}: XMage's rules code ({@code PlayerImpl} and the interface's defaults), whose choices go through
 * the posed callbacks.</li>
 * </ul>
 * Fails (exit 1) when a {@code ComputerPlayer} method is reachable without a classification, or a decision callback
 * of the {@link Player} interface is not implemented by the overlay.
 * <p>
 * {@code java -cp "lib/*" mage.player.spellbench.decide.CallbackAudit}
 */
public final class CallbackAudit {

    /** Decision callbacks of {@link Player}: each must be implemented by the overlay. */
    static final List<String> DECISIONS = Arrays.asList(
            "priority", "choose", "chooseTarget", "chooseTargetAmount", "chooseMulligan", "chooseUse", "choosePile",
            "playMana", "announceX", "chooseReplacementEffect", "chooseTriggeredAbility", "chooseMode",
            "selectAttackers", "selectBlockers", "getAmount", "getMultiAmountWithIndividualConstraints",
            "chooseAbilityForCast", "chooseLandOrSpellAbility", "scry", "doSurveil", "putCardsOnTopOfLibrary",
            "putCardsOnBottomOfLibrary");

    /** ComputerPlayer methods that make no game decision, and why. */
    static final Map<String, String> NOT_A_DECISION = new LinkedHashMap<>();

    static {
        NOT_A_DECISION.put("abort", "sets the abort flag; no choice");
        NOT_A_DECISION.put("skip", "empty");
        NOT_A_DECISION.put("getAvailableManaProducers", "a query the autopay planner uses (engine_autopay)");
        NOT_A_DECISION.put("sideboard", "match sideboarding: no Spellbench game calls it");
        NOT_A_DECISION.put("construct", "tournament deck construction: no Spellbench game calls it");
        NOT_A_DECISION.put("pickCard", "draft picks: no Spellbench game calls it");
        NOT_A_DECISION.put("makePickCard", "draft picks: no Spellbench game calls it");
        NOT_A_DECISION.put("cleanUpOnMatchEnd", "match cleanup");
        NOT_A_DECISION.put("equals", "identity");
        NOT_A_DECISION.put("hashCode", "identity");
        NOT_A_DECISION.put("isHuman", "false: XMage's combat checks treat the seat as a computer player, which the "
                + "combat oracle accounts for (CombatOracle)");
        NOT_A_DECISION.put("restore", "state restore after a rollback");
        NOT_A_DECISION.put("rememberPick", "draft");
        NOT_A_DECISION.put("chooseDeckColorsIfPossible", "draft");
        NOT_A_DECISION.put("remove", "list helper");
        NOT_A_DECISION.put("logList", "logging");
        NOT_A_DECISION.put("chooseCreatureType", "only called from ComputerPlayer.choose(Choice), which SeatPlayer "
                + "overrides; inside a payment that override first gives the choice a visible order");
    }

    public static void main(String[] args) {
        Map<String, String> rows = new TreeMap<>();
        List<String> failures = new ArrayList<>();
        List<Class<?>> chain = Arrays.asList(SeatPlayer.class, AutoPayPlayer.class, ComputerPlayer.class,
                PlayerImpl.class);
        Map<String, Method> seen = new LinkedHashMap<>();
        for (Class<?> c : chain) {
            for (Method m : c.getDeclaredMethods()) {
                if (Modifier.isStatic(m.getModifiers()) || Modifier.isPrivate(m.getModifiers()) || m.isSynthetic()) {
                    continue;
                }
                seen.putIfAbsent(signature(m), m); // the most derived implementation wins
            }
        }
        for (Method m : Player.class.getMethods()) {
            seen.putIfAbsent(signature(m), m); // interface defaults nobody overrides
        }
        for (Map.Entry<String, Method> e : seen.entrySet()) {
            Method m = e.getValue();
            Class<?> owner = m.getDeclaringClass();
            String category;
            if (owner == SeatPlayer.class) {
                category = "posed";
            } else if (owner == AutoPayPlayer.class) {
                category = "engine_autopay";
            } else if (owner == ComputerPlayer.class) {
                String why = NOT_A_DECISION.get(m.getName());
                if (why == null && m.getName().equals("playManaHandling")) {
                    why = "the autopay planner (engine_autopay); AutoPayPlayer replaces it";
                }
                category = why == null ? "UNCLASSIFIED_AI_FALLBACK" : "not_a_decision: " + why;
                if (why == null) {
                    failures.add(e.getKey() + " is ComputerPlayer's and unclassified");
                }
            } else {
                category = "rules";
            }
            boolean oneCard = m.getName().startsWith("putCardsOn") && m.getParameterTypes().length > 0
                    && m.getParameterTypes()[0] == mage.cards.Card.class;
            if (oneCard) {
                category = "rules: one card, nothing to order";
            } else if (DECISIONS.contains(m.getName()) && Player.class.isAssignableFrom(owner)
                    && owner != SeatPlayer.class && owner != AutoPayPlayer.class
                    && isInterfaceMethod(m) && !m.isDefault()) {
                failures.add(e.getKey() + " is a decision callback implemented by " + owner.getSimpleName());
                category = "DECISION_NOT_IN_OVERLAY (" + owner.getSimpleName() + ")";
            }
            rows.put(e.getKey(), owner.getSimpleName() + "\t" + category);
        }
        for (Map.Entry<String, String> r : rows.entrySet()) {
            System.out.println(r.getKey() + "\t" + r.getValue());
        }
        System.out.println("callback audit: " + rows.size() + " methods, " + failures.size() + " failures");
        for (String f : failures) {
            System.out.println("FAIL " + f);
        }
        System.out.println(failures.isEmpty() ? "callback audit verdict: PASS" : "callback audit verdict: FAIL");
        System.exit(failures.isEmpty() ? 0 : 1);
    }

    private static boolean isInterfaceMethod(Method m) {
        try {
            Player.class.getMethod(m.getName(), m.getParameterTypes());
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    private static String signature(Method m) {
        StringBuilder sb = new StringBuilder(m.getName()).append('(');
        Class<?>[] p = m.getParameterTypes();
        for (int i = 0; i < p.length; i++) {
            sb.append(i == 0 ? "" : ",").append(p[i].getSimpleName());
        }
        return sb.append(')').toString();
    }
}
