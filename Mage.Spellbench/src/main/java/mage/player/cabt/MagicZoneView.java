package mage.player.cabt;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A named zone with its visible objects. Used for zones addressed by name
 * (e.g. revealed or named exile zones) rather than by a dedicated field on
 * {@link MagicCurrent} or {@link MagicPlayerView}.
 */
public final class MagicZoneView {

    private final String zone;
    private final List<MagicObjectView> objects;

    public MagicZoneView(String zone, List<MagicObjectView> objects) {
        this.zone = zone;
        this.objects = objects == null
                ? Collections.<MagicObjectView>emptyList()
                : Collections.unmodifiableList(new ArrayList<MagicObjectView>(objects));
    }

    public String getZone() {
        return zone;
    }

    public List<MagicObjectView> getObjects() {
        return objects;
    }
}
