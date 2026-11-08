package mage.player.spellbench.observe;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One viewer's observation (Section 6) and the object references it holds (Section 5.1). The decision mapper takes
 * candidate references from {@link #reference} and {@link #target}: they equal the observation's records field for
 * field, and an object outside the observation is {@code null}.
 */
public final class Observation {

    private final Map<String, Object> json;
    private final Map<UUID, Map<String, Object>> held;
    private final Map<UUID, String> seats;
    private final List<Map<String, Object>> audit;

    Observation(Map<String, Object> json, Map<UUID, Map<String, Object>> held, Map<UUID, String> seats,
                List<Map<String, Object>> audit) {
        this.json = json;
        this.held = held;
        this.seats = seats;
        this.audit = audit;
    }

    /** The observation as JSON values (maps, lists, strings, integers, booleans, nulls). */
    public Map<String, Object> json() {
        return json;
    }

    /** The viewer's object reference for an XMage object id (a card, permanent, spell or ability), or null. */
    public Map<String, Object> reference(UUID objectId) {
        Map<String, Object> ref = held.get(objectId);
        return ref == null ? null : new LinkedHashMap<>(ref);
    }

    /** A target reference (Section 5.2): {@code {"player": seat}}, {@code {"object": reference}}, or null. */
    public Map<String, Object> target(UUID id) {
        Map<String, Object> t = new LinkedHashMap<>();
        String seat = seats.get(id);
        if (seat != null) {
            t.put("player", seat);
            return t;
        }
        Map<String, Object> ref = reference(id);
        if (ref == null) {
            return null;
        }
        t.put("object", ref);
        return t;
    }

    /**
     * Engine-side record of every held object: {@code object_id}, the internal key, the zone and whether it is a
     * look. For test harnesses only; it carries internal identities and never reaches agents (Section 5.3).
     */
    public List<Map<String, Object>> audit() {
        return Collections.unmodifiableList(new ArrayList<>(audit));
    }
}
