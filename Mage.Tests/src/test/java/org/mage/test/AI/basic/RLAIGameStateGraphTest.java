package org.mage.test.AI.basic;

import mage.constants.PhaseStep;
import mage.constants.RangeOfInfluence;
import mage.constants.Zone;
import mage.player.ai.CardState;
import mage.player.ai.GameStateGraphNode;
import org.junit.Assert;
import org.junit.Test;
import org.mage.test.player.TestComputerPlayerRL;
import org.mage.test.player.TestPlayer;
import org.mage.test.serverside.base.CardTestPlayerBaseAI;

import java.util.Arrays;
import java.util.List;

/**
 * @author JayDi85
 */
public class RLAIGameStateGraphTest extends CardTestPlayerBaseAI {

    @Override
    public List<String> getFullSimulatedPlayers() {
        return Arrays.asList("PlayerA", "PlayerB");
    }

    @Test
    public void test_Graph_Simple() {
        // both must kill x2 bears by x2 bolts
        GameStateGraphNode root = new GameStateGraphNode();
        CardState aState = new CardState("A");
        CardState bState = new CardState("B");


        GameStateGraphNode A = new GameStateGraphNode();
        A.cardsBattleField.addCardState(aState);
        GameStateGraphNode B = new GameStateGraphNode();
        B.cardsBattleField.addCardState(bState);
        GameStateGraphNode AB = new GameStateGraphNode();
        AB.cardsBattleField.addCardState(new CardState("A"));
        AB.cardsBattleField.addCardState(new CardState("B"));

        GameStateGraphNode A_ = GameStateGraphNode.GetLargestSharedSubset(AB, A);

        assert (A_.equals(A));

        root.linkStateNode(A);
        root.linkStateNode(B);
        root.linkStateNode(AB);

        assert (A.isDescendentOf(root));
        assert (!A.isDescendentOf(B));
        assert (!B.isDescendentOf(A));
        assert(AB.isDescendentOf(A));

        root.printGraph(0);
        System.out.println("Hello World!");
    }
    @Test
    public void test_Graph_Simple2() {
        // both must kill x2 bears by x2 bolts
        GameStateGraphNode root = new GameStateGraphNode();

        GameStateGraphNode A = new GameStateGraphNode();
        A.cardsBattleField.addCardState(new CardState("A"));
        GameStateGraphNode AB = new GameStateGraphNode();
        AB.cardsBattleField.addCardState(new CardState("A"));
        AB.cardsBattleField.addCardState(new CardState("B"));
        GameStateGraphNode BC = new GameStateGraphNode();
        BC.cardsBattleField.addCardState(new CardState("B"));
        BC.cardsBattleField.addCardState(new CardState("C"));
        GameStateGraphNode AC = new GameStateGraphNode();
        AC.cardsBattleField.addCardState(new CardState("A"));
        AC.cardsBattleField.addCardState(new CardState("C"));

        GameStateGraphNode A_ = GameStateGraphNode.GetLargestSharedSubset(AC, AB);
        assert (A.equals(A_));

        root.linkStateNode(AB);
        root.linkStateNode(BC);
        root.linkStateNode(AC);

        root.printGraph(0);
        System.out.println("Hello World!");
    }

    @Test
    public void test_AIvsAI_LongGame() {
        // many bears and bolts must help to end game fast
        int maxTurn = 50;
        removeAllCardsFromLibrary(playerA);
        removeAllCardsFromLibrary(playerB);

        addCard(Zone.LIBRARY, playerA, "Mountain", 10);
        addCard(Zone.LIBRARY, playerA, "Forest", 10);
        addCard(Zone.LIBRARY, playerA, "Lightning Bolt", 20);
        addCard(Zone.LIBRARY, playerA, "Balduvian Bears", 10);
        //
        addCard(Zone.LIBRARY, playerB, "Mountain", 10);
        addCard(Zone.LIBRARY, playerA, "Forest", 10);
        addCard(Zone.LIBRARY, playerB, "Lightning Bolt", 20);
        addCard(Zone.LIBRARY, playerB, "Balduvian Bears", 10);

        // full ai simulation
        setStrictChooseMode(true);
        setStopAt(maxTurn, PhaseStep.END_TURN);
        execute();

        Assert.assertTrue("One of player must won a game before turn " + maxTurn + ", but it ends on " + currentGame, currentGame.hasEnded());
    }
    @Override
    protected TestPlayer createPlayer(String name, RangeOfInfluence rangeOfInfluence) {
        if (getFullSimulatedPlayers().contains(name)) {
            TestPlayer testPlayer = new TestPlayer(new TestComputerPlayerRL(name, RangeOfInfluence.ONE, getSkillLevel()));
            testPlayer.setAIPlayer(true); // enable full AI support (game simulations) for all turns by default
            return testPlayer;
        }
        return super.createPlayer(name, rangeOfInfluence);
    }
}
