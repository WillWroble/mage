package mage.player.ai;


import javafx.util.Pair;
import mage.game.Game;
import org.jboss.util.collection.CollectionsUtil;
import org.apache.commons.*;

import java.util.*;

public class GameStateGraphNode {
    GameStateGraphNode opponentNode;
    private HashSet<CardState> cardsStack = new HashSet<>();
    private HashSet<CardState> cardsHand = new HashSet<>();
    private HashSet<CardState> cardsBattleField = new HashSet<>();
    private HashSet<CardState> cardsGraveyard = new HashSet<>();
    private HashSet<CardState> cardsExile = new HashSet<>();
    private HashSet<CardState> cardsLibrary = new HashSet<>();
    private final List<Pair<GameStateGraphNode, Float>> parents = new ArrayList<>();;
    

    private final List<Pair<GameStateGraphNode, Float>> children = new ArrayList<>();
    public static GameStateGraphNode ROOT;
   


    private int lifeTotal = 0;
    private int numParents = 0;
    private int numChildren = 0;


    GameStateGraphNode(HashSet<CardState> stack, HashSet<CardState> hand, HashSet<CardState> battlefield, HashSet<CardState> graveyard,
                       HashSet<CardState> exile, HashSet<CardState> library, int life, GameStateGraphNode opponent) {
        cardsStack = stack;
        cardsHand = hand;
        cardsBattleField = battlefield;
        cardsGraveyard = graveyard;
        cardsExile = exile;
        cardsLibrary = library;
        opponentNode = opponent;
        lifeTotal = life;

    }
    private static GameStateGraphNode GetLargestSharedSubset(GameStateGraphNode A, GameStateGraphNode B) {
        HashSet<CardState> sharedBattleField = new HashSet<>(A.cardsBattleField);
        sharedBattleField.retainAll(B.cardsBattleField);
        HashSet<CardState> sharedHand = new HashSet<>(A.cardsHand);
        sharedHand.retainAll(B.cardsHand);
        HashSet<CardState> sharedLibrary = new HashSet<>(A.cardsLibrary);
        sharedLibrary.retainAll(B.cardsLibrary);
        HashSet<CardState> sharedExile = new HashSet<>(A.cardsExile);
        sharedExile.retainAll(B.cardsExile);
        HashSet<CardState> sharedGraveyard = new HashSet<>(A.cardsGraveyard);
        sharedGraveyard.retainAll(B.cardsGraveyard);
        HashSet<CardState> sharedStack = new HashSet<>(A.cardsStack);
        sharedStack.retainAll(B.cardsStack);
        int lowestLife = Math.min(A.lifeTotal, B.lifeTotal);

        HashSet<CardState> opponentCombinedBattlefield = new HashSet<>(A.opponentNode.cardsBattleField);
        opponentCombinedBattlefield.addAll(B.opponentNode.cardsBattleField);
        HashSet<CardState> opponentCombinedHand = new HashSet<>(A.opponentNode.cardsHand);
        opponentCombinedHand.addAll(B.opponentNode.cardsHand);
        HashSet<CardState> opponentCombinedLibrary = new HashSet<>(A.opponentNode.cardsLibrary);
        opponentCombinedLibrary.addAll(B.opponentNode.cardsLibrary);
        HashSet<CardState> opponentCombinedExile = new HashSet<>(A.opponentNode.cardsExile);
        opponentCombinedExile.addAll(B.opponentNode.cardsExile);
        HashSet<CardState> opponentCombinedGraveyard = new HashSet<>(A.opponentNode.cardsGraveyard);
        opponentCombinedGraveyard.addAll(B.opponentNode.cardsGraveyard);
        HashSet<CardState> opponentCombinedStack = new HashSet<>(A.opponentNode.cardsStack);
        opponentCombinedStack.addAll(B.opponentNode.cardsStack);
        int opMaxLife = Math.max(A.opponentNode.lifeTotal, B.opponentNode.lifeTotal);

        GameStateGraphNode newNode = null;
        GameStateGraphNode newOpNode = null;
        newNode = new GameStateGraphNode(sharedStack, sharedHand, sharedBattleField, sharedGraveyard, sharedExile, sharedLibrary, lowestLife, newOpNode);
        newOpNode = new GameStateGraphNode(opponentCombinedStack, opponentCombinedHand,
                opponentCombinedBattlefield, opponentCombinedGraveyard, opponentCombinedExile, opponentCombinedLibrary, opMaxLife, newNode);
        return newNode;

    }
    boolean Equals(GameStateGraphNode node) {
        return (cardsBattleField.equals(node.cardsBattleField) &&
                cardsGraveyard.equals(node.cardsGraveyard) &&
                cardsHand.equals(node.cardsHand) &&
                cardsLibrary.equals(node.cardsLibrary) &&
                cardsExile.equals(node.cardsExile) &&
                cardsStack.equals(node.cardsStack) &&
                lifeTotal == node.lifeTotal &&
                opponentNode.Equals(node.opponentNode));
    }
    /**this = root
     * @param node node to look for
     * @return reference to matching node if found, null otherwise
     */
    GameStateGraphNode Contains(GameStateGraphNode node) {
        if(!node.isChildOf(this)) {
            return null;
        }
        if(this.Equals(node)) {
            return this;
        }
        for(Pair<GameStateGraphNode, Float> p : children) {
            GameStateGraphNode child = p.getKey();
            GameStateGraphNode out = child.Contains(node);
            if(out != null) {
                return out;
            }
        }
        return null;
    }
    boolean isChildOf(GameStateGraphNode node) {
        return node.cardsBattleField.containsAll(cardsBattleField) &&
                node.cardsHand.containsAll(cardsBattleField) &&
                node.cardsGraveyard.containsAll(cardsBattleField) &&
                node.cardsLibrary.containsAll(cardsBattleField) &&
                node.cardsExile.containsAll(cardsExile) &&
                node.cardsStack.containsAll(cardsBattleField);
    }
    void AddChild(GameStateGraphNode child, Float weight) {
        Pair<GameStateGraphNode, Float> edge = new Pair<>(child, weight);
        Pair<GameStateGraphNode, Float> backEdge = new Pair<>(this, weight);
        child.parents.add(backEdge);
        children.add(edge);
    }
    /**
     * finds all leaf nodes in a graph where children of given parent node are removed
     * @param root root of the graph to look for leaf node in
     * @param parent ignore all nodes that are children of this node
     * @param out set that all found leaves are added to.
     */
    public void GetAllLeafNodes(GameStateGraphNode root, GameStateGraphNode parent, Set<GameStateGraphNode> out) {
        boolean isLeaf = true;
        for(Pair<GameStateGraphNode, Float> p : root.children) {
            GameStateGraphNode child = p.getKey();
            if(!child.isChildOf(parent)) {
                GetAllLeafNodes(child, parent, out);
                isLeaf = false;
            }
        }
        if(isLeaf) {
            out.add(this);
        }
    }

    /**
     * Heavily modifies state network to accommodate another leaf node. This process includes breaking the new node
     * into stems which are also added to the network
     * @param newNode new leaf node to add to the network. Should generally be a high complexity naturally created game state
     */
    public void LinkStateNode(GameStateGraphNode newNode) {
        Set<GameStateGraphNode> leaves = new HashSet<>();
        GetAllLeafNodes(this, newNode, leaves);
        for(GameStateGraphNode leaf : leaves) {
            GameStateGraphNode sharedNode = GetLargestSharedSubset(leaf, newNode);
            GameStateGraphNode foundNode = this.Contains(sharedNode);
            if(foundNode != null) {
                foundNode.AddChild(leaf, 1f);
                continue;
            }
            sharedNode.AddChild(leaf, 1f);
            sharedNode.AddChild(newNode, 1f);
            LinkStateNode(sharedNode);
        }
    }
}
