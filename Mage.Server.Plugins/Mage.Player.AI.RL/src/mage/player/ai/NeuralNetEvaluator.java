package mage.player.ai;

import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtException;

import java.nio.LongBuffer; // For int64 tensors
import java.util.HashMap; // For multiple inputs
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Lightweight ONNX Runtime wrapper for policy/value inference.
 * Adapted for models expecting sparse indices and offsets (e.g., from EmbeddingBag).
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
    public static boolean USE_GPU = false;


    // You'll need to get these names by inspecting your exported ONNX model
    // (e.g., using a tool like Netron)
    private final String onnxIndicesInputName = "indices"; // Placeholder name
    private final String onnxOffsetsInputName = "offsets"; // Placeholder name
    private final String onnxPolicyOutputName = "policy";  // Assuming this remains the same
    private final String onnxValueOutputName = "value";    // Assuming this remains the same


    public NeuralNetEvaluator(String onnxPath) throws OrtException {
        this(onnxPath,
                Math.max(1, Runtime.getRuntime().availableProcessors() - 1));
    }

    public NeuralNetEvaluator(String onnxPath, int threads) throws OrtException {
        this.env = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
        opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
        opts.setIntraOpNumThreads(1); // Good for when executor handles parallelism
        opts.setInterOpNumThreads(1); // Good for when executor handles parallelism
        if(USE_GPU) {
            try {
                // This line is the key. It tells ONNX to use the CUDA provider.
                opts.addCUDA(0); // 0 is the device ID for your first GPU
                System.out.println("INFO: ONNX Runtime configured to use GPU (CUDA).");
            } catch (OrtException e) {
                // This will happen if the GPU library is missing or CUDA drivers are not installed.
                System.err.println("WARNING: Failed to initialize CUDA provider. Falling back to CPU. Error: " + e.getMessage());
            }
        }

        this.session = env.createSession(onnxPath, opts);
        this.executor = Executors.newFixedThreadPool(Math.max(1, threads));
    }

    /**
     * Performs inference using sparse feature indices.
     * @param activeGlobalIndices Array of active global feature indices for the state.
     * @return InferenceResult containing policy and value.
     */
    public InferenceResult infer(long[] activeGlobalIndices) { // Changed signature
        // For single sample inference, offsets tensor is just [0]
        long[] offsets = new long[]{0};

        // The shape of the indices tensor is [num_active_indices]
        long[] indicesShape = new long[]{activeGlobalIndices.length};
        // The shape of the offsets tensor is [batch_size], which is 1 here
        long[] offsetsShape = new long[]{1};


        Map<String, OnnxTensor> inputs = null;
        OrtSession.Result outputs = null;
        OnnxTensor indicesTensor = null;
        OnnxTensor offsetsTensor = null;

        try {
            indicesTensor = OnnxTensor.createTensor(
                    env,
                    LongBuffer.wrap(activeGlobalIndices),
                    indicesShape
            );
            offsetsTensor = OnnxTensor.createTensor(
                    env,
                    LongBuffer.wrap(offsets),
                    offsetsShape
            );

            inputs = new HashMap<>();
            inputs.put(onnxIndicesInputName, indicesTensor);
            inputs.put(onnxOffsetsInputName, offsetsTensor);

            outputs = session.run(inputs);

            // Policy tensor
            Optional<OnnxValue> optP = outputs.get(onnxPolicyOutputName);
            if (!optP.isPresent()) {
                throw new RuntimeException("Missing '" + onnxPolicyOutputName + "' output from ONNX model");
            }
            OnnxTensor policyTensor = (OnnxTensor) optP.get();
            float[] policy = ((float[][]) policyTensor.getValue())[0]; // Assumes policy output shape [1, policy_dim]

            // Value tensor
            Optional<OnnxValue> optV = outputs.get(onnxValueOutputName);
            if (!optV.isPresent()) {
                throw new RuntimeException("Missing '" + onnxValueOutputName + "' output from ONNX model");
            }
            OnnxTensor valueTensor = (OnnxTensor) optV.get();
            float[] valueArray = (float[]) valueTensor.getValue();
            // Access the first (and only) element of the 1D array.
            float value = valueArray[0];

            return new InferenceResult(policy, value);
        } catch (OrtException e) {
            throw new RuntimeException("ONNX inference failed", e);
        } finally {
            // Ensure tensors are closed to free native memory
            if (indicesTensor != null) indicesTensor.close();
            if (offsetsTensor != null) offsetsTensor.close();
            if (outputs != null) outputs.close();
            // The 'inputs' map itself doesn't need explicit closing if its OnnxTensor values are closed.
        }
    }

    /**
     * Performs asynchronous inference using sparse feature indices.
     * @param activeGlobalIndices Array of active global feature indices for the state.
     * @return CompletableFuture<InferenceResult> containing policy and value.
     */
    public CompletableFuture<InferenceResult> inferAsync(long[] activeGlobalIndices) { // Changed signature
        return CompletableFuture.supplyAsync(() -> infer(activeGlobalIndices), executor);
    }

    @Override
    public void close() throws OrtException {
        if (session != null) session.close();
        // OrtEnvironment.getEnvironment() is a singleton; closing it here might affect other users.
        // Usually, you close it when the application is shutting down if you obtained it via getEnvironment().
        // If you created it with OrtEnvironment.create(), then you must close it.
        // For simplicity, let's assume it's fine if only this class uses it.
        // env.close(); // Be cautious with closing the shared environment.
        if (executor != null) executor.shutdown();
    }
}