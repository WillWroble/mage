package org.mage.test.AI.RL;

import mage.constants.RangeOfInfluence;
import mage.player.ai.ComputerPlayerMCTS;
import org.junit.Before;
import org.junit.Test;
import org.mage.test.player.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class SimulateRLvsMinimax extends ParallelDataGenerator {
    @Before
    public void setup() {
        //this is a test for Will


        DISCOUNT_FACTOR = 0.97; //default for dense states; might be worth lowering for particularly fast decks
        VALUE_LAMBDA = 0.5; //default for MCTS root scores
        DONT_USE_NOISE = true; //keep on unless agent has really plateaued. this should be a last resort; try retraining policy before running this
        DONT_USE_POLICY = true; //turn off after policy network has been trained on ~1000 games with this on


        DECK_A = "GBLegends";
        DECK_B = "MTGA_MonoR";
    }
    @Test
    public void test_single_game() {
        DECK_A = "GBLegends";
        DECK_B = "MTGA_MonoR";
        super.test_single_game(-7524173106912918197L);
    }
    @Test
    public void generateData() {
        super.generateData();
    }

    @Test
    public void createTestDataSet() {
        DATA_OUT_FILE = "testing.hdf5";
        NUM_GAMES_TO_SIMULATE = 50;
        super.generateData();
        writeResults("test_results.txt", "WR with " + DECK_A + " vs " +
                DECK_B + ": " + winCount.get() * 1.0 / gameCount.get() + " in " + gameCount.get() + " games");
    }
    @Test
    public void createTrainDataSet() {
        DATA_OUT_FILE = "training.hdf5";
        NUM_GAMES_TO_SIMULATE = 250;
        super.generateData();
        writeResults("train_results.txt", "WR with " + DECK_A + " vs " +
                DECK_B + ": " + winCount.get() * 1.0 / gameCount.get() + " in " + gameCount.get() + " games");
    }
    @Test
    public void createTrainingAndTestingDataSets() {
        createTestDataSet();
        createTrainDataSet();
    }
    @Test
    public void roundRobin() {
        ComputerPlayerMCTS.ROUND_ROBIN_MODE = true;
        NUM_GAMES_TO_SIMULATE = 200;
        String [] deckPool = {"MTGA_MonoB", "MTGA_MonoG", "MTGA_MonoR", "MTGA_MonoU", "MTGA_MonoW"};
        for (String deckName :  deckPool) {
            DATA_OUT_FILE = "training/"+deckName+"_training.hdf5";
            DECK_B = deckName;
            super.generateData();
            writeResults("round_robin_train_results.txt", "WR with " + DECK_A + " vs " +
                    deckName + ": " + winCount.get() * 1.0 / gameCount.get() + " in " + gameCount.get() + " games");
        }
    }
    @Test
    public void roundRobinTest() {
        DECK_A = "GBLegends";
        ComputerPlayerMCTS.ROUND_ROBIN_MODE = true;
        NUM_GAMES_TO_SIMULATE = 40;
        String [] deckPool = {"MTGA_MonoB", "MTGA_MonoG", "MTGA_MonoR", "MTGA_MonoU", "MTGA_MonoW"};
        for (String deckName :  deckPool) {
            DATA_OUT_FILE = "testing/"+deckName+"_testing.hdf5";
            DECK_B = deckName;
            super.generateData();
            writeResults("round_robin_test_results.txt", "WR with " + DECK_A + " vs " +
                    deckName + ": " + winCount.get() * 1.0 / gameCount.get() + " in " + gameCount.get() + " games");
        }
    }

    // This is the correct override to use for creating players within our self-contained games.
    @Override
    protected TestPlayer createPlayer(String name, RangeOfInfluence rangeOfInfluence) {
        if(name.equals("PlayerA")) {
            TestComputerPlayerMonteCarlo2 mcts2 = new TestComputerPlayerMonteCarlo2(name, RangeOfInfluence.ONE, getSkillLevel());
            TestPlayer testPlayer = new TestPlayer(mcts2);
            testPlayer.setAIPlayer(true); // enable full AI support (game simulations) for all turns by default
            return testPlayer;
        } else {
            TestComputerPlayer8 t8 = new TestComputerPlayer8(name, RangeOfInfluence.ONE, getSkillLevel());
            TestPlayer testPlayer = new TestPlayer(t8);
            testPlayer.setAIPlayer(true);
            return testPlayer;
        }
    }
}
