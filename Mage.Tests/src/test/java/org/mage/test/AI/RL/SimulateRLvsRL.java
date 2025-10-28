package org.mage.test.AI.RL;

import mage.constants.RangeOfInfluence;
import org.junit.Before;
import org.junit.Test;
import org.mage.test.player.*;

public class SimulateRLvsRL extends ParallelDataGenerator {
    @Before
    public void setup() {
        DISCOUNT_FACTOR = 0.98; //tad higher because both players are logging micro decisions
        VALUE_LAMBDA = 0.5; //0.5 default for MCTS root scores
        DONT_USE_NOISE = true; //keep on unless agent has really plateaued. this should be a last resort; try retraining policy before running this
        DONT_USE_POLICY = true; //turn off after policy network has been trained on ~1000 games with this on
        MODEL_URL_A = "http://127.0.0.1:50052";
        MODEL_URL_B = "http://127.0.0.1:50053";
        ALWAYS_GO_FIRST = false; //consider turning on if your agent is really struggling to win (<20% WR)
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

    // This is the correct override to use for creating players within our self-contained games.
    @Override
    protected TestPlayer createPlayer(String name, RangeOfInfluence rangeOfInfluence) {
        if(name.equals("PlayerA")) {
            TestComputerPlayerMonteCarlo2 mcts2 = new TestComputerPlayerMonteCarlo2(name, RangeOfInfluence.ONE, getSkillLevel());
            TestPlayer testPlayer = new TestPlayer(mcts2);
            testPlayer.setAIPlayer(true); // enable full AI support (game simulations) for all turns by default
            return testPlayer;
        } else {
            TestComputerPlayerMonteCarlo2 mcts2 = new TestComputerPlayerMonteCarlo2(name, RangeOfInfluence.ONE, getSkillLevel());
            TestPlayer testPlayer = new TestPlayer(mcts2);
            testPlayer.setAIPlayer(true); // enable full AI support (game simulations) for all turns by default
            return testPlayer;
        }
    }
}
