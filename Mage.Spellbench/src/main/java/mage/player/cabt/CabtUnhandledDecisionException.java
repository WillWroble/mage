package mage.player.cabt;

/**
 * Thrown when the engine asks the bridge for a decision it cannot surface as
 * an option-index prompt: a FAIL_CLOSED callback (see
 * {@link CabtDecisionSurfaceAudit}) or a prompt whose option space exceeds an
 * enumeration cap.
 * <p>
 * This is the fail-closed contract made concrete: an unhandled decision stops
 * the game loudly instead of being silently AI-decided by the inherited
 * ComputerPlayer. Extends {@link UnsupportedOperationException} so earlier
 * cap-throwing call sites keep their contract.
 */
public class CabtUnhandledDecisionException extends UnsupportedOperationException {

    public CabtUnhandledDecisionException(String message) {
        super(message);
    }
}
