package mage.player.cabt;

import mage.abilities.ActivatedAbility;
import mage.game.Game;

/**
 * CABT bridge: applies a validated PRIORITY selection. PASS_PRIORITY passes
 * priority through Player.pass; every playable option dispatches through
 * PlayerImpl.activateAbility, the engine's own root dispatch (playLand for
 * lands, cast for spells, specialAction/playManaAbility/playAbility for the
 * rest). The engine's answer is reported, not assumed: an activation the
 * engine declines or cancels mid-flow comes back as {@link Result#REJECTED}
 * so the trace can say what actually happened — the priority response is
 * still consumed either way, matching HumanPlayer (the engine re-offers
 * priority after a failed activation).
 */
public final class CabtPrioritySelectionApplier {

    public enum Result {
        /** PASS_PRIORITY: priority was passed, no action taken. */
        PASSED,
        /** The engine accepted and performed the selected action. */
        APPLIED,
        /**
         * The engine declined or cancelled the selected action
         * (activateAbility returned false); the response is consumed and the
         * engine re-offers priority.
         */
        REJECTED
    }

    public Result apply(CabtBridgePlayer player, Game game,
                        Selection selection, CabtPriorityPrompt prompt) {
        MagicOption option = prompt.getDecision().options().get(selection.indices().get(0));
        if (option.type() == MagicOptionType.PASS_PRIORITY) {
            player.pass(game);
            return Result.PASSED;
        }
        Object playableIndex = option.payload().get(CabtPriorityOptionFactory.PAYLOAD_PLAYABLE_INDEX);
        if (!(playableIndex instanceof Integer)) {
            throw new IllegalStateException("priority option " + option + " has no playableIndex payload");
        }
        ActivatedAbility ability = prompt.playableAt((Integer) playableIndex);
        return player.activateAbility(ability, game) ? Result.APPLIED : Result.REJECTED;
    }
}
