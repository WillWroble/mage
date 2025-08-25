package org.mage.test.AI.RL;

import mage.constants.MultiplayerAttackOption;
import mage.constants.PhaseStep;
import mage.constants.RangeOfInfluence;
import mage.game.Game;
import mage.game.GameException;
import mage.game.TwoPlayerDuel;
import mage.game.mulligan.MulliganType;
import mage.player.ai.*;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mage.test.player.TestComputerPlayer7;
import org.mage.test.player.TestComputerPlayerMonteCarlo2;
import org.mage.test.player.TestPlayer;
import org.mage.test.serverside.base.CardTestPlayerBaseAI;

import java.io.FileNotFoundException;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

public class MCTSEncoderTests extends CardTestPlayerBaseAI {

    private StateEncoder encoder;
    private final String deckNameA = "simplegreen.dck"; // can be changed to desired deck
    private final String deckNameB = "simplegreen.dck";

    @Override
    public List<String> getFullSimulatedPlayers() {
        return Arrays.asList("PlayerA", "PlayerB");
    }

    @Override
    protected Game createNewGameAndPlayers() throws GameException, FileNotFoundException {
        Game game = new TwoPlayerDuel(
                MultiplayerAttackOption.LEFT,
                RangeOfInfluence.ONE,
                MulliganType.GAME_DEFAULT.getMulligan(0),
                60, 20, 7);
        playerA = createPlayer(game, "PlayerA", "C:\\Users\\owner\\Documents\\" + deckNameA);
        playerB = createPlayer(game, "PlayerB", "C:\\Users\\owner\\Documents\\" + deckNameB);
        return game;
    }

    @Override
    protected TestPlayer createPlayer(String name, RangeOfInfluence rangeOfInfluence) {
        if(getFullSimulatedPlayers().contains(name)) {
            if(name.equals("PlayerA")) {
                // Use the Monte Carlo agent version that utilizes the value function
                TestComputerPlayerMonteCarlo2 tmc2 = new TestComputerPlayerMonteCarlo2(name, RangeOfInfluence.ONE, getSkillLevel());
                TestPlayer testPlayer = new TestPlayer(tmc2);
                testPlayer.setAIPlayer(true); // Enable full AI support for all turns
                return testPlayer;
            } else {
                // For opponents, you can continue to use your other test computer player
                TestComputerPlayer7 t7 = new TestComputerPlayer7(name, RangeOfInfluence.ONE, getSkillLevel());
                TestPlayer testPlayer = new TestPlayer(t7);
                testPlayer.setAIPlayer(true); // enable full AI support (game simulations) for all turns by default
                return testPlayer;
            }
        }
        return super.createPlayer(name, rangeOfInfluence);
    }

    @Before
    public void init_encoder() {
        System.out.println("Setting up encoder");
        encoder = new StateEncoder();
        set_encoder();
    }

    public void set_encoder() {
        ComputerPlayerMCTS2 cp = (ComputerPlayerMCTS2) playerA.getComputerPlayer();
        cp.setEncoder(encoder);
        encoder.setAgent(playerA.getId());
        encoder.setOpponent(playerB.getId());
    }

    public void reset_game() {
        try {
            reset();
        } catch (FileNotFoundException | GameException e) {
            throw new RuntimeException(e);
        }
        set_encoder();
        ComputerPlayerMCTS2 mcts2 = (ComputerPlayerMCTS2) playerA.getComputerPlayer();
        mcts2.clearTree();
        MCTSNode.clearCaches();
    }

    // Test a single game with 5 turns
    @Test
    public void test_mcts2_5_1() {
        int maxTurn = 5;
        setStrictChooseMode(true);
        setStopAt(maxTurn, PhaseStep.END_TURN);
        execute();
    }

    // Test a single game with 20 turns
    @Test
    public void test_mcts2_20_1() {
        int maxTurn = 20;
        setStrictChooseMode(true);
        setStopAt(maxTurn, PhaseStep.END_TURN);
        execute();
    }

    // Test 5 games each with 10 turns
    @Test
    public void test_mcts2_10_5() {
        int maxTurn = 10;
        Features.printOldFeatures = false;
        for (int i = 0; i < 5; i++) {
            setStrictChooseMode(true);
            setStopAt(maxTurn, PhaseStep.END_TURN);
            execute();
            reset_game();
            System.out.printf("GAME #%d RESET... NEW GAME STARTING\n", i + 1);
        }
    }

    // Test 10 games each with 10 turns
    @Test
    public void test_mcts2_10_10() {
        int maxTurn = 10;
        Features.printOldFeatures = false;
        for (int i = 0; i < 10; i++) {
            setStrictChooseMode(true);
            setStopAt(maxTurn, PhaseStep.END_TURN);
            execute();
            reset_game();
            System.out.printf("GAME #%d RESET... NEW GAME STARTING\n", i + 1);
        }
    }
    @Test
    public void test_encoding_10_5_with_reduction100() {
        int maxTurn = 10;
        Features.printOldFeatures = false;
        for(int i = 0; i < 5; i++) {
            setStrictChooseMode(true);
            setStopAt(maxTurn, PhaseStep.END_TURN);
            execute();
            reset_game();
            System.out.printf("GAME #%d RESET... NEW GAME STARTING\n", i+1);
        }
        Set<Integer> ignore = FeatureMerger.computeIgnoreList(encoder.stateVectors);
        System.out.printf("IGNORE LIST SIZE: %d\n", ignore.size());
        System.out.printf("REDUCED VECTOR SIZE: %d\n", StateEncoder.indexCount - ignore.size());
    }
    @After
    public void print_vector_size() {
        System.out.printf("FINAL (unreduced) VECTOR SIZE: %d\n", StateEncoder.indexCount);
    }
}
