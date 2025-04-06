//package org.mage.test.AI.RL;
//
//import mage.cards.Card;
//import mage.cards.decks.Deck;
//import mage.cards.decks.DeckCardLists;
//import mage.cards.decks.importer.DeckImporter;
//import mage.cards.repository.CardInfo;
//import mage.constants.*;
//import mage.game.*;
//import mage.game.match.Match;
//import mage.game.match.MatchOptions;
//import mage.game.mulligan.MulliganType;
//import mage.player.ai.ComputerPlayerMCTS;
//import mage.player.ai.Features;
//import mage.players.Player;
//import mage.server.util.PluginClassLoader;
//import mage.util.ThreadUtils;
//import org.apache.log4j.Logger;
//import org.junit.Assert;
//import org.junit.Before;
//import org.junit.Test;
//import org.mage.test.player.PlayerAction;
//
//import java.io.File;
//import java.io.FileNotFoundException;
//import java.util.*;
//
//import static org.junit.Assert.assertTrue;
//
///**
// * @author WillWroble
// */
//public class MCTSTests {
//    protected static Logger logger = Logger.getLogger(MCTSTests.class);
//    private String deckNameA = "simplegreen.dck"; //simplegreen, UWTempo
//    private String deckNameB = "simplegreen.dck";
//    protected GameOptions gameOptions;
//
//    private ComputerPlayerMCTS playerA;
//    private ComputerPlayerMCTS playerB;
//    protected static Map<String, DeckCardLists> loadedDecks = new HashMap<>(); // deck's cache
//    protected static Map<String, CardInfo> loadedCardInfo = new HashMap<>(); // db card's cache
//    public static PluginClassLoader classLoader = new PluginClassLoader();
//
//    private static final String pluginFolder = "plugins";
//
//    protected Map<ComputerPlayerMCTS, List<Card>> handCards = new HashMap<>();
//    protected Map<ComputerPlayerMCTS, List<PutToBattlefieldInfo>> battlefieldCards = new HashMap<>(); // cards + additional status like tapped
//    protected Map<ComputerPlayerMCTS, List<Card>> graveyardCards = new HashMap<>();
//    protected Map<ComputerPlayerMCTS, List<Card>> libraryCards = new HashMap<>();
//    protected Map<ComputerPlayerMCTS, List<Card>> commandCards = new HashMap<>();
//    protected Map<ComputerPlayerMCTS, List<Card>> exiledCards = new HashMap<>();
//
//    protected Map<ComputerPlayerMCTS, Map<Zone, String>> commands = new HashMap<>();
//    protected static Match currentMatch = null;
//    protected static Game currentGame = null;
//    protected static Player activePlayer = null;
//    protected Integer stopOnTurn;
//
//    protected PhaseStep stopAtStep = PhaseStep.UNTAP;
//    private int rollbackBlock = 0; // used to handle actions that have to be added after a rollback
//    private boolean rollbackBlockActive = false;
//    private ComputerPlayerMCTS rollbackPlayer = null;
//
//    private static void deleteSavedGames() {
//        File directory = new File("saved/");
//        if (!directory.exists()) {
//            directory.mkdirs();
//        }
//        File[] files = directory.listFiles(
//                (dir, name) -> name.endsWith(".game")
//        );
//        for (File file : files) {
//            file.delete();
//        }
//    }
//
//    protected List<Card> getHandCards(ComputerPlayerMCTS player) {
//        if (handCards.containsKey(player)) {
//            return handCards.get(player);
//        }
//        List<Card> hand = new ArrayList<>();
//        handCards.put(player, hand);
//        return hand;
//    }
//
//    protected List<Card> getGraveCards(ComputerPlayerMCTS player) {
//        if (graveyardCards.containsKey(player)) {
//            return graveyardCards.get(player);
//        }
//        List<Card> res = new ArrayList<>();
//        graveyardCards.put(player, res);
//        return res;
//    }
//
//    protected List<Card> getLibraryCards(ComputerPlayerMCTS player) {
//        if (libraryCards.containsKey(player)) {
//            return libraryCards.get(player);
//        }
//        List<Card> res = new ArrayList<>();
//        libraryCards.put(player, res);
//        return res;
//    }
//
//    protected List<Card> getCommandCards(ComputerPlayerMCTS player) {
//        if (commandCards.containsKey(player)) {
//            return commandCards.get(player);
//        }
//        List<Card> res = new ArrayList<>();
//        commandCards.put(player, res);
//        return res;
//    }
//
//    protected List<PutToBattlefieldInfo> getBattlefieldCards(ComputerPlayerMCTS player) {
//        if (battlefieldCards.containsKey(player)) {
//            return battlefieldCards.get(player);
//        }
//        List<PutToBattlefieldInfo> res = new ArrayList<>();
//        battlefieldCards.put(player, res);
//        return res;
//    }
//
//    protected List<Card> getExiledCards(ComputerPlayerMCTS player) {
//        if (exiledCards.containsKey(player)) {
//            return exiledCards.get(player);
//        }
//        List<Card> res = new ArrayList<>();
//        exiledCards.put(player, res);
//        return res;
//    }
//
//    protected Map<Zone, String> getCommands(ComputerPlayerMCTS player) {
//        if (commands.containsKey(player)) {
//            return commands.get(player);
//        }
//        Map<Zone, String> command = new HashMap<>();
//        commands.put(player, command);
//        return command;
//    }
//
//    public void setStopAt(int turn, PhaseStep step) {
//        assertTrue("Wrong turn " + turn, turn >= 1);
//        stopOnTurn = turn;
//        stopAtStep = step;
//    }
//    public void execute() throws IllegalStateException {
//        if (currentGame == null || activePlayer == null) {
//            throw new IllegalStateException("Game is not initialized. Use load method to load a test case and initialize a game.");
//        }
//
//        ThreadUtils.ensureRunInGameThread();
//
//        // check stop command
//        int maxTurn = 1;
//        int maxPhase = 0;
//        for (Player player : currentGame.getPlayers().values()) {
//            if (player instanceof ComputerPlayerMCTS) {
//                ComputerPlayerMCTS testPlayer = (ComputerPlayerMCTS) player;
//                for (org.mage.test.player.PlayerAction action : testPlayer.getActions()) {
//                    assertTrue("Wrong turn in action " + action.getTurnNum(), action.getTurnNum() >= 1);
//                    int curTurn = action.getTurnNum();
//                    int curPhase = action.getStep().getIndex();
//                    if ((curTurn > maxTurn) || (curTurn == maxTurn && curPhase > maxPhase)) {
//                        maxTurn = curTurn;
//                        maxPhase = curPhase;
//                    }
//                }
//            }
//        }
//        Assert.assertFalse("Wrong stop command on " + this.stopOnTurn + " / " + this.stopAtStep + " (" + this.stopAtStep.getIndex() + ")"
//                        + " (found actions after stop on " + maxTurn + " / " + maxPhase + ")",
//                (maxTurn > this.stopOnTurn) || (maxTurn == this.stopOnTurn && maxPhase > this.stopAtStep.getIndex()));
//
//        // check commands order
//        for (Player player : currentGame.getPlayers().values()) {
//            if (true) break; // TODO: delete/comment and fix all failed tests
//            if (player instanceof ComputerPlayerMCTS) {
//                ComputerPlayerMCTS testPlayer = (ComputerPlayerMCTS) player;
//                int lastActionIndex = 0;
//                org.mage.test.player.PlayerAction lastAction = null;
//                for (PlayerAction currentAction : testPlayer.getActions()) {
//                    int currentActionIndex = 1000 * currentAction.getTurnNum() + currentAction.getStep().getIndex();
//                    if (currentActionIndex < lastActionIndex) {
//                        // how-to fix: find typo in step/turn number
//                        Assert.fail("Found wrong commands order for " + testPlayer.getName() + ":" + "\n"
//                                + lastAction + "\n"
//                                + currentAction);
//                    } else {
//                        lastActionIndex = currentActionIndex;
//                        lastAction = currentAction;
//                    }
//                }
//            }
//        }
//
//        if (!currentGame.isPaused()) {
//            // workaround to fill range info (cause real range fills after game start, but some cheated cards needs range on ETB)
//            for (Player player : currentGame.getPlayers().values()) {
//                player.updateRange(currentGame);
//            }
//            // add cards to game
//            for (Player player : currentGame.getPlayers().values()) {
//                ComputerPlayerMCTS testPlayer = (ComputerPlayerMCTS) player;
//                currentGame.cheat(testPlayer.getId(), getCommands(testPlayer));
//                currentGame.cheat(testPlayer.getId(), getLibraryCards(testPlayer), getHandCards(testPlayer),
//                        getBattlefieldCards(testPlayer), getGraveCards(testPlayer), getCommandCards(testPlayer),
//                        getExiledCards(testPlayer));
//
//            }
//        }
//
//        long t1 = System.nanoTime();
//
//        gameOptions.testMode = true;
//        gameOptions.stopOnTurn = stopOnTurn;
//        gameOptions.stopAtStep = stopAtStep;
//        currentGame.setGameOptions(gameOptions);
//        if (currentGame.isPaused()) {
//            currentGame.resume();// needed if execute() is performed multiple times
//        }
//        currentGame.start(activePlayer.getId());
//        currentGame.setGameStopped(true); // used for rollback handling
//        long t2 = System.nanoTime();
//        logger.debug("Winner: " + currentGame.getWinner());
//
//    }
//    /**
//     * @throws GameException
//     * @throws FileNotFoundException
//     */
//    @Before
//    public void reset() throws GameException, FileNotFoundException {
//        if (currentGame != null) {
//            logger.debug("Resetting previous game and creating new one!");
//            currentGame = null;
//        }
//
//        // prepare fake match (needs for testing some client-server code)
//        // always 4 seats
//        MatchOptions matchOptions = new MatchOptions("test match", "test game type", true, 4);
//        currentMatch = new FreeForAllMatch(matchOptions);
//        currentGame = createNewGameAndPlayers();
//
//        activePlayer = playerA;
//        stopOnTurn = 2;
//        stopAtStep = PhaseStep.UNTAP;
//
//        for (Player player : currentGame.getPlayers().values()) {
//            ComputerPlayerMCTS testPlayer = (ComputerPlayerMCTS) player;
//            getCommands(testPlayer).clear();
//            getLibraryCards(testPlayer).clear();
//            getHandCards(testPlayer).clear();
//            getBattlefieldCards(testPlayer).clear();
//            getGraveCards(testPlayer).clear();
//            getExiledCards(testPlayer).clear();
//            // Reset the turn counter for tests
//            ((ComputerPlayerMCTS) player).setInitialTurns(0);
//        }
//
//        gameOptions = new GameOptions();
//
//        rollbackBlock = 0;
//        rollbackBlockActive = false;
//
//    }
//
//    public List<String> getFullSimulatedPlayers() {
//        return Arrays.asList("PlayerA", "PlayerB");
//    }
//
//    protected Game createNewGameAndPlayers() throws GameException, FileNotFoundException {
//        Game game = new TwoPlayerDuel(MultiplayerAttackOption.LEFT, RangeOfInfluence.ONE, MulliganType.GAME_DEFAULT.getMulligan(0), 60, 20, 7);
//        playerA = createPlayer(game, "PlayerA", "C:\\Users\\WillWroble\\Documents\\" + deckNameA);
//        playerB = createPlayer(game, "PlayerB", "C:\\Users\\WillWroble\\Documents\\" + deckNameB);
//        return game;
//    }
//    protected ComputerPlayerMCTS createPlayer(Game game, String name, String deckName) throws GameException {
//        ComputerPlayerMCTS player = createNewPlayer(name, game.getRangeOfInfluence());
//        player.setTestMode(true);
//
//        logger.debug("Loading deck...");
//        DeckCardLists list;
//        if (loadedDecks.containsKey(deckName)) {
//            list = loadedDecks.get(deckName);
//        } else {
//            list = DeckImporter.importDeckFromFile(deckName, true);
//            loadedDecks.put(deckName, list);
//        }
//        Deck deck = Deck.load(list, false, false, loadedCardInfo);
//        logger.debug("Done!");
//        if (deck.getMaindeckCards().size() < 40) {
//            throw new IllegalArgumentException("Couldn't load deck, deck size=" + deck.getMaindeckCards().size());
//        }
//        game.loadCards(deck.getCards(), player.getId());
//        game.loadCards(deck.getSideboard(), player.getId());
//        game.addPlayer(player, deck);
//        currentMatch.addPlayer(player, deck); // fake match
//
//        return player;
//    }
//    protected ComputerPlayerMCTS createNewPlayer(String name, RangeOfInfluence rangeOfInfluence) {
//        if (getFullSimulatedPlayers().contains(name)) {
//
//            return new ComputerPlayerMCTS(name, RangeOfInfluence.ONE, SkillLevel.SERIOUS.ordinal());
//
//        }
//        return null;
//    }
//    public void reset_game() {
//        try {
//            reset();
//        } catch (FileNotFoundException e) {
//            throw new RuntimeException(e);
//        } catch (GameException e) {
//            throw new RuntimeException(e);
//        }
//
//    }
//    //5 turns across 1 game
//    @Test
//    public void test_mcts_5_1() {
//        // simple test of 5 turns
//        int maxTurn = 5;
//        //addCard(Zone.HAND, playerA, "Fauna Shaman", 3);
//        setStopAt(maxTurn, PhaseStep.END_TURN);
//        execute();
//
//    }
//    //20 turns across 1 game
//    @Test
//    public void test_mcts_20_1() {
//        int maxTurn = 20;
//        setStopAt(maxTurn, PhaseStep.END_TURN);
//        execute();
//
//    }
//    //5 turns across 5 games
//    @Test
//    public void test_mcts_5_5() {
//        int maxTurn = 5;
//        Features.printOldFeatures = false;
//        for(int i = 0; i < 5; i++) {
//            setStopAt(maxTurn, PhaseStep.END_TURN);
//            execute();
//            reset_game();
//            System.out.printf("GAME #%d RESET... NEW GAME STARTING\n", i+1);
//        }
//    }
//    //10 turns across 10 games
//    @Test
//    public void test_mcts_10_10() {
//        int maxTurn = 10;
//        Features.printOldFeatures = false;
//        for(int i = 0; i < 10; i++) {
//            setStopAt(maxTurn, PhaseStep.END_TURN);
//            execute();
//            reset_game();
//            System.out.printf("GAME #%d RESET... NEW GAME STARTING\n", i+1);
//        }
//    }
//
//}
