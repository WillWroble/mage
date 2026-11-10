package mage.player.spellbench.observe;

/**
 * Who holds priority at a prompt (Section 6.2 {@code priority_seat}). XMage keeps its priority player set through
 * resolution and turn-based actions, so the holder is tracked from the decision stream instead: the seat at a
 * priority prompt holds priority, and keeps it through the prompts of the action it took (targets, modes, costs)
 * until the next priority prompt. After a pass, the prompts until the next priority prompt (resolution, combat
 * declarations, cleanup) have no holder.
 */
public final class PriorityHolder {

    private String acting;

    /** The {@code priority_seat} of a prompt posed to {@code seat}. */
    public String holder(String seat, boolean priorityPrompt) {
        return priorityPrompt ? seat : acting;
    }

    /** Records an answer: a priority action keeps its seat holding priority, a pass ends the holding. */
    public void answered(String seat, boolean priorityPrompt, boolean passed) {
        if (priorityPrompt) {
            acting = passed ? null : seat;
        }
    }
}
