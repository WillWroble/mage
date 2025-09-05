package org.mage.test.AI.RL;

import mage.constants.RangeOfInfluence;
import org.junit.Test;
import org.mage.test.player.*;

public class SimulateMCTS extends ParallelDataGenerator {

    @Test
    public void test_single_game() {
        super.test_single_game();
    }
    @Test
    public void generateTrainingAndTestingData() {
        super.generateTrainingAndTestingData();
    }

    //generates 4 250 batch files (1000 games)
    @Test
    public void train_4_250() {
        NUM_GAMES_TO_SIMULATE_TRAIN=250;
        NUM_GAMES_TO_SIMULATE_TEST=0;
        for(int i = 0; i<4; i++) {
            TRAIN_OUT_FILE = ("training"+i+".bin");
            super.generateTrainingAndTestingData();
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
            TestComputerPlayer7 t7 = new TestComputerPlayer7(name, RangeOfInfluence.ONE, getSkillLevel());
            TestPlayer testPlayer = new TestPlayer(t7);
            testPlayer.setAIPlayer(true);
            return testPlayer;
        }
    }
}
