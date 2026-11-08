package mage.player.cabt;

/**
 * Thrown inside the game thread when a decision is requested after the
 * session was closed ({@code game_finish}): the game loop unwinds instead of
 * waiting forever for a selection that will never come.
 */
public final class CabtSessionClosedException extends RuntimeException {

    public CabtSessionClosedException() {
        super("CABT session closed while a decision was pending");
    }
}
