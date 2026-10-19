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
        final long initialHash; // oldHash before getStateHash's rounds
        public enum Type {
            PLAYER, ZONE, STACK_OBJECT, PERMANENT, CARD, ABILITY, LEAF
        }
        HashMap<UUID, String> children; // while the encoder builds the graph; null once frozen
        UUID key; // the key the node is stored under
        // once frozen: the children map as arrays (child nodes and edge labels, in map order)
        Node[] childNodes;
        String[] childEdges;
        int index; // position in getStateHash's arrays
        public Node(String n, int i) {
            name = n;
            id = i;
            oldHash = i;
            initialHash = oldHash;
            hash = oldHash;
            children = new HashMap<>();
        }
        public Node(String n, int i, int v) {
            name = n;
            id = i;
            value = v;
            oldHash = StateEncoder.mix64(id) ^ value;
            initialHash = oldHash;
            hash = oldHash;
            children = new HashMap<>();
        }
    }
    // while building: the nodes by key; a frozen graph keeps only the node array and builds the map on demand
    private HashMap<UUID, Node> state;
    private Node[] nodes = null;
    private GraphArrays graphArrays = null;
    private long stateHash = -1;
    private long labelSum;
    private boolean hasLabelSum = false;

    public FeatureGraph() {
        state = new HashMap<UUID, Node>();
        put(GAME_ROOT_ID, new Node("root", 0, 0));
    }
    public FeatureGraph(FeatureGraph fg) {
        state = fg.nodes == null ? new HashMap<>(fg.state) : null;
        nodes = fg.nodes;
        graphArrays = fg.graphArrays;
        stateHash = fg.stateHash;
        labelSum = fg.labelSum;
        hasLabelSum = fg.hasLabelSum;
    }

    /**
     * Copy of this graph that keeps its nodes compactly (arrays instead of maps), for graphs that are kept but no
     * longer built: the result of StateEncoder.processState. Node values, hashes and graph arrays stay the same.
     * The nodes are shared, so this graph must not be built further (StateEncoder clears it first).
     */
    public FeatureGraph freeze() {
        Node[] frozen = state.values().toArray(new Node[0]);
        for (Node node : frozen) {
            node.childNodes = new Node[node.children.size()];
            node.childEdges = new String[node.childNodes.length];
            int i = 0;
            for (Map.Entry<UUID, String> child : node.children.entrySet()) {
                node.childNodes[i] = state.get(child.getKey());
                node.childEdges[i++] = child.getValue();
            }
            node.children = null;
        }
        FeatureGraph graph = new FeatureGraph(this);
        graph.state = null;
        graph.nodes = frozen;
        return graph;
    }

    private Collection<Node> nodes() {
        return nodes == null ? state.values() : Arrays.asList(nodes);
    }

    private static int childCount(Node node) {
        return node.children != null ? node.children.size() : node.childNodes.length;
    }

    private Map<UUID, Node> byKey() {
        if (state == null) { // frozen: only for the rare lookups
            state = new HashMap<>(nodes.length);
            for (Node node : nodes) {
                state.put(node.key, node);
            }
        }
        return state;
    }

    /**
     * Cheap summary of the nodes' labels and child counts (the input of getStateHash's first round). Graphs with
     * different sums differ, so their state hashes differ too, except for a 64-bit hash collision: compare it
     * first to skip computing getStateHash for most comparisons.
     */
    public long getLabelSum() {
        if (!hasLabelSum) {
            long sum = 0;
            for (Node node : nodes()) {
                sum += StateEncoder.mix64(node.initialHash ^ ((long) childCount(node) << 32));
            }
            labelSum = sum;
            hasLabelSum = true;
        }
        return labelSum;
    }

    public void put(UUID uuid, Node node) {
        node.key = uuid;
        state.put(uuid, node);
    }
    public Node get(UUID uuid) {
        return byKey().get(uuid);
    }
    public boolean containsKey(UUID uuid) {
        return byKey().containsKey(uuid);
    }
    public void clear() {
        state.clear();
        put(GAME_ROOT_ID, new Node("root", 0, 0));
        stateHash = -1;
        hasLabelSum = false;
        graphArrays = null;
    }
    public long getStateHash() {
        if (stateHash == -1) {
            // flat arrays: edge label hashes and child lookups don't change between rounds, so resolve them once
            Node[] nodes = nodes().toArray(new Node[0]);
            for (int i = 0; i < nodes.length; i++) {
                nodes[i].index = i;
            }
            Map<String, Long> labelHashes = new HashMap<>();
            int[][] children = new int[nodes.length][];
            long[][] labels = new long[nodes.length][];
            long[] oldHash = new long[nodes.length];
            for (int n = 0; n < nodes.length; n++) {
                Node node = nodes[n];
                Node[] childNodes = childNodes(node);
                String[] childEdges = childEdges(node);
                int[] c = new int[childNodes.length];
                long[] l = new long[c.length];
                for (int i = 0; i < c.length; i++) {
                    c[i] = childNodes[i].index;
                    l[i] = labelHashes.computeIfAbsent(childEdges[i], StateEncoder::hash64);
                }
                children[n] = c;
                labels[n] = l;
                oldHash[n] = node.oldHash;
            }
            long[] hash = new long[nodes.length];
            for (int round = 0; round < 16; round++) {
                for (int n = 0; n < nodes.length; n++) {
                    int[] c = children[n];
                    long[] l = labels[n];
                    long h = 0;
                    for (int i = 0; i < c.length; i++) {
                        h += (oldHash[c[i]] ^ l[i]);
                    }
                    hash[n] = StateEncoder.mix64(h ^ oldHash[n]);
                }
                long[] t = oldHash;
                oldHash = hash;
                hash = t;
            }
            stateHash = 0;
            for (int n = 0; n < nodes.length; n++) {
                nodes[n].hash = oldHash[n];
                nodes[n].oldHash = oldHash[n];
                stateHash += oldHash[n];
            }
            stateHash = StateEncoder.mix64(stateHash);
        }
        return stateHash;
    }

    private Node[] childNodes(Node node) {
        if (node.children == null) {
            return node.childNodes;
        }
        Node[] childNodes = new Node[node.children.size()];
        int i = 0;
        for (UUID childId : node.children.keySet()) {
            childNodes[i++] = state.get(childId);
        }
        return childNodes;
    }

    private static String[] childEdges(Node node) {
        return node.children == null ? node.childEdges : node.children.values().toArray(new String[0]);
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
        List<UUID> order = new ArrayList<>(byKey().keySet());
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
            Node node = get(order.get(i));
            ids[i] = node.id;
            values[i] = node.value;
            edgeCount += childCount(node);
        }

        // edges
        int[] edgeChild = new int[edgeCount];
        int[] edgeParent = new int[edgeCount];
        int[] edgeLabel = new int[edgeCount];
        int e = 0;
        for (int i = 0; i < n; i++) {
            Node node = get(order.get(i));
            Node[] childNodes = childNodes(node);
            String[] childEdges = childEdges(node);
            for (int c = 0; c < childNodes.length; c++) {
                Integer childIndex = childNodes[c] == null ? null : localIndex.get(childNodes[c].key);
                if (childIndex == null) {
                    // addFeature always creates the node before the edge, so this is an encoder bug
                    throw new IllegalStateException("edge to missing node");
                }
                edgeChild[e] = childIndex;
                edgeParent[e] = i;
                edgeLabel[e] = StateEncoder.indexFor(StateEncoder.hash64(childEdges[c]));
                e++;
            }
        }

        graphArrays = new GraphArrays(order, localIndex, ids, values, edgeChild, edgeParent, edgeLabel);
        return graphArrays;
    }

}
