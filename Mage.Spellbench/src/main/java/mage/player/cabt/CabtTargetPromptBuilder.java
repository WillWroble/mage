package mage.player.cabt;

import mage.abilities.Ability;
import mage.cards.Cards;
import mage.constants.Outcome;
import mage.game.Game;
import mage.players.Player;
import mage.target.Target;
import mage.target.TargetCard;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * CABT bridge: builds TARGET prompts from the same target source objects
 * XMage's human UI uses — Target.possibleTargets(...) and the target's own
 * min/max/selected state — never from serialized board state.
 * <p>
 * Options are ordered by target UUID so the same game state always produces
 * the same option indices (possibleTargets returns an unordered set).
 */
public final class CabtTargetPromptBuilder {

    public PendingDecision buildTargetPrompt(Player player, Game game, Outcome outcome,
                                             Target target, Ability source) {
        CabtTargetPromptContext context = new CabtTargetPromptContext(
                outcome, target, null, null, source, !target.isNotTarget());
        UUID abilityControllerId = target.getAffectedAbilityControllerId(player.getId());
        Set<UUID> possibleTargets = target.possibleTargets(abilityControllerId, source, game);
        PendingDecision decision = emptyDecision(player, target);
        for (UUID targetId : orderedNewTargets(possibleTargets, target)) {
            decision.addOption(CabtTargetOptionFactory.targetOption(
                    game, targetId, context.isTargeted(), false));
        }
        return decision;
    }

    public PendingDecision buildTargetCardPrompt(Player player, Game game, Outcome outcome,
                                                 Cards cards, TargetCard target, Ability source) {
        CabtTargetPromptContext context = new CabtTargetPromptContext(
                outcome, target, cards, target, source, !target.isNotTarget());
        UUID abilityControllerId = target.getAffectedAbilityControllerId(player.getId());
        Set<UUID> possibleTargets = target.possibleTargets(abilityControllerId, source, game, cards);
        PendingDecision decision = emptyDecision(player, target);
        for (UUID cardId : orderedNewTargets(possibleTargets, target)) {
            decision.addOption(CabtTargetOptionFactory.cardOption(
                    game, cardId, context.isTargeted(), false));
        }
        return decision;
    }

    private static PendingDecision emptyDecision(Player player, Target target) {
        int alreadyChosen = target.getTargets().size();
        int remainingMin = Math.max(0, target.getMinNumberOfTargets() - alreadyChosen);
        int remainingMax = Math.max(0, target.getMaxNumberOfTargets() - alreadyChosen);
        return new PendingDecision(MagicSelectType.TARGET, player.getId(), remainingMin, remainingMax);
    }

    private static List<UUID> orderedNewTargets(Set<UUID> possibleTargets, Target target) {
        List<UUID> ordered = new ArrayList<UUID>(possibleTargets);
        // possibleTargets implementations are expected to drop selected ids
        // already; filter again so a lenient implementation cannot offer the
        // same target twice
        ordered.removeAll(target.getTargets());
        ordered.sort(new Comparator<UUID>() {
            @Override
            public int compare(UUID left, UUID right) {
                return left.toString().compareTo(right.toString());
            }
        });
        return ordered;
    }
}
