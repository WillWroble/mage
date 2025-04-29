package mage.player.ai;

import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtException;

import java.nio.FloatBuffer;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Lightweight ONNX Runtime wrapper for policy/value inference.
 */
public class NeuralNetEvaluator implements AutoCloseable {

    public static class InferenceResult {
        public final float[] policy;
        public final float  value;
        public InferenceResult(float[] policy, float value) {
            this.policy = policy;
            this.value  = value;
        }
    }

    private final OrtEnvironment  env;
    private final OrtSession      session;
    private final ExecutorService executor;

    public NeuralNetEvaluator(String onnxPath) throws OrtException {
        this(onnxPath,
                Math.max(1, Runtime.getRuntime().availableProcessors() - 1));
    }

    public NeuralNetEvaluator(String onnxPath, int threads) throws OrtException {
        // 1) Shared environment
        this.env = OrtEnvironment.getEnvironment();

        // 2) Tune session options via nested SessionOptions
        OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
        opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
        opts.setIntraOpNumThreads(1);
        opts.setInterOpNumThreads(1);

        // 3) Thread-safe session (parsed once at startup)
        this.session = env.createSession(onnxPath, opts);

        // 4) Executor for non-blocking inference
        this.executor = Executors.newFixedThreadPool(Math.max(1, threads));
    }

    public InferenceResult infer(float[] state) {
        try (
                OnnxTensor input = OnnxTensor.createTensor(
                        env,
                        FloatBuffer.wrap(state),
                        new long[]{1, state.length}
                );
                OrtSession.Result res = session.run(
                        Collections.singletonMap("state_input", (OnnxTensor)input)
                )
        ) {
            // policy tensor
            Optional<OnnxValue> optP = res.get("policy");
            if (!optP.isPresent()) {
                throw new RuntimeException("Missing 'policy' output from ONNX model");
            }
            OnnxTensor policyTensor = (OnnxTensor) optP.get();
            float[] policy = ((float[][]) policyTensor.getValue())[0];

            // value tensor
            Optional<OnnxValue> optV = res.get("value");
            if (!optV.isPresent()) {
                throw new RuntimeException("Missing 'value' output from ONNX model");
            }
            OnnxTensor valueTensor = (OnnxTensor) optV.get();
            float value = ((float[]) valueTensor.getValue())[0];

            return new InferenceResult(policy, value);
        } catch (OrtException e) {
            throw new RuntimeException("ONNX inference failed", e);
        }
    }

    public CompletableFuture<InferenceResult> inferAsync(float[] state) {
        return CompletableFuture.supplyAsync(() -> infer(state), executor);
    }

    @Override
    public void close() throws OrtException {
        session.close();
        env.close();
        executor.shutdown();
    }
}
