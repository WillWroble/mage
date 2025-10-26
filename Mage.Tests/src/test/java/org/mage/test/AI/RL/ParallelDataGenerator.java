package org.mage.test.AI.RL;

import mage.abilities.Ability;
import mage.abilities.ActivatedAbility;
import mage.abilities.effects.Effect;
import mage.abilities.effects.common.CreateTokenEffect;
import mage.cards.Card;
import mage.cards.decks.Deck;
import mage.cards.decks.DeckCardLists;
import mage.cards.decks.importer.DeckImporter;
import mage.constants.*;
import mage.game.*;
import mage.game.match.Match;
import mage.game.match.MatchOptions;
import mage.game.mulligan.MulliganType;
import mage.game.permanent.token.Token;
import mage.player.ai.*;
import mage.util.RandomUtil;
import org.jetbrains.annotations.NotNull;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static java.nio.file.StandardOpenOption.READ;


/**
 * Base class for all RL data generators. see <a href="https://github.com/WillWroble/MageZero">MageZero repo</a>
 * for how to use
 *
 */
public class ParallelDataGenerator extends CardTestPlayerBaseAI {

    // ============================ DATA GENERATION SETTINGS ============================
    protected static int NUM_GAMES_TO_SIMULATE = 100;
    protected static int MAX_GAME_TURNS = 50;
    protected static int MAX_CONCURRENT_GAMES = 8;
    // =============================== AI SETTINGS ===============================
    protected static boolean DONT_USE_NOISE = true;
    protected static boolean DONT_USE_POLICY = false;
    protected static double DISCOUNT_FACTOR = 0.95;
    protected static double VALUE_LAMBDA = 0.5;
    /**MCTS settings in ComputerPlayerMCTS.java*/
    // =============================== MATCH SETTINGS ===============================
    protected static boolean ALWAYS_GO_FIRST = false;
    protected static boolean ALLOW_MULLIGANS = false; //TODO: implement mulligans
    protected static String DECK_A = "UWTempo";
    protected static String DECK_B = "MTGA_MonoU";
    protected static String MODEL_URL_A = "http://127.0.0.1:50052";
    protected static String MODEL_URL_B = "http://127.0.0.1:50053";
    // ================================== FILE PATHS ==================================
    protected static String DECK_A_PATH = "decks/" + DECK_A + ".dck";
    protected static String DECK_B_PATH = "decks/" + DECK_B + ".dck";
    protected static String IGNORE_PATH = "ignores/" + DECK_A + "/ignore3.roar";
    protected static String SEEN_FEATURES_PATH = "seenFeatures.roar";
    protected static String DATA_OUT_FILE = "training.hdf5";
    // ================================== GLOBAL FIELDS ==================================
    private RoaringBitmap seenFeatures = new RoaringBitmap();
    private int initialRawSize;
    private final AtomicInteger gameCount = new AtomicInteger(0);
    private final AtomicInteger winCount = new AtomicInteger(0);
    private RemoteModelEvaluator remoteModelEvaluatorA;
    private RemoteModelEvaluator remoteModelEvaluatorB;
    private final BlockingQueue<List<LabeledState>> LSQueue = new ArrayBlockingQueue<>(32);
    private final AtomicBoolean stop = new AtomicBoolean(false);



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


    public void createAllActionsFromDeckList(String deckName, Map<String, Integer> actionMap) throws GameException {
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
        //pass is always 0
        actionMap.put("Pass", 0);
        int index = 1;
        List<Card> sortedCards = new ArrayList<>(deck.getCards());
        sortedCards.sort(Comparator.comparing(Card::getName));
        for(Card card : sortedCards) {
            List<Ability> sortedAbilities = new ArrayList<>(card.getAbilities());
            sortedAbilities.sort(Comparator.comparing(Ability::toString));
            for(Ability aa : sortedAbilities) {
                if(aa instanceof ActivatedAbility) {
                    String name = aa.toString();
                    if (!actionMap.containsKey(name)) {
                        actionMap.put(name, index++);
                        logger.info("mapping " + name + " to " + (index - 1));
                    }
                }
            }
        }
    }
    public void createAllTargetsFromDeckLists(String deckNameA, String deckNameB) throws GameException {
        String[] decks = new String[] {deckNameA, deckNameB};
        ActionEncoder.targetMap.put("PlayerA", 0);
        ActionEncoder.targetMap.put("PlayerB", 1);
        int index = 2;
        logger.debug("Loading decks...");
        for(String deckName : decks) {
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
            for (Card card : sortedCards) {
                if(!ActionEncoder.targetMap.containsKey(card.getName())) {
                    ActionEncoder.targetMap.put(card.getName(), index++);
                    logger.info("mapping " + card.getName() + " to " + (index - 1));
                }
                //check for tokens
                List<Ability> sortedAbilities = new ArrayList<>(card.getAbilities());
                sortedAbilities.sort(Comparator.comparing(Ability::toString));
                for (Ability ta : sortedAbilities) {
                    List<Effect>  sortedEffects = new ArrayList<>(ta.getEffects());
                    sortedEffects.sort(Comparator.comparing(Effect::toString));
                    for (Effect effect : sortedEffects) {
                        if (effect instanceof CreateTokenEffect) {
                            CreateTokenEffect createTokenEffect = (CreateTokenEffect) effect;
                            List<Token> sortedTokens = new ArrayList<>(createTokenEffect.tokens);
                            sortedTokens.sort(Comparator.comparing(Token::getName));
                            for (Token token : sortedTokens) {
                                String name = token.getName();
                                if (!ActionEncoder.targetMap.containsKey(token.getName())) {
                                    ActionEncoder.targetMap.put(name, index++);
                                    logger.info("mapping " + name + " to " + (index - 1));
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * run once
     */
    private void loadAllFiles() {
        //create the action map from the provided decklists
        try {
            createAllActionsFromDeckList(DECK_A_PATH, ActionEncoder.playerActionMap);
            createAllActionsFromDeckList(DECK_B_PATH, ActionEncoder.opponentActionMap);
            createAllTargetsFromDeckLists(DECK_A_PATH, DECK_B_PATH);
        } catch (GameException e) {
            logger.error("could not load Deck files!", e);
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
            logger.info("global seen features list size: " + seenFeatures.getCardinality());
        } catch (IOException e) {
            logger.warn("external seen feature list not found");
        }
        try {
            remoteModelEvaluatorA = new RemoteModelEvaluator(MODEL_URL_A);
            remoteModelEvaluatorB  = new RemoteModelEvaluator(MODEL_URL_B);
        } catch (Exception e) {
            logger.warn("Failed to establish connection to network model; falling back to offline mode");
            ComputerPlayerMCTS2.OFFLINE_MODE = true;
            VALUE_LAMBDA = 0.3;
        }
        initialRawSize = seenFeatures.getCardinality();
    }
    /**
     * prints the sizes of the action spaces for these 2 decks
     */
    @Test
    public void get_action_spaces() {
        try {
            createAllActionsFromDeckList(DECK_A_PATH, ActionEncoder.playerActionMap);
            createAllActionsFromDeckList(DECK_B_PATH, ActionEncoder.opponentActionMap);
            createAllTargetsFromDeckLists(DECK_A_PATH, DECK_B_PATH);
        } catch (GameException e) {
            logger.error("could not load Deck files!", e);
        }
        logger.info("PlayerAPriority size: " + ActionEncoder.playerActionMap.size());
        logger.info("PlayerBPriority size: " + ActionEncoder.opponentActionMap.size());
        logger.info("Num Possible Targets for Both Players: " + ActionEncoder.targetMap.size());
        logger.info("=========================================");
        logger.info("            PRIORITY A ACTIONS:           ");
        logger.info("==========================================");
        printMapByValue(ActionEncoder.playerActionMap);
        logger.info("=========================================");
        logger.info("            PRIORITY B ACTIONS:           ");
        logger.info("==========================================");
        printMapByValue(ActionEncoder.opponentActionMap);
        logger.info("=========================================");
        logger.info("               TARGET ACTIONS:            ");
        logger.info("==========================================");
        printMapByValue(ActionEncoder.targetMap);
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
        ComputerPlayerMCTS.NO_NOISE = DONT_USE_NOISE;
        ComputerPlayerMCTS.NO_POLICY = DONT_USE_POLICY;
        ComputerPlayer.PRINT_DECISION_FALLBACKS = false;
        int maxTurn = 50;
        //ComputerPlayer.PRINT_DECISION_FALLBACKS = true;
        //MCTSPlayer.PRINT_CHOOSE_DIALOGUES = false;
        //Features.printOldFeatures = false;
        //long seed = System.nanoTime();
        //seed = 751314143315900L; //opponent turn priority order bug
        //seed = -4411935635951101274L; //blocking bug
        //seed = 5401683921170803014L; //unable to find matching
        //seed = 1405302846091300L; //chooseTarget() and chooseUse() are used
        //seed = 1489032704055400L; //illegal block targets



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
        /* usage example for manual card insertion
        GameImpl.drawHand = false;
        addCard(Zone.BATTLEFIELD, playerA, "Island", 2);
        addCard(Zone.BATTLEFIELD, playerA, "Malcolm, Alluring Scoundrel", 1);
        addCard(Zone.HAND, playerA, "Combat Research", 7);
         */
        execute();
        //saveRoaring(threadEncoder.seenFeatures, SEEN_FEATURES_PATH);

    }
    @Test
    public void generateData() {


        initialRawSize = 0;
        loadAllFiles();
        ComputerPlayerMCTS2.SHOW_THREAD_INFO = true;
        ComputerPlayerMCTS.NO_NOISE = DONT_USE_NOISE;
        ComputerPlayerMCTS.NO_POLICY = DONT_USE_POLICY;

        LabeledStateWriter fw;
        try {
            fw = new LabeledStateWriter(DATA_OUT_FILE);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        Thread writer = getWriter(fw);


        logger.info("=========================================");
        logger.info("   STARTING DATA GENERATION     ");
        logger.info("=========================================");


        runSimulations(NUM_GAMES_TO_SIMULATE);

        //end writer thread
        stop.set(true);
        try {
            writer.join();
        } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }

        saveRoaring(seenFeatures, SEEN_FEATURES_PATH);

        logger.info("Processing " + fw.batchStates + " states.");
        logger.info("Initial feature count: " + initialRawSize);
        logger.info("Final unique feature count from dataset: " + fw.batchFeatures.size());
        logger.info("Global unique feature count: " + seenFeatures.getCardinality());
        logger.info("Features added: " + (seenFeatures.getCardinality() - initialRawSize));
    }

    @NotNull
    private Thread getWriter(LabeledStateWriter fw) {
        Thread writer = new Thread(() -> {
            try {
                while (!stop.get() || !LSQueue.isEmpty()) {
                    List<LabeledState> batch = LSQueue.take();
                    for (LabeledState s : batch) fw.writeRecord(s);
                    fw.flush(); // flush per game to keep data durable
                }
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                try { fw.close(); } catch (Exception ignore) {}
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
                    LSQueue.put(out.states);
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
            StateEncoder threadEncoder = new StateEncoder();

            // Use a thread-safe random number generator for the seed.
            logger.info("Using seed: " + gameSeed);
            RandomUtil.setSeed(gameSeed);



            // All game objects are local to this thread to prevent race conditions.
            MatchOptions matchOptions = new MatchOptions("test match", "test game type", false, 2);
            Match localMatch = new TwoPlayerMatch(matchOptions);
            game = new TwoPlayerDuel(MultiplayerAttackOption.LEFT, RangeOfInfluence.ONE, MulliganType.GAME_DEFAULT.getMulligan(0), 60, 20, 7);
            TestPlayer playerA = createLocalPlayer(game, "PlayerA", DECK_A_PATH, localMatch);
            TestPlayer playerB = createLocalPlayer(game, "PlayerB", DECK_B_PATH, localMatch);

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
            if(ALWAYS_GO_FIRST) {
                game.start(null);
            } else {
                int dieRoll = ThreadLocalRandom.current().nextInt()%2;
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
            synchronized (seenFeatures) {
                seenFeatures.or(threadEncoder.seenFeatures);
            }
            if(playerA.hasWon()) winCount.incrementAndGet();
            logger.info("Game #" + gameCount.incrementAndGet() + " completed successfully");
            logger.info("Current WR: " + winCount.get()*1.0/gameCount.get());
            return new GameResult(generateLabeledStatesForGame(threadEncoder, playerAWon), playerAWon);
        } catch (Exception e) {
            logger.error("Caught an internal AI/Game exception in a worker thread. Ignoring this game. Cause: " + e.getMessage());
            throw new ExecutionException("Worker thread failed - ignoring", e);
        }
    }

    private void configurePlayer(TestPlayer player, StateEncoder encoder) {
        if (player.getComputerPlayer() instanceof ComputerPlayerMCTS2) {
            ((ComputerPlayerMCTS2) player.getComputerPlayer()).setEncoder(encoder);
            if(player.getName().equals("PlayerA")) {
                ((ComputerPlayerMCTS2) player.getComputerPlayer()).nn = remoteModelEvaluatorA;
            } else {
                ((ComputerPlayerMCTS2) player.getComputerPlayer()).nn = remoteModelEvaluatorB;
            }
        } else if (player.getComputerPlayer() instanceof ComputerPlayer8) {
            ((ComputerPlayer8) player.getComputerPlayer()).setEncoder(encoder);
        } else  {
            logger.warn("unexpected player type" + player.getComputerPlayer().getClass().getName());
        }
    }

    private List<LabeledState> generateLabeledStatesForGame(StateEncoder encoder, boolean didPlayerAWin) {
        int N = encoder.labeledStates.size();
        double lambda = VALUE_LAMBDA;

        for (int i = 0; i < N; i++) {
            double normScore = encoder.labeledStates.get(i).stateScore;
            double terminal = didPlayerAWin ? 1.0 : -1.0;
            double discount = Math.pow(DISCOUNT_FACTOR, (N - i - 1));
            encoder.labeledStates.get(i).resultLabel = lambda * normScore + (1.0 - lambda) * terminal * discount;
        }
        return encoder.labeledStates;

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
    @Override
    public List<String> getFullSimulatedPlayers() {
        return Arrays.asList("PlayerA", "PlayerB");
    }
    @Override
    protected Game createNewGameAndPlayers() throws GameException, FileNotFoundException {
        Game game = new TwoPlayerDuel(MultiplayerAttackOption.LEFT, RangeOfInfluence.ONE, MulliganType.GAME_DEFAULT.getMulligan(0), 60, 20, 7);
        playerA = createPlayer(game, "PlayerA", DECK_A_PATH);
        playerB = createPlayer(game, "PlayerB", DECK_B_PATH);
        return game;
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
    // This is the correct override to use for choosing our AI types.
    @Override
    protected TestPlayer createPlayer(String name, RangeOfInfluence rangeOfInfluence) {
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
}