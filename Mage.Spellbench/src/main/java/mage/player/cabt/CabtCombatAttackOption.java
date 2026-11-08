package mage.player.cabt;

import java.util.UUID;

/**
 * CABT bridge: one legal attacker/defender pair. The option is a combat
 * declaration, not "deal damage" — combat damage stays an engine consequence.
 */
public final class CabtCombatAttackOption {

    private final UUID attackerId;
    private final String attackerName;
    private final UUID defenderId;
    private final String defenderName;
    private final String defenderType;

    public CabtCombatAttackOption(UUID attackerId, String attackerName,
                                  UUID defenderId, String defenderName, String defenderType) {
        this.attackerId = attackerId;
        this.attackerName = attackerName;
        this.defenderId = defenderId;
        this.defenderName = defenderName;
        this.defenderType = defenderType;
    }

    public UUID getAttackerId() {
        return attackerId;
    }

    public String getAttackerName() {
        return attackerName;
    }

    public UUID getDefenderId() {
        return defenderId;
    }

    public String getDefenderName() {
        return defenderName;
    }

    public String getDefenderType() {
        return defenderType;
    }
}
