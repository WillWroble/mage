package org.mage.test.AI.RL;

import mage.constants.RangeOfInfluence;
import mage.player.ai.ComputerPlayer8;
import mage.player.ai.ComputerPlayerMCTS2;
import mage.players.Player;
import org.junit.Before;
import org.junit.Test;

public class SimulateRLvsMinimax extends ParallelDataGenerator {
    @Before
    public void setup() {
        VALUE_LAMBDA = 0.95; //default for MCTS root scores
        DONT_USE_NOISE = true; //keep on unless agent has really plateaued. this should be a last resort; try retraining policy before running this
        DONT_USE_POLICY = false; //turn off after policy network has been trained on ~1000 games with this on
        DONT_USE_POLICY_TARGET = true; //if you want to use other policies but not targeting decisions
        DONT_USE_POLICY_USE = false; //if you want to use other policies but not use decisions
        DECK_A = "UWTempo";
        DECK_B = "simplegreen";
    }
    @Test
    public void test_single_game() {
        DECK_A = "UWTempo";
        DECK_B = "MTGA_MonoG";
        super.test_single_game(190422044292051552L);//countering own spell for no reason
    }
    @Test
    public void generateData() {
        super.generateData();
    }

    @Test
    public void createTestDataSet() {
        DATA_OUT_FILE_A = "testing.hdf5";
        DECK_A = "UWTempo";
        DECK_B = "simplegreen";
        NUM_GAMES_TO_SIMULATE = 200;
        //ALWAYS_GO_FIRST = true;
        super.generateData();
        writeResults("test_results.txt", "WR with " + DECK_A + " vs " +
                DECK_B + ": " + winCount.get() * 1.0 / gameCount.get() + " in " + gameCount.get() + " games");
    }
    @Test
    public void createTrainDataSet() {
        DATA_OUT_FILE_A = "training.hdf5";
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
        DECK_A = "Standard-MonoU";
        //DECK_A = "UWTempo";

        isRoundRobin = true;
        NUM_GAMES_TO_SIMULATE = 200;
        String [] deckPool = {"Standard-MonoB", "Standard-MonoG", "Standard-MonoR", "Standard-MonoU", "Standard-MonoW"};
        //String [] deckPool = {"MTGA_MonoB", "MTGA_MonoG", "MTGA_MonoR", "MTGA_MonoU", "MTGA_MonoW"};
        for (String deckName :  deckPool) {
            DATA_OUT_FILE_A = "training/"+deckName+"_training.hdf5";
            DECK_B = deckName;
            super.generateData();
            writeResults("round_robin_train_results.txt", "WR with " + DECK_A + " vs " +
                    deckName + ": " + winCount.get() * 1.0 / gameCount.get() + " in " + gameCount.get() + " games");
        }
    }
    @Test
    public void roundRobinTest() {
        DECK_A = "Standard-MonoU";
        isRoundRobin = true;
        NUM_GAMES_TO_SIMULATE = 20;
        String [] deckPool = {"Standard-MonoB", "Standard-MonoG", "Standard-MonoR", "Standard-MonoU", "Standard-MonoW"};
        for (String deckName :  deckPool) {
            DATA_OUT_FILE_A = "testing/"+deckName+"_testing.hdf5";
            DECK_B = deckName;
            super.generateData();
            writeResults("round_robin_test_results.txt", "WR with " + DECK_A + " vs " +
                    deckName + ": " + winCount.get() * 1.0 / gameCount.get() + " in " + gameCount.get() + " games");
        }
    }

    // This is the correct override to use for creating players within our self-contained games.
    @Override
    protected Player createPlayer(String name, RangeOfInfluence rangeOfInfluence) {
        if(name.equals("PlayerA")) {
            ComputerPlayerMCTS2 mcts2 = new ComputerPlayerMCTS2(name, RangeOfInfluence.ONE, 6);
            //TestPlayer testPlayer = new TestPlayer(mcts2);
            //testPlayer.setAIPlayer(true); // enable full AI support (game simulations) for all turns by default
            return mcts2;
        } else {
            ComputerPlayer8 t8 = new ComputerPlayer8(name, RangeOfInfluence.ONE, 6);
            //TestPlayer testPlayer = new TestPlayer(t8);
            //testPlayer.setAIPlayer(true);
            return t8;
        }
    }
}
