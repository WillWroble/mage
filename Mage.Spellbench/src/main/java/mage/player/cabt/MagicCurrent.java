package mage.player.cabt;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * CABT bridge observation: the public game state snapshot under "current" —
 * turn/phase/step position, per-player state, and the shared public zones
 * (battlefield, stack, exile, command) as object views.
 */
public final class MagicCurrent {

    private final int turnNumber;
    private final String activePlayerId;
    private final String priorityPlayerId;
    private final String phase;
    private final String step;
    private final List<MagicPlayerView> players;
    private final int stackSize;
    private final int battlefieldSize;
    private final boolean gameEnded;
    private final String winner;
    private final List<MagicPermanentView> battlefield;
    private final List<MagicStackObjectView> stack;
    private final List<MagicObjectView> exile;
    private final List<MagicObjectView> command;

    public MagicCurrent(int turnNumber, String activePlayerId, String priorityPlayerId,
                        String phase, String step, List<MagicPlayerView> players,
                        int stackSize, int battlefieldSize, boolean gameEnded, String winner,
                        List<MagicPermanentView> battlefield,
                        List<MagicStackObjectView> stack,
                        List<MagicObjectView> exile,
                        List<MagicObjectView> command) {
        this.turnNumber = turnNumber;
        this.activePlayerId = activePlayerId;
        this.priorityPlayerId = priorityPlayerId;
        this.phase = phase;
        this.step = step;
        this.players = Collections.unmodifiableList(new ArrayList<MagicPlayerView>(players));
        this.stackSize = stackSize;
        this.battlefieldSize = battlefieldSize;
        this.gameEnded = gameEnded;
        this.winner = winner;
        this.battlefield = battlefield == null
                ? Collections.<MagicPermanentView>emptyList()
                : Collections.unmodifiableList(new ArrayList<MagicPermanentView>(battlefield));
        this.stack = stack == null
                ? Collections.<MagicStackObjectView>emptyList()
                : Collections.unmodifiableList(new ArrayList<MagicStackObjectView>(stack));
        this.exile = copyOf(exile);
        this.command = copyOf(command);
    }

    private static List<MagicObjectView> copyOf(List<MagicObjectView> values) {
        return values == null
                ? Collections.<MagicObjectView>emptyList()
                : Collections.unmodifiableList(new ArrayList<MagicObjectView>(values));
    }

    public int getTurnNumber() {
        return turnNumber;
    }

    public String getActivePlayerId() {
        return activePlayerId;
    }

    public String getPriorityPlayerId() {
        return priorityPlayerId;
    }

    public String getPhase() {
        return phase;
    }

    public String getStep() {
        return step;
    }

    public List<MagicPlayerView> getPlayers() {
        return players;
    }

    public int getStackSize() {
        return stackSize;
    }

    public int getBattlefieldSize() {
        return battlefieldSize;
    }

    public boolean isGameEnded() {
        return gameEnded;
    }

    public String getWinner() {
        return winner;
    }

    public List<MagicPermanentView> getBattlefield() {
        return battlefield;
    }

    public List<MagicStackObjectView> getStack() {
        return stack;
    }

    public List<MagicObjectView> getExile() {
        return exile;
    }

    public List<MagicObjectView> getCommand() {
        return command;
    }
}
