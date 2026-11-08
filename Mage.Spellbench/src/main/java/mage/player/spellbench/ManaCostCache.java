package mage.player.spellbench;

import mage.abilities.costs.mana.ManaCosts;
import mage.abilities.costs.mana.ManaCostsImpl;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * XMage's process-wide cache of parsed mana costs ({@code ManaCostsImpl.costsCache}) mints object ids on a miss
 * and none on a hit, so a game that parses a mana string mid-game (a plotted card's spell ability, say) drew a
 * different number of ids depending on the games its process had played before, and its digest changed (X5).
 * The engine snapshots the cache once after the boot warm-up and restores that snapshot before building each
 * game: every game then starts from the cache state of a fresh process's first game, so its ids and digest
 * depend on the game alone (spec 11.8), and a fresh process's games keep the digests they had.
 */
public final class ManaCostCache {

    private static Map<String, ManaCosts> snapshot;

    private ManaCostCache() {
    }

    /** Takes the snapshot (after the boot warm-up). */
    public static synchronized void snapshot() {
        snapshot = new LinkedHashMap<>(cache());
    }

    /** Restores the snapshot (before a game is built); a no-op before {@link #snapshot()}. */
    public static synchronized void restore() {
        if (snapshot == null) {
            return;
        }
        Map<String, ManaCosts> cache = cache();
        cache.clear();
        cache.putAll(snapshot);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, ManaCosts> cache() {
        try {
            Field f = ManaCostsImpl.class.getDeclaredField("costsCache");
            f.setAccessible(true);
            return (Map<String, ManaCosts>) f.get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("ManaCostsImpl.costsCache is not reachable", e);
        }
    }
}
