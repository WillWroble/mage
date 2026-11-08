package mage.player.cabt;

import mage.cards.Card;
import mage.players.Player;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CABT bridge: builds the PILE prompt for choosePile. Option 0 is pile 1,
 * option 1 is pile 2; each payload carries the pile's cards as
 * objectId/name pairs.
 */
public final class CabtPilePromptBuilder {

    public PendingDecision build(Player player, String message,
                                 List<? extends Card> pile1, List<? extends Card> pile2) {
        PendingDecision decision = new PendingDecision(
                MagicSelectType.PILE, player.getId(), 1, 1);
        decision.addOption(pileOption(1, "Pile 1", message, pile1));
        decision.addOption(pileOption(2, "Pile 2", message, pile2));
        return decision;
    }

    private static MagicOption pileOption(int pileIndex, String label, String message,
                                          List<? extends Card> pile) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("pileIndex", pileIndex);
        payload.put("message", message);
        payload.put("cards", cardsPayload(pile));
        return new MagicOption(MagicOptionType.PROMPT_PILE, label, payload);
    }

    private static List<Map<String, Object>> cardsPayload(List<? extends Card> pile) {
        List<Map<String, Object>> cards = new ArrayList<Map<String, Object>>();
        if (pile != null) {
            for (Card card : pile) {
                Map<String, Object> entry = new LinkedHashMap<String, Object>();
                entry.put("objectId", card.getId() == null ? null : card.getId().toString());
                entry.put("name", card.getName());
                cards.add(entry);
            }
        }
        return cards;
    }
}
