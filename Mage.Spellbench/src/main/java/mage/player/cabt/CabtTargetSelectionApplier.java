package mage.player.cabt;

import mage.abilities.Ability;
import mage.game.Game;
import mage.target.Target;
import mage.target.TargetCard;

import java.util.UUID;

/**
 * CABT bridge: applies a validated selection back to the engine's target
 * object — Target.addTarget(...) for targeted prompts, TargetCard.add(...)
 * for card selection — mirroring how XMage's own players commit targets.
 * <p>
 * Returns true when the target object is chosen/completed enough for XMage
 * to proceed, false when it remains unchosen (including an empty optional
 * selection).
 */
public final class CabtTargetSelectionApplier {

    public boolean applyToTarget(Target target, Ability source, Game game,
                                 Selection selection, PendingDecision decision) {
        for (Integer index : selection.indices()) {
            target.addTarget(selectedTargetId(decision, index), source, game);
        }
        if (selection.indices().isEmpty()) {
            return false;
        }
        return target.isChosen(game);
    }

    public boolean applyToTargetCard(TargetCard target, Game game,
                                     Selection selection, PendingDecision decision) {
        for (Integer index : selection.indices()) {
            target.add(selectedTargetId(decision, index), game);
        }
        if (selection.indices().isEmpty()) {
            return false;
        }
        return target.isChosen(game);
    }

    private static UUID selectedTargetId(PendingDecision decision, int index) {
        Object targetId = decision.options().get(index)
                .payload().get(CabtTargetOptionFactory.PAYLOAD_TARGET_ID);
        if (!(targetId instanceof String)) {
            throw new IllegalStateException("target option " + index + " has no targetId payload");
        }
        return UUID.fromString((String) targetId);
    }
}
