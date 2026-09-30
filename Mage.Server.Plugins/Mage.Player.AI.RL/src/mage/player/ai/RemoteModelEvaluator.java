package mage.player.ai;

import mage.player.ai.encoder.FeatureGraph;
import okhttp3.*;
import org.msgpack.core.MessageBufferPacker;
import org.msgpack.core.MessagePack;
import org.msgpack.core.MessageUnpacker;

import java.io.IOException;
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
    public static int MAX_CONCURRENT_CALLS = 16;


    // ---------- batching controls ----------
    public static final int batchInterval = 2500;//micro seconds
    public static final int maxBatchSize = 4;

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
     * Request (msgpack map), same concatenated CSR form as the HDF5 files:
     *   indices, values           : per node, all states concatenated
     *   offsets                   : per state, start of its nodes (length B)
     *   edge_child, edge_parent,
     *   edge_label                : per edge, all states concatenated; child/parent are LOCAL to their state
     *   edge_offsets              : per state, start of its edges (length B)
     * Response: array of B maps, each with
     *   policy_priority, policy_target : one float per node of that state, in the order sent
     *   policy_binary                  : [false, true]
     *   value                          : float
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
        long[] offsets = new long[B];
        int[] edgeChild = new int[totalEdges];
        int[] edgeParent = new int[totalEdges];
        int[] edgeLabel = new int[totalEdges];
        long[] edgeOffsets = new long[B];

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
        pk.packMapHeader(7);
        packIntArray(pk, "indices", ids);
        packIntArray(pk, "values", values);
        packLongArray(pk, "offsets", offsets);
        packIntArray(pk, "edge_child", edgeChild);
        packIntArray(pk, "edge_parent", edgeParent);
        packIntArray(pk, "edge_label", edgeLabel);
        packLongArray(pk, "edge_offsets", edgeOffsets);
        pk.close();

        RequestBody body = RequestBody.create(pk.toByteArray(), MediaType.parse("application/x-msgpack"));
        Request req = new Request.Builder().url(evalUrl).post(body).header("Connection", "keep-alive").build();

        // 3) Execute & parse
        try (Response resp = http.newCall(req).execute()) {
            if (!resp.isSuccessful()) throw new RuntimeException("HTTP " + resp.code() + " from model server");
            byte[] bytes = resp.body().bytes();
            MessageUnpacker up = MessagePack.newDefaultUnpacker(bytes);

            int n = up.unpackArrayHeader();
            if (n != B) throw new RuntimeException("Batch size mismatch: sent " + B + " got " + n);
            InferenceResult[] results = new InferenceResult[n];
            for (int i = 0; i < n; i++) {
                results[i] = unpackOneResultMap(up, batch.get(i).graph.order);
            }

            // 4) Fulfill promises in order
            for (int i = 0; i < B; i++) {
                batch.get(i).promise.complete(results[i]);
            }
        }
    }

    private static void packIntArray(MessageBufferPacker pk, String key, int[] arr) throws IOException {
        pk.packString(key);
        pk.packArrayHeader(arr.length);
        for (int v : arr) pk.packInt(v);
    }

    private static void packLongArray(MessageBufferPacker pk, String key, long[] arr) throws IOException {
        pk.packString(key);
        pk.packArrayHeader(arr.length);
        for (long v : arr) pk.packLong(v);
    }

    private static float[] unpackFloatArray(MessageUnpacker up) throws Exception {
        int n = up.unpackArrayHeader();
        float[] arr = new float[n];
        for (int j = 0; j < n; j++) {
            arr[j] = (float) up.unpackDouble();
        }
        return arr;
    }

    /** Per-node scores -> map keyed by node UUID, using the order the state was sent in. */
    private static Map<UUID, Float> toNodeMap(float[] scores, List<UUID> order) {
        if (scores.length != order.size()) {
            throw new RuntimeException("Node count mismatch: sent " + order.size() + " got " + scores.length);
        }
        Map<UUID, Float> out = new HashMap<>();
        for (int i = 0; i < scores.length; i++) {
            out.put(order.get(i), scores[i]);
        }
        return out;
    }

    private static InferenceResult unpackOneResultMap(MessageUnpacker up, List<UUID> order) throws Exception {
        int mapSz = up.unpackMapHeader();
        Map<UUID, Float> policyPriority = null;
        Map<UUID, Float> policyTarget = null;
        Map<UUID, Float> policyUse = null;
        float value = 0f;

        for (int i = 0; i < mapSz; i++) {
            String key = up.unpackString();
            switch (key) {
                case "policy_priority":
                    policyPriority = toNodeMap(unpackFloatArray(up), order);
                    break;
                case "policy_target":
                    policyTarget = toNodeMap(unpackFloatArray(up), order);
                    break;
                case "policy_binary": {
                    float[] b = unpackFloatArray(up);
                    policyUse = new HashMap<>();
                    policyUse.put(FeatureGraph.USE_FALSE_ID, b[0]);
                    policyUse.put(FeatureGraph.USE_TRUE_ID, b[1]);
                    break;
                }
                case "value":
                    value = (float) up.unpackDouble();
                    break;
                default:
                    up.skipValue();
            }
        }
        return new InferenceResult(policyPriority, policyTarget, policyUse, value);
    }

    private int pendingSizeApprox() {
        // For ConcurrentLinkedQueue size() can be O(n); this is an approximation path.
        // Good enough to trigger immediate flush when we cross ~maxBatchSize.
        int c = 0;
        for (Iterator<PendingReq> it = pending.iterator(); it.hasNext() && c <= maxBatchSize; ) { it.next(); c++; }
        return c;
    }
}