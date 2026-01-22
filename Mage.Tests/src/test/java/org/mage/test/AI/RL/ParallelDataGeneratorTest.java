package org.mage.test.AI.RL;

import mage.cards.decks.Deck;
import mage.cards.decks.DeckCardLists;
import mage.cards.decks.importer.DeckImporter;
import mage.cards.repository.CardInfo;
import mage.constants.*;
import mage.game.*;
import mage.game.match.Match;
import mage.game.match.MatchOptions;
import mage.game.mulligan.MulliganType;
import mage.player.ai.*;
import mage.players.Player;
import mage.util.RandomUtil;
import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.junit.Test;
import org.mage.test.player.TestComputerPlayer8;
import org.roaringbitmap.RoaringBitmap;
import org.roaringbitmap.buffer.ImmutableRoaringBitmap;

import java.io.*;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static java.lang.Math.abs;
import static java.nio.file.StandardOpenOption.READ;


/**
 * Base class for all RL data generators. see <a href="https://github.com/WillWroble/MageZero">MageZero repo</a>
 * for how to use
 *
 */
public class ParallelDataGeneratorTest {

    // ============================ DATA GENERATION SETTINGS ============================
    protected static int NUM_GAMES_TO_SIMULATE = 100;
    protected static int MAX_GAME_TURNS = 50;
    protected static int MAX_CONCURRENT_GAMES = 4;
    // =============================== AI SETTINGS ===============================
    protected static boolean DONT_USE_NOISE = true;
    protected static boolean DONT_USE_POLICY = false;
    protected static boolean DONT_USE_POLICY_TARGET = true;
    protected static boolean DONT_USE_POLICY_USE = false;
    //higher means less bootstrapping
    protected static double TD_DISCOUNT = 0.95;
    /**MCTS settings in ComputerPlayerMCTS.java*/
    // =============================== MATCH SETTINGS ===============================
    protected static boolean ALWAYS_GO_FIRST = false;
    protected static boolean ALLOW_MULLIGANS_A = true;
    protected static boolean ALLOW_MULLIGANS_B = false;
    protected static String DECK_A = "UWTempo";
    protected static String DECK_B = "MTGA_MonoR";
    protected static String MODEL_URL_A = "http://127.0.0.1:50052";
    protected static String MODEL_URL_B = "http://127.0.0.1:50053";
    // ================================== FILE PATHS ==================================
    protected static String DECK_A_PATH = "decks/" + DECK_A + ".dck";
    protected static String DECK_B_PATH = "decks/" + DECK_B + ".dck";
    protected static String IGNORE_PATH = "ignores/" + DECK_A + "/ignore3.roar";
    protected static String SEEN_INDICES_PATH = "seenIndices.roar";
    protected static String SEEN_FEATURES_PATH = "seenFeatures.ser";
    protected static String DATA_OUT_FILE_A = "trainingA.hdf5";
    protected static String DATA_OUT_FILE_B = "trainingB.hdf5";
    protected static String FEATURE_TABLE_OUT = "FeatureTable.txt";

    // ================================== GLOBAL FIELDS ==================================
    private RoaringBitmap seenIndices = new RoaringBitmap();
    private FeatureMap seenFeatures = new FeatureMap();
    private int initialRawSize;
    public final AtomicInteger gameCount = new AtomicInteger(0);
    public final AtomicInteger winCount = new AtomicInteger(0);
    protected RemoteModelEvaluator remoteModelEvaluatorA = null;
    protected RemoteModelEvaluator remoteModelEvaluatorB = null;
    private final BlockingQueue<GameResult> LSQueue = new ArrayBlockingQueue<>(32);
    private final AtomicBoolean stop = new AtomicBoolean(false);

    protected static Map<String, DeckCardLists> loadedDecks = new HashMap<>(); // deck's cache
    protected static Map<String, CardInfo> loadedCardInfo = new HashMap<>(); // db card's cache
    protected static boolean isRoundRobin = false;


    protected static Logger logger = Logger.getLogger(ParallelDataGeneratorTest.class);



    private static class GameResult {
        private final List<LabeledState> statesA;
        private final List<LabeledState> statesB;
        private final boolean didPlayerAWin;
        public GameResult(List<LabeledState> statesA, List<LabeledState> statesB, boolean didPlayerAWin) {
            this.statesA = statesA; this.statesB = statesB; this.didPlayerAWin = didPlayerAWin;
        }
        public List<LabeledState> getStatesA() { return statesA; }
        public List<LabeledState> getStatesB() { return statesB; }
        public boolean didPlayerAWin() { return didPlayerAWin; }
    }
    /**
     * run once
     */
    protected void loadAllFiles() {
        //reset writer thread
        stop.set(false);

        //reset counts
        winCount.set(0);
        gameCount.set(0);

        //update file paths
        DECK_A_PATH = "decks/" + DECK_A + ".dck";
        DECK_B_PATH = "decks/" + DECK_B + ".dck";
        IGNORE_PATH = "ignores/" + DECK_A + "/ignore3.roar";

        try (FileChannel ch = FileChannel.open(Paths.get(IGNORE_PATH), READ)) {
            MappedByteBuffer mbb = ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size());
            StateEncoder.globalIgnore = new ImmutableRoaringBitmap(mbb);
            //logger.info("global ignore list: " + StateEncoder.globalIgnore);
        } catch (IOException e) {
            logger.warn("external ignore list not found");
        }
        try (FileChannel ch = FileChannel.open(Paths.get(SEEN_INDICES_PATH), READ)) {
            MappedByteBuffer mbb = ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size());
            ImmutableRoaringBitmap imm = new ImmutableRoaringBitmap(mbb);
            seenIndices = imm.toRoaringBitmap();
            logger.info("global seen features list size: " + seenIndices.getCardinality());
        } catch (IOException e) {
            logger.warn("external seen index list not found");
        }
        try {
            remoteModelEvaluatorA = new RemoteModelEvaluator(MODEL_URL_A);
        } catch (Exception e) {
            logger.warn("Failed to establish connection to network model; falling back to offline mode");
            remoteModelEvaluatorA = null;
            TD_DISCOUNT = 0.95;
        }
        initialRawSize = seenIndices.getCardinality();
    }
    @Test
    public void print_known_feature_map() {
        try {
            FeatureMap fm = FeatureMap.loadFromFile(SEEN_FEATURES_PATH);
            fm.printFeatureTable(FEATURE_TABLE_OUT);
        } catch (IOException | ClassNotFoundException e) {
            logger.warn("couldn't load feature table");
            e.printStackTrace();
        }
    }
    @Test
    public void test_single_game() {
        test_single_game(System.nanoTime());
    }
    /**
     * test function to run a single game for debugging purposes without saving any data.
     */
    public void test_single_game(long seed) {
        logger.info("\n=========================================");
        logger.info("       RUNNING SINGLE DEBUG GAME         ");
        logger.info("=========================================");
        loadAllFiles();
        ComputerPlayerMCTS2.SHOW_THREAD_INFO = true;


        // Use a thread-safe random number generator for the seed.
        logger.info("Using seed: " + seed);
        try {
            runSingleGame(seed);
        } catch (ExecutionException e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }


    }
    @Test
    public void generateData() {


        initialRawSize = 0;
        loadAllFiles();
        ComputerPlayerMCTS2.SHOW_THREAD_INFO = true;
        LabeledStateWriter fwA;
        LabeledStateWriter fwB;
        Thread writer;
        try {
            fwA = new LabeledStateWriter(DATA_OUT_FILE_A);
            fwB = new LabeledStateWriter(DATA_OUT_FILE_B);
            writer = getThread(fwA, fwB);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }



        logger.info("=========================================");
        logger.info("   STARTING DATA GENERATION     ");
        logger.info("=========================================");


        runSimulations(NUM_GAMES_TO_SIMULATE);

        //end writer thread
        stop.set(true);
        try {
            writer.join();
        } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }

        saveRoaring(seenIndices, SEEN_INDICES_PATH);
        saveFeatureMap(seenFeatures, SEEN_FEATURES_PATH);

        logger.info("Processing " + fwA.batchStates + " states.");
        logger.info("Initial feature count: " + initialRawSize);
        logger.info("Final unique feature count from dataset: " + fwA.batchFeatures.size());
        logger.info("Global unique feature count: " + seenIndices.getCardinality());
        logger.info("Features added: " + (seenIndices.getCardinality() - initialRawSize));
    }

    @NotNull
    private Thread getThread(LabeledStateWriter fwA, LabeledStateWriter fwB) {
        Thread writer = new Thread(() -> {
            try {
                do {
                    GameResult batch = LSQueue.poll(200, TimeUnit.MILLISECONDS);
                    if (batch != null) {
                        for (LabeledState s : batch.getStatesA()) fwA.writeRecord(s);
                        for (LabeledState s : batch.getStatesB()) fwB.writeRecord(s);
                        fwA.flush();
                        fwB.flush();
                    }
                } while (!stop.get() || !LSQueue.isEmpty());
            } catch (Exception e) {
                e.printStackTrace();
                Thread.currentThread().interrupt();
            } finally {
                try { fwA.close(); } catch (Exception ignore) {}
                try { fwB.close(); } catch (Exception ignore) {}
            }
        }, "lz-writer");
        writer.start();
        return writer;
    }


    private void runSimulations(int numGames) {
        int availableCores = Runtime.getRuntime().availableProcessors();
        int poolSize = MAX_CONCURRENT_GAMES;
        logger.info(String.format("Simulating %d games. Using thread pool of size %d on %d available cores.", numGames, poolSize, availableCores));

        ExecutorService executor = Executors.newFixedThreadPool(poolSize);
        List<Callable<GameResult>> tasks = new ArrayList<>();
        for (int i = 0; i < numGames; i++) {
            tasks.add(new Callable<GameResult>() {
                @Override
                public GameResult call() throws Exception {
                    GameResult out = runSingleGame();
                    LSQueue.put(out);
                    return out;
                }
            });
        }
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
                    if (result.didPlayerAWin()) {
                        wins++;
                    }
                    successfulGames++;

                } catch (ExecutionException e) {
                    failedGames++;
                    logger.error("A game simulation failed and its result will be ignored. Cause: " + e.getCause());
                    e.getCause().printStackTrace();
                }
                // The loop continues to the next future, ignoring the failed one.
            }
        } catch (InterruptedException e) {
            logger.error("Main simulation thread was interrupted. Shutting down.");
            e.printStackTrace();
            Thread.currentThread().interrupt();
        }
        logger.info("--- Simulation Summary ---");
        logger.info(String.format("Total requested: %d games", numGames));
        logger.info(String.format("Successful: %d", successfulGames));
        logger.info(String.format("Failed: %d", failedGames));
        logger.info(String.format("Player A win rate: %.2f%% (%d/%d)", (100.0 * wins / numGames), wins, numGames));
    }
    private GameResult runSingleGame() throws ExecutionException {
        long seed = ThreadLocalRandom.current().nextLong();
        return runSingleGame(seed);
    }
    private GameResult runSingleGame(long gameSeed) throws ExecutionException {
        try {

            Game game;
            StateEncoder threadEncoderA = new StateEncoder();
            StateEncoder threadEncoderB = new StateEncoder();

            // Use a thread-safe random number generator for the seed.
            logger.info("Using seed: " + gameSeed);
            RandomUtil.setSeed(gameSeed);



            // All game objects are local to this thread to prevent race conditions.
            MatchOptions matchOptions = new MatchOptions("test match", "test game type", false);
            Match localMatch = new TwoPlayerMatch(matchOptions);
            game = new TwoPlayerDuel(MultiplayerAttackOption.LEFT, RangeOfInfluence.ONE, MulliganType.GAME_DEFAULT.getMulligan(0), 60, 20, 7);
            Player playerA = createLocalPlayer(game, "PlayerA", DECK_A_PATH, localMatch);
            Player playerB = createLocalPlayer(game, "PlayerB", DECK_B_PATH, localMatch);

            threadEncoderA.seenIndices = seenIndices.clone();
            threadEncoderB.seenIndices = seenIndices.clone();


            configurePlayer(playerA, threadEncoderA, threadEncoderB);
            configurePlayer(playerB, threadEncoderB, threadEncoderA);
            threadEncoderA.setAgent(playerA.getId());
            threadEncoderA.setOpponent(playerB.getId());
            threadEncoderB.setAgent(playerB.getId());
            threadEncoderB.setOpponent(playerA.getId());

            // Based on CardTestPlayerAPIImpl.java, this is the correct thread-safe
            // way to configure and run a game simulation.
            GameOptions options = new GameOptions();
            options.testMode = true;
            options.stopOnTurn = MAX_GAME_TURNS;
            options.stopAtStep = PhaseStep.END_TURN;
            game.setGameOptions(options);


            // Start the game simulation. This is a blocking call that will run the game to completion.
            if(ALWAYS_GO_FIRST) {
                game.start(null);
            } else {
                int dieRoll;
                if(gameCount.get()==0) {
                    dieRoll = (int)(Thread.currentThread().getId()%2);
                } else {
                    dieRoll = gameCount.get() % 2;
                }
                if(dieRoll==0) {
                    logger.info("Player A won the die roll");
                    game.setStartingPlayerId(playerA.getId());
                } else {
                    logger.info("Player B won the die roll");
                    game.setStartingPlayerId(playerB.getId());
                }
                game.start(null);
            }
            boolean playerAWon = playerA.hasWon();
            //merge to the final features
            synchronized (seenIndices) {
                seenIndices.or(threadEncoderA.seenIndices);
                seenFeatures.merge(threadEncoderA.featureMap);
            }
            if(playerA.hasWon()) winCount.incrementAndGet();
            logger.info("Game #" + gameCount.incrementAndGet() + " completed successfully");
            logger.info("Current WR: " + winCount.get()*1.0/gameCount.get());
            List<LabeledState> statesA = generateLabeledStatesForGame(threadEncoderA, playerAWon);
            List<LabeledState> statesB = generateLabeledStatesForGame(threadEncoderB,!playerAWon);
            return new GameResult(statesA, statesB, playerAWon);
        } catch (Exception e) {
            logger.error("Caught an internal AI/Game exception in a worker thread. Ignoring this game. Cause: " + e.getMessage());
            e.printStackTrace();
            throw new ExecutionException("Worker thread failed - ignoring", e);
        }
    }

    protected void configurePlayer(Player player, StateEncoder encoder, StateEncoder opponentEncoder) {
        if (player.getRealPlayer() instanceof ComputerPlayerMCTS2) {
            ComputerPlayerMCTS2 mcts2  = (ComputerPlayerMCTS2) player.getRealPlayer();
            mcts2.setStateEncoder(encoder);
            if(player.getName().equals("PlayerA")) {
                mcts2.nn = remoteModelEvaluatorA;
                mcts2.noPolicyPriority = DONT_USE_POLICY;
                mcts2.noPolicyTarget = DONT_USE_POLICY_TARGET;
                mcts2.noPolicyUse = DONT_USE_POLICY_USE;
                mcts2.noNoise = DONT_USE_NOISE;
                mcts2.noPolicyOpponent = isRoundRobin;
                mcts2.allowMulligans = ALLOW_MULLIGANS_A;
                if(remoteModelEvaluatorA == null) mcts2.offlineMode = true;
            } else {
                mcts2.nn = remoteModelEvaluatorB;
                mcts2.allowMulligans = ALLOW_MULLIGANS_B;
                if(remoteModelEvaluatorB == null) mcts2.offlineMode = true;
            }
        } else if (player.getRealPlayer() instanceof ComputerPlayer8) {
            ComputerPlayer8 cp8 = (ComputerPlayer8) player.getRealPlayer();
            cp8.setEncoder(opponentEncoder);
            cp8.allowMulligans = ALLOW_MULLIGANS_B;
        } else  {
            logger.warn("unexpected player type" + player.getRealPlayer().getClass().getName());
        }
    }
    private List<LabeledState> generateLabeledStatesForGame(StateEncoder encoder, boolean didPlayerAWin) {
        int N = encoder.labeledStates.size();

        double discountedFuture = didPlayerAWin ? 1.0 : -1.0;
        for (int i = N-1; i >= 0; i--) {
            discountedFuture = (TD_DISCOUNT * discountedFuture) + (encoder.labeledStates.get(i).stateScore*(1- TD_DISCOUNT));
            encoder.labeledStates.get(i).resultLabel = discountedFuture;
        }
        return encoder.labeledStates;

    }
    void saveFeatureMap(FeatureMap fm, String filePath) {
        FeatureMap baseMap = new FeatureMap();
        try {
            baseMap = FeatureMap.loadFromFile(filePath);
        } catch (IOException | ClassNotFoundException e) {
            logger.warn("couldn't load feature map");
        }
        try {
            baseMap.merge(fm);
            baseMap.saveToFile(filePath);
        } catch (IOException e) {
            logger.warn("couldn't save feature map");
        }
    }

    void saveRoaring(RoaringBitmap rb, String filePath) {
        try (DataOutputStream out = new DataOutputStream(new FileOutputStream(filePath))) {
            rb.serialize(out);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    public static void printMapByValue(Map<String, Integer> map) {
        if (map == null || map.isEmpty()) {
            System.out.println("(empty)");
            return;
        }

        int maxKeyLen = map.keySet().stream().mapToInt(String::length).max().orElse(0);

        map.entrySet().stream()
                .sorted(Comparator.<Map.Entry<String,Integer>>comparingInt(Map.Entry::getValue).reversed()
                        .thenComparing(Map.Entry::getKey)) // tie-breaker: key asc
                .forEach(e -> System.out.printf("%-" + maxKeyLen + "s  : %,d%n", e.getKey(), e.getValue()));
    }
    protected Player createLocalPlayer(Game game, String name, String deckName, Match match) throws GameException {
        Player player = createPlayer(name, game.getRangeOfInfluence());
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
    public void writeResults(String filePath, String results) {

        try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(
                Paths.get(filePath),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,   // create if it doesn't exist
                StandardOpenOption.APPEND))) // append if it does
        {
            out.println(results);

        } catch (IOException ex) {
            logger.error("Error while writing results: " + ex.getMessage(), ex);
        }
    }
    // This is the correct override to use for choosing our AI types.
    protected Player createPlayer(String name, RangeOfInfluence rangeOfInfluence) {
        if (name.equals("PlayerA")) {
            TestComputerPlayer8 t8 = new TestComputerPlayer8(name, RangeOfInfluence.ONE, 6);
            //Player testPlayer = new Player(t8);
            //testPlayer.setAIPlayer(true);
            return t8;
        } else {
            TestComputerPlayer8 t8 = new TestComputerPlayer8(name, RangeOfInfluence.ONE, 6);
            //Player testPlayer = new Player(t8);
            //testPlayer.setAIPlayer(true);
            return t8;
        }
    }
}