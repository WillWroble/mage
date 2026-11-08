package spellbench.kit.core;

import java.util.List;
import java.util.Map;

/**
 * Which offered candidate a world action answers. A world semantic answers the candidate with the same canonical JSON
 * (Section 5.5.2). Protocol v2 Section 7.4 also lets an engine offer {@code cast_spell} with {@code method: null},
 * followed by a {@code choose_cast_method} decision; such a candidate answers every world cast of the same source,
 * whatever its method, and the plan answers the method decision from the world's method. The XMage engine always
 * names the method, so this changes nothing there; the mtg-kernel v2 engine always sends null.
 */
public final class Offers {

    private Offers() {
    }

    /** True when the world semantic {@code world} answers the offered semantic {@code offered}. */
    public static boolean answers(Map<String, Object> offered, Map<String, Object> world) {
        if (offered == null || world == null) {
            return false;
        }
        if (Json.canonical(offered).equals(Json.canonical(world))) {
            return true;
        }
        return "cast_spell".equals(offered.get("kind")) && "cast_spell".equals(world.get("kind"))
                && offered.get("method") == null && offered.containsKey("method")
                && Json.canonical(offered.get("source")).equals(Json.canonical(world.get("source")));
    }

    /** The index of the offered candidate {@code world} answers: an exact match first, else a method-null cast; -1. */
    public static int candidateFor(List<Object> candidates, Map<String, Object> world) {
        if (world == null) {
            return -1;
        }
        String key = Json.canonical(world);
        int loose = -1;
        for (int i = 0; i < candidates.size(); i++) {
            Map<String, Object> sem = Json.obj(Json.obj(candidates.get(i)), "semantic");
            if (key.equals(Json.canonical(sem))) {
                return i;
            }
            if (loose < 0 && answers(sem, world)) {
                loose = i;
            }
        }
        return loose;
    }

    /** The cast method a world semantic names, or null when it names none. */
    public static String worldMethod(String worldKey) {
        if (worldKey == null) {
            return null;
        }
        Map<String, Object> sem = Json.parseObject(worldKey);
        return "cast_spell".equals(sem.get("kind")) ? Json.str(sem, "method") : null;
    }
}
