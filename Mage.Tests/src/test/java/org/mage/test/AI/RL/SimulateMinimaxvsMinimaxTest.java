package org.mage.test.AI.RL;

import mage.constants.RangeOfInfluence;
import mage.players.Player;
import org.junit.Before;
import org.junit.Test;
import org.mage.test.player.*;

public class SimulateMinimaxvsMinimaxTest extends ParallelDataGeneratorTest {

    @Before
    public void setup() {
        TD_DISCOUNT = 0.95; //consider higher lambda for heuristic minimax scores
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
        DATA_OUT_FILE_A = "testing.hdf5";
        NUM_GAMES_TO_SIMULATE = 100;
        super.generateData();
    }
    @Test
    public void createTrainDataSet() {
        DATA_OUT_FILE_A = "training.hdf5";
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
