package spellbench.kit.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Observation equality modulo object ids (design Section 7.1 step 3): every {@code object_id} value is relabelled by
 * its first appearance in a fixed traversal (keys in code-point order, arrays in order), so two observations that
 * differ only in their ids compare equal. Used by the round-trip diagnostics (Section 7.2) and the exact-transition
 * fixtures (Section 7.1).
 */
public final class ObsCompare {

    private ObsCompare() {
    }

    public static Object normalize(Object value) {
        Map<String, String> labels = new HashMap<>();
        return relabel(value, labels);
    }

    @SuppressWarnings("unchecked")
    private static Object relabel(Object v, Map<String, String> labels) {
        if (v instanceof Map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : ordered((Map<String, Object>) v).entrySet()) {
                if (e.getKey().equals("object_id") && e.getValue() instanceof String) {
                    String id = (String) e.getValue();
                    String l = labels.get(id);
                    if (l == null) {
                        l = "#" + labels.size();
                        labels.put(id, l);
                    }
                    out.put(e.getKey(), l);
                } else {
                    out.put(e.getKey(), relabel(e.getValue(), labels));
                }
            }
            return out;
        }
        if (v instanceof List) {
            List<Object> out = new ArrayList<>();
            for (Object o : (List<Object>) v) {
                out.add(relabel(o, labels));
            }
            return out;
        }
        if (v instanceof Integer) {
            return ((Integer) v).longValue();
        }
        return v;
    }

    /**
     * Keys in traversal order: zone arrays and the stack first (where objects live), references elsewhere later, so a
     * difference in pending triggers or knowledge does not shift the labels of the zones.
     */
    static Map<String, Object> ordered(Map<String, Object> m) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String k : new String[]{"seat", "hand", "battlefield", "graveyard", "exile", "command", "players", "stack"}) {
            if (m.containsKey(k)) {
                out.put(k, m.get(k));
            }
        }
        for (Map.Entry<String, Object> e : new TreeMap<>(m).entrySet()) {
            if (!out.containsKey(e.getKey())) {
                out.put(e.getKey(), e.getValue());
            }
        }
        return out;
    }

    /** Paths where the two values differ after relabelling (at most {@code limit}). */
    public static List<String> diff(Object a, Object b, int limit) {
        List<String> out = new ArrayList<>();
        walk("", normalize(a), normalize(b), out, limit);
        return out;
    }

    @SuppressWarnings("unchecked")
    private static void walk(String path, Object a, Object b, List<String> out, int limit) {
        if (out.size() >= limit) {
            return;
        }
        if (a instanceof Map && b instanceof Map) {
            Map<String, Object> ma = (Map<String, Object>) a;
            Map<String, Object> mb = (Map<String, Object>) b;
            TreeMap<String, Object> keys = new TreeMap<>();
            keys.putAll(ma);
            keys.putAll(mb);
            for (String k : keys.keySet()) {
                if (!ma.containsKey(k) || !mb.containsKey(k)) {
                    out.add(path + "/" + k + " (missing on one side)");
                    continue;
                }
                walk(path + "/" + k, ma.get(k), mb.get(k), out, limit);
            }
            return;
        }
        if (a instanceof List && b instanceof List) {
            List<Object> la = (List<Object>) a;
            List<Object> lb = (List<Object>) b;
            if (la.size() != lb.size()) {
                out.add(path + " (length " + la.size() + " vs " + lb.size() + ")");
                return;
            }
            for (int i = 0; i < la.size(); i++) {
                walk(path + "/" + i, la.get(i), lb.get(i), out, limit);
            }
            return;
        }
        String ca = Json.canonical(a);
        String cb = Json.canonical(b);
        if (!ca.equals(cb)) {
            out.add(path + " (" + abbreviate(ca) + " vs " + abbreviate(cb) + ")");
        }
    }

    /**
     * For two observations equal modulo ids: the map from {@code a}'s object ids to {@code b}'s, from a parallel walk
     * in the same traversal order.
     */
    public static Map<String, String> alignIds(Object a, Object b) {
        Map<String, String> out = new LinkedHashMap<>();
        align(a, b, out);
        return out;
    }

    @SuppressWarnings("unchecked")
    private static void align(Object a, Object b, Map<String, String> out) {
        if (a instanceof Map && b instanceof Map) {
            Map<String, Object> ma = ordered((Map<String, Object>) a);
            Map<String, Object> mb = (Map<String, Object>) b;
            for (Map.Entry<String, Object> e : ma.entrySet()) {
                Object other = mb.get(e.getKey());
                if (e.getKey().equals("object_id") && e.getValue() instanceof String && other instanceof String) {
                    if (!out.containsKey(e.getValue())) {
                        out.put((String) e.getValue(), (String) other);
                    }
                } else {
                    align(e.getValue(), other, out);
                }
            }
        } else if (a instanceof List && b instanceof List) {
            List<Object> la = (List<Object>) a;
            List<Object> lb = (List<Object>) b;
            for (int i = 0; i < Math.min(la.size(), lb.size()); i++) {
                align(la.get(i), lb.get(i), out);
            }
        }
    }

    private static String abbreviate(String s) {
        return s.length() > 60 ? s.substring(0, 57) + "..." : s;
    }
}
