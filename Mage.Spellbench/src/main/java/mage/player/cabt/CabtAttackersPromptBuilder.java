package mage.player.cabt;

import mage.game.Game;
import mage.game.permanent.Permanent;
import mage.players.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * CABT bridge: builds the DECLARE_ATTACKERS prompt — one option per legal
 * attacker/defender pair, from the same sources HumanPlayer.selectAttackers
 * uses: Player.getAvailableAttackers(game) crossed with the defenders the
 * engine registered on game.getCombat(), filtered by
 * Permanent.canAttack(defenderId, game).
 * <p>
 * minCount is 0 — attacking is optional (attack requirements like "attacks
 * each combat if able" are not enforced by the bridge yet and stay with the
 * engine's own requirement checks). Options are sorted by attacker/defender
 * id so the same board always produces the same indices.
 */
public final class CabtAttackersPromptBuilder {

    public PendingDecision build(Player player, Game game, UUID attackingPlayerId) {
        List<CabtCombatAttackOption> pairs = new ArrayList<CabtCombatAttackOption>();
        if (game.getCombat() != null) {
            for (Permanent attacker : player.getAvailableAttackers(game)) {
                for (UUID defenderId : game.getCombat().getDefenders()) {
                    if (attacker.canAttack(defenderId, game)) {
                        pairs.add(CabtCombatOptionFactory.attackOption(game, attacker, defenderId));
                    }
                }
            }
        }
        pairs.sort(new Comparator<CabtCombatAttackOption>() {
            @Override
            public int compare(CabtCombatAttackOption left, CabtCombatAttackOption right) {
                int byAttacker = left.getAttackerId().toString()
                        .compareTo(right.getAttackerId().toString());
                if (byAttacker != 0) {
                    return byAttacker;
                }
                return left.getDefenderId().toString().compareTo(right.getDefenderId().toString());
            }
        });
        PendingDecision decision = new PendingDecision(
                MagicSelectType.DECLARE_ATTACKERS, player.getId(), 0, pairs.size());
        for (CabtCombatAttackOption pair : pairs) {
            decision.addOption(CabtCombatOptionFactory.toMagicOption(pair));
        }
        return decision;
    }
}
