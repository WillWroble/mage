package mage.player.cabt;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * CABT bridge: one selectable option inside a {@link PendingDecision}.
 * Immutable; the payload carries option-specific data for later tasks (e.g. card ids).
 */
public final class MagicOption {

    private final MagicOptionType type;
    private final String label;
    private final Map<String, Object> payload;

    public MagicOption(MagicOptionType type, String label, Map<String, Object> payload) {
        this.type = type;
        this.label = label;
        this.payload = payload == null
                ? Collections.<String, Object>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(payload));
    }

    public MagicOptionType type() {
        return type;
    }

    public String label() {
        return label;
    }

    public Map<String, Object> payload() {
        return payload;
    }

    @Override
    public String toString() {
        return type + " (" + label + ')';
    }
}
