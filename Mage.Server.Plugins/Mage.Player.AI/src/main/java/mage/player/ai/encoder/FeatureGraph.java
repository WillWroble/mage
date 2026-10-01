package mage.player.ai.encoder;

import java.util.*;

import static mage.player.ai.encoder.StateEncoder.stringToUUID;

public class FeatureGraph {
    public static final UUID BATTLEFIELD_ID = stringToUUID("BattlefieldId");
    public static final UUID GRAVEYARD_ID = stringToUUID("GraveyardId");
    public static final UUID HAND_ID = stringToUUID("HandId");
    public static final UUID EXILE_ID = stringToUUID("ExileId");
    public static final UUID STACK_ID = stringToUUID("StackId");
    public static final UUID COMMAND_ZONE_ID = stringToUUID("CommandZoneId");
    public static final UUID GAME_ROOT_ID = stringToUUID("GameRootId");
    public static final UUID PASS_ABILITY_ID = new UUID(0, "Pass".hashCode());
    public static final UUID USE_TRUE_ID = stringToUUID("UseTrueId");
    public static final UUID USE_FALSE_ID = stringToUUID("UseFalseId");


    public static class Node {
        public String name;
        public int id;
        public int value = 0;
        public long hash = -1;
        public long oldHash = -1;
        public enum Type {
            PLAYER, ZONE, STACK_OBJECT, PERMANENT, CARD, ABILITY, LEAF
        }
        HashMap<UUID, String> children;
        public Node(String n, int i) {
            name = n;
            id = i;
            oldHash = i;
            hash = oldHash;
            children = new HashMap<>();
        }
        public Node(String n, int i, int v) {
            name = n;
            id = i;
            value = v;
            oldHash = StateEncoder.mix64(id) ^ value;
            hash = oldHash;
            children = new HashMap<>();
        }
    }
    private final HashMap<UUID, Node> state;
    private GraphArrays graphArrays = null;
    private long stateHash = -1;

    public FeatureGraph() {
        state = new HashMap<UUID, Node>();
        state.put(GAME_ROOT_ID, new Node("root", 0, 0));
    }
    public FeatureGraph(FeatureGraph fg) {
        state = new HashMap<UUID, Node>(fg.state);
        graphArrays = fg.graphArrays;
        stateHash = fg.stateHash;
    }

    public void put(UUID uuid, Node node) {
        state.put(uuid, node);
    }
    public Node get(UUID uuid) {
        return state.get(uuid);
    }
    public boolean containsKey(UUID uuid) {
        return state.containsKey(uuid);
    }
    public void clear() {
        state.clear();
        state.put(GAME_ROOT_ID, new Node("root", 0, 0));
        stateHash = -1;
        graphArrays = null;
    }
    public void mixHash() {
        for (Node node : state.values()) {
            long h = 0;
            for (Map.Entry<UUID, String> entry : node.children.entrySet()) {
                h += (state.get(entry.getKey()).oldHash ^ StateEncoder.hash64(entry.getValue()));
            }
            node.hash = StateEncoder.mix64(h ^ node.oldHash);
        }
        for (UUID nodeId : state.keySet()) {
            state.get(nodeId).oldHash = state.get(nodeId).hash;
        }
    }
    public long getStateHash() {
        if (stateHash == -1) {
            for (int i = 0; i < 16; i++) {
                mixHash();
            }
            stateHash = 0;
            for (UUID nodeId : state.keySet()) {
                stateHash += state.get(nodeId).hash;
            }
            stateHash = StateEncoder.mix64(stateHash);
        }
        return stateHash;
    }
    /**
     * Array view of the graph for serialization (training data and inference requests).
     * Nodes get local indices 0..n-1 in sorted UUID order; edges refer to those indices.
     */
    public static class GraphArrays {
        public final List<UUID> order;               // local index -> UUID
        public final Map<UUID, Integer> localIndex;  // UUID -> local index
        public final int[] ids;                      // per node: Node.id
        public final int[] values;                   // per node: Node.value
        public final int[] edgeChild;                // per edge: local index of child
        public final int[] edgeParent;               // per edge: local index of parent
        public final int[] edgeLabel;                // per edge: hashed edge label

        GraphArrays(List<UUID> order, Map<UUID, Integer> localIndex, int[] ids, int[] values,
                    int[] edgeChild, int[] edgeParent, int[] edgeLabel) {
            this.order = order;
            this.localIndex = localIndex;
            this.ids = ids;
            this.values = values;
            this.edgeChild = edgeChild;
            this.edgeParent = edgeParent;
            this.edgeLabel = edgeLabel;
        }
    }

    public GraphArrays getGraphArrays() {
        if (graphArrays != null) {
            return graphArrays;
        }
        // sorted UUID order, so the same graph always serializes the same way
        List<UUID> order = new ArrayList<>(state.keySet());
        Collections.sort(order);
        Map<UUID, Integer> localIndex = new HashMap<>();
        for (int i = 0; i < order.size(); i++) {
            localIndex.put(order.get(i), i);
        }

        // nodes
        int n = order.size();
        int[] ids = new int[n];
        int[] values = new int[n];
        int edgeCount = 0;
        for (int i = 0; i < n; i++) {
            Node node = state.get(order.get(i));
            ids[i] = node.id;
            values[i] = node.value;
            edgeCount += node.children.size();
        }

        // edges
        int[] edgeChild = new int[edgeCount];
        int[] edgeParent = new int[edgeCount];
        int[] edgeLabel = new int[edgeCount];
        int e = 0;
        for (int i = 0; i < n; i++) {
            Node node = state.get(order.get(i));
            for (Map.Entry<UUID, String> child : node.children.entrySet()) {
                Integer childIndex = localIndex.get(child.getKey());
                if (childIndex == null) {
                    // addFeature always creates the node before the edge, so this is an encoder bug
                    throw new IllegalStateException("edge to missing node " + child.getKey());
                }
                edgeChild[e] = childIndex;
                edgeParent[e] = i;
                edgeLabel[e] = StateEncoder.indexFor(StateEncoder.hash64(child.getValue()));
                e++;
            }
        }

        graphArrays = new GraphArrays(order, localIndex, ids, values, edgeChild, edgeParent, edgeLabel);
        return graphArrays;
    }

}
