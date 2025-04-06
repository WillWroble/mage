package mage.player.ai;

import mage.abilities.Ability;
import mage.abilities.ActivatedAbility;
import mage.abilities.common.PassAbility;
import mage.constants.PhaseStep;
import mage.constants.RangeOfInfluence;
import mage.game.Game;
import mage.game.combat.Combat;
import mage.game.combat.CombatGroup;
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
     * @param game the game state to evaluate
     * @param playerId the player's UUID
     * @return an integer evaluation (positive for favorable, negative otherwise)
     */
    protected int evaluateState(Game game, UUID playerId) {
        // TODO: Integrate your value network here.
        // For now, return a dummy value (0).
        return GameStateEvaluator2.evaluate(playerId, game).getTotalScore();
    }

    /**
     * Overrides applyMCTS to use the value function at leaf nodes.
     */
    @Override
    protected void applyMCTS(final Game game, final NextAction action) {
        int thinkTime = calculateThinkTime(game, action);

        if (thinkTime > 0) {
            if (USE_MULTIPLE_THREADS) {
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
                    tasks.add(exec);
                }

                try {
                    List<Future<Boolean>> runningTasks = threadPoolSimulations.invokeAll(tasks, thinkTime, TimeUnit.SECONDS);
                    for (Future<Boolean> runningTask : runningTasks) {
                        runningTask.get();
                    }
                } catch (InterruptedException | CancellationException e) {
                    logger.warn("applyMCTS timeout");
                } catch (ExecutionException e) {
                    if (this.isTestsMode()) {
                        throw new IllegalStateException("One of the simulated games raised an error: " + e, e);
                    }
                }

                int simCount = 0;
                for (MCTSExecutor task : tasks) {
                    simCount += task.getSimCount();
                    root.merge(task.getRoot());
                    task.clear();
                }
                tasks.clear();
                totalThinkTime += thinkTime;
                totalSimulations += simCount;
                logger.info("Player: " + name + " simulated " + simCount + " evaluations in " + thinkTime
                        + " seconds - nodes in tree: " + root.size());
                logger.info("Total: simulated " + totalSimulations + " evaluations in " + totalThinkTime
                        + " seconds - Average: " + totalSimulations / totalThinkTime);
                MCTSNode.logHitMiss();
            }
            else {
                long startTime = System.nanoTime();
                long endTime = startTime + (thinkTime * 1000000000L);
                MCTSNode current;
                int simCount = 0;
                while (System.nanoTime() < endTime) {
                    current = root;
                    // Selection: traverse down the tree until a leaf is reached.
                    while (!current.isLeaf()) {
                        current = current.select(this.playerId);
                    }
                    int result;
                    if (!current.isTerminal()) {
                        // Expansion:
                        current.expand();
                        // Select a child and evaluate using the value function.
                        current = current.select(this.playerId);
                        result = evaluateState(current.getGame(), this.playerId);
                        simCount++;
                    } else {
                        result = current.isWinner(this.playerId) ? 1 : -1;
                    }
                    // Backpropagation:
                    current.backpropagate(result);
                }
                logger.info("Simulated " + simCount + " evaluations - nodes in tree: " + root.size());
            }
        }
    }
}
