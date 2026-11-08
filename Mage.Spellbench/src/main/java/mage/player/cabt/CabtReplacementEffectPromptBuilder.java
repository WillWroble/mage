package mage.player.cabt;

import mage.MageObject;
import mage.players.Player;

import java.util.Map;

/**
 * CABT bridge: builds the REPLACEMENT_EFFECT prompt from the effectsMap the
 * engine passes to chooseReplacementEffect, preserving its iteration order —
 * the same key/text source HumanPlayer turns into a Choice. The answer XMage
 * expects is the chosen entry's index in that order.
 */
public final class CabtReplacementEffectPromptBuilder {

    public PendingDecision build(Player player, Map<String, String> effectsMap,
                                 Map<String, MageObject> objectsMap) {
        PendingDecision decision = new PendingDecision(
                MagicSelectType.REPLACEMENT_EFFECT, player.getId(), 1, 1);
        int originalIndex = 0;
        for (Map.Entry<String, String> entry : effectsMap.entrySet()) {
            MageObject object = objectsMap == null ? null : objectsMap.get(entry.getKey());
            decision.addOption(CabtReplacementEffectOptionFactory.replacementEffectOption(
                    entry.getKey(), entry.getValue(), object, originalIndex));
            originalIndex++;
        }
        return decision;
    }
}
