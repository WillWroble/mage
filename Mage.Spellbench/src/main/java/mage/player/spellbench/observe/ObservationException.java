package mage.player.spellbench.observe;

/**
 * A state the observation cannot represent, or an object id collision (Section 5.3). The game ends {@code halted}
 * with a reason beginning {@code engine_contract_failure:} (Section 9.5). {@link #cause} is a short phrase that
 * names no card, so it may reach agents; the detail (the offending value) goes to stderr only.
 */
public final class ObservationException extends Exception {

    private static final long serialVersionUID = 1L;

    /** For the terminal reason: {@code observation_<cause>}. */
    public final String cause;

    ObservationException(String cause, String detail) {
        super(cause + ": " + detail);
        this.cause = "observation_" + cause;
    }
}
