package mage.player.cabt;

import mage.players.Player;
import mage.util.MultiAmountMessage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CABT bridge: builds the MULTI_AMOUNT prompt — one option per valid
 * assignment of amounts to buckets, honoring each bucket's min/max and the
 * total range. Only tractable for small totals; larger spaces fail closed
 * at {@link #MAX_ENUMERATED_ASSIGNMENTS}.
 */
public final class CabtMultiAmountPromptBuilder {

    public static final int MAX_ENUMERATED_ASSIGNMENTS = 200;

    public PendingDecision build(Player player, List<MultiAmountMessage> messages,
                                 int totalMin, int totalMax) {
        List<List<Integer>> assignments = new ArrayList<List<Integer>>();
        enumerate(messages, 0, new ArrayList<Integer>(), 0, totalMin, totalMax, assignments);
        PendingDecision decision = new PendingDecision(
                MagicSelectType.MULTI_AMOUNT, player.getId(), 1, 1);
        for (List<Integer> assignment : assignments) {
            Map<String, Object> payload = new LinkedHashMap<String, Object>();
            payload.put("assignment", Collections.unmodifiableList(new ArrayList<Integer>(assignment)));
            payload.put("totalMin", totalMin);
            payload.put("totalMax", totalMax);
            decision.addOption(new MagicOption(
                    MagicOptionType.PROMPT_AMOUNT_ASSIGNMENT, assignment.toString(), payload));
        }
        return decision;
    }

    private static void enumerate(List<MultiAmountMessage> messages, int bucket,
                                  List<Integer> current, int currentSum,
                                  int totalMin, int totalMax, List<List<Integer>> assignments) {
        if (bucket == messages.size()) {
            if (currentSum >= totalMin && currentSum <= totalMax) {
                assignments.add(new ArrayList<Integer>(current));
                if (assignments.size() > MAX_ENUMERATED_ASSIGNMENTS) {
                    throw new CabtUnhandledDecisionException(
                            "multi-amount assignment space exceeds the enumeration cap ("
                                    + MAX_ENUMERATED_ASSIGNMENTS + "); failing closed");
                }
            }
            return;
        }
        MultiAmountMessage constraint = messages.get(bucket);
        for (int value = constraint.min; value <= constraint.max; value++) {
            if (currentSum + value > totalMax) {
                break;
            }
            current.add(value);
            enumerate(messages, bucket + 1, current, currentSum + value,
                    totalMin, totalMax, assignments);
            current.remove(current.size() - 1);
        }
    }
}
