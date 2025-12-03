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
import java.util.Objects;

public class TxtToDckConversionTest {
    public static String DECK_NAME = "Deck - Izzet Storm";
    public static String DECK_IN_PATH = DECK_NAME+".txt";
    public static String DECK_OUT_PATH = "decks/"+DECK_NAME+".dck";
    //put txt files here
    public static String DECK_IN_DIR = "decks_to_convert";
    public static String DECK_OUT_DIR = "decks";
    @BeforeClass
    public static void initCardsRepo() {
        // Load card database once for the test JVM
        CardScanner.scan();
    }

    //just use convert all instead.
    @Deprecated
    public void convert() throws Exception {

        StringBuilder errors = new StringBuilder();
        DeckCardLists deck = DeckImporter.importDeckFromFile(DECK_IN_PATH, errors, true);
        if (errors.length() > 0) {
            System.out.println("Deck import messages:\n" + errors);
        }


        mage.cards.decks.DeckFormats.writeDeck(DECK_OUT_PATH, deck, new XmageDeckExporter());
    }
    @Test
    public void convertAll() throws Exception {
        File in_dir =  new File(DECK_IN_DIR);
        for (File f : Objects.requireNonNull(in_dir.listFiles())) {
            StringBuilder errors = new StringBuilder();
            DeckCardLists deck = DeckImporter.importDeckFromFile(DECK_IN_DIR + "/" + f.getName(), errors, true);
            if (errors.length() > 0) {
                System.out.println("Deck import messages:\n" + errors);
            }
            mage.cards.decks.DeckFormats.writeDeck(DECK_OUT_DIR+"/"+getFileNameWithoutExtension(f.getName())+".dck", deck, new XmageDeckExporter());
        }
    }
    public static String getFileNameWithoutExtension(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex == -1) { // No extension found
            return fileName;
        }
        return fileName.substring(0, dotIndex);
    }
}