package spellbench.kit.xmage;

import mage.game.Game;
import mage.game.stack.StackAbility;
import mage.game.stack.StackObject;
import mage.players.Player;
import mage.players.PlayerImpl;

import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;

/**
 * Resolution in a world, as {@code ComputerPlayer6.resolve} does it (without its search hints): both seats have
 * passed, the top object resolves, a resolved ability leaves the stack, effects apply, priority returns to the active
 * seat, and state-based actions and triggers are checked (the world's players put their triggers on the stack). Used
 * by the fixtures and the continuation's finish mode.
 */
public final class Resolver {

    private Resolver() {
    }

    public static StackObject resolveTop(World w) {
        Game game = w.game;
        for (UUID pid : w.seatPlayer.values()) {
            Player p = game.getPlayer(pid);
            Reflect.set(PlayerImpl.class, p, "passed", true);
        }
        StackObject top = game.getStack().getFirstOrNull();
        if (top == null) {
            return null;
        }
        top.resolve(game);
        finish(w, top);
        return top;
    }

    static void finish(World w, StackObject top) {
        Game game = w.game;
        if (top instanceof StackAbility && game.getStack().contains(top)) {
            game.getStack().remove(top, game);
        }
        game.applyEffects();
        game.getPlayers().resetPassed();
        game.getPlayerList().setCurrent(game.getActivePlayerId());
        game.getState().setPriorityPlayerId(game.getActivePlayerId());
        game.checkStateAndTriggered();
        game.applyEffects();
    }

    /** Finishes a resolution and projects the viewer's observation with priority to the active seat. */
    public static Map<String, Object> finishAndProject(World w, StackObject top, Map<String, Object> flags) {
        finish(w, top);
        try {
            return RoundTrip.project(w, flags, w.seatOf(w.game.getActivePlayerId()), new ArrayList<>());
        } catch (Exception e) {
            return null;
        }
    }
}
