package mage.player.ai;

import mage.player.ai.encoder.FeatureGraph;
import okhttp3.*;
import org.msgpack.core.MessageBufferPacker;
import org.msgpack.core.MessagePack;
import org.msgpack.core.MessageUnpacker;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;
import java.util.concurrent.*;

public class RemoteModelEvaluator implements AutoCloseable {

    /**
     * Network output for one state. Policies are keyed by node UUID; MCTS looks up only its children.
     */
    public static class InferenceResult {
        public final Map<UUID, Float> policyPriority; // one score per node (read at ABILITY nodes)
        public final Map<UUID, Float> policyTarget;   // one score per node (read at target nodes)
        public final Map<UUID, Float> policyUse;      // USE_FALSE_ID, USE_TRUE_ID
        public final float value;

        public InferenceResult(Map<UUID, Float> policyPriority,
                               Map<UUID, Float> policyTarget,
                               Map<UUID, Float> policyUse,
                               float value) {
            this.policyPriority = policyPriority;
            this.policyTarget = policyTarget;
            this.policyUse = policyUse;
            this.value = value;
        }
    }

    /** How many concurrent HTTP calls are allowed. Keep small when batching is enabled. */
    public static int MAX_CONCURRENT_CALLS = 8;


    // ---------- batching controls ----------
    public static final int batchInterval = 32000;//micro seconds
    public static final int maxBatchSize = 32;

    private final OkHttpClient http;
    private final HttpUrl evalUrl;
    private final Semaphore permits;
    private final ExecutorService exec;


    /** Queue of pending requests when batching is enabled. */
    private final ConcurrentLinkedQueue<PendingReq> pending;
    /** Periodic flusher for micro-batching. */
    private final ScheduledExecutorService scheduler;

    private static final class PendingReq {
        final FeatureGraph.GraphArrays graph; // also holds the UUID order for mapping results back
        final CompletableFuture<InferenceResult> promise;
        PendingReq(FeatureGraph.GraphArrays graph) {
            this.graph = graph;
            this.promise = new CompletableFuture<>();
        }
    }

    public RemoteModelEvaluator(String baseUrl) throws IOException {
        this.permits = new Semaphore(Math.max(1, MAX_CONCURRENT_CALLS), true);
        this.http = new OkHttpClient.Builder()
                .retryOnConnectionFailure(true)
                .connectionPool(new ConnectionPool(8, 120, TimeUnit.SECONDS))
                .build();
        this.evalUrl = HttpUrl.parse(baseUrl + "/evaluate");
        if (this.evalUrl == null) {
            throw new IllegalArgumentException("Invalid baseUrl: " + baseUrl);
        }
        this.exec = Executors.newFixedThreadPool(
                Math.max(1, Runtime.getRuntime().availableProcessors() - 1));

        //connection test
        Request ping = new Request.Builder()
                .url(baseUrl + "/healthz")
                .get()
                .build();
        try (Response r = http.newCall(ping).execute()) {
            if (!r.isSuccessful()) {
                throw new IOException("Health check failed: HTTP " + r.code());
            }
        }
        this.pending = new ConcurrentLinkedQueue<>();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "RemoteModelEvaluator-BatchFlusher");
            t.setDaemon(true);
            return t;
        });
        // Flush every N ms (micro-batch cadence)
        this.scheduler.scheduleAtFixedRate(this::flushIfAny, batchInterval, batchInterval, TimeUnit.MICROSECONDS);
    }

    public RemoteModelEvaluator() throws IOException { this("http://127.0.0.1:50052"); }

    // ----------------- Public API -----------------

    public InferenceResult infer(FeatureGraph state) {

        try {
            return inferAsync(state).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted waiting for batched result", e);
        } catch (ExecutionException e) {
            throw new RuntimeException(e.getCause() != null ? e.getCause() : e);
        }
    }

    /** Asynchronous API for leaf parallelization
     * @param state
     * @return
     */
    public CompletableFuture<InferenceResult> inferAsync(FeatureGraph state) {

        PendingReq pr = new PendingReq(state.getGraphArrays());
        pending.add(pr);

        // If we just hit the max batch size, try to flush immediately (best-effort).
        if (pendingSizeApprox() >= maxBatchSize) {
            flushIfAny(); // non-blocking; if another flush is in-flight, we’ll skip via semaphore
        }
        return pr.promise;
    }

    @Override
    public void close() {
        if (scheduler != null) scheduler.shutdownNow();
        if (exec != null) exec.shutdown();
        // OkHttp cleans up via its pool.
    }

    // --------------- Internals ---------------
    /** Periodic/bounds-triggered flush. No-op if nothing pending or permit not available. */
    private void flushIfAny() {
        if (pending.isEmpty()) return;
        // Try to acquire a permit without blocking; if we can’t, another HTTP call is active.
        if (!permits.tryAcquire()) return;

        // Drain up to maxBatchSize
        List<PendingReq> batch = new ArrayList<>(maxBatchSize);
        for (int i = 0; i < maxBatchSize; i++) {
            PendingReq pr = pending.poll();
            if (pr == null) break;
            batch.add(pr);
        }

        if (batch.isEmpty()) { // nothing after all
            permits.release();
            return;
        }

        // Do the HTTP work on the executor so we don't block the scheduler thread
        exec.submit(() -> {
            try {
                runBatchedHttpCall(batch);
            } catch (Throwable t) {
                // Fail all promises in this batch
                for (PendingReq pr : batch) pr.promise.completeExceptionally(t);
            } finally {
                permits.release();
            }
        });
    }

    /**
     * Request (msgpack map), same concatenated CSR form as the HDF5 files, each array as raw little-endian int32
     * bytes (msgpack bin):
     *   indices, values           : per node, all states concatenated
     *   offsets                   : per state, start of its nodes (length B)
     *   edge_child, edge_parent,
     *   edge_label                : per edge, all states concatenated; child/parent are LOCAL to their state
     *   edge_offsets              : per state, start of its edges (length B)
     *   binary                    : true (the server then replies with raw float32 arrays)
     * Response: msgpack map of raw little-endian float32 arrays (msgpack bin):
     *   priority, target          : one float per node, all states concatenated in the order sent
     *   use                       : [false, true] per state
     *   value                     : one float per state
     */
    private void runBatchedHttpCall(List<PendingReq> batch) throws Exception {
        // 1) Build concatenated arrays & per-state start offsets
        int B = batch.size();
        int totalNodes = 0;
        int totalEdges = 0;
        for (PendingReq p : batch) {
            totalNodes += p.graph.ids.length;
            totalEdges += p.graph.edgeChild.length;
        }
        int[] ids = new int[totalNodes];
        int[] values = new int[totalNodes];
        int[] offsets = new int[B];
        int[] edgeChild = new int[totalEdges];
        int[] edgeParent = new int[totalEdges];
        int[] edgeLabel = new int[totalEdges];
        int[] edgeOffsets = new int[B];

        int nodePos = 0;
        int edgePos = 0;
        for (int i = 0; i < B; i++) {
            FeatureGraph.GraphArrays g = batch.get(i).graph;
            offsets[i] = nodePos;
            edgeOffsets[i] = edgePos;
            int n = g.ids.length;
            int m = g.edgeChild.length;
            System.arraycopy(g.ids, 0, ids, nodePos, n);
            System.arraycopy(g.values, 0, values, nodePos, n);
            System.arraycopy(g.edgeChild, 0, edgeChild, edgePos, m);
            System.arraycopy(g.edgeParent, 0, edgeParent, edgePos, m);
            System.arraycopy(g.edgeLabel, 0, edgeLabel, edgePos, m);
            nodePos += n;
            edgePos += m;
        }

        // 2) Encode request
        MessageBufferPacker pk = MessagePack.newDefaultBufferPacker();
        pk.packMapHeader(8);
        packInts(pk, "indices", ids);
        packInts(pk, "values", values);
        packInts(pk, "offsets", offsets);
        packInts(pk, "edge_child", edgeChild);
        packInts(pk, "edge_parent", edgeParent);
        packInts(pk, "edge_label", edgeLabel);
        packInts(pk, "edge_offsets", edgeOffsets);
        pk.packString("binary");
        pk.packBoolean(true);
        pk.close();

        RequestBody body = RequestBody.create(pk.toByteArray(), MediaType.parse("application/x-msgpack"));
        Request req = new Request.Builder().url(evalUrl).post(body).header("Connection", "keep-alive").build();

        // 3) Execute & parse
        try (Response resp = http.newCall(req).execute()) {
            if (!resp.isSuccessful()) throw new RuntimeException("HTTP " + resp.code() + " from model server");
            MessageUnpacker up = MessagePack.newDefaultUnpacker(resp.body().bytes());

            float[] priority = null, target = null, use = null, value = null;
            int keys = up.unpackMapHeader();
            for (int k = 0; k < keys; k++) {
                String key = up.unpackString();
                switch (key) {
                    case "priority": priority = unpackFloats(up); break;
                    case "target": target = unpackFloats(up); break;
                    case "use": use = unpackFloats(up); break;
                    case "value": value = unpackFloats(up); break;
                    default: up.skipValue();
                }
            }
            if (priority == null || target == null || use == null || value == null) {
                throw new RuntimeException("Model server response is missing arrays (is the server up to date?)");
            }
            if (priority.length != totalNodes || target.length != totalNodes || use.length != 2 * B || value.length != B) {
                throw new RuntimeException("Size mismatch: sent " + B + " states / " + totalNodes + " nodes, got "
                        + value.length + " states / " + priority.length + " nodes");
            }

            // 4) Fulfill promises in order
            int nodeStart = 0;
            for (int i = 0; i < B; i++) {
                List<UUID> order = batch.get(i).graph.order;
                Map<UUID, Float> policyUse = new HashMap<>();
                policyUse.put(FeatureGraph.USE_FALSE_ID, use[2 * i]);
                policyUse.put(FeatureGraph.USE_TRUE_ID, use[2 * i + 1]);
                batch.get(i).promise.complete(new InferenceResult(toNodeMap(priority, nodeStart, order),
                        toNodeMap(target, nodeStart, order), policyUse, value[i]));
                nodeStart += order.size();
            }
        }
    }

    private static void packInts(MessageBufferPacker pk, String key, int[] arr) throws IOException {
        ByteBuffer bytes = ByteBuffer.allocate(4 * arr.length).order(ByteOrder.LITTLE_ENDIAN);
        bytes.asIntBuffer().put(arr);
        pk.packString(key);
        pk.packBinaryHeader(bytes.capacity());
        pk.writePayload(bytes.array());
    }

    private static float[] unpackFloats(MessageUnpacker up) throws IOException {
        byte[] bytes = up.readPayload(up.unpackBinaryHeader());
        float[] arr = new float[bytes.length / 4];
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(arr);
        return arr;
    }

    /** One state's per-node scores (starting at `start`) -> map keyed by node UUID, in the order the state was sent. */
    private static Map<UUID, Float> toNodeMap(float[] scores, int start, List<UUID> order) {
        Map<UUID, Float> out = new HashMap<>();
        for (int i = 0; i < order.size(); i++) {
            out.put(order.get(i), scores[start + i]);
        }
        return out;
    }

    private int pendingSizeApprox() {
        // For ConcurrentLinkedQueue size() can be O(n); this is an approximation path.
        // Good enough to trigger immediate flush when we cross ~maxBatchSize.
        int c = 0;
        for (Iterator<PendingReq> it = pending.iterator(); it.hasNext() && c <= maxBatchSize; ) { it.next(); c++; }
        return c;
    }
}