package mage.player.cabt;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The result of resolving a whole decklist against XMage's card identity: one
 * {@link CardResolution} per deck entry, in the order submitted. A deck is
 * {@link #isValid() valid} only when every entry resolved; otherwise
 * {@link #failures()} lists exactly which entries could not be resolved so the
 * caller can fail closed with a precise, structured error instead of a silently
 * shortened deck.
 */
public final class DeckValidation {

    /** One deck line: {@code count} copies of {@code requestedName}, resolved. */
    public static final class Entry {
        private final String requestedName;
        private final int count;
        private final CardResolution resolution;

        Entry(String requestedName, int count, CardResolution resolution) {
            this.requestedName = requestedName;
            this.count = count;
            this.resolution = resolution;
        }

        public String requestedName() {
            return requestedName;
        }

        public int count() {
            return count;
        }

        public CardResolution resolution() {
            return resolution;
        }
    }

    private final List<Entry> entries;

    DeckValidation(List<Entry> entries) {
        this.entries = Collections.unmodifiableList(new ArrayList<Entry>(entries));
    }

    public List<Entry> entries() {
        return entries;
    }

    /** True only when every entry resolved to a real XMage card. */
    public boolean isValid() {
        for (Entry entry : entries) {
            if (!entry.resolution().isResolved()) {
                return false;
            }
        }
        return true;
    }

    /** The subset of entries whose names did not resolve (empty when valid). */
    public List<Entry> failures() {
        List<Entry> failures = new ArrayList<Entry>();
        for (Entry entry : entries) {
            if (!entry.resolution().isResolved()) {
                failures.add(entry);
            }
        }
        return failures;
    }
}
