package org.mage.test.AI.RL;

import mage.constants.MultiplayerAttackOption;
import mage.constants.PhaseStep;
import mage.constants.RangeOfInfluence;
import mage.game.Game;
import mage.game.GameException;
import mage.game.TwoPlayerDuel;
import mage.game.mulligan.MulliganType;
import mage.player.ai.*;
import mage.util.RandomUtil;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mage.test.player.TestComputerPlayer7;
import org.mage.test.player.TestComputerPlayer8;
import org.mage.test.player.TestComputerPlayerMonteCarlo2;
import org.mage.test.player.TestPlayer;
import org.mage.test.serverside.base.CardTestPlayerBaseAI;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

public class MCTS2WithNNTests extends CardTestPlayerBaseAI {
    private String deckNameA = "UWTempo.dck"; //simplegreen, UWTempo
    private String deckNameB = "simplegreen.dck";
    private List<LabeledState> labeledStates = new ArrayList<>();
    private List<LabeledState> labeledStateBatch = new ArrayList<>();
    private StateEncoder encoder;
    //private Set<Integer> ignore;
    //private Map<String, Integer> actions;
    // File where the persistent mapping is stored
    private static final String MAPPING_FILE = "features_mapping.ser";
    private static final String ACTIONS_FILE = "actions_mapping.ser";
    private static final String TRAIN_OUT_FILE = "training.bin";
    private static final String TEST_OUT_FILE = "testing.bin";
    private int seed;



    @Override
    public List<String> getFullSimulatedPlayers() {
        return Arrays.asList("PlayerA", "PlayerB");
    }

    @Override
    protected Game createNewGameAndPlayers() throws GameException, FileNotFoundException {
        ComputerPlayerMCTS2.PATH_TO_NN = "C:\\Users\\WillWroble\\Documents\\GitHub\\MageZero\\exports\\UWTempo\\UWTempo2.onnx";

        Game game = new TwoPlayerDuel(MultiplayerAttackOption.LEFT, RangeOfInfluence.ONE, MulliganType.GAME_DEFAULT.getMulligan(0), 60, 20, 7);
        playerA = createPlayer(game, "PlayerA", "C:\\Users\\WillWroble\\Documents\\" + deckNameA);
        playerB = createPlayer(game, "PlayerB", "C:\\Users\\WillWroble\\Documents\\" + deckNameB);
        return game;
    }
    @Override
    protected TestPlayer createPlayer(String name, RangeOfInfluence rangeOfInfluence) {
        if (getFullSimulatedPlayers().contains(name)) {
            if(name.equals("PlayerA")) {
                TestComputerPlayerMonteCarlo2 mcts2 = new TestComputerPlayerMonteCarlo2(name, RangeOfInfluence.ONE, getSkillLevel());
                TestPlayer testPlayer = new TestPlayer(mcts2);
                testPlayer.setAIPlayer(true); // enable full AI support (game simulations) for all turns by default
                return testPlayer;
            } else {
                TestComputerPlayer7 t7 = new TestComputerPlayer7(name, RangeOfInfluence.ONE, getSkillLevel());
                TestPlayer testPlayer = new TestPlayer(t7);
                testPlayer.setAIPlayer(true); // enable full AI support (game simulations) for all turns by default
                return testPlayer;
            }
        }
        return super.createPlayer(name, rangeOfInfluence);
    }
    public void init_seed() {
        seed = RandomUtil.nextInt();
        //seed = -1421792887;
        seed = 233400479;
        System.out.printf("USING SEED: %d\n", seed);
        RandomUtil.setSeed(seed);
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
                ActionEncoder.makeInverseMap();
                ActionEncoder.indexCount = ActionEncoder.actionMap.size();
                System.out.println("Loaded persistent mapping from " + ACTIONS_FILE);
            } catch (IOException | ClassNotFoundException e) {
                System.out.println("Failed to load mapping. Starting with a fresh mapping.");
            }
        } else {
            System.out.println("No persistent mapping found. Starting fresh.");
        }

        set_encoder();
        labeledStates = new ArrayList<>();
    }
    public void set_encoder() {
        ComputerPlayerMCTS2 mcts2 = (ComputerPlayerMCTS2) playerA.getComputerPlayer();
        mcts2.setEncoder(encoder);
        encoder.setAgent(playerA.getId());
        encoder.setOpponent(playerB.getId());
    }
    public void reset_game() {
        try {
            reset();
        } catch (FileNotFoundException e) {
            throw new RuntimeException(e);
        } catch (GameException e) {
            throw new RuntimeException(e);
        }
        set_encoder();

    }
    public void reset_vectors() {
        encoder.macroStateVectors.clear();
        encoder.stateScores.clear();
        ActionEncoder.actionVectors.clear();
    }

    /**
     * uses saved list of actions and states to make a labeled vector batch for training
     */
    public void create_labeled_states() {
        int N = encoder.macroStateVectors.size();
        double γ = 0.99;          // discount factor
        double λ = 0.5;           // how much weight to give the minimax estimate vs. terminal

        labeledStateBatch.clear();
        for(int i = 0; i < N; i++) {
            // 1) decompress your raw state and action bits (you already have this)
            boolean[] state = encoder.getCompressedVector(encoder.macroStateVectors.get(i));
            boolean[] action = ActionEncoder.actionVectors.get(i);

            // 2) get your raw minimax score and normalize into [-1,+1]
            double rawScore = encoder.stateScores.get(i);
            //double normScore = rawScore / (double)Math.abs(GameStateEvaluator2.LOSE_GAME_SCORE);

            double scale = 20000.0;              // or better yet: maxAbs(stateScores)
            double normScore = Math.tanh(rawScore/scale);


            // 3) build your discounted terminal label in [-1,+1]
            boolean win = playerA.hasWon();
            double terminal = win ? +1.0 : -1.0;
            double discount = Math.pow(γ, N - i - 1);

            // 4) blend them
            double blended = λ * normScore + (1.0 - λ) * terminal * discount;

            // 5) store a single LabeledState with that double label
            labeledStateBatch.add(new LabeledState(state, action, blended));
        }

        // shuffle before writing out / persisting
        //Collections.shuffle(labeledStateBatch);

        reset_vectors();
    }
    public void print_labeled_states() {
        for (LabeledState ls : labeledStates) {
            StringBuilder sb1 = new StringBuilder();
            for (int i = 0; i < 100; i++) {
                sb1.append(ls.stateVector[i] ? "1" : "0");
                //sb1.append(", ");
            }
            StringBuilder sb2 = new StringBuilder();
            for (int i = 0; i < 100; i++) {
                sb2.append(ls.actionVector[i] ? "1" : "0");
                //sb2.append(", ");
            }
            System.out.printf("State: %s, Action: %s, Result: %s\n", sb1.toString(), sb2.toString(), ls.resultLabel);
        }
    }
    @Test
    public void test_1_game() {
        int maxTurn = 50;
        Features.printOldFeatures = false;
        setStrictChooseMode(true);
        setStopAt(maxTurn, PhaseStep.END_TURN);
        execute();



    }
    /**
     * make a training set of 50 games
     */
    @Test
    public void make_train_ds_X_50() {
        int maxTurn = 50;
        Features.printOldFeatures = false;
        for(int i = 0; i < 50; i++) {
            setStrictChooseMode(true);
            setStopAt(maxTurn, PhaseStep.END_TURN);
            execute();
            create_labeled_states();
            labeledStates.addAll(labeledStateBatch);
            labeledStateBatch.clear();
            reset_game();
            System.out.printf("GAME #%d RESET... NEW GAME STARTING\n", i+1);
        }
        //Collections.shuffle(labeledStates);
        print_labeled_states();
        persistLabeledStates(TRAIN_OUT_FILE);
        persistData();
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
    @After
    public void print_vector_size() {
        System.out.printf("FINAL (unreduced) VECTOR SIZE: %d\n", StateEncoder.indexCount);
        System.out.printf("FINAL ACTION VECTOR SIZE: %d\n", ActionEncoder.indexCount);
        for(String s : ActionEncoder.actionMap.keySet()) {
            System.out.printf("[%s => %d] ", s, ActionEncoder.actionMap.get(s));
        }
        System.out.println();
    }
    private void persistLabeledStates(String filename) {
        try (DataOutputStream out = new DataOutputStream(
                new BufferedOutputStream(new FileOutputStream(filename)))) {
            int n = labeledStates.size();
            int S = labeledStates.get(0).stateVector.length;
            int A = labeledStates.get(0).actionVector.length;
            // Write header: #records, state-dim, action-dim
            out.writeInt(n);
            out.writeInt(S);
            out.writeInt(A);
            // Write raw data: one byte per boolean
            for (LabeledState ls : labeledStates) {
                for (boolean b : ls.stateVector) out.writeByte(b ? 1 : 0);
                for (boolean b : ls.actionVector) out.writeByte(b ? 1 : 0);
                out.writeDouble(ls.resultLabel);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void persistData() {
        try {
            encoder.persistMapping(MAPPING_FILE);
            System.out.printf("Persisted feature mapping to %s\n", MAPPING_FILE);
            //saveObject(ignore, IGNORE_FILE);
            //System.out.printf("Persisted ignore list to %s\n", IGNORE_FILE);
            saveObject(new HashMap<>(ActionEncoder.actionMap), ACTIONS_FILE);
            System.out.printf("Persisted action mapping to %s\n", ACTIONS_FILE);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // Method to save a Serializable object to a file
    public static void saveObject(Object obj, String fileName) throws IOException {
        try (ObjectOutputStream out = new ObjectOutputStream(Files.newOutputStream(Paths.get(fileName)))) {
            out.writeObject(obj);
        }
    }

    // Method to load a Serializable object from a file
    public static Object loadObject(String fileName) throws IOException, ClassNotFoundException {
        try (ObjectInputStream in = new ObjectInputStream(Files.newInputStream(Paths.get(fileName)))) {
            return in.readObject();
        }
    }
}
