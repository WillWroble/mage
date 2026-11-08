package mage.player.cabt;

import mage.game.Game;
import mage.players.Player;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * CABT bridge: builds the pregame MULLIGAN prompt — option 0 Keep, option 1
 * Mulligan. Bottoming/discarding after a mulligan is handled by the engine's
 * own follow-up callbacks (target/card/choice prompts), not here.
 */
public final class CabtMulliganPromptBuilder {

    public PendingDecision build(Player player, Game game) {
        PendingDecision decision = new PendingDecision(
                MagicSelectType.MULLIGAN, player.getId(), 1, 1);
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("handCount", player.getHand() == null ? 0 : player.getHand().size());
        payload.put("libraryCount", player.getLibrary() == null ? 0 : player.getLibrary().size());
        payload.put("mulliganDownTo", game.mulliganDownTo(player.getId()));
        decision.addOption(new MagicOption(MagicOptionType.PROMPT_KEEP, "Keep", payload));
        decision.addOption(new MagicOption(MagicOptionType.PROMPT_MULLIGAN, "Mulligan", payload));
        return decision;
    }
}
