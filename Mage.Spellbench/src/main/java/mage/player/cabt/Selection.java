package mage.player.cabt;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * CABT bridge: the controller's answer to a {@link PendingDecision} — the chosen
 * option indices, in the order they were picked. Immutable.
 */
public final class Selection {

    private final List<Integer> indices;

    public Selection(List<Integer> indices) {
        this.indices = Collections.unmodifiableList(new ArrayList<>(indices));
    }

    public static Selection of(int... indices) {
        List<Integer> list = new ArrayList<>(indices.length);
        for (int index : indices) {
            list.add(index);
        }
        return new Selection(list);
    }

    public List<Integer> indices() {
        return indices;
    }

    @Override
    public String toString() {
        return "Selection" + indices;
    }
}
