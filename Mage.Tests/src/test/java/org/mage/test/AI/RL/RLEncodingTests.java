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
import org.mage.test.player.TestComputerPlayer8;
import org.mage.test.player.TestPlayer;
import org.mage.test.serverside.base.CardTestPlayerBaseAI;

import java.io.FileNotFoundException;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.Set;

/**
 * @author WillWroble
 */
public class RLEncodingTests extends CardTestPlayerBaseAI {
    StateEncoder encoder;
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
                TestComputerPlayer8 t8 = new TestComputerPlayer8(name, RangeOfInfluence.ONE, getSkillLevel());
                TestPlayer testPlayer = new TestPlayer(t8);
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
    @Before
    public void init_encoder() {
        System.out.println("Setting up encoder");
        encoder = new StateEncoder();
        set_encoder();
    }
    public void set_encoder() {
        ComputerPlayer8 c8 = (ComputerPlayer8)playerA.getComputerPlayer();
        c8.setEncoder(encoder);
        encoder.setAgent(playerA.getId());
        encoder.setOpponent(playerB.getId());
    }
    public void reset_game() {
        try {
            reset();
        } catch (FileNotFoundException e) {
            throw new RuntimeException(e);
        } catch (GameException e) {
            throw new RuntimeException(e);
        }
        set_encoder();
    }

    //5 turns across 1 game
    @Test
    public void test_encoding_5_1() {
        // simple test of 5 turns
        int maxTurn = 5;

        //addCard(Zone.HAND, playerA, "Fauna Shaman", 3);
        setStrictChooseMode(true);
        setStopAt(maxTurn, PhaseStep.END_TURN);
        execute();

    }
    //20 turns across 1 game
    @Test
    public void test_encoding_20_1() {
        int maxTurn = 20;

        setStrictChooseMode(true);
        setStopAt(maxTurn, PhaseStep.END_TURN);
        execute();

    }
    //5 turns across 5 games
    @Test
    public void test_encoding_5_5() {
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
    //10 turns across 5 games
    @Test
    public void test_encoding_10_5() {
        int maxTurn = 10;
        Features.printOldFeatures = false;
        for(int i = 0; i < 5; i++) {
            setStrictChooseMode(true);
            setStopAt(maxTurn, PhaseStep.END_TURN);
            execute();
            reset_game();
            System.out.printf("GAME #%d RESET... NEW GAME STARTING\n", i+1);
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
        Set<Integer> ignore = FeatureMerger.computeIgnoreList(encoder.macroStateVectors);
        System.out.printf("IGNORE LIST SIZE: %d\n", ignore.size());
        System.out.printf("REDUCED VECTOR SIZE: %d\n", StateEncoder.indexCount - ignore.size());
    }
    @Test
    public void test_encoding_20_50_with_reduction100() {
        int maxTurn = 20;
        Features.printOldFeatures = false;
        for(int i = 0; i < 50; i++) {
            setStrictChooseMode(true);
            setStopAt(maxTurn, PhaseStep.END_TURN);
            execute();
            reset_game();
            System.out.printf("GAME #%d RESET... NEW GAME STARTING\n", i+1);
        }
        Set<Integer> ignore = FeatureMerger.computeIgnoreList(encoder.macroStateVectors);
        System.out.printf("IGNORE LIST SIZE: %d\n", ignore.size());
        System.out.printf("REDUCED VECTOR SIZE: %d\n", StateEncoder.indexCount - ignore.size());
    }
    @Test
    public void test_encoding_20_100_with_reduction100() {
        int maxTurn = 20;
        Features.printOldFeatures = false;
        for(int i = 0; i < 100; i++) {
            setStrictChooseMode(true);
            setStopAt(maxTurn, PhaseStep.END_TURN);
            execute();
            reset_game();
            System.out.printf("GAME #%d RESET... NEW GAME STARTING\n", i+1);
        }
        Set<Integer> ignore = FeatureMerger.computeIgnoreList(encoder.macroStateVectors);
        System.out.printf("IGNORE LIST SIZE: %d\n", ignore.size());
        System.out.printf("REDUCED VECTOR SIZE: %d\n", StateEncoder.indexCount - ignore.size());
    }
    @Test
    public void test_encoding_after_10_10_with_ignore() {
        int maxTurn = 10;
        Features.printOldFeatures = false;
        for(int i = 0; i < 10; i++) {
            setStrictChooseMode(true);
            setStopAt(maxTurn, PhaseStep.END_TURN);
            execute();
            reset_game();
            System.out.printf("GAME #%d RESET... NEW GAME STARTING\n", i+1);
        }
        Set<Integer> ignore = FeatureMerger.computeIgnoreList(encoder.macroStateVectors);
        System.out.printf("IGNORE LIST SIZE: %d\n", ignore.size());
        System.out.printf("REDUCED VECTOR SIZE: %d\n", StateEncoder.indexCount - ignore.size());
        //run one more game this time using ignore list
        Features.printOldFeatures = true;
        Features.printNewFeatures = false;
        encoder.ignoreList = ignore;
        setStrictChooseMode(true);
        setStopAt(maxTurn, PhaseStep.END_TURN);
        execute();
        System.out.println(ignore);
    }
    @Test
    public void test_encoding_after_20_20_with_ignore() {
        int maxTurn = 20;
        Features.printOldFeatures = false;
        for(int i = 0; i < 20; i++) {
            setStrictChooseMode(true);
            setStopAt(maxTurn, PhaseStep.END_TURN);
            execute();
            reset_game();
            System.out.printf("GAME #%d RESET... NEW GAME STARTING\n", i+1);
        }
        Set<Integer> ignore = FeatureMerger.computeIgnoreList(encoder.macroStateVectors);
        System.out.printf("IGNORE LIST SIZE: %d\n", ignore.size());
        System.out.printf("REDUCED VECTOR SIZE: %d\n", StateEncoder.indexCount - ignore.size());
        //run one more game this time using ignore list
        Features.printOldFeatures = true;
        Features.printNewFeatures = false;
        encoder.ignoreList = ignore;
        setStrictChooseMode(true);
        setStopAt(maxTurn, PhaseStep.END_TURN);
        execute();
        System.out.println(ignore);
    }
    @Test
    public void test_state_consistency() {
        int maxTurn = 10;
        //removeAllCardsFromHand(playerA);
        setStrictChooseMode(true);
        setStopAt(maxTurn, PhaseStep.END_TURN);
        execute();
        //save state after 5 turns
        int bookmarkedState = currentGame.bookmarkState();
        BitSet savedVec = StateEncoder.featureVector;
        reset_game();
        //simulate another 5 turns
        setStrictChooseMode(true);
        setStopAt(maxTurn, PhaseStep.END_TURN);
        execute();
        //reload state and read it
        currentGame.restoreState(bookmarkedState, "rolling_back_for_testing");
        encoder.processState(currentGame);
        BitSet newVec = StateEncoder.featureVector;
        System.out.println(savedVec);
        System.out.println(newVec);
        assert (savedVec.equals(newVec));
    }
    @After
    public void print_vector_size() {
        System.out.printf("FINAL (unreduced) VECTOR SIZE: %d\n", StateEncoder.indexCount);
        System.out.printf("FINAL ACTION VECTOR SIZE: %d\n", ActionEncoder.indexCount);
        for(String s : ActionEncoder.actionMap.keySet()) {
            System.out.printf("[%s => %d] ", s, ActionEncoder.actionMap.get(s));
        }
        System.out.println();
    }
}
