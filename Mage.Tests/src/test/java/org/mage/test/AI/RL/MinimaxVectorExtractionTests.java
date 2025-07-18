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
import org.mage.test.player.TestPlayer;
import org.mage.test.serverside.base.CardTestPlayerBaseAI;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

public class MinimaxVectorExtractionTests extends CardTestPlayerBaseAI {
    private String deckNameA = "UWTempo.dck"; //simplegreen, UWTempo
    private String deckNameB = "simplegreen.dck";
    public List<LabeledState> labeledStates = new ArrayList<>();
    public List<LabeledState> labeledStateBatch = new ArrayList<>();
    public StateEncoder encoder;
    public int seed;

    //private Set<Integer> ignore;
    //private Map<String, Integer> actions;
    // File where the persistent mapping is stored
    public static final String MAPPING_FILE = "features_mapping.ser";
    public static final String ACTIONS_FILE = "actions_mapping.ser";
    public static final String TRAIN_OUT_FILE = "training.bin";
    public static final String TEST_OUT_FILE = "testing.bin";


    @Override
    public List<String> getFullSimulatedPlayers() {
        return Arrays.asList("PlayerA", "PlayerB");
    }

    @Override
    protected Game createNewGameAndPlayers() throws GameException, FileNotFoundException {
        Game game = new TwoPlayerDuel(MultiplayerAttackOption.LEFT, RangeOfInfluence.ONE, MulliganType.GAME_DEFAULT.getMulligan(0), 60, 20, 7);
        playerA = createPlayer(game, "PlayerA",  deckNameA);
        playerB = createPlayer(game, "PlayerB",  deckNameB);
        return game;
    }
    @Override
    protected TestPlayer createPlayer(String name, RangeOfInfluence rangeOfInfluence) {
        if (getFullSimulatedPlayers().contains(name)) {
            if(name.equals("PlayerA")) {
                TestComputerPlayer8 t8 = new TestComputerPlayer8(name, RangeOfInfluence.ONE, getSkillLevel());
                TestPlayer testPlayer = new TestPlayer(t8);
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
    public void init_seed() {
        seed = RandomUtil.nextInt();
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
        ComputerPlayer8 c8 = (ComputerPlayer8)playerA.getComputerPlayer(); c8.setEncoder(encoder);
        c8 = (ComputerPlayer8)playerB.getComputerPlayer(); c8.setEncoder(encoder);
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
        encoder.activeStates.clear();
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
            Set<Integer> state = encoder.macroStateVectors.get(i);
            double[] action = ActionEncoder.actionVectors.get(i);
            double normScore = encoder.stateScores.get(i);

            boolean win = playerA.hasWon();
            double terminal = win ? +1.0 : -1.0;
            double discount = Math.pow(γ, N - i - 1);

            double blended = λ * normScore + (1.0 - λ) * terminal * discount;

            labeledStateBatch.add(new LabeledState(state, action, blended));
        }
        reset_vectors();
    }
    public void print_labeled_states() {
        for (LabeledState ls : labeledStates) {
            StringBuilder sb1 = new StringBuilder();
            for (int i = 0; i < 100; i++) {
                sb1.append(ls.stateVector[i]);
                sb1.append(" ");
            }

            System.out.printf("State: %s, Action: %s, Result: %s\n", sb1.toString(), Arrays.toString(ls.actionVector), ls.resultLabel);

        }
    }

    /**
     * can remove items from ignore list
     * @param oldList
     * @param newList
     * @return
     */
    public Set<Integer> combine_ignore_lists(Set<Integer> oldList, Set<Integer> newList) {
        Set<Integer> updatedIgnoreList = new HashSet<>();

        int boundaryForOldFeatures = this.encoder.initialRawSize;


        for (int i = 0; i < boundaryForOldFeatures; i++) {
            if (oldList.contains(i) && newList.contains(i)) {
                updatedIgnoreList.add(i);
            }
        }

        for (Integer featureIndexInNewList : newList) {
            if (featureIndexInNewList >= boundaryForOldFeatures) {
                updatedIgnoreList.add(featureIndexInNewList);
            }
        }

        return updatedIgnoreList;
    }

    /**
     * use the current encoder's compression at the end so it can use the new ignore list
     */
    public void compress_labeled_states() {
        for (LabeledState ls : labeledStates) {
            ls.compress(encoder);
        }
    }
    @Test
    public void print_current_ignore_list() {
        System.out.printf("IGNORE LIST SIZE: %d\n", encoder.ignoreList.size());
        System.out.printf("REDUCED VECTOR SIZE: %d\n", StateEncoder.indexCount - encoder.ignoreList.size());
    }
    @Test
    public void make_ignore_X_50() {
        int maxTurn = 50;
        Features.printOldFeatures = false;
        for(int i = 0; i < 10; i++) {
            setStrictChooseMode(true);
            setStopAt(maxTurn, PhaseStep.END_TURN);
            execute();
            reset_game();
            System.out.printf("GAME #%d RESET... NEW GAME STARTING\n", i+1);
        }
        //assert(!encoder.ignoreList.isEmpty());
        Set<Integer> newIgnore = new HashSet<>(FeatureMerger.computeIgnoreList(encoder.macroStateVectors));
        Set<Integer> oldIgnore = new HashSet<>(encoder.ignoreList);
        encoder.ignoreList = combine_ignore_lists(oldIgnore, newIgnore);
        //actions = new HashMap<>(ActionEncoder.actionMap);
        persistData();
        System.out.printf("IGNORE LIST SIZE: %d\n", encoder.ignoreList.size());
        System.out.printf("REDUCED VECTOR SIZE: %d\n", StateEncoder.indexCount - encoder.ignoreList.size());
        //encoder.ignoreList = new HashSet<>(ignore);

    }
    /**
     * make a training set of 50 games
     */
    @Test
    public void make_train_ds_X_250() {
        int maxTurn = 50;
        Features.printOldFeatures = false;
        for(int i = 0; i < 250; i++) {
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
        System.out.printf("IGNORE LIST SIZE: %d\n", encoder.ignoreList.size());
        System.out.printf("REDUCED VECTOR SIZE: %d\n", StateEncoder.indexCount - encoder.ignoreList.size());
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
            Collections.shuffle(labeledStateBatch);
            labeledStates.addAll(labeledStateBatch.subList(0, 5));
            labeledStateBatch.clear();
            reset_game();
            System.out.printf("GAME #%d RESET... NEW GAME STARTING\n", i+1);
        }
        Set<Integer> newIgnore = new HashSet<>(FeatureMerger.computeIgnoreListFromLS(labeledStates));
        Set<Integer> oldIgnore = new HashSet<>(encoder.ignoreList);
        encoder.ignoreList = combine_ignore_lists(oldIgnore, newIgnore);
        compress_labeled_states();

        print_labeled_states();
        persistLabeledStates(TEST_OUT_FILE);
        persistData();
        System.out.printf("IGNORE LIST SIZE: %d\n", encoder.ignoreList.size());
        System.out.printf("REDUCED VECTOR SIZE: %d\n", StateEncoder.indexCount - encoder.ignoreList.size());
    }
    @After
    public void print_vector_size() {
        System.out.printf("RAW VECTOR SIZE: %d\n", StateEncoder.indexCount);
        System.out.printf("FINAL ACTION VECTOR SIZE: %d\n", ActionEncoder.indexCount);
        for(String s : ActionEncoder.actionMap.keySet()) {
            System.out.printf("[%s => %d] ", s, ActionEncoder.actionMap.get(s));
        }
        System.out.println();
    }
    public void persistLabeledStates(String filename) {
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(Paths.get(filename))))) {

            // 1) Header
            int n = labeledStates.size();

            // 'S' now represents the TOTAL size of your global feature vocabulary.
            // The constant should be updated to reflect this.
            int S = StateEncoder.indexCount;

            // 'A' is still the size of the policy vector.
            int A = 128;

            // The new, simpler header:
            out.writeInt(n);
            out.writeInt(S); // Tells PyTorch num_embeddings for the EmbeddingBag
            out.writeInt(A); // Tells PyTorch the size of the policy vector

            // 2) Body
            for (LabeledState ls : labeledStates) {
                // This now calls your MODIFIED LabeledState.persist() method,
                // which writes a variable-length list of indices.
                ls.persist(out);
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
