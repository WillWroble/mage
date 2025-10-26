package mage.player.ai;

import okhttp3.*;
import org.msgpack.core.MessageBufferPacker;
import org.msgpack.core.MessagePack;
import org.msgpack.core.MessageUnpacker;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

public class RemoteModelEvaluator implements AutoCloseable {

    public static class InferenceResult {
        public final float[] policy; // alias of player
        public final float[] policy_player;
        public final float[] policy_opponent;
        public final float[] policy_target;
        public final float[] policy_binary; // len 2
        public final float value;

        public InferenceResult(float[] policySingle, float value) {
            this.policy_player = policySingle;
            this.policy_opponent = null;
            this.policy_target = null;
            this.policy_binary = null;
            this.policy = policySingle;
            this.value = value;
        }
        public InferenceResult(float[] policyPlayer,
                               float[] policyOpponent,
                               float[] policyTarget,
                               float[] policyBinary,
                               float value) {
            this.policy_player = policyPlayer;
            this.policy_opponent = policyOpponent;
            this.policy_target = policyTarget;
            this.policy_binary = policyBinary;
            this.policy = policyPlayer;
            this.value = value;
        }
    }

    /** How many concurrent HTTP calls are allowed. Keep small when batching is enabled. */
    public static int MAX_CONCURRENT_CALLS = 2;

    // ---------- batching controls ----------
    public static final boolean enableBatching = true;
    public static final int batchInterval = 2500;//micro seconds
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
        final long[] indices;
        final CompletableFuture<InferenceResult> promise;
        PendingReq(long[] indices) {
            this.indices = indices;
            this.promise = new CompletableFuture<>();
        }
    }

    /** host:port like "http://127.0.0.1:50052" with explicit batching controls. */
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
                .url(baseUrl + "/health")
                .get()
                .build();
        try (Response r = http.newCall(ping).execute()) {
            if (!r.isSuccessful()) {
                throw new IOException("Health check failed: HTTP " + r.code());
            }
        }

        if (enableBatching) {
            this.pending = new ConcurrentLinkedQueue<>();
            this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "RemoteModelEvaluator-BatchFlusher");
                t.setDaemon(true);
                return t;
            });
            // Flush every N ms (micro-batch cadence)
            this.scheduler.scheduleAtFixedRate(this::flushIfAny, batchInterval, batchInterval, TimeUnit.MICROSECONDS);
        } else {
            this.pending = null;
            this.scheduler = null;
        }
    }

    /** Default to localhost; batching OFF by default. */
    public RemoteModelEvaluator() throws IOException { this("http://127.0.0.1:50052"); }

    // ----------------- Public API -----------------

    /** Back-compat sync call. If batching is on, this waits for the micro-batch result. */
    public InferenceResult infer(long[] activeGlobalIndices) {
        if (!enableBatching) {
            // Legacy single-sample path
            return doHttpCallSingle(activeGlobalIndices);
        }
        try {
            return inferAsync(activeGlobalIndices).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted waiting for batched result", e);
        } catch (ExecutionException e) {
            throw new RuntimeException(e.getCause() != null ? e.getCause() : e);
        }
    }

    /** Asynchronous API. If batching is enabled, enqueues; otherwise does a single-sample async call. */
    public CompletableFuture<InferenceResult> inferAsync(long[] activeGlobalIndices) {
        if (!enableBatching) {
            return CompletableFuture.supplyAsync(() -> doHttpCallSingle(activeGlobalIndices), exec);
        }

        PendingReq pr = new PendingReq(activeGlobalIndices);
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

    /** single-sample HTTP call. best used with highly parallel server */
    private InferenceResult doHttpCallSingle(long[] activeGlobalIndices) {
        try {
            permits.acquire();
            try {
                MessageBufferPacker pk = MessagePack.newDefaultBufferPacker();
                pk.packMapHeader(2);
                pk.packString("indices");
                pk.packArrayHeader(activeGlobalIndices.length);
                for (long v : activeGlobalIndices) pk.packLong(v);
                pk.packString("offsets");
                pk.packArrayHeader(1);
                pk.packLong(0);
                pk.close();

                RequestBody body = RequestBody.create(pk.toByteArray(), MediaType.parse("application/x-msgpack"));
                Request req = new Request.Builder().url(evalUrl).post(body).header("Connection", "keep-alive").build();

                try (Response resp = http.newCall(req).execute()) {
                    if (!resp.isSuccessful()) throw new RuntimeException("HTTP " + resp.code() + " from model server");
                    byte[] bytes = resp.body().bytes();
                    MessageUnpacker up = MessagePack.newDefaultUnpacker(bytes);
                    // Expect MAP for single
                    return unpackOneResultMap(up);
                }
            } finally {
                permits.release();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted during inference", e);
        } catch (Exception e) {
            throw new RuntimeException("HTTP inference failed", e);
        }
    }

    /** Periodic/bounds-triggered flush. No-op if nothing pending or permit not available. */
    private void flushIfAny() {
        if (!enableBatching) return;
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

    private void runBatchedHttpCall(List<PendingReq> batch) throws Exception {
        // 1) Build concatenated indices & cumulative offsets
        int B = batch.size();
        int totalLen = 0;
        for (PendingReq p : batch) totalLen += (p.indices == null ? 0 : p.indices.length);
        long[] cat = new long[totalLen];
        long[] offsets = new long[B];

        int pos = 0;
        for (int i = 0; i < B; i++) {
            offsets[i] = pos;
            long[] arr = batch.get(i).indices;
            if (arr != null && arr.length > 0) {
                System.arraycopy(arr, 0, cat, pos, arr.length);
                pos += arr.length;
            }
        }

        // 2) Encode request
        MessageBufferPacker pk = MessagePack.newDefaultBufferPacker();
        pk.packMapHeader(2);
        pk.packString("indices");
        pk.packArrayHeader(cat.length);
        for (long v : cat) pk.packLong(v);
        pk.packString("offsets");
        pk.packArrayHeader(offsets.length);
        for (long v : offsets) pk.packLong(v);
        pk.close();

        RequestBody body = RequestBody.create(pk.toByteArray(), MediaType.parse("application/x-msgpack"));
        Request req = new Request.Builder().url(evalUrl).post(body).header("Connection", "keep-alive").build();

        // 3) Execute & parse
        try (Response resp = http.newCall(req).execute()) {
            if (!resp.isSuccessful()) throw new RuntimeException("HTTP " + resp.code() + " from model server");
            byte[] bytes = resp.body().bytes();
            MessageUnpacker up = MessagePack.newDefaultUnpacker(bytes);

            // Server may return:
            //  - ARRAY of MAPs (length B)
            //  - MAP (legacy) if B==1
            InferenceResult[] results;
            switch (up.getNextFormat().getValueType()) {
                case ARRAY: {
                    int n = up.unpackArrayHeader();
                    if (n != B) throw new RuntimeException("Batch size mismatch: sent " + B + " got " + n);
                    results = new InferenceResult[n];
                    for (int i = 0; i < n; i++) results[i] = unpackOneResultMap(up);
                    break;
                }
                case MAP: {
                    if (B != 1) throw new RuntimeException("Server returned single result for B=" + B);
                    results = new InferenceResult[]{unpackOneResultMap(up)};
                    break;
                }
                default:
                    throw new RuntimeException("Unexpected response type: " + up.getNextFormat());
            }

            // 4) Fulfill promises in order
            for (int i = 0; i < B; i++) {
                batch.get(i).promise.complete(results[i]);
            }
        }
    }

    private static float[] unpackFloatArray(MessageUnpacker up) throws Exception {
        int n = up.unpackArrayHeader();
        float[] arr = new float[n];
        for (int j = 0; j < n; j++) {
            arr[j] = (float) up.unpackDouble();
        }
        return arr;
    }

    private static InferenceResult unpackOneResultMap(MessageUnpacker up) throws Exception {
        int mapSz = up.unpackMapHeader();
        float[] policyPlayer   = null;
        float[] policyOpponent = null;
        float[] policyTarget   = null;
        float[] policyBinary   = null;
        float[] legacyPolicy   = null;
        float value            = 0f;

        for (int i = 0; i < mapSz; i++) {
            String key = up.unpackString();
            switch (key) {
                case "policy_player":   policyPlayer   = unpackFloatArray(up); break;
                case "policy_opponent": policyOpponent = unpackFloatArray(up); break;
                case "policy_target":   policyTarget   = unpackFloatArray(up); break;
                case "policy_binary":   policyBinary   = unpackFloatArray(up); break;
                case "policy":          legacyPolicy   = unpackFloatArray(up); break; // legacy
                case "value":           value          = (float) up.unpackDouble(); break;
                default:                up.skipValue();
            }
        }
        if (policyPlayer != null || policyOpponent != null || policyTarget != null || policyBinary != null) {
            if (policyPlayer == null) policyPlayer = legacyPolicy;
            return new InferenceResult(policyPlayer, policyOpponent, policyTarget, policyBinary, value);
        } else if (legacyPolicy != null) {
            return new InferenceResult(legacyPolicy, value);
        } else {
            throw new RuntimeException("Missing policy fields in server response");
        }
    }

    private int pendingSizeApprox() {
        // For ConcurrentLinkedQueue size() can be O(n); this is an approximation path.
        // Good enough to trigger immediate flush when we cross ~maxBatchSize.
        int c = 0;
        for (Iterator<PendingReq> it = pending.iterator(); it.hasNext() && c <= maxBatchSize; ) { it.next(); c++; }
        return c;
    }
}
