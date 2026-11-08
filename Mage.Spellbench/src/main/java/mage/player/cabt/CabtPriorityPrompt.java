package mage.player.cabt;

import mage.abilities.ActivatedAbility;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * CABT bridge: a built PRIORITY prompt together with the playable abilities
 * its options were enumerated from. The option payloads carry only
 * serializable identifiers; the live {@link ActivatedAbility} references stay
 * here so the applier dispatches exactly the object the option described
 * (payload "playableIndex" indexes into {@link #playables()}).
 */
public final class CabtPriorityPrompt {

    private final PendingDecision decision;
    private final List<ActivatedAbility> playables;

    CabtPriorityPrompt(PendingDecision decision, List<ActivatedAbility> playables) {
        this.decision = decision;
        this.playables = Collections.unmodifiableList(new ArrayList<ActivatedAbility>(playables));
    }

    public PendingDecision getDecision() {
        return decision;
    }

    public List<ActivatedAbility> playables() {
        return playables;
    }

    public ActivatedAbility playableAt(int playableIndex) {
        if (playableIndex < 0 || playableIndex >= playables.size()) {
            throw new IllegalStateException(
                    "playableIndex " + playableIndex + " outside the " + playables.size()
                            + " playable abilities this prompt was built from");
        }
        return playables.get(playableIndex);
    }
}
