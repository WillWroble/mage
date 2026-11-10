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

    /**
     * An XMage option's text names the kernel's option: its first word is the option, as a choice of costs reads in
     * both ("Discard a card" and "discard", "Sacrifice a land" and "sacrifice").
     */
    static boolean names(String text, String option) {
        return text != null && option != null && text.trim().split(" ")[0].equalsIgnoreCase(option);
    }

    /**
     * The yes/no an effect option answers: the kernel programs an optional effect ("you may draw a card") as an
     * unlabelled option between doing nothing, first, and doing it. Null for any other option.
     */
    static Boolean may(JsonObject semantic) {
        return semantic.get("option_count").getAsInt() == 2 ? semantic.get("option_index").getAsInt() == 1 : null;
    }

    /** A priority decision: its candidates are actions and pass. */
    static boolean isPriority(JsonObject decision) {
        return "priority".equals(Views.text(decision.getAsJsonObject("context"), "kind"))
                || PRIORITY.contains(firstKind(decision));
    }

    /** How many picks of its selection are made: a selection may be asked pick by pick outside a decision group. */
    static int selected(JsonObject decision) {
        for (JsonElement c : decision.getAsJsonArray("candidates")) {
            JsonElement selected = c.getAsJsonObject().getAsJsonObject("semantic").get("selected_count");
            if (selected != null) {
                return selected.getAsInt();
            }
        }
        return substep(decision);
    }

    /** Which substep of its decision group this decision is (0 for the first, or an ungrouped decision). */
    static int substep(JsonObject decision) {
        JsonElement group = decision.get("group");
        return group == null || group.isJsonNull() ? 0 : group.getAsJsonObject().get("substep_index").getAsInt();
    }

}
