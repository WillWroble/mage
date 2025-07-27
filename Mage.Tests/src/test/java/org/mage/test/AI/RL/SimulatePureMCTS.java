package org.mage.test.AI.RL;

import mage.constants.RangeOfInfluence;
import mage.player.ai.ComputerPlayerPureMCTS;
import org.junit.Test;
import org.mage.test.player.*;

public class SimulatePureMCTS extends ParallelDataGenerator {


    @Test
    public void generateTrainingAndTestingData() {
        super.generateTrainingAndTestingData();
    }

    // This is the correct override to use for creating players within our self-contained games.
    @Override
    protected TestPlayer createPlayer(String name, RangeOfInfluence rangeOfInfluence) {
        if(name.equals("PlayerA")) {
            TestComputerPlayerPureMonteCarlo pmcts = new TestComputerPlayerPureMonteCarlo(name, RangeOfInfluence.ONE, getSkillLevel());
            TestPlayer testPlayer = new TestPlayer(pmcts);
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
