package org.mage.test.AI.RL;

import mage.constants.RangeOfInfluence;
import org.junit.Test;
import org.mage.test.player.*;

public class SimulateMCTS extends ParallelDataGenerator {


    @Test
    public void generateTrainingAndTestingData() {
        super.generateTrainingAndTestingData();
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
