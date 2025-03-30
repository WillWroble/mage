package mage.player.ai;

import mage.cards.decks.DeckCardInfo;
import mage.cards.decks.DeckCardLists;
import mage.constants.RangeOfInfluence;
import mage.game.Game;

import java.util.Arrays;

public class ComputerPlayer8 extends ComputerPlayer7{
    private StateEmbedder embedder;
    public ComputerPlayer8(ComputerPlayer7 player) {
        super(player);
    }

    public ComputerPlayer8(String name, RangeOfInfluence range, int skill) {
        super(name, range, skill);
    }

    public void setEmbedder(StateEmbedder emb) {
        this.embedder = emb;
    }
    public StateEmbedder getEmbedder() {return embedder;}

    @Override
    public boolean priority(Game game) {
        game.resumeTimer(getTurnControlledBy());
        boolean result = priorityPlay(game);
        game.pauseTimer(getTurnControlledBy());
        return result;
    }
    private boolean priorityPlay(Game game) {
        game.getState().setPriorityPlayerId(playerId);
        game.firePriorityEvent(playerId);

        //state learning testing
        //embedder.processState(game);

        switch (game.getTurnStepType()) {
            case UPKEEP:
                //state learning testing
                embedder.processState(game);
                game.toString();
                printBattlefieldScore(game, "UPKEEP====================");
            case DRAW:
                pass(game);
                return false;
            case PRECOMBAT_MAIN:
                // 09.03.2020:
                // in old version it passes opponent's pre-combat step (game.isActivePlayer(playerId) -> pass(game))
                // why?!

                //add stuff here
                //System.out.print("State Vector: ");
                //System.out.println(Arrays.toString(embedder.stateToVec(game.getState())));

                //printBattlefieldScore(game, "Sim PRIORITY on MAIN 1");

                if (actions.isEmpty()) {
                    calculateActions(game);
                } else {
                    // TODO: is it possible non empty actions without calculation?!
                    throw new IllegalStateException("wtf");
                }
                act(game);
                return true;
            case BEGIN_COMBAT:
                pass(game);
                return false;
            case DECLARE_ATTACKERS:
                //printBattlefieldScore(game, "Sim PRIORITY on DECLARE ATTACKERS");
                if (actions.isEmpty()) {
                    calculateActions(game);
                } else {
                    // TODO: is it possible non empty actions without calculation?!
                    throw new IllegalStateException("wtf");
                }
                act(game);
                return true;
            case DECLARE_BLOCKERS:
                //printBattlefieldScore(game, "Sim PRIORITY on DECLARE BLOCKERS");
                if (actions.isEmpty()) {
                    calculateActions(game);
                } else {
                    // TODO: is it possible non empty actions without calculation?!
                    throw new IllegalStateException("wtf");
                }
                act(game);
                return true;
            case FIRST_COMBAT_DAMAGE:
            case COMBAT_DAMAGE:
            case END_COMBAT:
                pass(game);
                return false;
            case POSTCOMBAT_MAIN:
                //printBattlefieldScore(game, "Sim PRIORITY on MAIN 2");
                if (actions.isEmpty()) {
                    calculateActions(game);
                } else {
                    // TODO: is it possible non empty actions without calculation?!
                    throw new IllegalStateException("wtf");
                }
                act(game);
                return true;
            case END_TURN:
            case CLEANUP:
                actionCache.clear();
                pass(game);
                return false;
        }
        return false;
    }
}
