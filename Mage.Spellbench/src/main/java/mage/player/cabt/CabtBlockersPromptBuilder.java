package mage.player.cabt;

import mage.game.Game;
import mage.game.combat.CombatGroup;
import mage.game.permanent.Permanent;
import mage.players.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * CABT bridge: builds the DECLARE_BLOCKERS prompt — one option per legal
 * blocker/attacker pair, from Player.getAvailableBlockers(game) crossed with
 * the attackers in the engine's combat groups, using the same legality check
 * declareBlocker applies (CombatGroup.canBlock). minCount is 0 — blocking is
 * optional. Options are sorted by blocker/attacker id for stable indices.
 */
public final class CabtBlockersPromptBuilder {

    public PendingDecision build(Player player, Game game, UUID defendingPlayerId) {
        List<CabtCombatBlockOption> pairs = new ArrayList<CabtCombatBlockOption>();
        if (game.getCombat() != null) {
            for (Permanent blocker : player.getAvailableBlockers(game)) {
                for (CombatGroup group : game.getCombat().getGroups()) {
                    if (!group.canBlock(blocker, game)) {
                        continue;
                    }
                    for (UUID attackerId : group.getAttackers()) {
                        Permanent attacker = game.getPermanent(attackerId);
                        pairs.add(new CabtCombatBlockOption(
                                blocker.getId(), blocker.getName(),
                                attackerId, attacker == null ? null : attacker.getName(),
                                defendingPlayerId));
                    }
                }
            }
        }
        pairs.sort(new Comparator<CabtCombatBlockOption>() {
            @Override
            public int compare(CabtCombatBlockOption left, CabtCombatBlockOption right) {
                int byBlocker = left.getBlockerId().toString()
                        .compareTo(right.getBlockerId().toString());
                if (byBlocker != 0) {
                    return byBlocker;
                }
                return left.getAttackerId().toString().compareTo(right.getAttackerId().toString());
            }
        });
        PendingDecision decision = new PendingDecision(
                MagicSelectType.DECLARE_BLOCKERS, player.getId(), 0, pairs.size());
        for (CabtCombatBlockOption pair : pairs) {
            decision.addOption(CabtCombatOptionFactory.toMagicOption(pair));
        }
        return decision;
    }
}
