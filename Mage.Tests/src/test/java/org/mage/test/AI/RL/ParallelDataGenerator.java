package org.mage.test.AI.RL;

import ai.onnxruntime.OrtException;
import mage.abilities.Ability;
import mage.abilities.ActivatedAbility;
import mage.cards.Card;
import mage.cards.decks.Deck;
import mage.cards.decks.DeckCardLists;
import mage.cards.decks.importer.DeckImporter;
import mage.constants.*;
import mage.game.*;
import mage.game.match.Match;
import mage.game.match.MatchOptions;
import mage.game.mulligan.MulliganType;
import mage.player.ai.*;
import mage.util.RandomUtil;
import org.junit.Test;
import org.mage.test.player.TestComputerPlayer8;
import org.mage.test.player.TestPlayer;
import org.mage.test.serverside.base.CardTestPlayerBaseAI;
import org.roaringbitmap.RoaringBitmap;
import org.roaringbitmap.buffer.ImmutableRoaringBitmap;

import java.io.*;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.WRITE;


/**
 * A dedicated, parallelized test class for generating training and testing data sets.
 * This version is fully compatible with Java 1.8 and uses the correct, thread-safe
 * game execution logic as defined by the test framework.
 */
public class ParallelDataGenerator extends CardTestPlayerBaseAI {

    //region Configuration
    // ============================ DATA GENERATION SETTINGS ============================
    public static int NUM_GAMES_TO_SIMULATE_TRAIN = 250;
    public static int NUM_GAMES_TO_SIMULATE_TEST = 0;
    private static final int MAX_GAME_TURNS = 50;
    private static final int MAX_CONCURRENT_GAMES = 6;
    // =============================== DECK AND AI SETTINGS ===============================
    private static final String DECK_A = "MTGA_MonoU";
    private static final String DECK_B= "MTGA_MonoR";
    private static final boolean DONT_USE_NOISE = true;
    private static final boolean DONT_USE_POLICY = true;
    private static double DISCOUNT_FACTOR = 0.99;
    // ================================== FILE PATHS ==================================
    private static final String DECK_A_PATH = "decks/" + DECK_A + ".dck";
    private static final String DECK_B_PATH = "decks/" + DECK_B + ".dck";
    private static final String IGNORE_PATH = "ignores/" + DECK_A + "/ignore3.roar";//was 14
    private static final String SEEN_FEATURES_PATH = "seenFeatures.roar";
    private static final String ACTIONS_FILE = "actionMappings/" + DECK_A + "/actions_mapping.ser";
    private static final String MICRO_ACTIONS_FILE = "micro_actions_mapping.ser";
    public static String TRAIN_OUT_FILE = "training.bin";
    public static String TEST_OUT_FILE = "testing.bin";
    // ================================== GLOBAL FIELDS ==================================
    //private Features finalFeatures;
    private RoaringBitmap seenFeatures = new RoaringBitmap();
    private int initialRawSize;
    private int previousRawSize;
    private final AtomicInteger gameCount = new AtomicInteger(0);
    private final AtomicInteger winCount = new AtomicInteger(0);
    //private NeuralNetEvaluator neuralNetEvaluator;
    private RemoteModelEvaluator remoteModelEvaluator;
    //end

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

    /**
     * note is only priority actions
     * @param deckName
     * @throws GameException
     */
    public void getAllActionsFromDeckList(String deckName) throws GameException {
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
        List<Card> sortedCards = new ArrayList<>(deck.getCards());
        sortedCards.sort(Comparator.comparing(Card::getName));
        for(Card card : sortedCards) {
            for(Ability aa : card.getAbilities()) {
                ActionEncoder.getAction(aa);
            }
        }

        //game.loadCards(deck.getCards(), player.getId());
    }

    /**
     * run once
     */
    private void loadAllFiles() {
        try {
            // Load mappings so the encoder works correctly
            //finalFeatures = Features.loadMapping(MAPPING_FILE);
            ActionEncoder.actionMap = (Map<String, Integer>) loadObject(ACTIONS_FILE);
            ActionEncoder.microActionMap = (Map<String, Integer>) loadObject(MICRO_ACTIONS_FILE);
            ActionEncoder.indexCount = ActionEncoder.actionMap.size();
            ActionEncoder.microIndexCount = ActionEncoder.microActionMap.size();
        } catch (IOException | ClassNotFoundException e) {
            logger.error("Warning: Failed to load persistent mappings");
            ActionEncoder.actionMap = new HashMap<>(); // Ensure it's not null
        }
        try (FileChannel ch = FileChannel.open(Paths.get(IGNORE_PATH), READ)) {
            MappedByteBuffer mbb = ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size());
            StateEncoder.globalIgnore = new ImmutableRoaringBitmap(mbb);
            //logger.info("global ignore list: " + StateEncoder.globalIgnore);
        } catch (IOException e) {
            logger.warn("external ignore list not found");
        }
        try (FileChannel ch = FileChannel.open(Paths.get(SEEN_FEATURES_PATH), READ)) {
            MappedByteBuffer mbb = ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size());
            ImmutableRoaringBitmap imm = new ImmutableRoaringBitmap(mbb);
            seenFeatures = imm.toRoaringBitmap();
            logger.info("global seen features list: " + seenFeatures);
        } catch (IOException e) {
            logger.warn("external seen feature list not found");
        }
        try {
            remoteModelEvaluator = new RemoteModelEvaluator();
        } catch (Exception e) {
            logger.warn("Failed to establish connection to network model");
            throw new RuntimeException(e);
        }
        initialRawSize = seenFeatures.getCardinality();
    }
    @Override
    protected Game createNewGameAndPlayers() throws GameException, FileNotFoundException {
        Game game = new TwoPlayerDuel(MultiplayerAttackOption.LEFT, RangeOfInfluence.ONE, MulliganType.GAME_DEFAULT.getMulligan(0), 60, 20, 7);
        playerA = createPlayer(game, "PlayerA", DECK_A_PATH);
        playerB = createPlayer(game, "PlayerB", DECK_B_PATH);
        return game;
    }
    @Test
    public void train_4_250() {
        NUM_GAMES_TO_SIMULATE_TRAIN=250;
        NUM_GAMES_TO_SIMULATE_TEST=0;
        for(int i = 0; i<4; i++) {
            TRAIN_OUT_FILE = ("training"+i+".bin");
            generateTrainingAndTestingData();
        }
    }
    /**
     * New test function to run a single game for debugging purposes without saving any data.
     */
    @Test
    public void test_single_game() {
        logger.info("\n=========================================");
        logger.info("       RUNNING SINGLE DEBUG GAME         ");
        logger.info("=========================================");
        // --- Setup (required for the game to run) ---
        //finalFeatures = new Features(); // Start with fresh features for this run
        loadAllFiles();
        ComputerPlayerMCTS2.SHOW_THREAD_INFO = true;
        ComputerPlayerMCTS.NO_NOISE = DONT_USE_NOISE;
        ComputerPlayerMCTS.NO_POLICY = DONT_USE_POLICY;
        ComputerPlayer.PRINT_DECISION_FALLBACKS = false;
        int maxTurn = 50;
        //ComputerPlayer.PRINT_DECISION_FALLBACKS = true;
        //MCTSPlayer.PRINT_CHOOSE_DIALOGUES = false;
        //Features.printOldFeatures = false;
        // --- End Setup ---
        long seed = System.nanoTime();
        //seed = 751314143315900L; //opponent turn priority order bug
        //seed = -4411935635951101274L; //blocking bug
        //seed = 5401683921170803014L; //unable to find matching
        //seed = 1405302846091300L; //chooseTarget() and chooseUse() are used


        StateEncoder threadEncoder = new StateEncoder();
        threadEncoder.seenFeatures = seenFeatures;

        // Use a thread-safe random number generator for the seed.
        logger.info("Using seed: " + seed);
        RandomUtil.setSeed(seed);


        configurePlayer(playerA, threadEncoder);
        configurePlayer(playerB, threadEncoder);
        threadEncoder.setAgent(playerA.getId());
        threadEncoder.setOpponent(playerB.getId());


        setStrictChooseMode(true);
        setStopAt(maxTurn, PhaseStep.END_TURN);
        //GameImpl.drawHand = false;
        //addCard(Zone.BATTLEFIELD, playerA, "Island", 2);
        //addCard(Zone.BATTLEFIELD, playerA, "Malcolm, Alluring Scoundrel", 1);
        //addCard(Zone.HAND, playerA, "Combat Research", 7);
        execute();
        saveRoaring(threadEncoder.seenFeatures, SEEN_FEATURES_PATH);

        logger.info("=========================================");
    }

    @Test
    public void generateTrainingAndTestingData() {

        //load original mapping as starting point
        //finalFeatures = new Features();
        initialRawSize = 0;
        loadAllFiles();
        ComputerPlayerMCTS2.SHOW_THREAD_INFO = true;
        ComputerPlayerMCTS.NO_NOISE = DONT_USE_NOISE;
        ComputerPlayerMCTS.NO_POLICY = DONT_USE_POLICY;
        //Features.printNewFeatures = false;

        logger.info("\n=========================================");
        logger.info("    STARTING TESTING DATA GENERATION     ");
        logger.info("=========================================");

        List<LabeledState> testingStates = runSimulations(NUM_GAMES_TO_SIMULATE_TEST);

        logger.info("=========================================");
        logger.info("   STARTING TRAINING DATA GENERATION     ");
        logger.info("=========================================");


        List<LabeledState> trainingStates = runSimulations(NUM_GAMES_TO_SIMULATE_TRAIN);

        //save both data files at once
        processAndSaveData(trainingStates, testingStates);

        //logger.info("\nData generation complete.");
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
        int successfulGames = 0;
        int failedGames = 0;
        try {
            List<Future<GameResult>> futures = executor.invokeAll(tasks);
            executor.shutdown();

            for (Future<GameResult> future : futures) {
                try {
                    // future.get() will block until the task is complete.
                    GameResult result = future.get();
                    allLabeledStates.addAll(result.getStates());
                    if (result.didPlayerAWin()) {
                        wins++;
                    }
                    successfulGames++;

                } catch (ExecutionException e) {
                    failedGames++;
                    System.err.println("A game simulation failed and its result will be ignored. Cause: " + e.getCause());
                    e.getCause().printStackTrace();
                }
                // The loop continues to the next future, ignoring the failed one.
            }
        } catch (InterruptedException e) {
            System.err.println("Main simulation thread was interrupted. Shutting down.");
            e.printStackTrace();
            Thread.currentThread().interrupt();
        }
        System.out.printf("\n--- Simulation Summary ---\n");
        System.out.printf("Total requested: %d games\n", numGames);
        System.out.printf("Successful: %d\n", successfulGames);
        System.out.printf("Failed: %d\n", failedGames);
        System.out.printf("Player A win rate: %.2f%% (%d/%d)\n", (100.0 * wins / numGames), wins, numGames);
        return allLabeledStates;
    }
    private void processAndSaveData(List<LabeledState> trainingStates, List<LabeledState> testingStates) {
        List<LabeledState> allStates = new ArrayList<>(trainingStates);
        allStates.addAll(testingStates);
        if (allStates.isEmpty()) {
            logger.info("No states were generated, skipping file save for " + ParallelDataGenerator.TRAIN_OUT_FILE);
            return;
        }
        //print_labeled_states(trainingStates);
        logger.info("Processing " + allStates.size() + " states.");
        logger.info("Initial feature count: " + initialRawSize);
        //Set<Integer> oldIgnoreList = new HashSet<>(finalFeatures.ignoreList);
        //Set<Integer> newIgnoreListA = new HashSet<>(FeatureMerger.computeIgnoreListFromLS(allStates, 0, initialRawSize));
        //Set<Integer> newIgnoreListB = new HashSet<>(FeatureMerger.computeIgnoreListFromLS(allStates, initialRawSize, finalFeatures.localIndexCount.get()));
        //logger.info("Computed " + newIgnoreListB.size() + " features to ignore from this batch.");
        //intersect
        //newIgnoreListA.retainAll(oldIgnoreList);
        //logger.info("Computed " + (oldIgnoreList.size()-newIgnoreListA.size()) + " features to unignore");
        //union
        //newIgnoreListA.addAll(newIgnoreListB);
        //finalFeatures.ignoreList = newIgnoreListA;
        //logger.info("Final combined ignore list size: " + finalFeatures.ignoreList.size());

        //logger.info("Compressing all states...");
        //for (LabeledState ls : allStates) {
        //ls.compress(finalFeatures.ignoreList);
        //}
        logger.info("Final unique feature count from dataset: " + LabeledState.getUniqueFeaturesFromBatch(allStates));
        logger.info("Global unique feature count: " + seenFeatures.getCardinality());
        logger.info("Features added: " + (seenFeatures.getCardinality() - initialRawSize));
        //logger.info("Final Compressed Feature Vector Size: " + (finalFeatures.localIndexCount.get() - finalFeatures.ignoreList.size()));
        persistLabeledStates(trainingStates, ParallelDataGenerator.TRAIN_OUT_FILE);
        persistLabeledStates(testingStates, ParallelDataGenerator.TEST_OUT_FILE);
        persistData();
        logger.info("Successfully saved data to " + ParallelDataGenerator.TRAIN_OUT_FILE + " and " + ParallelDataGenerator.TEST_OUT_FILE);
    }
    private GameResult runSingleGame() throws ExecutionException {
        long seed = ThreadLocalRandom.current().nextLong();
        return runSingleGame(seed);
    }
    private GameResult runSingleGame(long gameSeed) throws ExecutionException {
        try {

            Game game;
            StateEncoder threadEncoder = new StateEncoder();

            // Use a thread-safe random number generator for the seed.
            logger.info("Using seed: " + gameSeed);
            RandomUtil.setSeed(gameSeed);



            // All game objects are local to this thread to prevent race conditions.
            MatchOptions matchOptions = new MatchOptions("test match", "test game type", true, 4);
            Match localMatch = new FreeForAllMatch(matchOptions);
            game = new TwoPlayerDuel(MultiplayerAttackOption.LEFT, RangeOfInfluence.ONE, MulliganType.GAME_DEFAULT.getMulligan(0), 60, 20, 7);
            TestPlayer playerA = createLocalPlayer(game, "PlayerA", DECK_A_PATH, localMatch);
            TestPlayer playerB = createLocalPlayer(game, "PlayerB", DECK_B_PATH, localMatch);


            //threadEncoder.loadMapping(finalFeatures);
            threadEncoder.seenFeatures = seenFeatures.clone();


            configurePlayer(playerA, threadEncoder);
            configurePlayer(playerB, threadEncoder);
            threadEncoder.setAgent(playerA.getId());
            threadEncoder.setOpponent(playerB.getId());

            // Based on CardTestPlayerAPIImpl.java, this is the correct thread-safe
            // way to configure and run a game simulation.
            GameOptions options = new GameOptions();
            options.testMode = true;
            options.stopOnTurn = MAX_GAME_TURNS;
            options.stopAtStep = PhaseStep.END_TURN;
            game.setGameOptions(options);


            // Start the game simulation. This is a blocking call that will run the game to completion.
            game.start(playerA.getId());

            boolean playerAWon = playerA.hasWon();
            //merge to the final features
            synchronized (seenFeatures) {
                seenFeatures.or(threadEncoder.seenFeatures);
            }
            //finalFeatures.merge(threadEncoder.getFeatures(), newStateVectors);
            //update generated dataset with remapped one
            //threadEncoder.stateVectors = newStateVectors;
            if(playerA.hasWon()) winCount.incrementAndGet();
            logger.info("Game #" + gameCount.incrementAndGet() + " completed successfully");
            logger.info("Current WR: " + winCount.get()*1.0/gameCount.get());
            return new GameResult(generateLabeledStatesForGame(threadEncoder, playerAWon), playerAWon);
        } catch (Exception e) {
            System.err.println("Caught an internal AI/Game exception in a worker thread. Ignoring this game. Cause: " + e.getMessage());
            throw new ExecutionException("Worker thread failed - ignoring", e);
        }
    }

    private void configurePlayer(TestPlayer player, StateEncoder encoder) {
        if (player.getComputerPlayer() instanceof ComputerPlayerMCTS2) {
            ((ComputerPlayerMCTS2) player.getComputerPlayer()).setEncoder(encoder);
            //((ComputerPlayerMCTS2) player.getComputerPlayer()).initNN(MCTS_MODEL_PATH);
            ((ComputerPlayerMCTS2) player.getComputerPlayer()).nn = remoteModelEvaluator;//shared reference
        } else if (player.getComputerPlayer() instanceof ComputerPlayer8) {
            ((ComputerPlayer8) player.getComputerPlayer()).setEncoder(encoder);
        } else if (player.getComputerPlayer() instanceof ComputerPlayerPureMCTS) {
            ((ComputerPlayerPureMCTS) player.getComputerPlayer()).setEncoder(encoder);
        }
    }

    private List<LabeledState> generateLabeledStatesForGame(StateEncoder encoder, boolean didPlayerAWin) {
        List<LabeledState> results = new ArrayList<>();
        int N = encoder.stateVectors.size();
        double lambda = 0.5;

        for (int i = 0; i < N; i++) {
            Set<Integer> state = encoder.stateVectors.get(i);
            double[] action = encoder.actionVectors.get(i);
            double normScore = encoder.stateScores.get(i);
            double terminal = didPlayerAWin ? +1.0 : -1.0;
            double discount = Math.pow(DISCOUNT_FACTOR, (N - i - 1));
            double blended = lambda * normScore + (1.0 - lambda) * terminal * discount;
            results.add(new LabeledState(state, action, blended));
        }
        return results;

    }

    //region Helper Methods
    public Set<Integer> combine_ignore_lists(Set<Integer> oldList, Set<Integer> newList) {
        Set<Integer> updatedIgnoreList = new HashSet<>();
        int boundaryForOldFeatures = previousRawSize;

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
    public void persistData() {
        try {
            saveObject(new HashMap<>(ActionEncoder.actionMap), ACTIONS_FILE);
            logger.info("Persisted action mapping to: " + ACTIONS_FILE);
            saveObject(new HashMap<>(ActionEncoder.microActionMap), MICRO_ACTIONS_FILE);
            logger.info("Persisted micro action mapping to: " +  MICRO_ACTIONS_FILE);
            saveRoaring(seenFeatures, SEEN_FEATURES_PATH);
            logger.info("Persisted seen feature set to: "  + SEEN_FEATURES_PATH);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void persistLabeledStates(List<LabeledState> states, String filename) {
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(filename)))) {
            out.writeInt(states.size());
            out.writeInt(2000000);//out.writeInt(finalFeatures.localIndexCount.get());
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
    void saveRoaring(RoaringBitmap rb, String filePath) {
        try (DataOutputStream out = new DataOutputStream(new FileOutputStream(filePath))) {
            rb.serialize(out);
        } catch (IOException e) {
            e.printStackTrace();
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

    protected TestPlayer createLocalPlayer(Game game, String name, String deckName, Match match) throws GameException {
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
        match.addPlayer(player, deck); // fake match

        return player;
    }

    // This is the correct override to use for creating players within our self-contained games.
    @Override
    protected TestPlayer createPlayer(String name, RangeOfInfluence rangeOfInfluence) {
        DISCOUNT_FACTOR = 0.95; //less states means higher discount
        if (name.equals("PlayerA")) {
            TestComputerPlayer8 t8 = new TestComputerPlayer8(name, RangeOfInfluence.ONE, getSkillLevel());
            TestPlayer testPlayer = new TestPlayer(t8);
            testPlayer.setAIPlayer(true);
            return testPlayer;
        } else {
            TestComputerPlayer8 t8 = new TestComputerPlayer8(name, RangeOfInfluence.ONE, getSkillLevel());
            TestPlayer testPlayer = new TestPlayer(t8);
            testPlayer.setAIPlayer(true);
            return testPlayer;
        }
    }
    //endregion
}