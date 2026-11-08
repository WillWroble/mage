package mage.player.cabt;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Static metadata for one ability printed on a card: what kind of ability it
 * is, its rule text, and the cost/target/mode texts XMage derives from the
 * ability definition.
 * <p>
 * This is reference data only. Whether an ability is currently playable, and
 * with which targets/modes, always comes from live XMage state through the
 * prompt callbacks — never from this class.
 */
public final class MagicAbilityData {

    private final String abilityType;
    private final String rule;
    private final String zone;
    private final String manaCost;
    private final List<String> costs;
    private final List<String> targets;
    private final List<String> modes;

    public MagicAbilityData(String abilityType,
                            String rule,
                            String zone,
                            String manaCost,
                            List<String> costs,
                            List<String> targets,
                            List<String> modes) {
        if (abilityType == null || rule == null || zone == null
                || costs == null || targets == null || modes == null) {
            throw new IllegalArgumentException("ability data fields must not be null (manaCost may be null)");
        }
        this.abilityType = abilityType;
        this.rule = rule;
        this.zone = zone;
        this.manaCost = manaCost;
        this.costs = Collections.unmodifiableList(new ArrayList<String>(costs));
        this.targets = Collections.unmodifiableList(new ArrayList<String>(targets));
        this.modes = Collections.unmodifiableList(new ArrayList<String>(modes));
    }

    public String getAbilityType() {
        return abilityType;
    }

    public String getRule() {
        return rule;
    }

    public String getZone() {
        return zone;
    }

    public String getManaCost() {
        return manaCost;
    }

    public List<String> getCosts() {
        return costs;
    }

    public List<String> getTargets() {
        return targets;
    }

    public List<String> getModes() {
        return modes;
    }
}
