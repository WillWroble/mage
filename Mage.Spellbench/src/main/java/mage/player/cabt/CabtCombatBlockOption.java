package mage.player.cabt;

import java.util.UUID;

/**
 * CABT bridge: one legal blocker/attacker pair for the defending player.
 */
public final class CabtCombatBlockOption {

    private final UUID blockerId;
    private final String blockerName;
    private final UUID attackerId;
    private final String attackerName;
    private final UUID defendingPlayerId;

    public CabtCombatBlockOption(UUID blockerId, String blockerName,
                                 UUID attackerId, String attackerName, UUID defendingPlayerId) {
        this.blockerId = blockerId;
        this.blockerName = blockerName;
        this.attackerId = attackerId;
        this.attackerName = attackerName;
        this.defendingPlayerId = defendingPlayerId;
    }

    public UUID getBlockerId() {
        return blockerId;
    }

    public String getBlockerName() {
        return blockerName;
    }

    public UUID getAttackerId() {
        return attackerId;
    }

    public String getAttackerName() {
        return attackerName;
    }

    public UUID getDefendingPlayerId() {
        return defendingPlayerId;
    }
}
