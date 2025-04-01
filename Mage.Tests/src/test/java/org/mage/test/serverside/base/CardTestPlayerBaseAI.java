package org.mage.test.serverside.base;

import mage.cards.decks.Deck;
import mage.cards.decks.DeckCardLists;
import mage.cards.decks.importer.DeckImporter;
import mage.constants.MultiplayerAttackOption;
import mage.constants.RangeOfInfluence;
import mage.game.Game;
import mage.game.GameException;
import mage.game.TwoPlayerDuel;
import mage.game.mulligan.MulliganType;
import mage.player.ai.ComputerPlayer8;
import mage.player.ai.StateEncoder;
import org.mage.test.AI.basic.RLEncodingTests;
import org.mage.test.player.TestComputerPlayer7;
import org.mage.test.player.TestComputerPlayer8;
import org.mage.test.player.TestPlayer;
import org.mage.test.serverside.base.impl.CardTestPlayerAPIImpl;

import java.io.FileNotFoundException;
import java.util.Arrays;
import java.util.List;

/**
 * PlayerA is full AI player and process all actions as AI logic. You don't need aiXXX commands in that tests.
 * <p>
 * If you need simple AI tests for single command/priority then use CardTestPlayerBaseWithAIHelps with aiXXX commands
 * If you need full AI tests with game simulations then use current CardTestPlayerBaseAI
 * <p>
 * Only PlayerA ai-controlled by default. Use getFullSimulatedPlayers for additional AI players, e.g. AI vs AI tests.
 *
 * @author LevelX2, JayDi85
 */
public abstract class CardTestPlayerBaseAI extends CardTestPlayerAPIImpl {

    /**
     * Allow to change AI skill level
     */
    public int getSkillLevel() {
        return 6;
    }

    /**
     * Allow to change full simulates players (default is PlayerA)
     *
     * @return
     */
    public List<String> getFullSimulatedPlayers() {
        return Arrays.asList("PlayerA");
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
        //((ComputerPlayer8)player.getComputerPlayer()).setEmbedder(new StateEmbedder(list));
        game.loadCards(deck.getCards(), player.getId());
        game.loadCards(deck.getSideboard(), player.getId());
        game.addPlayer(player, deck);
        currentMatch.addPlayer(player, deck); // fake match

        return player;
    }
    @Override
    protected Game createNewGameAndPlayers() throws GameException, FileNotFoundException {
        Game game = new TwoPlayerDuel(MultiplayerAttackOption.LEFT, RangeOfInfluence.ONE, MulliganType.GAME_DEFAULT.getMulligan(0), 60, 20, 7);
        playerA = createPlayer(game, "PlayerA", "C:\\Users\\WillWroble\\Documents\\" + RLEncodingTests.deckNameA);
        playerB = createPlayer(game, "PlayerB", "C:\\Users\\WillWroble\\Documents\\" + RLEncodingTests.deckNameB);
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
                TestComputerPlayer7 t7 = new TestComputerPlayer7(name, RangeOfInfluence.ONE, getSkillLevel());
                TestPlayer testPlayer = new TestPlayer(t7);
                testPlayer.setAIPlayer(true); // enable full AI support (game simulations) for all turns by default
                return testPlayer;
            }
        }
        return super.createPlayer(name, rangeOfInfluence);
    }
}
