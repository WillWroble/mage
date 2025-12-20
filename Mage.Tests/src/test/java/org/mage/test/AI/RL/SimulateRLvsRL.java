package org.mage.test.AI.RL;

import mage.constants.RangeOfInfluence;
import mage.player.ai.ComputerPlayerMCTS2;
import mage.player.ai.RemoteModelEvaluator;
import mage.player.ai.StateEncoder;
import mage.players.Player;
import org.junit.Before;
import org.junit.Test;

public class SimulateRLvsRL extends ParallelDataGenerator {
    //RL params for opponent agent
    protected static boolean DONT_USE_POLICY_B = true;
    protected static boolean DONT_USE_POLICY_TARGET_B = true;
    protected static boolean DONT_USE_POLICY_USE_B = true;
    protected static boolean DONT_USE_NOISE_B = true;

    @Before
    public void setup() {
        VALUE_LAMBDA = 0.95; //0.5 default for MCTS root scores
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
        DATA_OUT_FILE_A = "training/" + DECK_A + "vs" + DECK_B + "_training.hdf5";
        DATA_OUT_FILE_B = "training/" + DECK_B + "vs" + DECK_A + "_training.hdf5";
        NUM_GAMES_TO_SIMULATE = 50;
        super.generateData();
    }
    @Test
    public void createTrainDataSet() {
        DATA_OUT_FILE_A = "testing/" + DECK_A + "vs" + DECK_B + "_testing.hdf5";
        DATA_OUT_FILE_B = "testing/" + DECK_B + "vs" + DECK_A + "_testing.hdf5";
        NUM_GAMES_TO_SIMULATE = 250;
        super.generateData();
    }
    @Test
    public void createTrainingAndTestingDataSets() {
        createTestDataSet();
        createTrainDataSet();
    }
    @Override
    protected void loadAllFiles() {
        super.loadAllFiles();
        try {
            remoteModelEvaluatorB = new RemoteModelEvaluator(MODEL_URL_B);
        } catch (Exception e) {
            e.printStackTrace();
            logger.warn("Failed to establish connection to opponent's network model; falling back to offline mode");
            remoteModelEvaluatorB = null;
        }
    }
    @Override
    protected void configurePlayer(Player player, StateEncoder encoder, StateEncoder opponentEncoder) {
        super.configurePlayer(player, encoder, opponentEncoder);
        if(player.getName().equals("PlayerB")) {
            ComputerPlayerMCTS2 mcts2  = (ComputerPlayerMCTS2) player.getRealPlayer();
            mcts2.nn = remoteModelEvaluatorB;
            mcts2.noNoise = DONT_USE_NOISE_B;
            mcts2.noPolicy = DONT_USE_POLICY_B;
            mcts2.noPolicyTarget = DONT_USE_POLICY_TARGET_B;
            mcts2.noPolicyUse = DONT_USE_POLICY_USE_B;
            if(remoteModelEvaluatorB == null) mcts2.offlineMode = true;
        }
    }
    // This is the correct override to use for creating players within our self-contained games.
    @Override
    protected Player createPlayer(String name, RangeOfInfluence rangeOfInfluence) {
        if(name.equals("PlayerA")) {
            ComputerPlayerMCTS2 mcts2 = new ComputerPlayerMCTS2(name, RangeOfInfluence.ONE, 6);
            return mcts2;
        } else {
            ComputerPlayerMCTS2 mcts2 = new ComputerPlayerMCTS2(name, RangeOfInfluence.ONE, 6);
            return mcts2;
        }
    }
}
