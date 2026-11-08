package mage.player.cabt;

import mage.game.Game;
import mage.game.permanent.Permanent;
import mage.players.Player;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * CABT bridge: builds combat declaration options — attacker/defender and
 * blocker/attacker pairs — resolving defenders the way the engine does
 * (players first, then planeswalker/battle permanents).
 */
public final class CabtCombatOptionFactory {

    private CabtCombatOptionFactory() {
    }

    public static CabtCombatAttackOption attackOption(Game game, Permanent attacker, UUID defenderId) {
        String defenderName;
        String defenderType;
        Player defendingPlayer = game.getPlayer(defenderId);
        if (defendingPlayer != null) {
            defenderName = defendingPlayer.getName();
            defenderType = "PLAYER";
        } else {
            Permanent defender = game.getPermanent(defenderId);
            defenderName = defender == null ? null : defender.getName();
            if (defender == null) {
                defenderType = null;
            } else if (defender.isPlaneswalker(game)) {
                defenderType = "PLANESWALKER";
            } else if (defender.isBattle(game)) {
                defenderType = "BATTLE";
            } else {
                defenderType = "PERMANENT";
            }
        }
        return new CabtCombatAttackOption(attacker.getId(), attacker.getName(),
                defenderId, defenderName, defenderType);
    }

    public static MagicOption toMagicOption(CabtCombatAttackOption attack) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("attackerId", attack.getAttackerId().toString());
        payload.put("attackerName", attack.getAttackerName());
        payload.put("defenderId", attack.getDefenderId().toString());
        payload.put("defenderName", attack.getDefenderName());
        payload.put("defenderType", attack.getDefenderType());
        return new MagicOption(MagicOptionType.PROMPT_ATTACKER,
                "Attack " + attack.getDefenderName() + " with " + attack.getAttackerName(),
                payload);
    }

    public static MagicOption toMagicOption(CabtCombatBlockOption block) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("blockerId", block.getBlockerId().toString());
        payload.put("blockerName", block.getBlockerName());
        payload.put("attackerId", block.getAttackerId().toString());
        payload.put("attackerName", block.getAttackerName());
        payload.put("defendingPlayerId", block.getDefendingPlayerId() == null
                ? null : block.getDefendingPlayerId().toString());
        return new MagicOption(MagicOptionType.PROMPT_BLOCKER,
                "Block " + block.getAttackerName() + " with " + block.getBlockerName(),
                payload);
    }
}
