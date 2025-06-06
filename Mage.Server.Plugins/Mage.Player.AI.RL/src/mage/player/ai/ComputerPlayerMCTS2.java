package mage.player.ai;

import ai.onnxruntime.OrtException;
import mage.constants.PhaseStep;
import mage.constants.RangeOfInfluence;
import mage.game.Game;
import mage.player.ai.MCTSPlayer.NextAction;
import mage.util.RandomUtil;
import mage.util.ThreadUtils;
import mage.util.XmageThreadFactory;
import org.apache.log4j.Logger;

import java.util.*;
import java.util.concurrent.*;

/**
 * ComputerPlayerMCTS2 extends ComputerPlayerMCTS and uses a value function at leaf nodes.
 * It replaces full rollout simulations with a call to evaluateState() on the leaf game state.
 */
public class ComputerPlayerMCTS2 extends ComputerPlayerMCTS {

    private static final Logger logger = Logger.getLogger(ComputerPlayerMCTS2.class);

    private StateEncoder encoder = null;
    private static final int MAX_MCTS_CYCLES = 2;//number of additional cycles the search is allowed to run
    private static final int BASE_THREAD_TIMEOUT = 3;//seconds
    private static final int MIN_TREE_VISITS = 100;
    private static final int MAX_TREE_VISITS = 200;

    public static boolean SHOW_THREAD_INFO = false;
    //protected List<UUID> targets = new ArrayList<>();
    //protected List<String> choices = new ArrayList<>();
    public NeuralNetEvaluator nn;

    public static String PATH_TO_NN = "null";
    private final Object encoderLock = new Object();
    private final Object executorLock = new Object();




    public ComputerPlayerMCTS2(String name, RangeOfInfluence range, int skill) {
        super(name, range, skill);
        try {
            nn = new NeuralNetEvaluator(PATH_TO_NN);
        } catch (OrtException e) {
            throw new RuntimeException(e);
        }
    }

    protected ComputerPlayerMCTS2(UUID id) {
        super(id);
    }

    public ComputerPlayerMCTS2(final ComputerPlayerMCTS2 player) {
        super(player); nn = player.nn;
        encoder = player.encoder;
    }

    @Override
    public ComputerPlayerMCTS2 copy() {
        return new ComputerPlayerMCTS2(this);
    }

    protected double evaluateState(MCTSNode node) {
        // 1) Get the active GLOBAL feature indices for the current state.
        // This replaces the old way of getting a deck-local BitSet and converting it.
        int[] activeGlobalIndices;
        synchronized(encoderLock) { // Keep synchronization if encoder access needs it
            encoder.processState(node.getGame()); // This should prepare the raw features

            // You need a method in your StateEncoder that returns the
            // final list of active GLOBAL indices. This method would:
            //   a. Get the raw features from the current game state.
            //   b. Map them to global indices (using rawToReduced, assigning new ones if needed).
            //   c. Apply the ignoreList.
            // Let's assume such a method exists, e.g., encoder.getActiveGlobalIndices();
            // For this example, I'll use a placeholder for how you get these.
            // If encoder.getCompressedVector still returns a Set<Integer> of global indices:
            Set<Integer> globalIndicesSet = encoder.getCompressedVector(StateEncoder.featureVector);
            activeGlobalIndices = globalIndicesSet.stream().mapToInt(Integer::intValue).toArray();
            // Or, if you have a more direct method:
            // activeGlobalIndices = encoder.getFinalActiveGlobalIndicesArray();
        }

        // 2) Prepare inputs for the ONNX model (expecting EmbeddingBag style input)
        //    The exact format depends on how your ONNX runtime library takes tensor inputs.
        //    Typically, for a single inference, you'd have:
        //    - indices: A 1D array/tensor of the activeGlobalIndices.
        //    - offsets: A 1D array/tensor like [0] indicating the start of the single sample.

        //    Let's assume your nn.infer() is adapted to take these.
        //    You might need to convert activeGlobalIndices to a long[] or specific tensor format
        //    required by your Java ONNX runtime.
        //    For example, if it needs long[] for indices:
        long[] onnxIndices = new long[activeGlobalIndices.length];
        for (int i = 0; i < activeGlobalIndices.length; i++) {
            onnxIndices[i] = activeGlobalIndices[i];
        }
        long[] onnxOffsets = new long[]{0}; // For a single sample batch

        NeuralNetEvaluator.InferenceResult out;
        synchronized(encoderLock) { // If nn.infer() also needs sync with encoder or is not thread-safe
            // 3) Run the ONNX model with the new sparse input format
            //    The signature of nn.infer() would need to change.
            out = nn.infer(onnxIndices);
        }

        // 4) Process outputs (this part remains the same)
        node.policy = out.policy;
        return out.value;
    }
    public void setEncoder(StateEncoder enc) {
        encoder = enc;
    }

    public StateEncoder getEncoder() {
        return encoder;
    }
    public int diffVisits(List<Integer> children) {
        int max = -1;
        int max2 = -1;//second highest
        for(int n : children) {
            if(n > max) {
                max2 = max;
                max = n;
            } else if(n > max2) {
                max2 = n;
            }
        }
        return max-max2;
    }
    public int averageVisits(List<Integer> children) {
        int sum = 0;
        if(children.isEmpty()) return 0;
        for(int c : children) {
            sum += c;
        }
        return sum/children.size();
    }
    @Override
    public boolean priority(Game game) {
        if (game.getTurnStepType() == PhaseStep.END_TURN) {
            GameStateEvaluator2.printBattlefield(game, game.getActivePlayerId());
        }
        boolean out = super.priority(game);
        //ActionEncoder.addAction(root.getAction());
        return out;
    }

    /**
     * Overrides applyMCTS to use the value function at leaf nodes.
     */
    @Override
    protected void applyMCTS(final Game game, final NextAction action) {
        int initialVisits = root.getAverageVisits();
        //if(initialVisits > MAX_TREE_VISITS) return;//just keep using tree
        if(SHOW_THREAD_INFO) System.out.printf("STARTING ROOT VISITS: %d\n", initialVisits);
        int thinkTime = BASE_THREAD_TIMEOUT;//calculateThinkTime(game, action);


        if (this.threadPoolSimulations == null) {
            this.threadPoolSimulations = new ThreadPoolExecutor(
                    poolSize,
                    poolSize,
                    0L,
                    TimeUnit.MILLISECONDS,
                    new LinkedBlockingQueue<>(),
                    new XmageThreadFactory(ThreadUtils.THREAD_PREFIX_AI_SIMULATION_MCTS)
            );
        }
        List<MCTSExecutor> tasks = new ArrayList<>();
        long seed = RandomUtil.nextInt();
        for (int i = 0; i < poolSize; i++) {
            Game sim = createMCTSGame(game);
            MCTSPlayer player = (MCTSPlayer) sim.getPlayer(playerId);
            player.chooseTargetOptions = chooseTargetOptions;
            player.chooseTargetAction = new ArrayList<>(chosenChooseTargetActions);
            player.setNextAction(action);
            player.dirichletSeed = seed;
            // Create an executor that overrides rollout() to use evaluateState().
            MCTSExecutor exec = new MCTSExecutor(sim, playerId, thinkTime) {
                @Override
                protected double rollout(MCTSNode node) {
                    // Instead of a full simulation, evaluate the leaf state with our value function.
                    return evaluateState(node);
                }
            };
            tasks.add(exec);
        }
        //runs mcts sims until the root has been visited enough times
        List<Integer> childVisits = new ArrayList<>();
        int cycleCounter = 0;
        int fullTime = 0;

        while (averageVisits(childVisits)+initialVisits < MIN_TREE_VISITS) {//use max visits of children as indicator

            if (cycleCounter > MAX_MCTS_CYCLES) break;
            cycleCounter++;
            try {
                List<Future<Boolean>> runningTasks = threadPoolSimulations.invokeAll(tasks, thinkTime, TimeUnit.SECONDS);
                for (Future<Boolean> runningTask : runningTasks) {
                    runningTask.get();
                }
            } catch (InterruptedException | CancellationException e) {
                if (SHOW_THREAD_INFO) logger.warn("applyMCTS timeout");
            } catch (ExecutionException e) {
                if (this.isTestsMode()) {
                    throw new IllegalStateException("One of the simulated games raised an error: " + e, e);
                }
            }

            childVisits = getChildVisits(tasks);

            if (SHOW_THREAD_INFO) {
                System.out.printf("CYCLE %d: %d threads were created\n", cycleCounter, tasks.size());
                for (MCTSExecutor task : tasks) {
                    if (task.reachedTerminalState && SHOW_THREAD_INFO)
                        System.out.print("-task reached a terminal state-");
                    if (SHOW_THREAD_INFO) System.out.printf("%d ", task.simCount);
                }
                System.out.printf("\nCOMPOSITE CHILDREN: %s\n", childVisits.toString());
            }
            fullTime += thinkTime;
            thinkTime += 1;
        }
        int simCount = 0;
        for (MCTSExecutor task : tasks) {
            simCount += task.getSimCount();
            root.merge(task.getRoot());
            task.clear();
        }
        tasks.clear();
        totalThinkTime += fullTime;
        totalSimulations += simCount;
        if (SHOW_THREAD_INFO) {
            logger.info("Player: " + name + " simulated " + simCount + " evaluations in " + fullTime
                    + " seconds - nodes in tree: " + root.size());
            logger.info("Total: simulated " + totalSimulations + " evaluations in " + totalThinkTime
                    + " seconds - Average: " + totalSimulations / totalThinkTime);
        }
        MCTSNode.logHitMiss();
    }
    double[] getActionVec() {
        double tau = 1.0;            // your temperature hyperparam
        int    A   = 128;
        double[] out = new double[A];
        double   sum = 0;
        // 1) accumulate visits^(1/tau)
        for (MCTSNode child : root.children) {
            if (child.getAction() != null) {
                int idx = ActionEncoder.getAction(child.getAction());
                double v = child.visits;
                // apply temperature
                double vt = Math.pow(v, 1.0 / tau);
                out[idx] = vt;
                sum += vt;
            }
        }

        // 2) normalize into a proper distribution
        if (sum > 0) {
            for (int i = 0; i < A; i++) {
                out[i] = out[i] / sum;
            }
        }
        return out;
    }
    @Override
    protected void calculateActions(Game game, NextAction action) {
        if (root == null) {
            Game sim = createMCTSGame(game);
            MCTSPlayer player = (MCTSPlayer) sim.getPlayer(playerId);
            player.setNextAction(action);
            player.isRoot = true;
            root = new MCTSNode(playerId, sim);
            player.chooseTargetOptions = chooseTargetOptions;
            player.chooseTargetAction = new ArrayList<>(chosenChooseTargetActions);
            root.chooseTargetAction = new ArrayList<>(chosenChooseTargetActions);
        }
        applyMCTS(game, action);
        if (root != null) {
            MCTSNode best = root.bestChild();
            if(best == null) return;
            synchronized (encoderLock) {
                encoder.processMacroState(game);
                encoder.stateScores.add(root.score);
                ActionEncoder.addAction(getActionVec());
            }
            macroState = root.macroState;
            macroPlayerId = getId();
            root = best;
            lastAction = root.action;
            root.emancipate();
        }
    }
    private static List<Integer> getChildVisits(List<MCTSExecutor> tasks) {
        List<Integer> childVisits = new ArrayList<>();
        int min = Integer.MAX_VALUE;
        for(MCTSExecutor task : tasks) {
            if(task.root.children.size()< min) min = task.root.children.size();
        }

        for(int i = 0; i <  min; i++) {
            int visitSum = 0;
            for(int j = 0; j < 8; j++) {
                visitSum += tasks.get(j).root.children.get(i).visits;
            }
            childVisits.add(visitSum);
        }
        return childVisits;
    }
}