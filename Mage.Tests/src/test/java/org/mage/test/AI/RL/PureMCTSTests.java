package org.mage.test.AI.RL;

import ai.onnxruntime.OrtException;
import mage.constants.MultiplayerAttackOption;
import mage.constants.PhaseStep;
import mage.constants.RangeOfInfluence;
import mage.game.Game;
import mage.game.GameException;
import mage.game.TwoPlayerDuel;
import mage.game.mulligan.MulliganType;
import mage.player.ai.ActionEncoder;
import mage.player.ai.ComputerPlayerPureMCTS;
import mage.player.ai.Features;
import mage.player.ai.MCTSNode;
import mage.util.RandomUtil;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mage.test.player.TestComputerPlayer7;
import org.mage.test.player.TestComputerPlayerMonteCarlo2;
import org.mage.test.player.TestComputerPlayerPureMonteCarlo;
import org.mage.test.player.TestPlayer;
import org.mage.test.serverside.base.CardTestPlayerBaseAI;

import java.io.FileNotFoundException;
import java.util.Arrays;
import java.util.List;

/**
 * @author WillWroble
 */
public class PureMCTSTests extends CardTestPlayerBaseAI {

    private String deckNameA = "UWTempo.dck"; //simplegreen, UWTempo
    private String deckNameB = "simplegreen.dck";
    private int seed;


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
                TestComputerPlayerPureMonteCarlo pmc = new TestComputerPlayerPureMonteCarlo(name, RangeOfInfluence.ONE, getSkillLevel());
                TestPlayer testPlayer = new TestPlayer(pmc);
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
        ComputerPlayerPureMCTS pmc = (ComputerPlayerPureMCTS) playerA.getComputerPlayer();
        pmc.clearTree();
        MCTSNode.clearCaches();

        try {
            reset();
        } catch (FileNotFoundException e) {
            throw new RuntimeException(e);
        } catch (GameException e) {
            throw new RuntimeException(e);
        }

    }
    @Before
    public void init_seed() {
        seed = RandomUtil.nextInt();
        //seed = -1421792887;
        seed = 233400479;
        System.out.printf("USING SEED: %d\n", seed);
        RandomUtil.setSeed(seed);
    }
    //10 turns across 1 game
    @Test
    public void test_mcts_10_1() {
        // simple test of 10 turns
        int maxTurn = 10;
        ComputerPlayerPureMCTS.SHOW_THREAD_INFO = true;
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
    @After
    public void show_data() {
        System.out.printf("USING SEED: %d\n", seed);
        //System.out.printf("FINAL ACTION VECTOR SIZE: %d\n", ActionEncoder.indexCount);
    }
}
