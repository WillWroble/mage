package spellbench.kit.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Card origins remembered exclusively from this seat's earlier observations. */
public final class VisibleNames {
    private VisibleNames() { }

    private static List<Map<String, Object>> records(Map<String, Object> observation) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (observation == null) return out;
        for (Object player : Json.arr(observation, "players")) {
            Map<String, Object> p = Json.obj(player);
            for (String zone : new String[]{"hand", "battlefield", "graveyard", "exile"}) {
                for (Object card : Json.arr(p, zone)) out.add(Json.obj(card));
            }
        }
        for (Object entry : Json.arr(observation, "stack")) {
            Map<String, Object> card = Json.obj(entry);
            if ("spell".equals(Json.str(card, "stack_kind"))) out.add(card);
        }
        return out;
    }

    public static void observe(Map<String, Object> observation, Map<String, String> origins) {
        for (Map<String, Object> card : records(observation)) {
            String id = Json.str(card, "object_id");
            String name = Json.str(card, "card_name");
            if (id != null && name != null) origins.putIfAbsent(id, name);
        }
    }

    public static Map<String, Object> changed(Map<String, Object> observation, Map<String, String> origins) {
        Map<String, Object> out = new TreeMap<>();
        for (Map<String, Object> card : records(observation)) {
            String id = Json.str(card, "object_id");
            String name = Json.str(card, "card_name");
            String origin = origins.get(id);
            if (name != null && origin != null && !name.equals(origin)) out.put(id, origin);
        }
        return out;
    }

    public interface Resolvable {
        boolean test(String name);
    }

    /** Restore unknown renamed nontokens in a caller-owned copy, retaining an explicit approximation. */
    public static Map<String, Object> restore(Map<String, Object> observation, Map<String, Object> history,
                                             Resolvable resolvable) {
        Map<String, Object> repairs = new LinkedHashMap<>();
        Map<String, Object> origins = history == null ? null : Json.obj(history, "card_origins");
        if (origins == null) return repairs;
        for (Map<String, Object> card : records(observation)) {
            String id = Json.str(card, "object_id");
            String name = Json.str(card, "card_name");
            Object origin = origins.get(id);
            // Tokens need their original token class and characteristics; do not
            // pretend that a similarly named card is a valid replacement.
            if (name != null && origin instanceof String && !name.equals(origin) && !Json.bool(card, "token")
                    && !resolvable.test(name) && resolvable.test((String) origin)) {
                card.put("card_name", origin);
                repairs.put(id, Json.map("observed", name, "origin", origin));
            }
        }
        return repairs;
    }
}
