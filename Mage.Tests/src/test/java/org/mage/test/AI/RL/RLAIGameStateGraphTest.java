package org.mage.test.AI.RL;

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

import java.util.*;

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
        GameStateGraphNode A__ = GameStateGraphNode.GetLargestSharedSubset(A, root);
        GameStateGraphNode B__ = GameStateGraphNode.GetLargestSharedSubset(B, root);
        assert (A__.equals(root));
        assert (B__.equals(root));


        GameStateGraphNode A_ = GameStateGraphNode.GetLargestSharedSubset(AB, A);

        assert (A_.equals(A));
        assert (root.equals(new GameStateGraphNode()));

        //root.linkStateNode(A);
        //root.linkStateNode(B);
        //root.linkStateNode(AB);

        Set<GameStateGraphNode> source = new HashSet<>();
        source.add(A);
        source.add(B);
        source.add(AB);
        source.add(root);

        assert (source.contains(A_));

        assert (A.isDescendentOf(root));
        assert (!A.isDescendentOf(B));
        assert (!B.isDescendentOf(A));
        assert(AB.isDescendentOf(A));

        //root.printGraph(0);
        GameStateGraphNode.validateGraph(root, source);
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
        GameStateGraphNode ACD = new GameStateGraphNode();
        ACD.cardsBattleField.addCardState(new CardState("A"));
        ACD.cardsBattleField.addCardState(new CardState("C"));
        ACD.cardsBattleField.addCardState(new CardState("D"));


        GameStateGraphNode A_ = GameStateGraphNode.GetLargestSharedSubset(AC, AB);
        assert (A.equals(A_));
        assert (GameStateGraphNode.GetLargestSharedSubset(ACD, AC).equals(AC));
        Set<GameStateGraphNode> sourceLeaves = new HashSet<>();
        sourceLeaves.add(AB);
        sourceLeaves.add(BC);
        sourceLeaves.add(AC);
        sourceLeaves.add(ACD);
        sourceLeaves.add(root);
        assert (sourceLeaves.size() == 5);
        assert (!sourceLeaves.contains(A));
        assert (GameStateGraphNode.GetLargestSharedSubset(AB, AC).equals(A));
        GameStateGraphNode.validateGraph(root, sourceLeaves);

        root.linkStateNode(AB);
        root.linkStateNode(BC);
        root.linkStateNode(AC);
        root.linkStateNode(ACD);
        assert(root.contains(AC) != null);
        //root.printGraph(0);
        System.out.println("Hello World!");
    }
    @Test
    public void test_Graph_Simple3() {
        // both must kill x2 bears by x2 bolts
        GameStateGraphNode root = new GameStateGraphNode();


        GameStateGraphNode ABC = GameStateGraphNode.quickMakeGraphNode(Arrays.asList("A", "B", "C"));
        GameStateGraphNode BCDE = GameStateGraphNode.quickMakeGraphNode(Arrays.asList("D", "B", "C", "E"));
        GameStateGraphNode DEFG = GameStateGraphNode.quickMakeGraphNode(Arrays.asList("D", "E", "F", "G"));
        GameStateGraphNode FGHI = GameStateGraphNode.quickMakeGraphNode(Arrays.asList("H", "I", "F", "G"));
        GameStateGraphNode AFGHI = GameStateGraphNode.quickMakeGraphNode(Arrays.asList("H", "I", "F", "G", "A"));



        root.linkStateNode(ABC);
        root.linkStateNode(BCDE);
        root.linkStateNode(DEFG);
        root.linkStateNode(FGHI);
        root.linkStateNode(AFGHI);


        root.printGraph(0);
        System.out.println("Hello World!");
    }
    @Test
    public void test_Graph_triangle() {
        // both must kill x2 bears by x2 bolts
        GameStateGraphNode root = new GameStateGraphNode();


//        GameStateGraphNode I = GameStateGraphNode.quickMakeGraphNode(Arrays.asList("I"));
//        GameStateGraphNode J = GameStateGraphNode.quickMakeGraphNode(Arrays.asList("J"));
//
//        GameStateGraphNode HI = GameStateGraphNode.quickMakeGraphNode(Arrays.asList("D", "E", "F", "G"));
//        GameStateGraphNode IJ = GameStateGraphNode.quickMakeGraphNode(Arrays.asList("H", "I", "F", "G"));
//        GameStateGraphNode JK = GameStateGraphNode.quickMakeGraphNode(Arrays.asList("H", "I", "F", "G"));
//
//        GameStateGraphNode GHI = GameStateGraphNode.quickMakeGraphNode(Arrays.asList("H", "I", "F", "G"));
//        GameStateGraphNode HIJ = GameStateGraphNode.quickMakeGraphNode(Arrays.asList("H", "I", "F", "G", "A"));
//        GameStateGraphNode IJK = GameStateGraphNode.quickMakeGraphNode(Arrays.asList("H", "I", "F", "G"));
//        GameStateGraphNode JKL = GameStateGraphNode.quickMakeGraphNode(Arrays.asList("H", "I", "F", "G"));

        GameStateGraphNode FGHI = GameStateGraphNode.quickMakeGraphNode(Arrays.asList("F", "G", "H", "I"));
        GameStateGraphNode GHIJ = GameStateGraphNode.quickMakeGraphNode(Arrays.asList("G", "H", "I", "J"));
        GameStateGraphNode HIJK = GameStateGraphNode.quickMakeGraphNode(Arrays.asList("H", "I", "J", "K"));
        GameStateGraphNode IJKL = GameStateGraphNode.quickMakeGraphNode(Arrays.asList("I", "J", "K", "L"));
        GameStateGraphNode JKLM = GameStateGraphNode.quickMakeGraphNode(Arrays.asList("J", "K", "L", "M"));
        GameStateGraphNode KLMN = GameStateGraphNode.quickMakeGraphNode(Arrays.asList("K", "L", "M", "N"));



        root.linkStateNode(FGHI);
        root.linkStateNode(GHIJ);
        root.linkStateNode(HIJK);
        root.linkStateNode(IJKL);
        root.linkStateNode(JKLM);
        //root.linkStateNode(KLMN);


        root.printGraph(0);
        System.out.println("Hello World!");
    }


}
