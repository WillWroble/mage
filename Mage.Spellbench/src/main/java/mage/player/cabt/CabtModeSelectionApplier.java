package mage.player.cabt;

import mage.abilities.Ability;
import mage.abilities.Mode;
import mage.abilities.Modes;
import mage.game.Game;

import java.util.UUID;

/**
 * CABT bridge: resolves a validated selection back to the XMage {@link Mode}
 * object. Like HumanPlayer.chooseMode, the chosen Mode is returned and the
 * engine's Modes.choose(...) marks it selected — no mutation happens here.
 */
public final class CabtModeSelectionApplier {

    public Mode apply(Modes modes, Ability source, Game game,
                      Selection selection, PendingDecision decision) {
        if (selection.indices().isEmpty()) {
            return null;
        }
        MagicOption option = decision.options().get(selection.indices().get(0));
        Object modeId = option.payload().get("modeId");
        if (!(modeId instanceof String)) {
            throw new IllegalStateException("mode option has no modeId payload");
        }
        return modes.get(UUID.fromString((String) modeId));
    }
}
