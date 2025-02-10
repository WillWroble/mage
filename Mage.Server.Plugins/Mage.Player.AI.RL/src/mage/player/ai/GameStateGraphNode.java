package mage.player.ai;


import java.util.*;

public class GameStateGraphNode implements Comparable{
    GameStateGraphNode opponentNode;
    private HashedZone cardsStack = new HashedZone();
    public HashedZone cardsHand = new HashedZone();
    public HashedZone cardsBattleField = new HashedZone();
    public HashedZone cardsGraveyard = new HashedZone();
    private HashedZone cardsExile = new HashedZone();
    private HashedZone cardsLibrary = new HashedZone();
    private final Map<GameStateGraphNode, Float> parents = new HashMap<>();;
    

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
        System.out.printf("Battlefield: ");
        for(Integer hash : cardsBattleField.getKeySet()) {
            System.out.printf("%s ", cardsBattleField.getCardStatesByKey(hash).get(0).cardName);
        }
        System.out.printf("\nHand: \n");
        for(Integer hash : cardsHand.getKeySet()) {
            System.out.printf("%s ", cardsHand.getCardStatesByKey(hash).get(0).cardName);
        }
        System.out.printf("\n%d CHILDREN ===================================================\n\n", children.size());
        for(GameStateGraphNode c : children.keySet()) {
            c.printGraph(depth+1);
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
    public static void getAllLeafNodes(GameStateGraphNode root, GameStateGraphNode parent, Set<GameStateGraphNode> out) {
        boolean isLeaf = true;
        for(GameStateGraphNode child : root.children.keySet()) {
            if(!child.isDescendentOf(parent)) {
                //System.out.println("FFFSHELLO");
                getAllLeafNodes(child, parent, out);
                isLeaf = false;
            }
        }
        if(isLeaf) {
            out.add(root);
        }
    }

    /**
     * Heavily modifies state network to accommodate another leaf node. This process includes breaking the new node
     * into stems which are also added to the network
     */
    public void linkStateNode(GameStateGraphNode newNode) {
        PriorityQueue<GameStateGraphNode> newNodes = new PriorityQueue<>();
        newNodes.add(newNode);
        //new nodes should be sorted so children go before parents
        while ((newNode = newNodes.poll()) != null) {
            Set<GameStateGraphNode> leaves = new HashSet<>();
            getAllLeafNodes(this, newNode, leaves);
            for(GameStateGraphNode leaf : leaves) {

                //leaf.printGraph( 0);
                GameStateGraphNode sharedNode = GetLargestSharedSubset(leaf, newNode);
                GameStateGraphNode foundNode = this.contains(sharedNode);

                Set<GameStateGraphNode> oldestChildrenOfNewNode = new HashSet<>();
                getOldestChildren(newNode, sharedNode, oldestChildrenOfNewNode);

                if (foundNode != null) {
                    foundNode.addChildren(oldestChildrenOfNewNode, 1f);
                    continue;
                }

                Set<GameStateGraphNode> oldestChildrenOfLeaf = new HashSet<>();
                getOldestChildren(leaf, sharedNode, oldestChildrenOfLeaf);

                for(GameStateGraphNode oldLeaf : oldestChildrenOfLeaf) {
                    for(GameStateGraphNode parent : oldLeaf.parents.keySet()) {
                        if(sharedNode.isDescendentOf(parent)) {
                            parent.removeChild(oldLeaf);
                        }
                    }
                }

                sharedNode.addChildren(oldestChildrenOfLeaf, 1f);
                sharedNode.addChildren(oldestChildrenOfNewNode, 1f);
                newNodes.add(sharedNode);
                //LinkStateNode(sharedNode);
            }
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
