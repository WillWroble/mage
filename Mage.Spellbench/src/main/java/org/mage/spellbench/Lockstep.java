package org.mage.spellbench;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Set;

/** Reading a kernel seat decision (spec 7, 8). */
final class Lockstep {

    private static final Set<String> PRIORITY = Set.of("pass", "play_land", "cast_spell", "activate_ability",
            "activate_mana_ability", "special_action");

    private Lockstep() {
    }

    static JsonObject observation(JsonObject decision) {
        return decision.getAsJsonObject("observation");
    }

    static JsonObject semantic(JsonObject decision, int candidate) {
        return decision.getAsJsonArray("candidates").get(candidate).getAsJsonObject().getAsJsonObject("semantic");
    }

    /** The kind of the decision's first candidate that is not a pass or a finish. */
    static String firstKind(JsonObject decision) {
        String first = null;
        for (JsonElement c : decision.getAsJsonArray("candidates")) {
            String kind = Views.text(c.getAsJsonObject().getAsJsonObject("semantic"), "kind");
            if (first == null) {
                first = kind;
            }
            if (!kind.equals("pass") && !kind.startsWith("finish_")) {
                return kind;
            }
        }
        return first;
    }

    /** A priority decision: its candidates are actions and pass. */
    static boolean isPriority(JsonObject decision) {
        return "priority".equals(Views.text(decision.getAsJsonObject("context"), "kind"))
                || PRIORITY.contains(firstKind(decision));
    }

    /** Which substep of its decision group this decision is (0 for the first, or an ungrouped decision). */
    static int substep(JsonObject decision) {
        JsonElement group = decision.get("group");
        return group == null || group.isJsonNull() ? 0 : group.getAsJsonObject().get("substep_index").getAsInt();
    }

}
