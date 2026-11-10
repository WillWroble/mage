package org.mage.spellbench;

/**
 * The two engines disagree: a game state, a decision one engine asks and the other cannot answer, or an answer with
 * no counterpart. The lockstep game fails and is counted.
 */
final class LockstepDivergence extends RuntimeException {

    private static final long serialVersionUID = 1L;

    LockstepDivergence(String message) {
        super(message);
    }
}
