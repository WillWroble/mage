package org.mage.test.AI.RL;

import mage.constants.PhaseStep;
import mage.constants.RangeOfInfluence;
import mage.constants.Zone;
import mage.player.ai.*;
import mage.util.RandomUtil;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mage.test.player.*;

import java.io.*;
import java.util.*;

public class MCTS2WithNNTests extends MinimaxVectorExtractionTests {

    public static final String REPLAY_BUFFER_FILE = "replay_buffer.ser";
    public static final int REPLAY_BUFFER_CAPACITY = 10000; // e.g., holds states from ~200-300 games
    public ReplayBuffer replayBuffer;
    public int wins = 0;
    public int total = 0;

    @Override
    protected TestPlayer createPlayer(String name, RangeOfInfluence rangeOfInfluence) {
        if (getFullSimulatedPlayers().contains(name)) {
            if(name.equals("PlayerA")) {
                TestComputerPlayerMonteCarlo2 mcts2 = new TestComputerPlayerMonteCarlo2(name, RangeOfInfluence.ONE, getSkillLevel());
                TestPlayer testPlayer = new TestPlayer(mcts2);
                testPlayer.setAIPlayer(true); // enable full AI support (game simulations) for all turns by default
                return testPlayer;
            } else {
                TestComputerPlayer8 t8 = new TestComputerPlayer8(name, RangeOfInfluence.ONE, getSkillLevel());
                TestPlayer testPlayer = new TestPlayer(t8);
                testPlayer.setAIPlayer(true); // enable full AI support (game simulations) for all turns by default
                return testPlayer;
            }
        }
        return super.createPlayer(name, rangeOfInfluence);
    }
    @Before
    public void init_encoder() {
        init_seed();
        System.out.println("Setting up encoder");
        encoder = new StateEncoder();
        //ignore = new HashSet<>();
        //actions = new HashMap<>();
        // Try to load the persistent mapping from file
        File mappingFile = new File(MAPPING_FILE);
        if (mappingFile.exists()) {
            try {
                encoder.loadMapping(MAPPING_FILE);
                System.out.println("Loaded persistent mapping from " + MAPPING_FILE);
            } catch (IOException | ClassNotFoundException e) {
                System.out.println("Failed to load mapping. Starting with a fresh mapping.");
            }
        } else {
            System.out.println("No persistent mapping found. Starting fresh.");
        }
        //try to load persistent action mappings from file
        File actionsFile = new File(ACTIONS_FILE);
        if (actionsFile.exists()) {
            try {
                ActionEncoder.actionMap = (Map<String, Integer>) loadObject(ACTIONS_FILE);
                ActionEncoder.indexCount = ActionEncoder.actionMap.size();
                System.out.println("Loaded persistent mapping from " + ACTIONS_FILE);
            } catch (IOException | ClassNotFoundException e) {
                System.out.println("Failed to load mapping. Starting with a fresh mapping.");
            }
        } else {
            System.out.println("No persistent mapping found. Starting fresh.");
        }
        //also set up buffer
        File bufferFile = new File(REPLAY_BUFFER_FILE);
        if (bufferFile.exists()) {
            try {
                replayBuffer = (ReplayBuffer) loadObject(REPLAY_BUFFER_FILE);
                System.out.printf("Loaded Replay Buffer with %d states from %s%n", replayBuffer.size(), REPLAY_BUFFER_FILE);
            } catch (IOException | ClassNotFoundException e) {
                System.out.println("Failed to load Replay Buffer. Starting with a fresh one.");
                replayBuffer = new ReplayBuffer(REPLAY_BUFFER_CAPACITY);
            }
        } else {
            System.out.println("No Replay Buffer found. Starting fresh.");
            replayBuffer = new ReplayBuffer(REPLAY_BUFFER_CAPACITY);
        }

        set_encoder();
        labeledStates = new ArrayList<>();
    }
    @Override
    public void init_seed() {
        seed = RandomUtil.nextInt();
        //seed = -1421792887;
        //seed = 233400479;
        //seed = 1603827803;
        //seed = -99205609;

        //seed = 144516733;
        //seed = 197732112;
        //seed = -781685651;
        //seed = 2036403658;
        //seed = -1702733670;
        //seed = 1617973009;
        //seed = 1735298645;
        //seed = -1943293127;
        seed = -1018550371;
        System.out.printf("USING SEED: %d\n", seed);
        RandomUtil.setSeed(seed);
    }
    @Override
    public void set_encoder() {
        ComputerPlayerMCTS2 mcts2 = (ComputerPlayerMCTS2) playerA.getComputerPlayer();
        mcts2.clearTree();
        MCTSNode.clearCaches();
        ComputerPlayer8 c8 = (ComputerPlayer8)playerB.getComputerPlayer();
        c8.setEncoder(encoder);
        mcts2.setEncoder(encoder);
        mcts2.setBuffer(replayBuffer);
        mcts2.initNN("models/Model1.onnx");
        encoder.setAgent(playerA.getId());
        encoder.setOpponent(playerB.getId());
    }
    @Override
    public void create_labeled_states() {
        total++;
        if(playerA.hasWon()) wins++;
        int N = encoder.macroStateVectors.size();
        double γ = 0.99;          // discount factor

        labeledStateBatch.clear();
        for(int i = 0; i < N; i++) {
            Set<Integer> state = encoder.macroStateVectors.get(i);
            double[] action = ActionEncoder.actionVectors.get(i);

            boolean win = playerA.hasWon();
            double terminal = win ? +1.0 : -1.0;
            double discount = Math.pow(γ, N - i - 1);

            double score = terminal * discount;

            labeledStateBatch.add(new LabeledState(state, action, score));
        }
        reset_vectors();
    }
    public void loadGame() {
        if (replayBuffer.size() == 0) {
            System.out.println("Replay buffer is empty, skipping state load.");
            reset_game();
            return;
        }
        currentGame = replayBuffer.sample(1).get(0).copy();
        TestPlayer newPlayerA = (TestPlayer) currentGame.getPlayer(playerA.getId());
        TestPlayer newPlayerB = (TestPlayer) currentGame.getPlayer(playerB.getId());
        newPlayerA.setMatchPlayer(playerA.getMatchPlayer());
        newPlayerB.setMatchPlayer(playerB.getMatchPlayer());

        //ComputerPlayerMCTS2 mcts2 = (ComputerPlayerMCTS2) newPlayerA.getComputerPlayer();
        //mcts2.root = null;
        //ComputerPlayerMCTS.macroState = ComputerPlayerMCTS.createCompleteMCTSGame(currentGame);

        playerA.restore(newPlayerA);
        playerB.restore(newPlayerB);
        currentGame.getState().getPlayers().put(playerA.getId(), playerA);
        currentGame.getState().getPlayers().put(playerB.getId(), playerB);
        currentGame.setGameOptions(gameOptions);
        set_encoder();
    }
    @Test
    public void test_1_game() {
        int maxTurn = 50;
        Features.printOldFeatures = false;
        addCard(Zone.HAND, playerA, "Sheltered by ghosts");
        //ComputerPlayer.PRINT_DECISION_FALLBACKS = true;
        ComputerPlayerMCTS2.SHOW_THREAD_INFO = true;
        setStrictChooseMode(false);
        setStopAt(maxTurn, PhaseStep.END_TURN);
        execute();
    }
    @Test
    public void test_save_1_game_to_buffer() {
        int maxTurn = 50;
        Features.printOldFeatures = false;
        ComputerPlayerMCTS2.SHOW_THREAD_INFO = true;
        setStrictChooseMode(true);
        setStopAt(maxTurn, PhaseStep.END_TURN);
        execute();
        save_buffer();
    }
    @Test
    public void test_1_game_from_buffer() {
        loadGame();
        int maxTurn = 50;
        Features.printOldFeatures = false;
        ComputerPlayerMCTS2.SHOW_THREAD_INFO = true;
        setStrictChooseMode(true);
        setStopAt(maxTurn, PhaseStep.END_TURN);
        currentGame.resume();
    }
    @Test
    public void print_data() {
        System.out.printf("IGNORE LIST SIZE: %d\n", encoder.ignoreList.size());
        System.out.printf("REDUCED VECTOR SIZE: %d\n", StateEncoder.indexCount - encoder.ignoreList.size());
        System.out.printf("REPLAY BUFFER SIZE: %d\n", replayBuffer.size());
    }
    /**
     * make a training set of 50 games
     */
    @Test
    public void make_train_ds_X_50() {
        int maxTurn = 50;
        Features.printOldFeatures = false;
        ComputerPlayerMCTS2.SHOW_THREAD_INFO = true;
        ComputerPlayer.PRINT_DECISION_FALLBACKS = true;
        for(int i = 0; i < 5; i++) {
            addCard(Zone.HAND, playerA, "Sheltered by ghosts", 1);
            removeAllCardsFromHand(playerB);
            setStrictChooseMode(true);
            setStopAt(maxTurn, PhaseStep.END_TURN);
            execute();
            create_labeled_states();
            labeledStates.addAll(labeledStateBatch);
            labeledStateBatch.clear();
            reset_game();
            System.out.printf("GAME #%d RESET... NEW GAME STARTING\n", i+1);
        }
        Set<Integer> newIgnore = new HashSet<>(FeatureMerger.computeIgnoreListFromLS(labeledStates));
        Set<Integer> oldIgnore = new HashSet<>(encoder.ignoreList);
        encoder.ignoreList = combine_ignore_lists(oldIgnore, newIgnore);
        compress_labeled_states();

        print_labeled_states();
        persistLabeledStates(TRAIN_OUT_FILE);
        persistData();
        save_buffer();
        System.out.printf("IGNORE LIST SIZE: %d\n", encoder.ignoreList.size());
        System.out.printf("REDUCED VECTOR SIZE: %d\n", StateEncoder.indexCount - encoder.ignoreList.size());
        System.out.printf("WINRATE: %f\n", wins*1.0/total);
    }
    @Test
    public void make_train_ds_50_from_buffer() {
        int maxTurn = 50;
        Features.printOldFeatures = false;
        ComputerPlayerMCTS2.SHOW_THREAD_INFO = true;
        for(int i = 0; i < 5; i++) {
            loadGame();
            setStrictChooseMode(true);
            setStopAt(maxTurn, PhaseStep.END_TURN);
            currentGame.resume();
            create_labeled_states();
            labeledStates.addAll(labeledStateBatch);
            labeledStateBatch.clear();
            reset_game();
            System.out.printf("GAME #%d RESET... NEW GAME STARTING\n", i+1);
        }
        Set<Integer> newIgnore = new HashSet<>(FeatureMerger.computeIgnoreListFromLS(labeledStates));
        Set<Integer> oldIgnore = new HashSet<>(encoder.ignoreList);
        encoder.ignoreList = combine_ignore_lists(oldIgnore, newIgnore);
        compress_labeled_states();

        print_labeled_states();
        //persistLabeledStates(TRAIN_OUT_FILE);
        //persistData();
        //save_buffer();
        System.out.printf("IGNORE LIST SIZE: %d\n", encoder.ignoreList.size());
        System.out.printf("REDUCED VECTOR SIZE: %d\n", StateEncoder.indexCount - encoder.ignoreList.size());
        System.out.printf("WINRATE: %f\n", wins*1.0/total);
    }
    /**
     * make a testing/validation set of 5 random states from each of 50 games
     */
    @Test
    public void make_test_ds_X_50() {
        int maxTurn = 50;
        Features.printOldFeatures = false;
        for(int i = 0; i < 50; i++) {
            setStrictChooseMode(true);
            setStopAt(maxTurn, PhaseStep.END_TURN);
            execute();
            create_labeled_states();
            labeledStates.addAll(labeledStateBatch.subList(0, 5));
            labeledStateBatch.clear();
            reset_game();
            System.out.printf("GAME #%d RESET... NEW GAME STARTING\n", i+1);
        }
        print_labeled_states();
        persistLabeledStates(TEST_OUT_FILE);
        persistData();
    }
    public void save_buffer() {
        try {
            saveObject(replayBuffer, REPLAY_BUFFER_FILE);
            System.out.printf("Persisted replay buffer to %s%n", REPLAY_BUFFER_FILE);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    @After
    public void print_vector_size() {
        System.out.printf("FINAL (unreduced) VECTOR SIZE: %d\n", StateEncoder.indexCount);
        System.out.printf("FINAL ACTION VECTOR SIZE: %d\n", ActionEncoder.indexCount);
        System.out.printf("REPLAY BUFFER SIZE: %d\n", replayBuffer.size());
        for(String s : ActionEncoder.actionMap.keySet()) {
            System.out.printf("[%s => %d] ", s, ActionEncoder.actionMap.get(s));
        }
        System.out.println();
    }
}
