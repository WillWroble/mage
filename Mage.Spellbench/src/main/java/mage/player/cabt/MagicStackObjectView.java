package mage.player.cabt;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * View of a spell or ability on the stack, with the ids of its current
 * targets so transitions can be traced back to the objects they reference.
 */
public final class MagicStackObjectView {

    private final MagicObjectReference ref;
    private final String controllerId;
    private final String sourceId;
    private final String name;
    private final String stackObjectClass;
    private final List<String> targetIds;

    public MagicStackObjectView(MagicObjectReference ref, String controllerId, String sourceId,
                                String name, String stackObjectClass, List<String> targetIds) {
        if (ref == null) {
            throw new IllegalArgumentException("stack object view requires a reference");
        }
        this.ref = ref;
        this.controllerId = controllerId;
        this.sourceId = sourceId;
        this.name = name;
        this.stackObjectClass = stackObjectClass;
        this.targetIds = targetIds == null
                ? Collections.<String>emptyList()
                : Collections.unmodifiableList(new ArrayList<String>(targetIds));
    }

    public MagicObjectReference getRef() {
        return ref;
    }

    public String getControllerId() {
        return controllerId;
    }

    public String getSourceId() {
        return sourceId;
    }

    public String getName() {
        return name;
    }

    public String getStackObjectClass() {
        return stackObjectClass;
    }

    public List<String> getTargetIds() {
        return targetIds;
    }
}
