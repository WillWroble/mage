package mage.player.cabt;

import mage.MageObject;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * CABT bridge: builds one option per applicable replacement effect, carrying
 * the effect key/text plus the associated object from the objectsMap when
 * available. originalIndex is the entry's position in the effectsMap
 * iteration order — the int XMage expects back.
 */
public final class CabtReplacementEffectOptionFactory {

    private CabtReplacementEffectOptionFactory() {
    }

    public static MagicOption replacementEffectOption(String effectKey, String effectText,
                                                      MageObject object, int originalIndex) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("effectKey", effectKey);
        payload.put("effectText", effectText);
        payload.put("objectId", object == null || object.getId() == null
                ? null : object.getId().toString());
        payload.put("objectName", object == null ? null : object.getName());
        payload.put("objectClass", object == null ? null : object.getClass().getSimpleName());
        payload.put("originalIndex", originalIndex);
        String label = "Choose replacement effect: "
                + (effectText == null ? effectKey : effectText);
        return new MagicOption(MagicOptionType.PROMPT_REPLACEMENT_EFFECT, label, payload);
    }
}
