package spellbench.kit.xmage;

import mage.player.spellbench.observe.Look;
import mage.player.spellbench.observe.Observation;
import mage.player.spellbench.observe.ObservationBuilder;
import spellbench.kit.core.Json;
import spellbench.kit.core.ObsCompare;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The world's projected observation (the engine's own {@link ObservationBuilder} run on the world) and its
 * difference from the received observation modulo ids: the observation round trip of design Section 7.2.
 */
public final class RoundTrip {

    private static final byte[] PROJECTION_SECRET = new byte[32];

    private RoundTrip() {
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> project(World w, Map<String, Object> flags, String prioritySeat,
                                              List<Object> known) throws Exception {
        return (Map<String, Object>) Json.copy(observe(w, flags, prioritySeat, known).json());
    }

    /** The projection itself, whose {@link Observation#reference} names each world object by its projected id. */
    public static Observation observe(World w, Map<String, Object> flags, String prioritySeat,
                                      List<Object> known) throws Exception {
        ObservationBuilder b = ObservationBuilder.forSession(w.game, PROJECTION_SECRET, flags);
        List<Look> looks = new ArrayList<>();
        for (Object o : known) {
            Map<String, Object> k = Json.obj(o);
            String id = Json.str(k, "object_id");
            UUID u = id == null ? null : w.idToUuid.get(id);
            String how = Json.str(k, "how");
            if (u == null || !("looked_at".equals(how) || "revealed".equals(how) || "searching".equals(how))) {
                continue;
            }
            Object top = k.get("position_from_top");
            Object bottom = k.get("position_from_bottom");
            looks.add(new Look(u, how, top == null ? null : ((Number) top).intValue(),
                    bottom == null ? null : ((Number) bottom).intValue()));
        }
        return b.build(w.viewer, prioritySeat, looks);
    }

    /** Differences between the world's projection and the decision's observation (empty: exact round trip). */
    public static List<Object> diff(World w, Map<String, Object> decision) {
        Map<String, Object> obs = Json.obj(decision, "observation");
        List<Object> out = new ArrayList<>();
        try {
            Map<String, Object> flags = flagsFrom(decision);
            Map<String, Object> projected = project(w, flags, Json.str(obs, "priority_seat"), Json.arr(obs, "known"));
            for (String d : ObsCompare.diff(obs, projected, 40)) {
                out.add(d);
            }
        } catch (Exception e) {
            out.add("projection failed: " + e);
        }
        return out;
    }

    /** The observation flags: the engine profile's, carried by the front in the decision as x_observation_flags. */
    static Map<String, Object> flagsFrom(Map<String, Object> decision) {
        Map<String, Object> f = Json.obj(decision, "x_observation_flags");
        if (f != null) {
            return f;
        }
        return Json.map("poison", true, "player_counters", false, "designations", false, "player_progress", true,
                "day_night", true, "passed_seats", true, "pending_triggers", true, "keywords", true, "full_name", true,
                "exiled_by", false, "stack_text", false, "permanent_details", false, "known_cards", false);
    }
}
