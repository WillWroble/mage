package org.mage.test.AI.basic;

import mage.constants.PhaseStep;
import mage.game.GameException;
import mage.player.ai.ComputerPlayer8;
import mage.player.ai.StateEncoder;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBaseAI;

import java.io.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

public class VectorExtractionTests extends CardTestPlayerBaseAI {
    // Persistent encoder file
    private static final String ENCODER_FILE = "stateEncoder.ser";

    // Our state encoder and list of labeled state vectors
    private StateEncoder encoder;
    private List<LabeledState> labeledStates;

    @Override
    public List<String> getFullSimulatedPlayers() {
        return Arrays.asList("PlayerA", "PlayerB");
    }

    @Before
    public void initEncoder() {
        System.out.println("Setting up persistent encoder...");
        // Try loading a persistent encoder mapping if it exists.
        File file = new File(ENCODER_FILE);
        if (file.exists()) {
            try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(file))) {
                encoder = (StateEncoder) ois.readObject();
                System.out.println("Loaded persistent encoder from " + ENCODER_FILE);
            } catch (IOException | ClassNotFoundException e) {
                System.err.println("Failed to load persistent encoder. Creating a new one.");
                encoder = new StateEncoder();
            }
        } else {
            encoder = new StateEncoder();
        }
        // Setup encoder for the computer player.
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
    public void test_extractVectors() {
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
    public void persistEncoder() {
        // After tests, persist the encoder mapping for future runs.
        try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(ENCODER_FILE))) {
            oos.writeObject(encoder);
            System.out.println("Persisted encoder mapping to " + ENCODER_FILE);
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
