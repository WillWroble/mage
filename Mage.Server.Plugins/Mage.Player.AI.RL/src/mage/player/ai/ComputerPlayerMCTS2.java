package mage.player.ai;


import mage.constants.PhaseStep;
import mage.constants.RangeOfInfluence;
import mage.game.Game;
import mage.player.ai.MCTSPlayer.NextAction;
import mage.player.ai.score.GameStateEvaluator2;
import mage.players.PlayerScript;
import mage.util.RandomUtil;
import org.apache.log4j.Logger;

import java.util.*;
import java.util.stream.Collectors;

/**
 *
 * AlphaZero style MCTS that uses a Neural Network with the PUCT formula. Supports almost all decision points for MTG (priority, choose_target, choose_use, make_choice)
 * All decision types besides make_choice have their own learnable priors as heads of the neural network. priorityA and priorityB are deck dependent; choose_target and choose_use are matchup dependent.
 * See StateEncoder.java for how MTG states are vectorized for the network.
 *
 *
 * @author WillWroble
 */
public class ComputerPlayerMCTS2 extends ComputerPlayerMCTS {

    private static final Logger logger = Logger.getLogger(ComputerPlayerMCTS2.class);

    private transient StateEncoder encoder = null;
    private static final int BASE_THREAD_TIMEOUT = 4;//seconds
    private static final int MAX_TREE_VISITS = 150;//per thread
    public static final int[] PASS_ACTION = {0,1000,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0};
    public static boolean SHOW_THREAD_INFO = false;
    /**if offline mode is on it won't use a neural network and will instead use a heuristic value function and even priors.
    is enabled by default if no network is found*/
    public static boolean OFFLINE_MODE = false;
    public transient RemoteModelEvaluator nn;



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
    protected double evaluateState(MCTSNode node, Game game) {
        MCTSPlayer myPlayer = (MCTSPlayer)game.getPlayer(node.playerId);

        Set<Integer> featureSet = encoder.processState(game, node.playerId, myPlayer.getNextAction(), myPlayer.decisionText);
        node.stateVector = featureSet;

        if(OFFLINE_MODE) {
            node.policy = null;
            int heuristicScore = GameStateEvaluator2.evaluate(playerId, game).getTotalScore();
            node.networkScore = Math.tanh(heuristicScore * 1.0 / 20000);
            return node.networkScore;
        }


        long[] nnIndices = new long[featureSet.size()];
        int k = 0;
        for (int i : featureSet)  {
            nnIndices[k++] = i;
        }

        RemoteModelEvaluator.InferenceResult out = nn.infer(nnIndices);
        switch (myPlayer.getNextAction()) {
            case PRIORITY:
                if(node.playerId.equals(playerId)) {
                    node.policy = out.policy_player;
                } else {
                    if(!ROUND_ROBIN_MODE) {
                        node.policy = out.policy_opponent;
                    }
                }
                break;
            case CHOOSE_TARGET:
                if(!ROUND_ROBIN_MODE ||
                    myPlayer.chooseTargetOptions.stream().anyMatch(o -> game.getOwnerId(o) == null || game.getOwnerId(o).equals(playerId))) {
                    node.policy = out.policy_target;
                }
                break;
            case CHOOSE_USE:
                node.policy = out.policy_binary;
                break;
            default:
                node.policy = null;

        }
        node.networkScore = out.value;

        return node.networkScore;
    }

    public void setEncoder(StateEncoder enc) {
        encoder = enc;
    }
    @Override
    public boolean priority(Game game) {
        if (game.getTurnStepType() == PhaseStep.END_TURN) {
            GameStateEvaluator2.printBattlefield(game, game.getActivePlayerId());
        }
        return super.priority(game);
    }
    /**
     * A single-threaded version of applyMCTS that preserves the custom logic for
     * value function evaluation and dynamic termination conditions.
     */
    @Override
    protected void applyMCTS(final Game game, final NextAction action) {
        int initialVisits = root.getVisits();
        if (SHOW_THREAD_INFO) logger.info(String.format("STARTING ROOT VISITS: %d", initialVisits));


        double totalThinkTimeThisMove = 0;

        // Apply dirichlet noise once at the start of the search for this turn
        if(!NO_NOISE) root.dirichletSeed = RandomUtil.nextInt();


        long startTime = System.nanoTime();
        long endTime = startTime + (BASE_THREAD_TIMEOUT * 1_000_000_000L);
        int simCount = 0;

        // --- Run simulations for one cycle (e.g., 1 second) ---
        while (System.nanoTime() < endTime) {
            if(simCount + initialVisits >= MAX_TREE_VISITS) {
                logger.info("required visits reached, ending search");
                break;
            }
            if(root.size() >= MAX_TREE_NODES) {
                logger.info("too many nodes in tree, ending search");
                break;
            }
            MCTSNode current = root;

            // selection
            while (!current.isLeaf()) {
                current = current.select(this.playerId);
            }
            //temporary reference to a game that represents this nodes state
            Game tempGame = null;
            if(!current.isTerminal()) {//if terminal is true current must be finalized so skip getGame()
                tempGame = current.getGame(); //can become terminal here
                if(!current.isTerminal() && ((MCTSPlayer)tempGame.getPlayer(current.playerId)).scriptFailed) {//remove child if failed
                    current.getParent().children.remove(current);
                    continue;
                }
            }
            double result;
            if (!current.isTerminal()) {
                // eval
                result = evaluateState(current, tempGame);
                //expand
                current.expand(tempGame);

            } else {
                result = current.isWinner() ? 1.0 : -1.0;
                logger.debug("found terminal node in tree");
            }
            // backprop
            current.backpropagate(result);
            simCount++;
        }
        totalSimulations += simCount;
        totalThinkTimeThisMove += (System.nanoTime() - startTime)/1e9;

        if (SHOW_THREAD_INFO) {
            logger.info(String.format("Ran %d simulations.", simCount));
            logger.info(String.format("COMPOSITE CHILDREN: %s", getChildVisitsFromRoot().toString()));
        }


        totalThinkTime += totalThinkTimeThisMove;

        if (SHOW_THREAD_INFO) {
            logger.info("Player: " + name + " simulated " + simCount + " evaluations in " + totalThinkTimeThisMove
                    + " seconds - nodes in tree: " + root.size());
            logger.info("Total: simulated " + totalSimulations + " evaluations in " + totalThinkTime
                    + " seconds - Average: " + (totalThinkTime > 0 ? totalSimulations / totalThinkTime : 0));
        }
        MCTSNode.logHitMiss();
    }
    int[] getActionVec(MCTSNode node, boolean isPlayer) {
        int[] out = new int[128];
        Arrays.fill(out, 0);
        for (MCTSNode child : node.children) {
            if (child.getAction() != null) {
                int idx = ActionEncoder.getActionIndex(child.getAction(), isPlayer);
                int v = child.visits;//un normalized counts
                out[idx%128] += v;
            }
        }
        return out;
    }
    int[] getTargetActionVec(MCTSNode node, Game game) {
        int[] out = new int[128];
        Arrays.fill(out, 0);
        for (MCTSNode child : node.children) {
            int idx = ActionEncoder.getTargetIndex(game.getEntity(child.chooseTargetAction).toString());
            int v = child.visits;
            out[idx%128] += v;
        }
        return out;
    }
    int[] getUseActionVec(MCTSNode node) {
        int[] out = new int[128];
        Arrays.fill(out, 0);
        for (MCTSNode child : node.children) {
            int idx = child.useAction ? 1 : 0;
            int v = child.visits;
            out[idx] += v;
        }
        return out;
    }
    @Override
    protected void calculateActions(Game game, NextAction action) {
        if (root == null) {
            Game sim = createMCTSGame(game.getLastPriority());
            MCTSPlayer player = (MCTSPlayer) sim.getPlayer(playerId);
            player.setNextAction(action);
            //this creates a new root with its own saved state to replay from.
            root = new MCTSNode(playerId, sim);
            root.prefixScript = new PlayerScript(getPlayerHistory());
            root.opponentPrefixScript = new PlayerScript(game.getPlayer(game.getOpponents(playerId).iterator().next()).getPlayerHistory());
            logger.debug("prefix at root: " + root.prefixScript.toString());
            logger.debug("opponent prefix at root: " + root.opponentPrefixScript.toString());
        }
        applyMCTS(game, action);
        if (root != null) {
            MCTSNode best = root.bestChild(game);
            if(best == null) return;
            int[] actionVec = null;
            if (action == NextAction.PRIORITY) {
                actionVec = getActionVec(root, name.equals("PlayerA"));
            }
            else if(action == NextAction.CHOOSE_TARGET) {
                actionVec = getTargetActionVec(root, game);
            }
            else if(action == NextAction.CHOOSE_USE) {
                actionVec = getUseActionVec(root);
            }
            if(actionVec != null) encoder.addLabeledState(root.stateVector, actionVec, root.getScoreRatio(), action, getName().equals("PlayerA"));
            root = best;
            root.emancipate();
        } else {
            logger.error("no root found somehow?");
        }
    }
    /**
     * Helper method to get the visit counts of the root's children for a single tree.
     */
    private List<Integer> getChildVisitsFromRoot() {
        if (root == null || root.children.isEmpty()) {
            return new ArrayList<>();
        }
        return root.children.stream().map(child -> child.visits).collect(Collectors.toList());
    }
}