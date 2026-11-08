package mage.player.spellbench.decide;

import mage.abilities.Ability;
import mage.abilities.SpellAbility;
import mage.game.Game;

/**
 * The XMage agent kit's shared library into the engine's decision mapper (design decision K2): the priority
 * semantic of an XMage action ({@code ability_index}, cast {@code method}) must be computed exactly as the engine's
 * {@code SeatPlayer} computes it, so the kit calls the same functions. See also
 * {@code mage.player.spellbench.observe.KitBridge}.
 */
public final class KitBridge {

    private KitBridge() {
    }

    /** Section 7.2 {@code ability_index}, or -1 when the object does not list the ability. */
    public static long abilityIndex(Game game, Ability a) {
        return SeatPlayer.abilityIndex(game, a);
    }

    /** Section 7.4 {@code method} of a cast. */
    public static String castMethod(SpellAbility sa) {
        return SeatPlayer.castMethod(sa);
    }
}
