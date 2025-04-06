package org.mage.test.AI.RL;

import mage.constants.MultiplayerAttackOption;
import mage.constants.PhaseStep;
import mage.constants.RangeOfInfluence;
import mage.game.Game;
import mage.game.GameException;
import mage.game.TwoPlayerDuel;
import mage.game.mulligan.MulliganType;
import mage.player.ai.ComputerPlayer8;
import mage.player.ai.FeatureMerger;
import mage.player.ai.Features;
import mage.player.ai.StateEncoder;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mage.test.AI.basic.RLEncodingTests;
import org.mage.test.player.*;
import org.mage.test.serverside.base.CardTestPlayerBaseAI;

import java.io.FileNotFoundException;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * @author WillWroble
 */
public class MCTS2Tests extends CardTestPlayerBaseAI {

    private String deckNameA = "simplegreen.dck"; //simplegreen, UWTempo
    private String deckNameB = "simplegreen.dck";


    @Override
    public List<String> getFullSimulatedPlayers() {
        return Arrays.asList("PlayerA", "PlayerB");
    }

    @Override
    protected Game createNewGameAndPlayers() throws GameException, FileNotFoundException {
        Game game = new TwoPlayerDuel(MultiplayerAttackOption.LEFT, RangeOfInfluence.ONE, MulliganType.GAME_DEFAULT.getMulligan(0), 60, 20, 7);
        playerA = createPlayer(game, "PlayerA", "C:\\Users\\WillWroble\\Documents\\" + deckNameA);
        playerB = createPlayer(game, "PlayerB", "C:\\Users\\WillWroble\\Documents\\" + deckNameB);
        return game;
    }
    @Override
    protected TestPlayer createPlayer(String name, RangeOfInfluence rangeOfInfluence) {
        if (getFullSimulatedPlayers().contains(name)) {
            if(name.equals("PlayerA")) {
                TestComputerPlayerMonteCarlo2 tmc2 = new TestComputerPlayerMonteCarlo2(name, RangeOfInfluence.ONE, getSkillLevel());
                TestPlayer testPlayer = new TestPlayer(tmc2);
                testPlayer.setAIPlayer(true); // enable full AI support (game simulations) for all turns by default
                return testPlayer;
            } else {
                TestComputerPlayer7 t7 = new TestComputerPlayer7(name, RangeOfInfluence.ONE, getSkillLevel());
                TestPlayer testPlayer = new TestPlayer(t7);
                testPlayer.setAIPlayer(true); // enable full AI support (game simulations) for all turns by default
                return testPlayer;
            }
        }
        return super.createPlayer(name, rangeOfInfluence);
    }
    public void reset_game() {
        try {
            reset();
        } catch (FileNotFoundException e) {
            throw new RuntimeException(e);
        } catch (GameException e) {
            throw new RuntimeException(e);
        }

    }
    //5 turns across 1 game
    @Test
    public void test_mcts_5_1() {
        // simple test of 5 turns
        int maxTurn = 50;

        //addCard(Zone.HAND, playerA, "Fauna Shaman", 3);
        //setStrictChooseMode(true);
        setStopAt(maxTurn, PhaseStep.END_TURN);
        execute();

    }
    //20 turns across 1 game
    @Test
    public void test_mcts_20_1() {
        int maxTurn = 20;

        setStrictChooseMode(true);
        setStopAt(maxTurn, PhaseStep.END_TURN);
        execute();

    }
    //5 turns across 5 games
    @Test
    public void test_mcts_5_5() {
        int maxTurn = 5;
        Features.printOldFeatures = false;
        for(int i = 0; i < 5; i++) {
            setStrictChooseMode(true);
            setStopAt(maxTurn, PhaseStep.END_TURN);
            execute();
            reset_game();
            System.out.printf("GAME #%d RESET... NEW GAME STARTING\n", i+1);
        }
    }
    //10 turns across 10 games
    @Test
    public void test_mcts_10_10() {
        int maxTurn = 10;
        Features.printOldFeatures = false;
        for(int i = 0; i < 10; i++) {
            setStrictChooseMode(true);
            setStopAt(maxTurn, PhaseStep.END_TURN);
            execute();
            reset_game();
            System.out.printf("GAME #%d RESET... NEW GAME STARTING\n", i+1);
        }
    }

}
