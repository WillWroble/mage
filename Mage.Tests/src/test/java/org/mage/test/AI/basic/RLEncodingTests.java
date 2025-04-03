package org.mage.test.AI.basic;

import mage.constants.PhaseStep;
import mage.constants.Zone;
import mage.game.GameException;
import mage.game.GameState;
import mage.player.ai.ComputerPlayer8;
import mage.player.ai.FeatureMerger;
import mage.player.ai.Features;
import mage.player.ai.StateEncoder;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBaseAI;

import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * @author WillWroble
 */
public class RLEncodingTests extends CardTestPlayerBaseAI {
    StateEncoder encoder;
    public static String deckNameA = "simplegreen.dck"; //simplegreen, UWTempo
    public static String deckNameB = "simplegreen.dck";


    @Override
    public List<String> getFullSimulatedPlayers() {
        return Arrays.asList("PlayerA", "PlayerB");
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
    //10 turns across 10 games
    @Test
    public void test_encoding_10_10() {
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
    @Test
    public void test_encoding_10_10_with_reduction100() {
        int maxTurn = 10;
        Features.printOldFeatures = false;
        for(int i = 0; i < 10; i++) {
            setStrictChooseMode(true);
            setStopAt(maxTurn, PhaseStep.END_TURN);
            execute();
            reset_game();
            System.out.printf("GAME #%d RESET... NEW GAME STARTING\n", i+1);
        }
        Set<Integer> ignore = FeatureMerger.computeIgnoreList(encoder.stateVectors, 1.00);
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
        Set<Integer> ignore = FeatureMerger.computeIgnoreList(encoder.stateVectors, 1.00);
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
        Set<Integer> ignore = FeatureMerger.computeIgnoreList(encoder.stateVectors, 1.00);
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
        Set<Integer> ignore = FeatureMerger.computeIgnoreList(encoder.stateVectors, 1.00);
        System.out.printf("IGNORE LIST SIZE: %d\n", ignore.size());
        System.out.printf("REDUCED VECTOR SIZE: %d\n", StateEncoder.indexCount - ignore.size());
        //run one more game this time using ignore list
        Features.printOldFeatures = true;
        Features.printNewFeatures = false;
        StateEncoder.ignoreList = ignore;
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
        Set<Integer> ignore = FeatureMerger.computeIgnoreList(encoder.stateVectors, 1.00);
        System.out.printf("IGNORE LIST SIZE: %d\n", ignore.size());
        System.out.printf("REDUCED VECTOR SIZE: %d\n", StateEncoder.indexCount - ignore.size());
        //run one more game this time using ignore list
        Features.printOldFeatures = true;
        Features.printNewFeatures = false;
        StateEncoder.ignoreList = ignore;
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
        boolean[] savedVec = StateEncoder.featureVector;
        reset_game();
        //simulate another 5 turns
        setStrictChooseMode(true);
        setStopAt(maxTurn, PhaseStep.END_TURN);
        execute();
        //reload state and read it
        currentGame.restoreState(bookmarkedState, "rolling_back_for_testing");
        encoder.processState(currentGame);
        boolean[] newVec = StateEncoder.featureVector;
        System.out.println(Arrays.toString(savedVec));
        System.out.println(Arrays.toString(newVec));
        assert (Arrays.equals(savedVec, newVec));
    }
    @After
    public void print_vector_size() {
        System.out.printf("FINAL (unreduced) VECTOR SIZE: %d\n", StateEncoder.indexCount);
    }
}
