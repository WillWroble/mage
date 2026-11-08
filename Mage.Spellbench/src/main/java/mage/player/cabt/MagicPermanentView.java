package mage.player.cabt;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * View of a battlefield permanent. Power/toughness are nullable for
 * non-creatures or when the exact values are unavailable.
 */
public final class MagicPermanentView {

    private final MagicObjectReference ref;
    private final String controllerId;
    private final String ownerId;
    private final boolean tapped;
    private final boolean faceDown;
    private final Integer power;
    private final Integer toughness;
    private final Map<String, Integer> counters;
    private final List<String> cardTypes;
    private final List<String> subTypes;

    public MagicPermanentView(MagicObjectReference ref, String controllerId, String ownerId,
                              boolean tapped, boolean faceDown,
                              Integer power, Integer toughness,
                              Map<String, Integer> counters,
                              List<String> cardTypes, List<String> subTypes) {
        if (ref == null) {
            throw new IllegalArgumentException("permanent view requires a reference");
        }
        this.ref = ref;
        this.controllerId = controllerId;
        this.ownerId = ownerId;
        this.tapped = tapped;
        this.faceDown = faceDown;
        this.power = power;
        this.toughness = toughness;
        this.counters = counters == null
                ? Collections.<String, Integer>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<String, Integer>(counters));
        this.cardTypes = copyOf(cardTypes);
        this.subTypes = copyOf(subTypes);
    }

    private static List<String> copyOf(List<String> values) {
        return values == null
                ? Collections.<String>emptyList()
                : Collections.unmodifiableList(new ArrayList<String>(values));
    }

    public MagicObjectReference getRef() {
        return ref;
    }

    public String getControllerId() {
        return controllerId;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public boolean isTapped() {
        return tapped;
    }

    public boolean isFaceDown() {
        return faceDown;
    }

    public Integer getPower() {
        return power;
    }

    public Integer getToughness() {
        return toughness;
    }

    public Map<String, Integer> getCounters() {
        return counters;
    }

    public List<String> getCardTypes() {
        return cardTypes;
    }

    public List<String> getSubTypes() {
        return subTypes;
    }
}
