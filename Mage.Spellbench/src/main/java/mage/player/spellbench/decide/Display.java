package mage.player.spellbench.decide;

import java.util.Map;

/**
 * {@code display_text} (Section 7.1): human-facing, built only from the candidate's own semantic, so it shows
 * nothing the semantic does not already show (Sections 6.8 and 13 F2).
 */
final class Display {

    private Display() {
    }

    static String of(Map<String, Object> s) {
        String kind = (String) s.get("kind");
        switch (kind) {
            case "pass":
                return "Pass priority";
            case "play_land":
                return "Play " + name(s.get("source"));
            case "cast_spell":
                return "Cast " + name(s.get("source")) + method(s.get("method"));
            case "activate_ability":
                return "Activate " + name(s.get("source")) + " ability " + s.get("ability_index");
            case "special_action":
                return name(s.get("source")) + ": " + s.get("action");
            case "choose_target":
                return "Target " + target(s.get("target"));
            case "finish_target_selection":
            case "finish_selection":
                return "Done";
            case "select_object":
                return "Choose " + target(s.get("choice"));
            case "choose_cost_target":
                return "Pay with " + name(s.get("candidate"));
            case "choose_spell_mode":
                return "Mode " + (((Number) s.get("mode_index")).longValue() + 1);
            case "choose_cast_method":
                return "Cast " + name(s.get("source")) + method(s.get("method"));
            case "choose_boolean":
                return Boolean.TRUE.equals(s.get("value")) ? "Yes" : "No";
            case "optional_cost":
                return (Boolean.TRUE.equals(s.get("pay")) ? "Pay " : "Do not pay ") + s.get("cost");
            case "mulligan":
                return Boolean.TRUE.equals(s.get("keep")) ? "Keep" : "Mulligan";
            case "order_pick":
                return "Place at " + s.get("position");
            case "declare_attack":
                return s.get("defender") == null ? name(s.get("attacker")) + " does not attack"
                        : name(s.get("attacker")) + " attacks " + target(s.get("defender"));
            case "declare_block":
                return s.get("attacker") == null ? name(s.get("blocker")) + " does not block"
                        : name(s.get("blocker")) + " blocks " + name(s.get("attacker"));
            case "choose_number":
                return String.valueOf(s.get("value"));
            case "choose_color":
                return String.valueOf(s.get("color"));
            case "choose_name":
                return String.valueOf(s.get("value"));
            case "choose_option":
                return s.get("option_label") == null ? "Option " + s.get("option_index")
                        : (String) s.get("option_label");
            case "distribute":
                return s.get("amount") + " to " + target(s.get("recipient"));
            case "arrange_card":
                return name(s.get("card")) + " to " + s.get("destination");
            case "choose_pile":
                return "Pile " + s.get("pile_index");
            default:
                return null;
        }
    }

    private static String method(Object m) {
        return m == null || "normal".equals(m) ? "" : " (" + m + ")";
    }

    private static String name(Object ref) {
        if (!(ref instanceof Map)) {
            return "an object";
        }
        Object n = ((Map<?, ?>) ref).get("card_name");
        return n == null ? "a hidden object" : (String) n;
    }

    private static String target(Object t) {
        if (!(t instanceof Map)) {
            return "nothing";
        }
        Map<?, ?> m = (Map<?, ?>) t;
        if (m.containsKey("player")) {
            return "player " + m.get("player");
        }
        return name(m.get("object"));
    }
}
