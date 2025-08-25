package org.mage.test.AI.RL;

import mage.constants.PhaseStep;
import mage.constants.RangeOfInfluence;
import mage.player.ai.*;
import mage.util.RandomUtil;
import org.junit.After;
import org.junit.Test;
import org.mage.test.player.TestComputerPlayer7;
import org.mage.test.player.TestComputerPlayerPureMonteCarlo;
import org.mage.test.player.TestPlayer;

import java.util.*;

public class GenerateMappings extends MinimaxVectorExtractionTests {


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
    @Override
    public void init_seed() {
        seed = RandomUtil.nextInt();
        //seed = -1421792887;
        seed = 233400479;
        System.out.printf("USING SEED: %d\n", seed);
        RandomUtil.setSeed(seed);
    }
    @Override
    public void set_encoder() {
        ComputerPlayerPureMCTS pmc = (ComputerPlayerPureMCTS)playerA.getComputerPlayer();
        pmc.setEncoder(encoder);
        encoder.setAgent(playerA.getId());
        encoder.setOpponent(playerB.getId());
    }
    @Test
    public void print_current_ignore_list() {
        System.out.printf("IGNORE LIST SIZE: %d\n", encoder.ignoreList.size());
        System.out.printf("REDUCED VECTOR SIZE: %d\n", StateEncoder.indexCount - encoder.ignoreList.size());
        System.out.print("RAW TO REDUCED MAPPING: ");
        System.out.println();
        System.out.println(encoder.ignoreList.toString());
    }
    /**
     * uses saved list of actions and states to make a labeled vector batch for training
     */

    @Test
    public void make_ignore_X_50() {
        int maxTurn = 50;
        Features.printOldFeatures = false;
        for(int i = 0; i < 50; i++) {
            setStrictChooseMode(true);
            setStopAt(maxTurn, PhaseStep.END_TURN);
            execute();
            reset_game();
            System.out.printf("GAME #%d RESET... NEW GAME STARTING\n", i+1);
        }
        Set<Integer> newIgnore = new HashSet<>(FeatureMerger.computeIgnoreList(encoder.stateVectors));
        Set<Integer> oldIgnore = new HashSet<>(encoder.ignoreList);
        encoder.ignoreList = combine_ignore_lists(oldIgnore, newIgnore);
        //actions = new HashMap<>(ActionEncoder.actionMap);
        persistData();
        System.out.printf("IGNORE LIST SIZE: %d\n", encoder.ignoreList.size());
        System.out.printf("REDUCED VECTOR SIZE: %d\n", StateEncoder.indexCount - encoder.ignoreList.size());
        //encoder.ignoreList = new HashSet<>(ignore);

    }
    /**
     * make a training set of 50 games
     */
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
