package mage.player.ai;

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

    private StateEncoder encoder;
    private static final int MAX_MCTS_CYCLES = 5;//number of additional cycles the search is allowed to run
    private static final int BASE_THREAD_TIMEOUT = 3;
    private static final int MIN_TREE_VISITS = 500;
    public static boolean SHOW_THREAD_INFO = false;


    public ComputerPlayerMCTS2(String name, RangeOfInfluence range, int skill) {
        super(name, range, skill);
    }

    protected ComputerPlayerMCTS2(UUID id) {
        super(id);
    }

    public ComputerPlayerMCTS2(final ComputerPlayerMCTS2 player) {
        super(player);
    }

    @Override
    public ComputerPlayerMCTS2 copy() {
        return new ComputerPlayerMCTS2(this);
    }

    /**
     * Evaluate the game state for the given player.
     * Replace this placeholder with your actual value network call.
     *
     * @param game     the game state to evaluate
     * @param playerId the player's UUID
     * @return an integer evaluation (positive for favorable, negative otherwise)
     */
    protected int evaluateState(Game game, UUID playerId) {
        // TODO: Integrate your value network here.
        // For now, return heuristic value
        return GameStateEvaluator2.evaluate(playerId, game).getTotalScore();
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
    @Override
    public boolean priority(Game game) {
        if (game.getTurnStepType() == PhaseStep.END_TURN) {
            if (game.getActivePlayerId() == getId()) {
                if (encoder != null) {
                    System.out.println("ENCODING STATE...");
                    encoder.processState(game);
                }
            }
            GameStateEvaluator2.printBattlefield(game, game.getActivePlayerId());
        }
        boolean out = super.priority(game);
        ActionEncoder.addAction(root.getAction());
        return out;
    }

    /**
     * Overrides applyMCTS to use the value function at leaf nodes.
     */
    @Override
    protected void applyMCTS(final Game game, final NextAction action) {

        if(root.getNumChildren() > 0 && root.visits/root.getNumChildren() > 1500) return;
        if(root.visits/(root.getNumChildren()+1) < 300) root.reset(); //better to start fresh if existing tree is too shallow
        if(SHOW_THREAD_INFO) System.out.printf("STARTING ROOT VISITS: %d\n", root.visits);
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
                protected int rollout(MCTSNode node) {
                    // Instead of a full simulation, evaluate the leaf state with our value function.
                    return evaluateState(node.getGame(), playerId);
                }
            };
            if(i < 2 && root.getNumChildren() > 0) {//reserve first 2 threads to build off existing root
                //exec.root = new MCTSNode(root);
            }
            tasks.add(exec);
        }
        //runs mcts sims until the root has been visited enough times
        int diffVisits = 0;
        int cycleCounter = 0;

        while (diffVisits < MIN_TREE_VISITS) {//use max visits of children as indicator

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
            if (SHOW_THREAD_INFO) System.out.printf("CYCLE %d: %d threads were created\n", cycleCounter, tasks.size());

            List<Integer> childVisits = new ArrayList<>();

            for(int i = 0; i <  tasks.get(7).root.children.size(); i++) {
                int visitSum = 0;
                for(int j = 0; j < 8; j++) {
                    visitSum += tasks.get(j).root.children.get(i).visits;
                }
                childVisits.add(visitSum);
            }
            diffVisits = diffVisits(childVisits);
            if (SHOW_THREAD_INFO) {
                System.out.printf("COMPOSITE CHILDREN: %s\n", childVisits.toString());
                System.out.printf("MAX DIFF OF CHILDREN: %d\n", diffVisits);
            }
            thinkTime += 1;
        }

        int simCount = 0;
        for (MCTSExecutor task : tasks) {
            if (task.reachedTerminalState && SHOW_THREAD_INFO)
                System.out.print("-task reached a terminal state-");
            if (SHOW_THREAD_INFO) System.out.printf("%d ", task.simCount);
            simCount += task.getSimCount();
            root.merge(task.getRoot());
            task.clear();
        }
        if (SHOW_THREAD_INFO) System.out.println();
        tasks.clear();
        totalThinkTime += thinkTime;
        totalSimulations += simCount;
        if (SHOW_THREAD_INFO) {
            logger.info("Player: " + name + " simulated " + simCount + " evaluations in " + thinkTime
                    + " seconds - nodes in tree: " + root.size());
            logger.info("Total: simulated " + totalSimulations + " evaluations in " + totalThinkTime
                    + " seconds - Average: " + totalSimulations / totalThinkTime);
        }
        MCTSNode.logHitMiss();
    }
}