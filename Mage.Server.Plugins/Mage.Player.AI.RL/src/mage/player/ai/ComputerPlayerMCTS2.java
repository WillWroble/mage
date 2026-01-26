package mage.player.ai;


import mage.constants.PhaseStep;
import mage.constants.RangeOfInfluence;
import mage.game.Game;
import mage.player.ai.MCTSPlayer.NextAction;
import mage.player.ai.score.GameStateEvaluator2;
import mage.player.ai.score.GameStateEvaluator3;
import mage.players.Player;
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

    private transient StateEncoder stateEncoder = null;
    public static boolean SHOW_THREAD_INFO = true;
    /**if offline mode is on it won't use a neural network and will instead use a heuristic value function and even priors.
    is enabled by default if no network is found*/
    public boolean offlineMode = false;
    public String DEFAULT_URL = "http://127.0.0.1:50052";
    public transient RemoteModelEvaluator nn;



    public ComputerPlayerMCTS2(String name, RangeOfInfluence range, int skill) {
        super(name, range, skill);

    }

    protected ComputerPlayerMCTS2(UUID id) {
        super(id);
    }

    public ComputerPlayerMCTS2(final ComputerPlayerMCTS2 player) {
        super(player); nn = player.nn;
        stateEncoder = player.stateEncoder;
    }
    public void RLInit(Game game) {
        logger.info("RL init for " + getName() + " (MZ ver1.2)");
        Player opponent = game.getPlayer(game.getOpponents(playerId).iterator().next());
        //make encoder
        stateEncoder = new StateEncoder();
        stateEncoder.setAgent(getId());
        stateEncoder.setOpponent(opponent.getId());
        //find model endpoint
        try {
            nn = new RemoteModelEvaluator(DEFAULT_URL);
        } catch (Exception e) {
            logger.warn("Failed to establish connection to network model; falling back to offline mode");
            offlineMode = true;
        }
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

        Set<Integer> featureSet = stateEncoder.processState(game, node.playerId, myPlayer.getNextAction(), myPlayer.decisionText);
        node.stateVector = featureSet;

        if(offlineMode) {
            node.policy = null;
            if(myPlayer.getNextAction().equals(NextAction.PRIORITY)) {
                node.networkScore = GameStateEvaluator3.evaluateNormalized(playerId, game);
            } else {
                if(node.getParent() != null) {
                    node.networkScore = node.getParent().networkScore;
                } else {
                    node.networkScore = 0;
                }
            }
            return node.networkScore;
        }


        long[] nnIndices = new long[featureSet.size()];
        int k = 0;
        for (int i : featureSet)  {
            nnIndices[k++] = i;
        }

        RemoteModelEvaluator.InferenceResult out = nn.infer(nnIndices);
        //logger.warn("INFER");

        switch (myPlayer.getNextAction()) {
            case PRIORITY:
                if(noPolicyPriority) break;
                if (node.playerId.equals(playerId)) {
                    node.policy = out.policy_player;
                } else {
                    if (!noPolicyOpponent) {
                        node.policy = out.policy_opponent;
                    }
                }
                break;
            case CHOOSE_TARGET:
                if(noPolicyTarget) break;
                if (!noPolicyOpponent ||
                        myPlayer.chooseTargetOptions.stream().anyMatch(o -> game.getOwnerId(o) == null || game.getOwnerId(o).equals(playerId))) {
                    node.policy = out.policy_target;
                }
                break;
            case CHOOSE_USE:
                if(noPolicyUse) break;
                node.policy = out.policy_binary;
                break;
            default:
                node.policy = null;

        }

        node.networkScore = out.value;

        return node.networkScore;
    }

    public void setStateEncoder(StateEncoder enc) {
        stateEncoder = enc;
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
        if(stateEncoder == null) {
            RLInit(game);
        }
        if(actionEncoder == null) {
            actionEncoder = new ActionEncoder();
        }
        root.resetVirtual();
        int initialVisits = root.getVisits();
        int childVisits = getChildVisitsFromRoot().stream().mapToInt(Integer::intValue).sum();
        if (SHOW_THREAD_INFO) logger.info(String.format("STARTING ROOT VISITS: %d", initialVisits));


        double totalThinkTimeThisMove = 0;

        // Apply dirichlet noise once at the start of the search for this turn
        if(!noNoise) root.dirichletSeed = RandomUtil.nextInt();

        long startTime = System.nanoTime();
        long endTime = (long) (startTime + (searchTimeout * 1_000_000_000L));
        long maxEndTime = startTime + 60_000_000_000L;
        int simCount = 0;


        while (true) {
            if(System.nanoTime() > maxEndTime) {
                logger.error("force time out after one minute - couldn't find legal move");
                break;
            }
            if(root.containsPriorityNode()) { //can only exit search if the tree contains a priority node (meaning a future legal state)
                if (System.nanoTime() > endTime) {
                    logger.info("timed out, ending search");
                    break;
                }
                if (simCount + childVisits >= searchBudget) {
                    logger.info("required visits reached, ending search");
                    break;
                }
                if (root.size() >= MAX_TREE_NODES) {
                    logger.info("too many nodes in tree, ending search");
                    break;
                }
            }
            MCTSNode current = root;

            // selection
            while (!current.isLeaf()) {
                current = current.select(this.playerId);
            }
            //temporary reference to a game that represents this node's state
            Game tempGame = null;
            if(!current.isTerminal()) {//if terminal is true current must be finalized so skip getGame()
                tempGame = current.getGame();
                if(!tempGame.checkIfGameIsOver()) {
                    //remove child if failed script or node is already in tree
                    if (((MCTSPlayer)tempGame.getPlayer(current.playerId)).scriptFailed
                            || (current != root && root.getMatchingState(current.getFullStateValue(), current.nextAction, current.prefixScript, current.opponentPrefixScript) != null)) {
                        if (current.getParent() != null) {
                            current.getParent().purge(current);
                        } else {
                            logger.error("tried purging root node");
                        }
                        continue;
                    }
                }
                //finalize node
                current.isFinalized = true;
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
            current.backpropagate(result, false);
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
    }
    int[] getActionVec(MCTSNode node, boolean isPlayer) {
        int[] out = new int[128];
        Arrays.fill(out, 0);
        for (MCTSNode child : node.children) {
            if (child.getAction() != null) {
                int idx = actionEncoder.getActionIndex(child.getAction(), isPlayer);
                int v = child.getVisits();//un normalized counts
                out[idx%128] += v;
            }
        }
        return out;
    }
    int[] getTargetActionVec(MCTSNode node, Game game) {
        int[] out = new int[128];
        Arrays.fill(out, 0);
        for (MCTSNode child : node.children) {
            int idx = actionEncoder.getTargetIndex(game.getEntityName(child.chooseTargetAction));
            int v = child.getVisits();
            out[idx%128] += v;
        }
        return out;
    }
    int[] getUseActionVec(MCTSNode node) {
        int[] out = new int[128];
        Arrays.fill(out, 0);
        for (MCTSNode child : node.children) {
            int idx = child.useAction ? 1 : 0;
            int v = child.getVisits();
            out[idx] += v;
        }
        return out;
    }
    @Override
    protected MCTSNode calculateActions(Game game, NextAction action) {
        applyMCTS(game, action);
        if (root != null) {
            MCTSNode best = root.bestChild(game);
            if(best == null) return null;
            int[] actionVec = null;
            if (action == NextAction.PRIORITY) {
                actionVec = getActionVec(root, true);
            }
            else if(action == NextAction.CHOOSE_TARGET) {
                actionVec = getTargetActionVec(root, game);
            }
            else if(action == NextAction.CHOOSE_USE) {
                actionVec = getUseActionVec(root);
            }
            if(actionVec != null) stateEncoder.addLabeledState(root.stateVector, actionVec, root.getMeanScore(), action, true);
            return best;
            //root.emancipate();
        } else {
            logger.error("no root found somehow?");
            return null;
        }
    }
    /**
     * Helper method to get the visit counts of the root's children for a single tree.
     */
    private List<Integer> getChildVisitsFromRoot() {
        if (root == null || root.children.isEmpty()) {
            return new ArrayList<>();
        }
        return root.children.stream().map(MCTSNode::getVisits).collect(Collectors.toList());
    }
}