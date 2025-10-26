package org.mage.test.AI.RL;

import mage.constants.RangeOfInfluence;
import org.junit.Before;
import org.junit.Test;
import org.mage.test.player.*;

import java.util.ArrayList;
import java.util.List;

public class SimulateRLvsMinimax extends ParallelDataGenerator {
    @Before
    public void setup() {
        DISCOUNT_FACTOR = 0.95; //default for sparse states; might be worth adjusting for particularly fast decks
        VALUE_LAMBDA = 0.5; //default for MCTS root scores
        DONT_USE_NOISE = true; //keep on unless agent has really plateaued. this should be a last resort; try retraining policy before running this
        DONT_USE_POLICY = false; //turn off after policy network has been trained on ~1000 games with this on
    }
    @Test
    public void test_single_game() {
        super.test_single_game(-6072638497124130761L);
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
    }
    @Test
    public void createTrainDataSet() {
        DATA_OUT_FILE = "training.hdf5";
        NUM_GAMES_TO_SIMULATE = 250;
        super.generateData();
    }
    @Test
    public void createTrainingAndTestingDataSets() {
        createTestDataSet();
        createTrainDataSet();
    }
    @Test
    public void roundRobin() {
        NUM_GAMES_TO_SIMULATE = 200;
        String [] deckPool = {"MTGA_MonoB", "MTGA_MonoG", "MTGA_MonoR", "MTGA_MonoU", "MTGA_MonoW"};
        for (String deckName :  deckPool) {
            DATA_OUT_FILE = "training/"+deckName+"_training.hdf5";
            DECK_B = deckName;
            super.generateData();
        }
    }
    @Test
    public void roundRobinTest() {
        NUM_GAMES_TO_SIMULATE = 40;
        String [] deckPool = {"MTGA_MonoB", "MTGA_MonoG", "MTGA_MonoR", "MTGA_MonoU", "MTGA_MonoW"};
        for (String deckName :  deckPool) {
            DATA_OUT_FILE = "testing/"+deckName+"_testing.hdf5";
            DECK_B = deckName;
            super.generateData();
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
