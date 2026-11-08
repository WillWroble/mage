package mage.player.cabt;

/**
 * CABT bridge: thrown when a controller's {@link Selection} violates the
 * constraints of the {@link PendingDecision} it answers.
 */
public final class InvalidSelectionException extends RuntimeException {

    public InvalidSelectionException(String message) {
        super(message);
    }
}
