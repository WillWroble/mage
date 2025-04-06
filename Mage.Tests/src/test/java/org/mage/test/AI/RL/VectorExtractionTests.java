package org.mage.test.AI.RL;

import mage.constants.PhaseStep;
import mage.player.ai.ComputerPlayer8;
import mage.player.ai.StateEncoder;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBaseAI;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class VectorExtractionTests extends CardTestPlayerBaseAI {
    private List<LabeledState> labeledStates;
    private StateEncoder encoder;
    // File where the persistent mapping is stored
    private static final String MAPPING_FILE = "features_mapping.ser";

    @Override
    public List<String> getFullSimulatedPlayers() {
        return Arrays.asList("PlayerA", "PlayerB");
    }

    @Before
    public void initEncoder() {
        System.out.println("Setting up encoder");
        encoder = new StateEncoder();
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
        setEncoderForPlayer();
        labeledStates = new ArrayList<>();
    }

    private void setEncoderForPlayer() {
        ComputerPlayer8 c8 = (ComputerPlayer8) playerA.getComputerPlayer();
        c8.setEncoder(encoder);
        encoder.setAgent(playerA.getId());
        encoder.setOpponent(playerB.getId());
    }
    /**
     * This test runs a game for a fixed number of turns.
     * The StateEncoder processes the state at the end of each turn and adds its binary vector
     * to its internal stateVectors list.
     */
    @Test
    public void test_extractVectors5_1() {
        int maxTurn = 5;
        setStrictChooseMode(true);
        setStopAt(maxTurn, PhaseStep.END_TURN);
        execute();

        // For now we use a simple dummy labeling scheme: a sequential integer label.
        int label = 0;
        for (boolean[] vector : encoder.stateVectors) {
            labeledStates.add(new LabeledState(vector, label++));
        }

        // Print out some sample labeled vectors.
        for (LabeledState ls : labeledStates) {
            System.out.println("Label: " + ls.label + " Vector: " + Arrays.toString(ls.vector));
        }
    }

    @After
    public void persistMapping() {
        try {
            encoder.persistMapping(MAPPING_FILE);
            System.out.printf("Persisted mapping to %s\n", MAPPING_FILE);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * A simple container class to hold a state vector and its label.
     * This can later be extended with more sophisticated labeling (e.g., game outcomes).
     */
    public static class LabeledState implements Serializable {
        public boolean[] vector;
        public int label;

        public LabeledState(boolean[] vector, int label) {
            this.vector = vector;
            this.label = label;
        }
    }
}
