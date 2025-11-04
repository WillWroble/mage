package org.mage.test.AI.RL;

import mage.constants.RangeOfInfluence;
import mage.players.Player;
import org.junit.Before;
import org.junit.Test;
import org.mage.test.player.*;

public class SimulateMinimaxvsMinimax extends ParallelDataGenerator {

    @Before
    public void setup() {
        DISCOUNT_FACTOR = 0.95; //less states means higher discount
        VALUE_LAMBDA = 0.5; //consider lower lambda for heuristic minimax scores
    }
    @Test
    public void test_single_game() {
        super.test_single_game();
    }
    @Test
    public void generateData() {
        super.generateData();
    }
    @Test
    public void createTestDataSet() {
        DATA_OUT_FILE = "testing.hdf5";
        NUM_GAMES_TO_SIMULATE = 100;
        super.generateData();
    }
    @Test
    public void createTrainDataSet() {
        DATA_OUT_FILE = "training.hdf5";
        NUM_GAMES_TO_SIMULATE = 1000;
        super.generateData();
    }
    @Test
    public void createTrainingAndTestingDataSets() {
        createTestDataSet();
        createTrainDataSet();
    }
    @Override
    protected Player createPlayer(String name, RangeOfInfluence rangeOfInfluence) {
        if (name.equals("PlayerA")) {
            TestComputerPlayer8 t8 = new TestComputerPlayer8(name, RangeOfInfluence.ONE, 6);
            TestPlayer testPlayer = new TestPlayer(t8);
            testPlayer.setAIPlayer(true);
            return testPlayer;
        } else {
            TestComputerPlayer8 t8 = new TestComputerPlayer8(name, RangeOfInfluence.ONE, 6);
            TestPlayer testPlayer = new TestPlayer(t8);
            testPlayer.setAIPlayer(true);
            return testPlayer;
        }
    }

}
