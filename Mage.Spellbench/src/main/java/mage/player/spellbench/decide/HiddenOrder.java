package mage.player.spellbench.decide;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Section 7.1 and validator V5 rule 5: candidates that reference cards in zones hidden from the acting seat (a
 * library, or the other seat's hand) come, among themselves, in {@code (card_name, object_id)} order. The key
 * follows P's {@code host.hidden.hidden_candidate_key}: every hidden reference of the semantic in
 * {@code candidates.object_references} order (fields in sorted name order, depth first), a null name first.
 */
final class HiddenOrder {

    private HiddenOrder() {
    }

    /** The key, or null when the semantic references no hidden card. Each entry is {name or null, object_id}. */
    static List<String[]> key(Map<String, Object> semantic, String viewer) {
        List<Map<String, Object>> refs = new ArrayList<>();
        for (String field : new TreeSet<>(semantic.keySet())) {
            collect(semantic.get(field), field, refs);
        }
        List<String[]> out = new ArrayList<>();
        for (Map<String, Object> ref : refs) {
            String zone = (String) ref.get("zone");
            boolean hidden = "library".equals(zone)
                    || ("hand".equals(zone) && !viewer.equals(ref.get("owner_seat")));
            if (hidden) {
                out.add(new String[]{(String) ref.get("card_name"), (String) ref.get("object_id")});
            }
        }
        return out.isEmpty() ? null : out;
    }

    private static void collect(Object value, String field, List<Map<String, Object>> out) {
        if (!(value instanceof Map)) {
            if (value instanceof List && field.equals("piles")) {
                for (Object pile : (List<?>) value) {
                    for (Object r : (List<?>) pile) {
                        out.add(Exchange.castMap(r));
                    }
                }
            }
            return;
        }
        Map<String, Object> m = Exchange.castMap(value);
        if (m.containsKey("object_id")) {
            out.add(m);
        } else if (m.containsKey("object")) {
            out.add(Exchange.castMap(m.get("object")));
        } else if (m.containsKey("trigger")) {
            Map<String, Object> t = Exchange.castMap(m.get("trigger"));
            for (Object r : (List<?>) t.get("event_objects")) {
                out.add(Exchange.castMap(r));
            }
            if (t.get("source") != null) {
                out.add(Exchange.castMap(t.get("source")));
            }
        }
    }

    static int compare(List<String[]> a, List<String[]> b) {
        for (int i = 0; i < Math.min(a.size(), b.size()); i++) {
            int c = compareName(a.get(i)[0], b.get(i)[0]);
            if (c == 0) {
                c = codePoints(a.get(i)[1], b.get(i)[1]);
            }
            if (c != 0) {
                return c;
            }
        }
        return Integer.compare(a.size(), b.size());
    }

    private static int compareName(String x, String y) {
        if (x == null) {
            return y == null ? 0 : -1;
        }
        return y == null ? 1 : codePoints(x, y);
    }

    static int codePoints(String x, String y) {
        int i = 0;
        int j = 0;
        while (i < x.length() && j < y.length()) {
            int a = x.codePointAt(i);
            int b = y.codePointAt(j);
            if (a != b) {
                return Integer.compare(a, b);
            }
            i += Character.charCount(a);
            j += Character.charCount(b);
        }
        return Integer.compare(x.length() - i, y.length() - j);
    }
}
