package mage.player.cabt;

import mage.abilities.Ability;
import mage.abilities.Mode;
import mage.abilities.Modes;
import mage.game.Game;
import mage.players.Player;

/**
 * CABT bridge: builds the MODE prompt for chooseMode, mirroring
 * HumanPlayer.chooseMode's option filtering — iterate
 * modes.getAvailableModes(source, game), skip modes already selected in the
 * current cast (unless the same mode may be chosen more than once), and
 * include only modes whose targets can be chosen.
 */
public final class CabtModePromptBuilder {

    public PendingDecision build(Player player, Game game, Modes modes, Ability source) {
        int minCount = modes.isMayChooseNone() ? 0 : 1;
        PendingDecision decision = new PendingDecision(
                MagicSelectType.MODE, player.getId(), minCount, 1);
        for (Mode mode : modes.getAvailableModes(source, game)) {
            if (!modes.isMayChooseSameModeMoreThanOnce()
                    && modes.getSelectedModes().contains(mode.getId())) {
                continue;
            }
            if (source != null
                    && !mode.getTargets().canChoose(source.getControllerId(), source, game)) {
                continue;
            }
            decision.addOption(CabtModeOptionFactory.modeOption(game, modes, mode, source));
        }
        return decision;
    }
}
