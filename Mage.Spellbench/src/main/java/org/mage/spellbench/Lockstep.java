package org.mage.spellbench;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Set;

/** Reading a kernel seat decision (spec 7, 8). */
final class Lockstep {

    private static final Set<String> PRIORITY = Set.of("pass", "play_land", "cast_spell", "activate_ability",
            "activate_mana_ability", "special_action");

    private Lockstep() {
    }

    /**
     * The decision's observation, its stack bottom first as spec 6.4 has it: mtg-kernel's protocol engine lists it top
     * first. A copy, so what the Archon is sent stays as the engine wrote it.
     */
    static JsonObject observation(JsonObject decision) {
        JsonObject observation = decision.getAsJsonObject("observation");
        JsonObject copy = new JsonObject();
        observation.entrySet().forEach(e -> copy.add(e.getKey(), e.getValue()));
        JsonArray stack = new JsonArray();
        JsonArray topFirst = observation.getAsJsonArray("stack");
        for (int i = topFirst.size() - 1; i >= 0; i--) {
            stack.add(topFirst.get(i));
        }
        copy.add("stack", stack);
        // an embalmed token is named as its card (CR 702.128a); mtg-kernel names it by its catalog entry
        JsonArray players = new JsonArray();
        for (JsonElement p : observation.getAsJsonArray("players")) {
            JsonObject player = p.getAsJsonObject();
            JsonArray battlefield = player.getAsJsonArray("battlefield");
            if (battlefield.toString().contains(EMBALMED)) {
                player = player.deepCopy();
                for (JsonElement b : player.getAsJsonArray("battlefield")) {
                    String name = Views.text(b.getAsJsonObject(), "card_name");
                    if (name != null && name.endsWith(EMBALMED)) {
                        b.getAsJsonObject().addProperty("card_name", name.substring(0, name.length() - EMBALMED.length()));
                    }
                }
            }
            players.add(player);
        }
        copy.add("players", players);
        return copy;
    }

    private static final String EMBALMED = " Embalmed Token";

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
     * The yes/no an effect option answers; null for an option not of two. The kernel's options are unlabelled, in the
     * card's program order: an optional effect ("you may draw a card") is doing nothing, first, then doing it, so yes is
     * the second; options XMage names (Winding Way's "Creature" or "Land") are the card's printed ones in its printed
     * order, so yes, the first named, is the first.
     */
    static Boolean may(JsonObject semantic, String option) {
        boolean named = option != null && !option.equalsIgnoreCase("yes") && !option.equalsIgnoreCase("no");
        return semantic.get("option_count").getAsInt() == 2
                ? semantic.get("option_index").getAsInt() == (named ? 0 : 1) : null;
    }

    /** A priority decision: its candidates are actions and pass. */
    static boolean isPriority(JsonObject decision) {
        return "priority".equals(Views.text(decision.getAsJsonObject("context"), "kind"))
                || PRIORITY.contains(firstKind(decision));
    }

    /**
     * A selection that must take every candidate it offers: no decision, and XMage makes it without asking (its
     * auto-choice, as two Mountains to sacrifice of two).
     */
    static boolean takesAll(JsonObject decision) {
        JsonObject first = semantic(decision, 0);
        if (!first.has("minimum") || !first.has("maximum") || !first.has("selected_count")
                || first.get("minimum").getAsInt() != first.get("maximum").getAsInt()) {
            return false;
        }
        int offered = 0;
        for (JsonElement c : decision.getAsJsonArray("candidates")) {
            if (Views.text(c.getAsJsonObject().getAsJsonObject("semantic"), "kind").startsWith("finish_")) {
                return false;
            }
            offered++;
        }
        return offered == first.get("minimum").getAsInt() - first.get("selected_count").getAsInt();
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
