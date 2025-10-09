package mage.player.ai;

import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;

import java.nio.LongBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Lightweight ONNX Runtime wrapper for policy/value inference.
 * SERIALIZES all session.run() calls behind a lock to prevent native memory blowups
 * with very large EmbeddingBag models.
 *
 * Drop-in replacement: same public API as before.
 */
public class NeuralNetEvaluator implements AutoCloseable {

    public static class InferenceResult {
        public final float[] policy;
        public final float value;
        public InferenceResult(float[] policy, float value) {
            this.policy = policy;
            this.value = value;
        }
    }

    private final OrtEnvironment env;
    private final OrtSession session;
    private final ExecutorService executor;

    // Serialize all session.run(...) calls across threads
    private final Object runLock = new Object();

    public static boolean USE_GPU = false;

    // IO names (match your exported ONNX)
    private final String onnxIndicesInputName = "indices";
    private final String onnxOffsetsInputName = "offsets";
    private final String onnxPolicyOutputName = "policy";
    private final String onnxValueOutputName = "value";

    // Reused offsets tensor for single-sample EmbeddingBag-style inputs
    private final OnnxTensor sharedOffsetsTensor;
    private static final long[] OFFSETS_1 = new long[]{0};
    private static final long[] OFFSETS_SHAPE = new long[]{1};

    public NeuralNetEvaluator(String onnxPath) throws OrtException {
        this(onnxPath, Math.max(1, Runtime.getRuntime().availableProcessors() - 1));
    }

    public NeuralNetEvaluator(String onnxPath, int threads) throws OrtException {
        this.env = OrtEnvironment.getEnvironment();

        // Pre-create reusable offsets tensor
        this.sharedOffsetsTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(OFFSETS_1), OFFSETS_SHAPE);

        // Session options: single-threaded, sequential, minimal allocator caching
        OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
        opts.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL);
        opts.setIntraOpNumThreads(1);
        opts.setInterOpNumThreads(1);
        opts.setMemoryPatternOptimization(false);

        // Reduce native CPU memory arena caching if available (ignore if not supported)
        try { opts.setCPUArenaAllocator(false); } catch (Throwable ignored) {}
        try { opts.addConfigEntry("session.enable_cpu_mem_arena", "0"); } catch (Throwable ignored) {}

        if (USE_GPU) {
            try {
                opts.addCUDA(0); // device 0
                System.out.println("INFO: ONNX Runtime configured to use GPU (CUDA).");
            } catch (OrtException e) {
                System.err.println("WARNING: Failed to initialize CUDA provider. Falling back to CPU. Error: " + e.getMessage());
            }
        }

        this.session = env.createSession(onnxPath, opts);

        // Keep API compatibility for async calls; runLock still serializes actual runs
        this.executor = Executors.newSingleThreadExecutor();
    }

    /**
     * Performs inference using sparse feature indices.
     * @param activeGlobalIndices Array of active global feature indices for the state.
     * @return InferenceResult containing policy and value.
     */
    public InferenceResult infer(long[] activeGlobalIndices) {
        final long[] indicesShape = new long[]{activeGlobalIndices.length};

        Map<String, OnnxTensor> inputs = null;
        OrtSession.Result outputs = null;
        OnnxTensor indicesTensor = null;

        try {
            indicesTensor = OnnxTensor.createTensor(
                    env,
                    LongBuffer.wrap(activeGlobalIndices),
                    indicesShape
            );

            inputs = new HashMap<>();
            inputs.put(onnxIndicesInputName, indicesTensor);
            inputs.put(onnxOffsetsInputName, sharedOffsetsTensor);

            // >>> Only one session.run() at a time <<<
            synchronized (runLock) {
                outputs = session.run(inputs);
            }
            // <<< ------------------------------- >>>

            // Policy tensor
            Optional<OnnxValue> optP = outputs.get(onnxPolicyOutputName);
            if (!optP.isPresent()) {
                throw new RuntimeException("Missing '" + onnxPolicyOutputName + "' output from ONNX model");
            }
            OnnxTensor policyTensor = (OnnxTensor) optP.get();
            float[] policy = ((float[][]) policyTensor.getValue())[0]; // shape [1, A]

            // Value tensor
            Optional<OnnxValue> optV = outputs.get(onnxValueOutputName);
            if (!optV.isPresent()) {
                throw new RuntimeException("Missing '" + onnxValueOutputName + "' output from ONNX model");
            }
            OnnxTensor valueTensor = (OnnxTensor) optV.get();
            float[] valueArray = (float[]) valueTensor.getValue();
            float value = valueArray[0];

            return new InferenceResult(policy, value);
        } catch (Exception e) {
            throw new RuntimeException("ONNX inference failed", e);
        } finally {
            // Free native memory promptly
            if (indicesTensor != null) try { indicesTensor.close(); } catch (Exception ignore) {}
            if (outputs != null) try { outputs.close(); } catch (Exception ignore) {}
        }
    }

    /**
     * Asynchronous wrapper; still serialized due to runLock.
     */
    public CompletableFuture<InferenceResult> inferAsync(long[] activeGlobalIndices) {
        return CompletableFuture.supplyAsync(() -> infer(activeGlobalIndices), executor);
    }

    @Override
    public void close() throws OrtException {
        if (session != null) session.close();
        if (sharedOffsetsTensor != null) try { sharedOffsetsTensor.close(); } catch (Exception ignore) {}
        if (executor != null) executor.shutdown();
        // Do not close env here; it's a shared singleton returned by getEnvironment().
    }
}
