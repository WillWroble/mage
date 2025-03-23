package mage.player.ai;


import mage.game.permanent.Battlefield;

import java.util.*;
/**
 * Node for the Game state graph which is the main engine behind state evaluation.
 * The game state graph is a massive Hasse Diagram containing all relevant set intersections between provided game states
 * this graph is weighted in tuned to produce deck-specific evaluation scores
 *
 * @author willwroble
 */
public class GameStateGraphNode implements Comparable{


    private Battlefield battlefield;


    GameStateGraphNode opponentNode;
    private HashedZone cardsStack = new HashedZone();
    public HashedZone cardsHand = new HashedZone();
    public HashedZone cardsBattleField = new HashedZone();
    public HashedZone cardsGraveyard = new HashedZone();
    private HashedZone cardsExile = new HashedZone();
    private HashedZone cardsLibrary = new HashedZone();
    private final Map<GameStateGraphNode, Float> parents = new HashMap<>();
    private final Set<GameStateGraphNode> parentsShouldNotCompare = new HashSet<>();
    

    private final Map<GameStateGraphNode, Float> children = new HashMap<GameStateGraphNode, Float>();
    public static GameStateGraphNode ROOT;
   


    private int lifeTotal = 0;
    private int numParents = 0;
    private int numChildren = 0;

    public GameStateGraphNode() {
        cardsBattleField = new HashedZone();
        cardsHand = new HashedZone();
        cardsGraveyard = new HashedZone();
        cardsLibrary = new HashedZone();
        cardsExile = new HashedZone();
        cardsStack = new HashedZone();
        opponentNode = null;
        lifeTotal = 0;
        parentsShouldNotCompare.add(this);
    }
    public GameStateGraphNode(HashedZone stack, HashedZone hand, HashedZone battlefield, HashedZone graveyard,
                       HashedZone exile, HashedZone library, int life, GameStateGraphNode opponent) {
        cardsStack = stack;
        cardsHand = hand;
        cardsBattleField = battlefield;
        cardsGraveyard = graveyard;
        cardsExile = exile;
        cardsLibrary = library;
        opponentNode = opponent;
        lifeTotal = life;
        parentsShouldNotCompare.add(this);

    }
    public static GameStateGraphNode GetLargestSharedSubset(GameStateGraphNode A, GameStateGraphNode B) {
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
    @Override
    public boolean equals(Object obj) {
        if(obj.getClass() == GameStateGraphNode.class) {
            return equals((GameStateGraphNode) obj);
        }
        return super.equals(obj);
    }

    @Override
    public int hashCode() {
        return cardsBattleField.hashCode()+cardsGraveyard.hashCode()
                +cardsHand.hashCode()+cardsExile.hashCode()+cardsStack.hashCode()+cardsLibrary.hashCode();
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
    public static GameStateGraphNode quickMakeGraphNode(List<String> names) {
        GameStateGraphNode newNode = new GameStateGraphNode();
        for(String name : names) {
            CardState newState = new CardState(name);
            newNode.cardsBattleField.addCardState(newState);
        }
        return newNode;
    }
    /**this = root
     * @param node node to look for
     * @return reference to matching node if found, null otherwise
     */
    public GameStateGraphNode contains(GameStateGraphNode node) {
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
    public boolean isDescendentOf(GameStateGraphNode node) {
        return cardsBattleField.containsAll(node.cardsBattleField) &&
                cardsHand.containsAll(node.cardsHand) &&
                cardsGraveyard.containsAll(node.cardsGraveyard) &&
                cardsLibrary.containsAll(node.cardsLibrary) &&
                cardsExile.containsAll(node.cardsExile) &&
                cardsStack.containsAll(node.cardsStack);
    }
    void addChild(GameStateGraphNode child, Float weight) {

        child.parents.put(this, weight);
        children.put(child, weight);
    }
    void addChildSafe(GameStateGraphNode child, Float weight) {
        if(this.contains(child) != null) {//make sure it isn't already connected via children
            return;
        }
        Set<GameStateGraphNode> childrenOfParent = new HashSet<>(this.children.keySet());
        for (GameStateGraphNode childOfParent : childrenOfParent) {
            if (childOfParent.isDescendentOf(child)) {
                this.removeChild(childOfParent);
            }
        }
        Set<GameStateGraphNode> parentsOfChild = new HashSet<>(child.parents.keySet());
        for (GameStateGraphNode parentOfChild : parentsOfChild) {
            if (this.isDescendentOf(parentOfChild)) {
                parentOfChild.removeChild(child);
            }
        }
        addChild(child, weight);
    }
    void addParentAbove(GameStateGraphNode parent, Float weight) {//check parent's children
        if(parent.contains(this) != null) {//make sure it isn't already connected via children
            return;
        }
        Set<GameStateGraphNode> children = new HashSet<>(parent.children.keySet());
        for (GameStateGraphNode child : children) {
            if (child.isDescendentOf(this)) {
                parent.removeChild(child);
                //this.addChild(child, 1f);
            }
        }
        parent.addChild(this, weight);
    }
    void addChildBelow(GameStateGraphNode child, Float weight) {//check child's parents
        Set<GameStateGraphNode> parents = new HashSet<>(child.parents.keySet());
        for (GameStateGraphNode parent : parents) {
            if (this.isDescendentOf(parent)) {
                parent.removeChild(child);
                //parent.addChild(this, 1f);
            }
        }
        this.addChild(child, weight);
    }
    void removeChild(GameStateGraphNode child) {
        this.children.remove(child);
        child.parents.remove(this);
    }
    void removeChildren(Set<GameStateGraphNode> children) {
        for(GameStateGraphNode child : children) {
            removeChild(child);
        }
    }
    void addChildren(Set<GameStateGraphNode> children, Float weight) {
        for(GameStateGraphNode child : children) {
            addChild(child, weight);
        }
    }
    public void printGraph(int depth) {
        System.out.printf("Node depth: %d Life total: %d\n", depth, lifeTotal);
        printNode();
        for(GameStateGraphNode c : children.keySet()) {
            c.printGraph(depth+1);
        }
    }
    public void printNode() {
        System.out.printf("Battlefield: ");
        for(Integer hash : cardsBattleField.getKeySet()) {
            System.out.printf("%s ", cardsBattleField.getCardStatesByKey(hash).get(0).cardName);
        }
        System.out.printf("\nHand: \n");
        for(Integer hash : cardsHand.getKeySet()) {
            System.out.printf("%s ", cardsHand.getCardStatesByKey(hash).get(0).cardName);
        }
        System.out.printf("\n%d CHILDREN ===================================================\n\n", children.size());
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
            if(parent.isDescendentOf(grandParent) && !parent.equals(grandParent)) {
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
     * @param out set that all found leaves are added to.
     */
    public static void getAllLeafNodes(GameStateGraphNode root, Set<GameStateGraphNode> out, Set<GameStateGraphNode> removedLeaves) {
        boolean isLeaf = true;
        for(GameStateGraphNode child : root.children.keySet()) {
            if(!removedLeaves.contains(child)) {
                getAllLeafNodes(child, out, removedLeaves);
                isLeaf = false;
            }
        }
        if(isLeaf) {
            out.add(root);
        }
    }
    public static void getRemovedLeavesFromChildren(GameStateGraphNode parent, Set<GameStateGraphNode> out) {
        for(GameStateGraphNode child : parent.children.keySet()) {
            getRemovedLeavesFromChildren(child, out);
        }
        out.addAll(parent.parentsShouldNotCompare);
    }
    public static GameStateGraphNode treeSearch(TreeSet<GameStateGraphNode> treeset, GameStateGraphNode key) {
        GameStateGraphNode ceil  = treeset.ceiling(key); // least elt >= key
        GameStateGraphNode floor = treeset.floor(key);   // highest elt <= key
        return ceil == floor? ceil : null;
    }
    public void clearAllHistory() {
        for(GameStateGraphNode child : children.keySet()) {
            child.clearAllHistory();
        }
        parentsShouldNotCompare.clear();
        parentsShouldNotCompare.add(this);
    }
    /**
     * Heavily modifies state network to accommodate another leaf node. This process includes calculating
     * intersections between every existing node and adding them to the graph
     */
    public void linkStateNode(GameStateGraphNode newNode) {
        //this.clearAllHistory();

        PriorityQueue<GameStateGraphNode> newNodes = new PriorityQueue<>();
        this.addChild(newNode, 1f);
        newNodes.add(newNode);
        Set<GameStateGraphNode> newlyGenerated = new HashSet<>();
        //new nodes should be sorted so children go before parents
        while ((newNode = newNodes.poll()) != null) {
            //System.out.println(newNodes.size());
            Set<GameStateGraphNode> leaves = new HashSet<>();

            Set<GameStateGraphNode> allRemoved = new HashSet<>();
            getRemovedLeavesFromChildren(newNode, allRemoved);
            allRemoved.addAll(newlyGenerated);

            getAllLeafNodes(this, leaves, allRemoved);

            for(GameStateGraphNode leaf : leaves) {
                assert (newNode != leaf);

                GameStateGraphNode sharedNode = GetLargestSharedSubset(leaf, newNode);
                GameStateGraphNode foundNode = this.contains(sharedNode);
                if (foundNode != null) {
                    //link to an existing node
                    foundNode.addChildSafe(newNode, 1f);
                    foundNode.addChildSafe(leaf, 1f);

                } else {
                    //new node cannot possibly already be in the queue by this point (or it would have been found on the graph)
                    newNodes.add(sharedNode);
                    sharedNode.addChildSafe(leaf, 1f);
                    sharedNode.addChildSafe(newNode, 1f);
                    //link to root temporarily, so it can be found by the leaf retriever
                    this.addChild(sharedNode, 1f);
                    //new internodes derived from the same newnode don't need to compare to each other
                    newlyGenerated.add(sharedNode);
                }
                if (!newNode.isDescendentOf(leaf)) { //don't ignore the leaf if it is equal to the comparison's shared set and vice versa(leaf is parent)
                    newNode.parentsShouldNotCompare.add(leaf);
                }
                if(!leaf.isDescendentOf(newNode)) {
                    leaf.parentsShouldNotCompare.add(newNode);
                }
            }
        }
    }
    public static void validateGraph(GameStateGraphNode root, Set<GameStateGraphNode> sourceLeaves) {
        PriorityQueue<GameStateGraphNode> allNodes = new PriorityQueue<>(sourceLeaves);
        assert (allNodes.size() == sourceLeaves.size());
        Set<GameStateGraphNode> out = new HashSet<>(sourceLeaves);
        GameStateGraphNode first;
        while((first = allNodes.poll()) != null) {
            Set<GameStateGraphNode> leaves = new HashSet<>(allNodes);
            for(GameStateGraphNode leaf : leaves) {
                GameStateGraphNode sharedNode = GetLargestSharedSubset(first, leaf);
                if(!out.contains(sharedNode)) {
                    allNodes.add(sharedNode);
                    out.add(sharedNode);
                }
            }
        }
        for(GameStateGraphNode n : out) {
            n.printNode();
        }
    }
    @Override
    public int compareTo(Object o) {
        GameStateGraphNode o2 = (GameStateGraphNode)o;
        GameStateGraphNode o1 = this;
        if(o1.isDescendentOf(o2)) {
            return -1;
        } else if(o2.isDescendentOf(o1)) {
            return 1;
        }
        return 0;
    }
}
