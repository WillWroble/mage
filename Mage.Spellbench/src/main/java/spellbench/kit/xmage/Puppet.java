package spellbench.kit.xmage;

import mage.abilities.Ability;
import mage.constants.RangeOfInfluence;
import mage.game.Game;
import mage.player.ai.KitPayPlayer;

import java.util.UUID;

/**
 * The other seat in a world (design Section 5.1; mzbridge {@code BridgePlayer}): passes priority, never attacks or
 * blocks, keeps its hand, and answers forced choices with {@code ComputerPlayer}'s heuristics.
 */
public final class Puppet extends KitPayPlayer {

    private static final long serialVersionUID = 1L;

    public Puppet(String name) {
        super(name, RangeOfInfluence.ALL);
    }

    private Puppet(final Puppet p) {
        super(p);
    }

    @Override
    public Puppet copy() {
        return new Puppet(this);
    }

    @Override
    public boolean priority(Game game) {
        pass(game);
        return false;
    }

    @Override
    public void selectAttackers(Game game, UUID attackingPlayerId) {
        // never attacks
    }

    @Override
    public void selectBlockers(Ability source, Game game, UUID defendingPlayerId) {
        // never blocks
    }

    @Override
    public boolean chooseMulligan(Game game) {
        return false;
    }
}
