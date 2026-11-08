package mage.player.cabt;

/**
 * How the CABT bridge currently treats a decision surface.
 */
public enum CabtDecisionSurfaceStatus {

    /**
     * Already routed through CabtBridgeController as an option-index prompt.
     */
    SURFACED,

    /**
     * A prompt family the bridge resolves automatically without a controller
     * round-trip because exactly one legal outcome exists (whole-surface
     * shortcuts; single-option shortcuts inside SURFACED families stay
     * SURFACED).
     */
    AUTO_SELECTED,

    /**
     * Deliberately left to the inherited/engine implementation; not a
     * decision the bridge needs to surface (e.g. commit APIs invoked from a
     * surfaced prompt's applier).
     */
    DELEGATED,

    /**
     * A real decision that is not yet surfaced. The bridge override throws
     * {@link CabtUnhandledDecisionException} rather than letting the
     * inherited ComputerPlayer silently decide it.
     */
    FAIL_CLOSED,

    /**
     * Not a prompt: a query API or design note kept as reference for building
     * option payloads later.
     */
    REFERENCE_ONLY
}
