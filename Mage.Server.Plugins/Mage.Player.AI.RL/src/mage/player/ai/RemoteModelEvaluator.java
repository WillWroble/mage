
package mage.player.ai;

import okhttp3.*;
import org.msgpack.core.MessageBufferPacker;
import org.msgpack.core.MessagePack;
import org.msgpack.core.MessageUnpacker;

import java.util.concurrent.*;

/**
 * Remote NN evaluator via Flask+HTTP using MessagePack payloads.
 * Same public API as before: infer(long[]) -> {policy, value}.
 */
public class RemoteModelEvaluator implements AutoCloseable {

    public static class InferenceResult {
        public final float[] policy;
        public final float value;
        public InferenceResult(float[] policy, float value) {
            this.policy = policy;
            this.value = value;
        }
    }

    // Tune: how many in-flight HTTP requests to allow (keep small)
    public static int MAX_CONCURRENT_RUNS = 2;

    private final OkHttpClient http;
    private final HttpUrl evalUrl;
    private final Semaphore permits;
    private final ExecutorService exec;

    /** host:port like "http://127.0.0.1:50052" */
    public RemoteModelEvaluator(String baseUrl) {
        this.permits = new Semaphore(Math.max(1, MAX_CONCURRENT_RUNS), true);
        this.http = new OkHttpClient.Builder()
                .retryOnConnectionFailure(true)
                .connectionPool(new ConnectionPool(8, 120, TimeUnit.SECONDS))
                .build();
        this.evalUrl = HttpUrl.parse(baseUrl + "/evaluate");
        this.exec = Executors.newFixedThreadPool(
                Math.max(1, Runtime.getRuntime().availableProcessors() - 1));
    }

    /** Default to localhost */
    public RemoteModelEvaluator() { this("http://127.0.0.1:50052"); }

    public InferenceResult infer(long[] activeGlobalIndices) {
        long[] offsets = new long[]{0}; // EmbeddingBag single sample

        try {
            permits.acquire();
            try {
                // MessagePack encode: {"indices":[...], "offsets":[...]}
                MessageBufferPacker pk = MessagePack.newDefaultBufferPacker();
                pk.packMapHeader(2);
                pk.packString("indices");
                pk.packArrayHeader(activeGlobalIndices.length);
                for (long v : activeGlobalIndices) pk.packLong(v);
                pk.packString("offsets");
                pk.packArrayHeader(offsets.length);
                for (long v : offsets) pk.packLong(v);
                pk.close();

                RequestBody body = RequestBody.create(pk.toByteArray(),
                        MediaType.parse("application/x-msgpack"));
                Request req = new Request.Builder()
                        .url(evalUrl)
                        .post(body)
                        .header("Connection", "keep-alive")
                        .build();

                try (Response resp = http.newCall(req).execute()) {
                    if (!resp.isSuccessful()) {
                        throw new RuntimeException("HTTP " + resp.code() + " from model server");
                    }
                    byte[] bytes = resp.body().bytes();
                    MessageUnpacker up = MessagePack.newDefaultUnpacker(bytes);

                    // Response: {"policy":[...], "value":float}
                    int mapSz = up.unpackMapHeader();
                    float[] policy = null;
                    float value = 0f;
                    for (int i = 0; i < mapSz; i++) {
                        String key = up.unpackString();
                        switch (key) {
                            case "policy": {
                                int n = up.unpackArrayHeader();
                                policy = new float[n];
                                for (int j = 0; j < n; j++) {
                                    // MessagePack stores as double by default; read as double, cast
                                    policy[j] = (float) up.unpackDouble();
                                }
                                break;
                            }
                            case "value": {
                                value = (float) up.unpackDouble();
                                break;
                            }
                            default:
                                up.skipValue();
                        }
                    }
                    if (policy == null) throw new RuntimeException("Missing policy field");
                    return new InferenceResult(policy, value);
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

    public CompletableFuture<InferenceResult> inferAsync(long[] activeGlobalIndices) {
        return CompletableFuture.supplyAsync(() -> infer(activeGlobalIndices), exec);
    }

    @Override
    public void close() {
        if (exec != null) exec.shutdown();
        // OkHttp cleans up via connectionPool; no explicit close needed here.
    }
}
