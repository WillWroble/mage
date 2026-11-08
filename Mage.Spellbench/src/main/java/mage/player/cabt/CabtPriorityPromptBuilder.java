package mage.player.cabt;

import mage.abilities.ActivatedAbility;
import mage.game.Game;

import java.util.ArrayList;
import java.util.List;

/**
 * CABT bridge: builds the PRIORITY prompt — PASS_PRIORITY at index 0, then
 * one option per playable ability the engine enumerated via
 * Player.getPlayable(Game, true) (land plays, castable spells, activatable
 * abilities, special actions). The caller passes the playables in so stubbed
 * tests can drive the builder directly and the bridge player enumerates once
 * per prompt.
 */
public final class CabtPriorityPromptBuilder {

    public CabtPriorityPrompt build(CabtBridgePlayer player, Game game,
                                    List<ActivatedAbility> playables) {
        PendingDecision decision = PendingDecision.priority(player.getId());
        List<ActivatedAbility> kept = new ArrayList<ActivatedAbility>();
        for (ActivatedAbility ability : playables) {
            decision.addOption(CabtPriorityOptionFactory.playableOption(game, ability, kept.size()));
            kept.add(ability);
        }
        return new CabtPriorityPrompt(decision, kept);
    }
}
