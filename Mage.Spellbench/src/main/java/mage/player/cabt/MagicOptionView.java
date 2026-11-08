package mage.player.cabt;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * CABT bridge observation: one selectable option as it will appear in the
 * "select.option" array — index, type, label, and an opaque payload.
 */
public final class MagicOptionView {

    private final int index;
    private final String type;
    private final String label;
    private final Map<String, Object> payload;

    public MagicOptionView(int index, String type, String label, Map<String, Object> payload) {
        this.index = index;
        this.type = type;
        this.label = label;
        this.payload = payload == null
                ? Collections.<String, Object>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<String, Object>(payload));
    }

    public int getIndex() {
        return index;
    }

    public String getType() {
        return type;
    }

    public String getLabel() {
        return label;
    }

    public Map<String, Object> getPayload() {
        return payload;
    }
}
