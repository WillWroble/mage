package mage.player.cabt;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * View of a game object in a public (or revealed) zone: graveyard, exile,
 * command, or a hand visible to the selecting player.
 */
public final class MagicObjectView {

    private final MagicObjectReference ref;
    private final List<String> cardTypes;
    private final List<String> subTypes;

    public MagicObjectView(MagicObjectReference ref, List<String> cardTypes, List<String> subTypes) {
        if (ref == null) {
            throw new IllegalArgumentException("object view requires a reference");
        }
        this.ref = ref;
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

    public List<String> getCardTypes() {
        return cardTypes;
    }

    public List<String> getSubTypes() {
        return subTypes;
    }
}
