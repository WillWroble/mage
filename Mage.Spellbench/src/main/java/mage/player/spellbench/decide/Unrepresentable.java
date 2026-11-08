package mage.player.spellbench.decide;

/**
 * A decision this mapper cannot pose faithfully: the game ends {@code halted} with reason
 * {@code engine_contract_failure:<cause>} (Section 9.5). {@link #cause} names no card; the detail goes to stderr.
 */
final class Unrepresentable extends Exception {

    private static final long serialVersionUID = 1L;

    final String cause;

    Unrepresentable(String cause, String detail) {
        super(cause + ": " + detail);
        this.cause = cause;
    }
}
