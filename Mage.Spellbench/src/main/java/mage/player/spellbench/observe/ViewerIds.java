package mage.player.spellbench.observe;

import mage.player.spellbench.ids.ObjectIds;

import java.util.HashMap;
import java.util.Map;

/**
 * One viewer's object ids (Section 5.3), minted with {@link ObjectIds} over the internal key
 * {@code <uuid>:z<zone change counter>}.
 * <ul>
 * <li>An object in a zone the viewer sees keeps its id while it stays in the viewer's consecutive observations.
 * If its internal key returns after it left the viewer's observation (XMage restores zone change counters when it
 * rolls back a failed action), it is a new incarnation, minted from {@code <key>:r<n>}: an id never returns
 * (Section 5.3; validator V7).</li>
 * <li>A card in a zone hidden from the viewer gets a look id ({@code <key>:look:<n>}) while consecutive observations
 * show it; a later showing is a new look with the next n.</li>
 * <li>Every id minted for this viewer is remembered with the identity it stands for; a second identity under the
 * same id is a collision, and the game ends halted (Section 5.3).</li>
 * </ul>
 * The counters advance only on what this viewer's own observations show, never on hidden events.
 */
final class ViewerIds {

    private static final class Held {
        final String id;
        final String zone;

        Held(String id, String zone) {
            this.id = id;
            this.zone = zone;
        }
    }

    private final ObjectIds ids;
    private final String viewer;
    private final Map<String, String> minted = new HashMap<>();        // object id -> identity it stands for
    private final Map<String, Integer> incarnations = new HashMap<>(); // internal key -> last incarnation
    private final Map<String, Integer> lookCounts = new HashMap<>();   // internal key -> looks so far
    private Map<String, Held> previous = new HashMap<>();
    private Map<String, Held> current = new HashMap<>();
    private Map<String, String> previousLooks = new HashMap<>();
    private Map<String, String> currentLooks = new HashMap<>();

    ViewerIds(ObjectIds ids, String viewer) {
        this.ids = ids;
        this.viewer = viewer;
    }

    void begin() {
        current = new HashMap<>();
        currentLooks = new HashMap<>();
    }

    /** Ends one observation: what it held is what the next one may keep. */
    void commit() {
        previous = current;
        previousLooks = currentLooks;
    }

    /** The id of an object in a zone the viewer sees. */
    String visible(String key, String zone) throws ObservationException {
        Held h = current.get(key);
        if (h != null) {
            return h.id;
        }
        Held p = previous.get(key);
        String id;
        if (p != null && p.zone.equals(zone)) {
            id = p.id;
        } else {
            Integer last = incarnations.get(key);
            int n = last == null ? 0 : last + 1;
            incarnations.put(key, n);
            String incarnation = n == 0 ? key : key + ":r" + n;
            id = ids.visible(viewer, incarnation);
            register(id, incarnation);
        }
        current.put(key, new Held(id, zone));
        return id;
    }

    /**
     * A sort key for objects that have no visible order among themselves: the id the object's first incarnation
     * would get, computed without minting anything (a keyed hash: no internal id order shows through).
     */
    String tieKey(String key) {
        return ids.visible(viewer, key);
    }

    /** The look id of a card in a zone hidden from the viewer that the current decision shows. */
    String look(String key) throws ObservationException {
        String id = currentLooks.get(key);
        if (id != null) {
            return id;
        }
        id = previousLooks.get(key);
        if (id == null) {
            Integer last = lookCounts.get(key);
            int n = last == null ? 0 : last;
            lookCounts.put(key, n + 1);
            id = ids.look(viewer, key, n);
            register(id, key + ":look:" + n);
        }
        currentLooks.put(key, id);
        return id;
    }

    private void register(String id, String identity) throws ObservationException {
        String before = minted.put(id, identity);
        if (before != null && !before.equals(identity)) {
            throw new ObservationException("object_id_collision", viewer + " " + id);
        }
    }
}
