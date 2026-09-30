package org.mage.magezero;

import ch.systemsx.cisd.hdf5.HDF5Factory;
import ch.systemsx.cisd.hdf5.HDF5FloatStorageFeatures;
import ch.systemsx.cisd.hdf5.HDF5IntStorageFeatures;
import ch.systemsx.cisd.hdf5.IHDF5Writer;
import mage.player.ai.encoder.FeatureGraph;
import mage.player.ai.encoder.LabeledState;

import java.io.Closeable;
import java.io.Flushable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Row-major HDF5 writer for MageZero graph datasets.
 *
 * Every state is a graph. Its nodes are given local indices 0..n-1 by sorting their UUIDs,
 * and edges and policy candidates refer to nodes by those local indices. No UUIDs are written.
 *
 * Layout (each per-item array is CSR-indexed by its offsets array, one entry per state + 1):
 *   /indices        : int32 [nodes]       node feature id (Node.id)
 *   /values         : int32 [nodes]       node numeric value (Node.value, 0 for non-numeric)
 *   /offsets        : int64 [N+1]         node offsets
 *   /edge_child     : int32 [edges]       local index of the child
 *   /edge_parent    : int32 [edges]       local index of the parent
 *   /edge_label     : int32 [edges]       hashed edge label
 *   /edge_offsets   : int64 [N+1]         edge offsets
 *   /policy_node    : int32 [candidates]  local index of the candidate node
 *   /policy_visits  : int32 [candidates]  MCTS visit count
 *   /policy_offsets : int64 [N+1]         candidate offsets
 *   /row            : float32 [N, 6]      resultLabel, stateScore, isPlayer, actionType, useFalseVisits, useTrueVisits
 *   /game_offsets   : int64 [G+1]         row index where each game starts
 *
 * Notes:
 * - No compression (fastest random read).
 * - CHOOSE_USE visits go in the last two /row columns because true/false are not graph nodes.
 */
public final class LabeledStateWriter implements Closeable, Flushable {

    private static final int ROW_WIDTH = 6;

    public final Set<Integer> batchFeatures = new HashSet<>();
    public int batchStates = 0;

    private final IHDF5Writer writer;

    private long nRows = 0;       // N
    private long nGames = 0;      // G
    private long nNodes = 0;      // total entries in /indices and /values
    private long nEdges = 0;      // total entries in the /edge_* arrays
    private long nCandidates = 0; // total entries in the /policy_* arrays

    public LabeledStateWriter(String path) throws IOException {
        this(path, /*rowsChunk*/2048, /*itemChunk*/1_000_000);
    }

    public LabeledStateWriter(String path, int rowsChunk, int itemChunk) throws IOException {
        try {
            this.writer = HDF5Factory.configure(path)
                    .overwrite()
                    .useUTF8CharacterEncoding()
                    .writer();

            // per-node arrays
            createItemArray("/indices", itemChunk);
            createItemArray("/values", itemChunk);
            createOffsets("/offsets", Math.max(rowsChunk, 512));

            // per-edge arrays
            createItemArray("/edge_child", itemChunk);
            createItemArray("/edge_parent", itemChunk);
            createItemArray("/edge_label", itemChunk);
            createOffsets("/edge_offsets", Math.max(rowsChunk, 512));

            // per-candidate arrays
            createItemArray("/policy_node", itemChunk);
            createItemArray("/policy_visits", itemChunk);
            createOffsets("/policy_offsets", Math.max(rowsChunk, 512));

            // game boundaries
            createOffsets("/game_offsets", 512);

            // row: 2D float32 [N, ROW_WIDTH], extendable rows, row-major chunks, uncompressed
            writer.float32().createMatrix(
                    "/row",
                    /*sizeX rows*/0L,
                    /*sizeY cols*/ROW_WIDTH,
                    /*blockX*/rowsChunk,
                    /*blockY*/ROW_WIDTH,
                    HDF5FloatStorageFeatures.FLOAT_NO_COMPRESSION
            );
        } catch (Exception e) {
            throw new IOException("Failed to initialize HDF5 writer", e);
        }
    }

    /** 1D int32, extendable, uncompressed, starts empty. */
    private void createItemArray(String name, int chunk) {
        writer.int32().createArray(name, 0L, chunk, HDF5IntStorageFeatures.INT_NO_COMPRESSION);
    }

    /** 1D int64, extendable, uncompressed, seeded with a single 0. */
    private void createOffsets(String name, int chunk) {
        writer.int64().createArray(name, 1L, chunk, HDF5IntStorageFeatures.INT_NO_COMPRESSION);
        writer.int64().writeArrayBlockWithOffset(name, new long[]{0L}, 1, 0L);
    }

    /** Append one record. */
    public synchronized void writeRecord(LabeledState s) throws IOException {
        try {
            // --- nodes and edges: same serialization the inference client uses ---
            FeatureGraph.GraphArrays g = s.stateGraph.getGraphArrays();
            int n = g.ids.length;
            int edgeCount = g.edgeChild.length;
            for (int id : g.ids) {
                batchFeatures.add(id);
            }

            // --- policy: graph-node candidates in local-index order, CHOOSE_USE into the row ---
            float useFalseVisits = 0f;
            float useTrueVisits = 0f;
            List<int[]> candidates = new ArrayList<>(); // {local index, visits}
            for (Map.Entry<UUID, Integer> action : s.actionMap.entrySet()) {
                UUID key = action.getKey();
                int visits = action.getValue();
                if (key.equals(FeatureGraph.USE_FALSE_ID)) {
                    useFalseVisits = visits;
                } else if (key.equals(FeatureGraph.USE_TRUE_ID)) {
                    useTrueVisits = visits;
                } else {
                    Integer nodeIndex = g.localIndex.get(key);
                    if (nodeIndex == null) {
                        // every candidate must be a node, or the network has nothing to score
                        throw new IllegalStateException("policy candidate is not a graph node: " + key);
                    }
                    candidates.add(new int[]{nodeIndex, visits});
                }
            }
            candidates.sort((a, b) -> Integer.compare(a[0], b[0]));
            int c = candidates.size();
            int[] policyNode = new int[c];
            int[] policyVisits = new int[c];
            for (int i = 0; i < c; i++) {
                policyNode[i] = candidates.get(i)[0];
                policyVisits[i] = candidates.get(i)[1];
            }

            // --- append items and offsets ---
            if (n > 0) {
                writer.int32().writeArrayBlockWithOffset("/indices", g.ids, n, nNodes);
                writer.int32().writeArrayBlockWithOffset("/values", g.values, n, nNodes);
                nNodes += n;
            }
            writer.int64().writeArrayBlockWithOffset("/offsets", new long[]{nNodes}, 1, nRows + 1);

            if (edgeCount > 0) {
                writer.int32().writeArrayBlockWithOffset("/edge_child", g.edgeChild, edgeCount, nEdges);
                writer.int32().writeArrayBlockWithOffset("/edge_parent", g.edgeParent, edgeCount, nEdges);
                writer.int32().writeArrayBlockWithOffset("/edge_label", g.edgeLabel, edgeCount, nEdges);
                nEdges += edgeCount;
            }
            writer.int64().writeArrayBlockWithOffset("/edge_offsets", new long[]{nEdges}, 1, nRows + 1);

            if (c > 0) {
                writer.int32().writeArrayBlockWithOffset("/policy_node", policyNode, c, nCandidates);
                writer.int32().writeArrayBlockWithOffset("/policy_visits", policyVisits, c, nCandidates);
                nCandidates += c;
            }
            writer.int64().writeArrayBlockWithOffset("/policy_offsets", new long[]{nCandidates}, 1, nRows + 1);

            // --- fixed row ---
            float[] row = new float[ROW_WIDTH];
            row[0] = (float) s.resultLabel;
            row[1] = (float) s.stateScore;
            row[2] = s.isPlayer ? 1f : 0f;
            row[3] = (float) s.actionType.ordinal();
            row[4] = useFalseVisits;
            row[5] = useTrueVisits;
            writer.float32().writeMatrixBlockWithOffset(
                    "/row",
                    new float[][]{ row },
                    /*blockSizeX*/1,
                    /*blockSizeY*/ROW_WIDTH,
                    /*offsetX*/nRows,
                    /*offsetY*/0
            );

            nRows++;
            batchStates++;
        } catch (Exception ex) {
            throw new IOException("HDF5 append failed", ex);
        }
    }

    public synchronized void endGame() throws IOException {
        try {
            writer.int64().writeArrayBlockWithOffset("/game_offsets", new long[]{ nRows }, 1, nGames + 1);
            nGames++;
        } catch (Exception e) {
            throw new IOException("HDF5 game_offsets append failed", e);
        }
    }

    @Override
    public synchronized void flush() throws IOException {
        try { writer.file().flush(); }
        catch (Exception e) { throw new IOException("HDF5 flush failed", e); }
    }

    @Override
    public synchronized void close() throws IOException {
        try { flush(); } catch (IOException ignored) {}
        try { writer.close(); } catch (Exception ignored) {}
    }
}