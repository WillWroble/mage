package org.mage.test.AI.basic;

import mage.constants.PhaseStep;
import mage.game.GameException;
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
    public static String deckNameA = "UWTempo.dck";
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

    //5 turns across 1 game
    @Test
    public void test_encoding_5_1() {
        ComputerPlayer8 c8 = (ComputerPlayer8)playerA.getComputerPlayer();
        // simple test of 5 turns
        int maxTurn = 5;

        //addCard(Zone.HAND, playerA, "Fauna Shaman", 3);
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
            try {
                reset();
            } catch (FileNotFoundException e) {
                throw new RuntimeException(e);
            } catch (GameException e) {
                throw new RuntimeException(e);
            }
            System.out.printf("GAME #%d RESET... NEW GAME STARTING\n", i+1);
            set_encoder();
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
            try {
                reset();
            } catch (FileNotFoundException e) {
                throw new RuntimeException(e);
            } catch (GameException e) {
                throw new RuntimeException(e);
            }
            System.out.printf("GAME #%d RESET... NEW GAME STARTING\n", i+1);
            set_encoder();
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
            try {
                reset();
            } catch (FileNotFoundException e) {
                throw new RuntimeException(e);
            } catch (GameException e) {
                throw new RuntimeException(e);
            }
            System.out.printf("GAME #%d RESET... NEW GAME STARTING\n", i+1);
            set_encoder();
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
            try {
                reset();
            } catch (FileNotFoundException e) {
                throw new RuntimeException(e);
            } catch (GameException e) {
                throw new RuntimeException(e);
            }
            System.out.printf("GAME #%d RESET... NEW GAME STARTING\n", i+1);
            set_encoder();
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
            try {
                reset();
            } catch (FileNotFoundException e) {
                throw new RuntimeException(e);
            } catch (GameException e) {
                throw new RuntimeException(e);
            }
            System.out.printf("GAME #%d RESET... NEW GAME STARTING\n", i+1);
            set_encoder();
        }
        Set<Integer> ignore = FeatureMerger.computeIgnoreList(encoder.stateVectors, 1.00);
        System.out.printf("IGNORE LIST SIZE: %d\n", ignore.size());
        System.out.printf("REDUCED VECTOR SIZE: %d\n", StateEncoder.indexCount - ignore.size());
    }
    @After
    public void print_vector_size() {
        System.out.printf("FINAL (unreduced) VECTOR SIZE: %d\n", StateEncoder.indexCount);
    }
}
