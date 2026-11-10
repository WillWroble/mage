package org.mage.spellbench;

import mage.constants.RangeOfInfluence;
import mage.player.ai.encoder.StateEncoder;
import mage.players.Player;
import org.mage.magezero.Config;
import org.mage.magezero.ParallelDataGenerator;

/**
 * MageZero's generator running lockstep games: a seat of type {@code on_policy} is MageZero's policy player (with its
 * moves recorded for the kernel), a seat of type {@code spellbench} is the Archon. Everything else (decks, seats,
 * seeds, the configuration of MageZero's player, the games themselves) is the generator's own.
 */
public class LockstepGenerator extends ParallelDataGenerator {

    @Override
    protected Player createPlayer(String name, RangeOfInfluence rangeOfInfluence) {
        String type = (name.equals("PlayerA") ? Config.INSTANCE.playerA : Config.INSTANCE.playerB).type;
        switch (type) {
            case "on_policy":
                return new ComputerPlayerPolicyOnlyLockstep(name, RangeOfInfluence.ONE, 6);
            case "spellbench":
                return new ComputerPlayerSpellBench(name, RangeOfInfluence.ONE);
            default:
                return super.createPlayer(name, rangeOfInfluence);
        }
    }

    @Override
    protected void configurePlayer(Player player, StateEncoder encoder, StateEncoder opponentEncoder) {
        if (!(player.getRealPlayer() instanceof ComputerPlayerSpellBench)) {
            super.configurePlayer(player, encoder, opponentEncoder);
        }
    }
}
