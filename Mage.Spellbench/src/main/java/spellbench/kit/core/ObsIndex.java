package spellbench.kit.core;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The object references of one observation by {@code object_id} (protocol Section 5.1: a reference equals the
 * observation record with that id, field for field): zone arrays, stack entries and {@code known} entries with ids.
 */
public final class ObsIndex {

    private final Map<String, Map<String, Object>> refs = new LinkedHashMap<>();
    private final Map<String, Map<String, Object>> records = new LinkedHashMap<>();

    public ObsIndex(Map<String, Object> observation) {
        for (Object p : Json.arr(observation, "players")) {
            Map<String, Object> pm = Json.obj(p);
            for (String zone : new String[]{"hand", "battlefield", "graveyard", "exile", "command"}) {
                for (Object o : Json.arr(pm, zone)) {
                    add(Json.obj(o));
                }
            }
        }
        for (Object o : Json.arr(observation, "stack")) {
            add(Json.obj(o));
        }
        for (Object o : Json.arr(observation, "known")) {
            Map<String, Object> k = Json.obj(o);
            if (k.get("object_id") != null) {
                Map<String, Object> ref = new LinkedHashMap<>();
                ref.put("object_id", k.get("object_id"));
                ref.put("card_name", k.get("card_name"));
                ref.put("owner_seat", k.get("owner_seat"));
                ref.put("controller_seat", k.get("owner_seat"));
                ref.put("zone", k.get("zone"));
                refs.put((String) k.get("object_id"), ref);
                records.put((String) k.get("object_id"), k);
            }
        }
    }

    private void add(Map<String, Object> rec) {
        String id = Json.str(rec, "object_id");
        if (id == null) {
            return;
        }
        Map<String, Object> ref = new LinkedHashMap<>();
        for (String f : new String[]{"object_id", "card_name", "owner_seat", "controller_seat", "zone"}) {
            ref.put(f, rec.get(f));
        }
        refs.put(id, ref);
        records.put(id, rec);
    }

    /** The reference for an object id, or null when the observation does not hold it. */
    public Map<String, Object> ref(String objectId) {
        Map<String, Object> r = objectId == null ? null : refs.get(objectId);
        return r == null ? null : new LinkedHashMap<>(r);
    }

    public Map<String, Object> record(String objectId) {
        return objectId == null ? null : records.get(objectId);
    }

    public boolean has(String objectId) {
        return objectId != null && refs.containsKey(objectId);
    }

    public List<String> ids() {
        return new java.util.ArrayList<>(refs.keySet());
    }
}
