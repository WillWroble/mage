package org.mage.test.AI.RL;

import mage.cards.decks.DeckCardLists;
import mage.cards.decks.exporter.XmageDeckExporter;
import mage.cards.decks.importer.DeckImporter;
import mage.cards.repository.CardScanner;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

public class TxtToDckConversionTest {
    public static String DECK_NAME = "Deck - Izzet Storm";
    public static String DECK_IN_PATH = DECK_NAME+".txt";
    public static String DECK_OUT_PATH = "decks/"+DECK_NAME+".dck";

    @BeforeClass
    public static void initCardsRepo() {
        // Load card database once for the test JVM
        CardScanner.scan();
    }

    @Test
    public void convert() throws Exception {

        StringBuilder errors = new StringBuilder();
        DeckCardLists deck = DeckImporter.importDeckFromFile(DECK_IN_PATH, errors, true);
        if (errors.length() > 0) {
            System.out.println("Deck import messages:\n" + errors);
        }


        mage.cards.decks.DeckFormats.writeDeck(DECK_OUT_PATH, deck, new XmageDeckExporter());

    }
}