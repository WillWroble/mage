package mage.player.spellbench.observe;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * A card in a zone hidden from the viewer (a library, or the other seat's hand) that the current decision shows it:
 * a {@code known} entry with a look id (Sections 5.3 and 6.7). {@code how} is {@code looked_at}, {@code revealed}
 * or {@code searching}. A library card has exactly one position, except while searching, where both may be null;
 * a hand card has none. Positions are given only where the viewer knows them.
 */
public final class Look {

    static final List<String> HOWS = Arrays.asList("looked_at", "revealed", "searching");

    public final UUID cardId;
    public final String how;
    public final Integer fromTop;
    public final Integer fromBottom;

    public Look(UUID cardId, String how, Integer fromTop, Integer fromBottom) {
        if (!HOWS.contains(how)) {
            throw new IllegalArgumentException("a current look is looked_at, revealed or searching, not " + how);
        }
        if (fromTop != null && fromBottom != null) {
            throw new IllegalArgumentException("a known entry has at most one position");
        }
        this.cardId = cardId;
        this.how = how;
        this.fromTop = fromTop;
        this.fromBottom = fromBottom;
    }

    /** A card being searched for, or one whose position the viewer does not know: no position. */
    public static Look searching(UUID cardId) {
        return new Look(cardId, "searching", null, null);
    }
}
