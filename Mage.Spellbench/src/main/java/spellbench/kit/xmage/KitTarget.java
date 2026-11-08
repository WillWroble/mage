package spellbench.kit.xmage;

import mage.MageObject;
import mage.abilities.Ability;
import mage.constants.Zone;
import mage.filter.Filter;
import mage.filter.FilterCard;
import mage.game.Game;
import mage.players.Player;
import mage.target.TargetImpl;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * A synthesized dialog's target (design Section 6.3): its possible targets are exactly the decision's candidate
 * objects or players (as world UUIDs), its counts come from the candidate fields, and {@code notTarget} is set for
 * selections. The bot's {@code ComputerPlayer} heuristics then choose among exactly what the engine offered.
 */
public final class KitTarget extends TargetImpl {

    private static final long serialVersionUID = 1L;

    private final Set<UUID> possible;
    private final FilterCard filter = new FilterCard("offered object");

    public KitTarget(Set<UUID> possible, int min, int max, boolean notTarget) {
        super(notTarget);
        this.possible = new LinkedHashSet<>(possible);
        this.minNumberOfTargets = min;
        this.maxNumberOfTargets = max;
        this.zone = Zone.ALL;
        this.targetName = "offered object";
    }

    private KitTarget(final KitTarget t) {
        super(t);
        this.possible = new LinkedHashSet<>(t.possible);
    }

    @Override
    public KitTarget copy() {
        return new KitTarget(this);
    }

    @Override
    public boolean canChoose(UUID sourceControllerId, Ability source, Game game) {
        return possible.size() >= minNumberOfTargets;
    }

    @Override
    public Set<UUID> possibleTargets(UUID sourceControllerId, Ability source, Game game) {
        Set<UUID> out = new LinkedHashSet<>();
        for (UUID id : possible) {
            if (!getTargets().contains(id)) {
                out.add(id);
            }
        }
        return out;
    }

    @Override
    public boolean canTarget(UUID id, Ability source, Game game) {
        return possible.contains(id);
    }

    @Override
    public boolean canTarget(UUID playerId, UUID id, Ability source, Game game) {
        return possible.contains(id);
    }

    @Override
    public Filter getFilter() {
        return filter;
    }

    @Override
    public String getTargetedName(Game game) {
        StringBuilder sb = new StringBuilder();
        for (UUID id : getTargets()) {
            MageObject o = game.getObject(id);
            Player p = game.getPlayer(id);
            sb.append(o != null ? o.getLogName() : (p != null ? p.getLogName() : "?")).append(' ');
        }
        return sb.toString().trim();
    }
}
