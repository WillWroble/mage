package mage.player.ai;


import javafx.util.Pair;
import mage.game.Game;
import mage.game.GameState;
import org.jboss.util.collection.CollectionsUtil;
import org.apache.commons.*;

import java.util.*;

public class GameStateGraphNode {
    GameStateGraphNode opponentNode;
    private HashedZone cardsStack = new HashedZone();
    private HashedZone cardsHand = new HashedZone();
    private HashedZone cardsBattleField = new HashedZone();
    private HashedZone cardsGraveyard = new HashedZone();
    private HashedZone cardsExile = new HashedZone();
    private HashedZone cardsLibrary = new HashedZone();
    private final Map<GameStateGraphNode, Float> parents = new HashMap<>();;
    

    private final Map<GameStateGraphNode, Float> children = new HashMap<GameStateGraphNode, Float>();
    public static GameStateGraphNode ROOT;
   


    private int lifeTotal = 0;
    private int numParents = 0;
    private int numChildren = 0;


    GameStateGraphNode(HashedZone stack, HashedZone hand, HashedZone battlefield, HashedZone graveyard,
                       HashedZone exile, HashedZone library, int life, GameStateGraphNode opponent) {
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
        HashedZone sharedBattleField = HashedZone.getIntersection(A.cardsBattleField, B.cardsBattleField);
        HashedZone sharedHand = HashedZone.getIntersection(A.cardsHand, B.cardsHand);
        HashedZone sharedGraveyard = HashedZone.getIntersection(A.cardsGraveyard, B.cardsGraveyard);
        HashedZone sharedLibrary = HashedZone.getIntersection(A.cardsLibrary, B.cardsLibrary);
        HashedZone sharedExile = HashedZone.getIntersection(A.cardsExile, B.cardsExile);
        HashedZone sharedStack = HashedZone.getIntersection(A.cardsStack, B.cardsStack);

        int lowestLife = Math.min(A.lifeTotal, B.lifeTotal);
        /*
        HashedZone opponentCombinedBattlefield = new HashedZone(A.opponentNode.cardsBattleField);
        opponentCombinedBattlefield.addAll(B.opponentNode.cardsBattleField);
        HashedZone opponentCombinedHand = new HashedZone(A.opponentNode.cardsHand);
        opponentCombinedHand.addAll(B.opponentNode.cardsHand);
        HashedZone opponentCombinedLibrary = new HashedZone(A.opponentNode.cardsLibrary);
        opponentCombinedLibrary.addAll(B.opponentNode.cardsLibrary);
        HashedZone opponentCombinedExile = new HashedZone(A.opponentNode.cardsExile);
        opponentCombinedExile.addAll(B.opponentNode.cardsExile);
        HashedZone opponentCombinedGraveyard = new HashedZone(A.opponentNode.cardsGraveyard);
        opponentCombinedGraveyard.addAll(B.opponentNode.cardsGraveyard);
        HashedZone opponentCombinedStack = new HashedZone(A.opponentNode.cardsStack);
        opponentCombinedStack.addAll(B.opponentNode.cardsStack);
        int opMaxLife = Math.max(A.opponentNode.lifeTotal, B.opponentNode.lifeTotal);
        */
        return new GameStateGraphNode(sharedStack, sharedHand, sharedBattleField, sharedGraveyard, sharedExile, sharedLibrary, lowestLife, null);
        //newNode.opponentNode = new GameStateGraphNode(opponentCombinedStack, opponentCombinedHand,
        //        opponentCombinedBattlefield, opponentCombinedGraveyard, opponentCombinedExile, opponentCombinedLibrary, opMaxLife, newNode);
        //return newNode;

    }
    boolean equals(GameStateGraphNode node) {
        return (cardsBattleField.equals(node.cardsBattleField) &&
                cardsGraveyard.equals(node.cardsGraveyard) &&
                cardsHand.equals(node.cardsHand) &&
                cardsLibrary.equals(node.cardsLibrary) &&
                cardsExile.equals(node.cardsExile) &&
                cardsStack.equals(node.cardsStack) &&
                lifeTotal == node.lifeTotal);
                //opponentNode.equals(node.opponentNode));
    }
    /**this = root
     * @param node node to look for
     * @return reference to matching node if found, null otherwise
     */
    GameStateGraphNode contains(GameStateGraphNode node) {
        if(!node.isDescendentOf(this)) {
            return null;
        }
        if(this.equals(node)) {
            return this;
        }
        for(GameStateGraphNode child : children.keySet()) {
            GameStateGraphNode out = child.contains(node);
            if(out != null) {
                return out;
            }
        }
        return null;
    }

    /**
     * returns true if this node is a descendent of the given node (A node is a descendent of itself)
     * @param node
     * @return
     */
    boolean isDescendentOf(GameStateGraphNode node) {
        return node.cardsBattleField.containsAll(cardsBattleField) &&
                node.cardsHand.containsAll(cardsBattleField) &&
                node.cardsGraveyard.containsAll(cardsBattleField) &&
                node.cardsLibrary.containsAll(cardsBattleField) &&
                node.cardsExile.containsAll(cardsExile) &&
                node.cardsStack.containsAll(cardsBattleField);
    }
    void addChild(GameStateGraphNode child, Float weight) {
        child.parents.put(this, weight);
        children.put(child, weight);
    }
    void AddChildren(Set<GameStateGraphNode> children, Float weight) {
        for(GameStateGraphNode child : children) {
            addChild(child, weight);
        }
    }

    /**
     * Gets the most immediate children of the given parent that are ancestors of the given child
     * @param child
     * @param grandParent
     * @param out
     */
    public void getOldestChildren(GameStateGraphNode child, GameStateGraphNode grandParent, Set<GameStateGraphNode> out) {
        boolean isOldestChild = true;
        for(GameStateGraphNode parent : child.parents.keySet()) {
            if(parent.isDescendentOf(grandParent)) {
                getOldestChildren(parent, grandParent, out);
                isOldestChild = false;
            }
        }
        if(isOldestChild) {
            out.add(child);
        }
    }
    /**
     * finds all leaf nodes in a graph where children of given parent node are removed
     * @param root root of the graph to look for leaf node in
     * @param parent ignore all nodes that are children of this node
     * @param out set that all found leaves are added to.
     */
    public void getAllLeafNodes(GameStateGraphNode root, GameStateGraphNode parent, Set<GameStateGraphNode> out) {
        boolean isLeaf = true;
        for(GameStateGraphNode child : root.children.keySet()) {
            if(!child.isDescendentOf(parent)) {
                getAllLeafNodes(child, parent, out);
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
     * @param newNode new leaf node to add to the network. SHOULD NOT BE A PARENT OF ANY NODE IN THE GRAPH
     * Should generally be a high complexity naturally created game state.
     */
    public void linkStateNode(GameStateGraphNode newNode) {
        PriorityQueue<GameStateGraphNode> newNodes = new PriorityQueue<>();
        newNodes.add(newNode);
        //new nodes should be sorted so children go before parents
        while ((newNode = newNodes.poll()) != null) {
            Set<GameStateGraphNode> leaves = new HashSet<>();
            getAllLeafNodes(this, newNode, leaves);
            for(GameStateGraphNode leaf : leaves) {
                GameStateGraphNode sharedNode = GetLargestSharedSubset(leaf, newNode);
                GameStateGraphNode foundNode = this.contains(sharedNode);
                Set<GameStateGraphNode> oldestChildrenOfLeaf = new HashSet<>();
                getOldestChildren(leaf, sharedNode, oldestChildrenOfLeaf);
                if (foundNode != null) {
                    foundNode.AddChildren(oldestChildrenOfLeaf, 1f);
                    continue;
                }
                Set<GameStateGraphNode> oldestChildrenOfNewNode = new HashSet<>();
                getOldestChildren(leaf, sharedNode, oldestChildrenOfNewNode);
                sharedNode.AddChildren(oldestChildrenOfLeaf, 1f);
                sharedNode.AddChildren(oldestChildrenOfNewNode, 1f);
                newNodes.add(sharedNode);
                //LinkStateNode(sharedNode);
            }
        }
    }
}
