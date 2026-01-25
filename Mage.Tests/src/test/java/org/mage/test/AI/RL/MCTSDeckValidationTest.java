package org.mage.test.AI.RL;

import mage.cards.repository.CardRepository;
import mage.cards.repository.CardScanner;
import mage.cards.repository.RepositoryUtil;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mage.magezero.Config;
import org.mage.magezero.ParallelDataGenerator;

import static org.junit.jupiter.api.Assertions.*;

public class MCTSDeckValidationTest {
    //config
    private static final int GAMES_PER_TEST = 4;
    private static final int MAX_TURNS = 50;
    private static final String OPPONENT_DECK = "decks/Standard-MonoB.dck";

    @BeforeAll
    static void setup() {
        Config.loadDefault();
        // Initialize card database
        RepositoryUtil.bootstrapLocalDb();
        CardScanner.scan();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "decks/Standard-MonoB.dck",
            "decks/Standard-MonoG.dck",
            "decks/Standard-MonoR.dck",
            "decks/Standard-MonoU.dck",
            "decks/Standard-MonoW.dck",
            "decks/MTGA_MonoB.dck",
            "decks/MTGA_MonoG.dck",
            "decks/MTGA_MonoR.dck",
            "decks/MTGA_MonoU.dck",
            "decks/MTGA_MonoW.dck",
            "decks/BGRoots.dck",
            "decks/BWBats.dck",
            "decks/EsperTempo.dck",
            "decks/GBLegends.dck",
            "decks/MonoUArtifacts.dck",
            "decks/RB Aggro.dck",
            "decks/UBArtifacts.dck",
            "decks/simic-eldrazi.dck",
            "decks/HighNoonControl.dck",
            "decks/EVG_Elves.dck",
            "decks/EVG_Goblins.dck",
            "decks/Mind(MindvsMight).dck",
            "decks/WillS_Tempo.dck",
            "decks/UW Control.dck"
    })
    public void testDeckAgainstMinimax(String testDeck) {
        Config.INSTANCE.playerA.deckPath = testDeck;
        Config.INSTANCE.playerB.deckPath = OPPONENT_DECK;
        Config.INSTANCE.playerA.type = "mcts";
        Config.INSTANCE.playerB.type = "minimax";
        Config.INSTANCE.training.games = GAMES_PER_TEST;
        Config.INSTANCE.training.maxTurns = MAX_TURNS;
        Config.INSTANCE.training.threads = 4;
        try {
            ParallelDataGenerator generator = new ParallelDataGenerator();
            generator.generateData();
            int gamesPlayed = generator.gameCount.get();
            assertEquals(GAMES_PER_TEST, gamesPlayed, "Should complete all games");
            assertTrue(gamesPlayed > 0, "Should play at least one game");
        } catch (Exception e) {
            fail("Deck " + testDeck + " caused crash: " + e.getMessage(), e);
        }
    }
    @AfterAll
    static void cleanup() {
        CardRepository.instance.closeDB(true);
    }
}
