package org.mage.test.AI.RL;

import mage.cards.decks.Deck;
import mage.cards.decks.DeckCardLists;
import mage.cards.decks.importer.DeckImporter;
import mage.constants.*;
import mage.game.Game;
import mage.game.GameException;
import mage.game.GameOptions;
import mage.game.TwoPlayerDuel;
import mage.game.mulligan.MulliganType;
import mage.player.ai.*;
import mage.util.RandomUtil;
import org.junit.Test;
import org.mage.test.player.TestComputerPlayer8;
import org.mage.test.player.TestComputerPlayerMonteCarlo2;
import org.mage.test.player.TestPlayer;
import org.mage.test.serverside.base.CardTestPlayerBaseAI;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * A dedicated, parallelized test class for generating training and testing data sets.
 * This version is fully compatible with Java 1.8 and uses the correct, thread-safe
 * game execution logic as defined by the test framework.
 */
public class ParallelDataGenerator extends CardTestPlayerBaseAI {

    //region Configuration
    // ============================ DATA GENERATION SETTINGS ============================
    private static final int NUM_GAMES_TO_SIMULATE_TRAIN = 400;
    private static final int NUM_GAMES_TO_SIMULATE_TEST = 80;
    private static final int MAX_GAME_TURNS = 50;
    private static final int MAX_CONCURRENT_GAMES = 4;

    // =============================== DECK AND AI SETTINGS ===============================
    private static final String DECK_A = "UWTempo.dck";
    private static final String DECK_B = "simplegreen.dck";
    private static final String MCTS_MODEL_PATH = "models/Model2.onnx";
    private static final int MCTS_ROLLOUT_THREADS = 2;

    // ================================== FILE PATHS ==================================
    private static final String MAPPING_FILE = "features_mapping.ser";
    private static final String ACTIONS_FILE = "actions_mapping.ser";
    private static final String TRAIN_OUT_FILE = "training.bin";
    private static final String TEST_OUT_FILE = "testing.bin";
    //endregion

    /**
     * A simple class to hold the results of a single game, compatible with Java 1.8.
     */
    private static class GameResult {
        private final List<LabeledState> states;
        private final boolean didPlayerAWin;

        public GameResult(List<LabeledState> states, boolean didPlayerAWin) {
            this.states = states;
            this.didPlayerAWin = didPlayerAWin;
        }

        public List<LabeledState> getStates() {
            return states;
        }

        public boolean didPlayerAWin() {
            return didPlayerAWin;
        }
    }

    @Test
    public void generateTrainingAndTestingData() {
        System.out.println("=========================================");
        System.out.println("   STARTING TRAINING DATA GENERATION     ");
        System.out.println("=========================================");
        List<LabeledState> trainingStates = runSimulations(NUM_GAMES_TO_SIMULATE_TRAIN);
        processAndSaveData(trainingStates, TRAIN_OUT_FILE);

        System.out.println("\n=========================================");
        System.out.println("    STARTING TESTING DATA GENERATION     ");
        System.out.println("=========================================");
        List<LabeledState> testingStates = runSimulations(NUM_GAMES_TO_SIMULATE_TEST);
        processAndSaveData(testingStates, TEST_OUT_FILE);

        System.out.println("\nData generation complete.");
    }

    private List<LabeledState> runSimulations(int numGames) {
        int availableCores = Runtime.getRuntime().availableProcessors();
        int poolSize = MAX_CONCURRENT_GAMES;
        System.out.printf("Simulating %d games. Using thread pool of size %d on %d available cores.\n", numGames, poolSize, availableCores);

        ExecutorService executor = Executors.newFixedThreadPool(poolSize);
        List<Callable<GameResult>> tasks = new ArrayList<>();
        for (int i = 0; i < numGames; i++) {
            tasks.add(new Callable<GameResult>() {
                @Override
                public GameResult call() throws Exception {
                    return runSingleGame();
                }
            });
        }

        List<LabeledState> allLabeledStates = new ArrayList<>();
        int wins = 0;
        try {
            List<Future<GameResult>> futures = executor.invokeAll(tasks);
            executor.shutdown();

            for (Future<GameResult> future : futures) {
                GameResult result = future.get();
                allLabeledStates.addAll(result.getStates());
                if (result.didPlayerAWin()) {
                    wins++;
                }
            }
        } catch (InterruptedException | ExecutionException e) {
            e.printStackTrace();
            Thread.currentThread().interrupt();
        }

        System.out.printf("Simulation complete. Player A win rate: %.2f%% (%d/%d)\n", (100.0 * wins / numGames), wins, numGames);
        return allLabeledStates;
    }

    private void processAndSaveData(List<LabeledState> allStates, String outputFilename) {
        if (allStates.isEmpty()) {
            System.out.println("No states were generated, skipping file save for " + outputFilename);
            return;
        }
        System.out.println("Processing " + allStates.size() + " states for " + outputFilename);

        StateEncoder finalEncoder = new StateEncoder();
        try {
            finalEncoder.loadMapping(MAPPING_FILE);
            System.out.println("Loaded feature map and old ignore list (" + finalEncoder.ignoreList.size() + " features).");
        } catch (IOException | ClassNotFoundException e) {
            System.out.println("No mapping file found. Starting fresh.");
        }

        Set<Integer> oldIgnoreList = new HashSet<>(finalEncoder.ignoreList);
        Set<Integer> newIgnoreList = new HashSet<>(FeatureMerger.computeIgnoreListFromLS(allStates));
        System.out.println("Computed " + newIgnoreList.size() + " features to ignore from this batch.");

        finalEncoder.ignoreList = combine_ignore_lists(oldIgnoreList, newIgnoreList, finalEncoder);
        System.out.println("Final combined ignore list size: " + finalEncoder.ignoreList.size());

        System.out.println("Compressing all states...");
        for (LabeledState ls : allStates) {
            ls.compress(finalEncoder);
        }

        persistLabeledStates(allStates, outputFilename);
        persistData(finalEncoder);
        System.out.println("Successfully saved data to " + outputFilename);
    }

    private GameResult runSingleGame() throws GameException, FileNotFoundException {
        // Use a thread-safe random number generator for the seed.
        long gameSeed = ThreadLocalRandom.current().nextLong();
        RandomUtil.setSeed(gameSeed);

        // All game objects are local to this thread to prevent race conditions.
        Game game = new TwoPlayerDuel(MultiplayerAttackOption.LEFT, RangeOfInfluence.ONE, MulliganType.GAME_DEFAULT.getMulligan(0), 60, 20, 7);
        TestPlayer playerA = createPlayer(game, "PlayerA", DECK_A);
        TestPlayer playerB = createPlayer(game, "PlayerB", DECK_B);

        StateEncoder threadEncoder = new StateEncoder();
        try {
            threadEncoder.loadMapping(MAPPING_FILE);
            ActionEncoder.actionMap = (Map<String, Integer>) loadObject(ACTIONS_FILE);
            ActionEncoder.indexCount = ActionEncoder.actionMap.size();
        } catch (IOException | ClassNotFoundException e) {
            throw new RuntimeException("Worker thread failed to load persistent mappings.", e);
        }

        configurePlayer(playerA, threadEncoder, playerB.getId());
        configurePlayer(playerB, threadEncoder, playerA.getId());

        // *** CRITICAL FIX ***
        // Based on CardTestPlayerAPIImpl.java, this is the correct thread-safe
        // way to configure and run a game simulation.
        GameOptions options = new GameOptions();
        options.testMode = true;
        options.stopOnTurn = MAX_GAME_TURNS;
        options.stopAtStep = PhaseStep.END_TURN;
        game.setGameOptions(options);

        // Start the game simulation. This is a blocking call that will run the game to completion.
        game.start(playerA.getId());

        // The rest of the logic is safe as it uses the local player objects.
        boolean playerAWon = playerA.hasWon();
        return new GameResult(generateLabeledStatesForGame(threadEncoder, playerAWon), playerAWon);
    }

    private void configurePlayer(TestPlayer player, StateEncoder encoder, UUID opponentId) {
        if (player.getComputerPlayer() instanceof ComputerPlayerMCTS2) {
            ((ComputerPlayerMCTS2) player.getComputerPlayer()).setEncoder(encoder);
            ((ComputerPlayerMCTS2) player.getComputerPlayer()).initNN(MCTS_MODEL_PATH);
        } else if (player.getComputerPlayer() instanceof ComputerPlayer8) {
            ((ComputerPlayer8) player.getComputerPlayer()).setEncoder(encoder);
        }
        encoder.setAgent(player.getId());
        encoder.setOpponent(opponentId);
    }

    private List<LabeledState> generateLabeledStatesForGame(StateEncoder encoder, boolean didPlayerAWin) {
        List<LabeledState> results = new ArrayList<>();
        int N = encoder.macroStateVectors.size();
        double gamma = 0.99;
        double lambda = 0.5;

        for (int i = 0; i < N; i++) {
            Set<Integer> state = encoder.macroStateVectors.get(i);
            double[] action = ActionEncoder.actionVectors.get(i);
            double normScore = encoder.stateScores.get(i);
            double terminal = didPlayerAWin ? +1.0 : -1.0;
            double discount = Math.pow(gamma, N - i - 1);
            double blended = lambda * normScore + (1.0 - lambda) * terminal * discount;
            results.add(new LabeledState(state, action, blended));
        }
        return results;
    }

    //region Helper Methods
    public Set<Integer> combine_ignore_lists(Set<Integer> oldList, Set<Integer> newList, StateEncoder encoder) {
        Set<Integer> updatedIgnoreList = new HashSet<>();
        int boundaryForOldFeatures = encoder.initialRawSize;

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

    public void persistData(StateEncoder encoder) {
        try {
            encoder.persistMapping(MAPPING_FILE);
            System.out.printf("Persisted feature mapping (and ignore list) to %s%n", MAPPING_FILE);
            saveObject(new HashMap<>(ActionEncoder.actionMap), ACTIONS_FILE);
            System.out.printf("Persisted action mapping to %s%n", ACTIONS_FILE);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void persistLabeledStates(List<LabeledState> states, String filename) {
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(filename)))) {
            out.writeInt(states.size());
            out.writeInt(StateEncoder.indexCount);
            out.writeInt(128); // Assuming policy vector size is constant

            for (LabeledState ls : states) {
                ls.persist(out);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void saveObject(Object obj, String fileName) throws IOException {
        try (ObjectOutputStream out = new ObjectOutputStream(new FileOutputStream(fileName))) {
            out.writeObject(obj);
        }
    }

    private static Object loadObject(String fileName) throws IOException, ClassNotFoundException {
        try (ObjectInputStream in = new ObjectInputStream(new FileInputStream(fileName))) {
            return in.readObject();
        }
    }
    //endregion
    @Override
    public List<String> getFullSimulatedPlayers() {
        return Arrays.asList("PlayerA", "PlayerB");
    }

    @Override
    protected TestPlayer createPlayer(Game game, String name, String deckName) throws GameException {
        TestPlayer player = createNewPlayer(name, game.getRangeOfInfluence());
        player.setTestMode(true);

        logger.debug("Loading deck...");
        DeckCardLists list;
        if (loadedDecks.containsKey(deckName)) {
            list = loadedDecks.get(deckName);
        } else {
            list = DeckImporter.importDeckFromFile(deckName, true);
            loadedDecks.put(deckName, list);
        }
        Deck deck = Deck.load(list, false, false, loadedCardInfo);
        logger.debug("Done!");
        if (deck.getMaindeckCards().size() < 40) {
            throw new IllegalArgumentException("Couldn't load deck, deck size=" + deck.getMaindeckCards().size());
        }

        game.loadCards(deck.getCards(), player.getId());
        game.loadCards(deck.getSideboard(), player.getId());
        game.addPlayer(player, deck);
        //currentMatch.addPlayer(player, deck); // fake match

        return player;
    }

    // This is the correct override to use for creating players within our self-contained games.
    @Override
    protected TestPlayer createPlayer(String name, RangeOfInfluence rangeOfInfluence) {
        TestPlayer testPlayer;
        if (name.equals("PlayerA")) {
            TestComputerPlayerMonteCarlo2 mcts2 = new TestComputerPlayerMonteCarlo2(name, RangeOfInfluence.ONE, getSkillLevel());
            testPlayer = new TestPlayer(mcts2);
        } else {
            TestComputerPlayer8 t8 = new TestComputerPlayer8(name, RangeOfInfluence.ONE, getSkillLevel());
            testPlayer = new TestPlayer(t8);
        }
        testPlayer.setAIPlayer(true);
        return testPlayer;
    }
    //endregion
}