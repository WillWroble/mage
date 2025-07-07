package mage.player.ai;

import ai.onnxruntime.OrtException;
import mage.constants.PhaseStep;
import mage.constants.RangeOfInfluence;
import mage.game.Game;
import mage.game.GameState;
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

    private transient StateEncoder encoder = null;
    private transient ReplayBuffer buffer = null;
    private static final int MAX_MCTS_CYCLES = 6;//number of additional cycles the search is allowed to run
    private static final int BASE_THREAD_TIMEOUT = 1;//seconds
    private static final int MIN_TREE_VISITS = 50;//per child per thread

    public static boolean SHOW_THREAD_INFO = false;
    public transient NeuralNetEvaluator nn;





    public ComputerPlayerMCTS2(String name, RangeOfInfluence range, int skill) {
        super(name, range, skill);
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

    /**
     * Evaluates a node's game state using the neural network.
     * This method encodes the state into sparse global indices, runs inference,
     * and updates the node's policy prior.
     *
     * @param node The MCTSNode to evaluate.
     * @return The value of the game state as predicted by the neural network's value head.
     */
    protected double evaluateState(MCTSNode node) {

        int[] activeGlobalIndices;

        encoder.processState(node.getGame());
        activeGlobalIndices = encoder.getFinalActiveGlobalIndicesArray();


        long[] onnxIndices = new long[activeGlobalIndices.length];

        for (int i = 0; i < activeGlobalIndices.length; i++) {
            onnxIndices[i] = activeGlobalIndices[i];
        }

        NeuralNetEvaluator.InferenceResult out = nn.infer(onnxIndices);
        node.policy = out.policy;

        return out.value;
    }

    public void setEncoder(StateEncoder enc) {
        encoder = enc;
    }
    public void setBuffer(ReplayBuffer buf) {
        buffer = buf;
    }
    public void initNN(String path) {
        try {
            nn = new NeuralNetEvaluator(path);
        } catch (OrtException e) {
            throw new RuntimeException(e);
        }
    }

    public double diffVisits(List<Integer> children) {
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
        return (max*1.0)/max2;
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
        int thinkTime = BASE_THREAD_TIMEOUT;


        if (this.threadPoolSimulations == null) {
            System.out.println(poolSize);
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

        while (averageVisits(childVisits)+initialVisits < MIN_TREE_VISITS*poolSize) {//use max visits of children as indicator

            if (cycleCounter > MAX_MCTS_CYCLES) break; //early exit

            if(diffVisits(childVisits) > 2.5 && averageVisits(childVisits) > MIN_TREE_VISITS*poolSize*0.5) break;

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
            root = new MCTSNode(playerId, sim);
            player.chooseTargetOptions = chooseTargetOptions;
            player.chooseTargetAction = new ArrayList<>(chosenChooseTargetActions);
            root.chooseTargetAction = new ArrayList<>(chosenChooseTargetActions);
        }
        applyMCTS(game, action);
        if (root != null) {
            MCTSNode best = root.bestChild();
            if(best == null) return;

            encoder.processMacroState(game);
            ActionEncoder.addAction(getActionVec());
            Game copiedState = game.copy();
            buffer.add(copiedState);

            //macroState = root.macroState;
            root = best;
            //game.setLastAction(root.action);
            root.emancipate();
        }
    }
    private List<Integer> getChildVisits(List<MCTSExecutor> tasks) {
        List<Integer> childVisits = new ArrayList<>();
        int min = Integer.MAX_VALUE;
        for(MCTSExecutor task : tasks) {
            if(task.root.children.size()< min) min = task.root.children.size();
        }

        for(int i = 0; i <  min; i++) {
            int visitSum = 0;
            for(int j = 0; j < poolSize; j++) {
                visitSum += tasks.get(j).root.children.get(i).visits;
            }
            childVisits.add(visitSum);
        }
        return childVisits;
    }
}