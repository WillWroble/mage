package mage.player.ai;

import ai.onnxruntime.OrtException;
import javafx.util.Pair;
import mage.abilities.Ability;
import mage.abilities.ActivatedAbility;
import mage.abilities.common.PassAbility;
import mage.constants.PhaseStep;
import mage.constants.RangeOfInfluence;
import mage.game.Game;
import mage.game.combat.Combat;
import mage.game.combat.CombatGroup;
import mage.game.turn.Phase;
import mage.player.ai.MCTSPlayer.NextAction;
import mage.players.Player;
import mage.util.ThreadUtils;
import mage.util.XmageThreadFactory;
import org.apache.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

/**
 * ComputerPlayerMCTS2 extends ComputerPlayerMCTS and uses a value function at leaf nodes.
 * It replaces full rollout simulations with a call to evaluateState() on the leaf game state.
 */
public class ComputerPlayerMCTS2 extends ComputerPlayerMCTS {

    private static final Logger logger = Logger.getLogger(ComputerPlayerMCTS2.class);

    private StateEncoder encoder = null;
    private static final int MAX_MCTS_CYCLES = 5;//number of additional cycles the search is allowed to run
    private static final int BASE_THREAD_TIMEOUT = 3;//seconds
    private static final int MIN_TREE_VISITS = 100;
    private static final int MAX_TREE_VISITS = 200;

    public static boolean SHOW_THREAD_INFO = false;
    public NeuralNetEvaluator nn;

    public static String PATH_TO_NN = "null";
    private final Object encoderLock = new Object();


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

    /**
     * Evaluate the game state for the given player.
     * Replace this placeholder with your actual value network call.
     *
     * @param node     the game state to evaluate

     */
    protected double evaluateState(MCTSNode node) {
        //return GameStateEvaluator2.evaluate(playerId, game).getTotalScore();

        // 1) First, encode the state exactly as you do in processState(),
        //    but return it as a float[] of length S (with 0f/1f).
        boolean[] bits;
        NeuralNetEvaluator.InferenceResult out;
        synchronized(encoderLock) {
            encoder.processState(node.getGame());
            bits = encoder.getCompressedVector(StateEncoder.featureVector);

            float[] input = new float[bits.length];
            for (int i = 0; i < bits.length; i++) input[i] = bits[i] ? 1.0f : 0.0f;

            // 2) Run the ONNX model
            out = nn.infer(input);
        }

        node.policy = out.policy;
        // 3a) If you just want a value‐based rollout, return the value:
        return (out.value);  // scale or cast to int as you like

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
            if (game.getActivePlayerId() == getId()) {
                if (encoder != null) {
                    System.out.println("ENCODING STATE...");
                    encoder.processMacroState(game);
                }
            }
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
        for (int i = 0; i < poolSize; i++) {
            Game sim = createMCTSGame(game);
            MCTSPlayer player = (MCTSPlayer) sim.getPlayer(playerId);
            player.setNextAction(action);
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