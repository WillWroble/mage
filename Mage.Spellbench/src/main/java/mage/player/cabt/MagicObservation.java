package mage.player.cabt;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * CABT bridge observation envelope, mirroring CABT's ToJsonApi shape:
 * "logs" + "current" + "select". Plain DTO — JSON emission comes later.
 */
public final class MagicObservation {

    private final List<?> logs;
    private final MagicCurrent current;
    private final MagicSelectView select;

    public MagicObservation(List<?> logs, MagicCurrent current, MagicSelectView select) {
        if (logs == null) {
            throw new IllegalArgumentException("logs must not be null");
        }
        if (current == null) {
            throw new IllegalArgumentException("current must not be null");
        }
        if (select == null) {
            throw new IllegalArgumentException("select must not be null");
        }
        this.logs = Collections.unmodifiableList(new ArrayList<Object>(logs));
        this.current = current;
        this.select = select;
    }

    public List<?> getLogs() {
        return logs;
    }

    public MagicCurrent getCurrent() {
        return current;
    }

    public MagicSelectView getSelect() {
        return select;
    }
}
