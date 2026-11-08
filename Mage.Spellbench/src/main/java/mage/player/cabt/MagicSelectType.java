package mage.player.cabt;

/**
 * CABT bridge: which player-input callback a {@link PendingDecision} originates from.
 */
public enum MagicSelectType {
    PRIORITY,
    TARGET,
    YES_NO,
    CHOICE,
    PILE,
    MODE,
    NUMBER,
    MULTI_AMOUNT,
    TRIGGER_ORDER,
    REPLACEMENT_EFFECT,
    PAY_MANA,
    DECLARE_ATTACKERS,
    DECLARE_BLOCKERS,
    MULLIGAN
}
