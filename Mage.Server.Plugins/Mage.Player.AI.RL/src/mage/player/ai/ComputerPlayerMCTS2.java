package mage.player.ai;


import mage.constants.PhaseStep;
import mage.constants.RangeOfInfluence;
import mage.game.Game;
import mage.player.ai.encoder.ActionEncoder;
import mage.player.ai.encoder.StateEncoder;
import mage.player.ai.score.GameStateEvaluator2;
import mage.player.ai.score.GameStateEvaluator3;
import mage.players.Player;
import mage.players.PlayerScript;
import org.apache.log4j.Logger;

import java.util.*;
import java.util.stream.Collectors;

/**
 *
 * AlphaZero style MCTS that uses a Neural Network with the PUCT formula. Supports almost all decision points for MTG (priority, choose_target, choose_use, make_choice, choose_mode)
 * All decision types besides make_choice have their own learnable priors as heads of the neural network. priorityA and priorityB are deck dependent; choose_target and choose_use are matchup dependent.
 * See StateEncoder.java for how MTG states are vectorized for the network.
 *
 *
 * @author WillWroble
 */
public class ComputerPlayerMCTS2 extends ComputerPlayerMCTS {

    private static final Logger logger = Logger.getLogger(ComputerPlayerMCTS2.class);


    public static boolean SHOW_THREAD_INFO = true;
    /**if offline mode is on it won't use a neural network and will instead use a heuristic value function and uniform priors.
    is enabled by default if no network is found*/
    public boolean offlineMode = false;
    public String defaultURL = "http://127.0.0.1:50052";
    public transient RemoteModelEvaluator nn;



    public ComputerPlayerMCTS2(String name, RangeOfInfluence range, int skill) {
        super(name, range, skill);

    }

    protected ComputerPlayerMCTS2(UUID id) {
        super(id);
    }

    public ComputerPlayerMCTS2(final ComputerPlayerMCTS2 player) {
        super(player);
        nn = player.nn;
        offlineMode = player.offlineMode;
        defaultURL = player.defaultURL;
    }
    public void RLInit(Game game) {
        logger.info("RL init for " + getName() + " (MZ ver1.0.2)");
        Player opponent = game.getOpponent(playerId);
        //make encoder
        stateEncoder = new StateEncoder();
        stateEncoder.setAgent(getId());
        stateEncoder.setOpponent(opponent.getId());
        //find model endpoint
        try {
            nn = new RemoteModelEvaluator(defaultURL);
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
     * Evaluates a node's game state using a neural network.
     * This method collects the encoded state of sparse indices, runs inference,
     * and updates the node's policy prior. If network can not be found falls back to heuristic evaluation
     *
     * @param node The MCTSNode to evaluate.
     * @return The value of the game state as predicted by the neural network's value head.
     */
    protected double evaluateState(MCTSNode node) {
        MCTSPlayer myPlayer = node.getPlayer();
        Game game = node.getGame();
        if(offlineMode) {
            node.policy = null;
            if(myPlayer.getNextAction().equals(ActionEncoder.ActionType.PRIORITY)) {
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


        long[] nnIndices = new long[node.stateVector.size()];
        int k = 0;
        for (int i : node.stateVector)  {
            nnIndices[k++] = i;
        }

        RemoteModelEvaluator.InferenceResult out = nn.infer(nnIndices);

        switch (myPlayer.getNextAction()) {
            case PRIORITY:
                if(noPolicyPriority) break;
                if (myPlayer.getId().equals(playerId)) {
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
    @Override
    public boolean priority(Game game) {
        if (game.getTurnStepType() == PhaseStep.END_TURN) {
            GameStateEvaluator2.printBattlefield(game, game.getActivePlayerId());
        }
        return super.priority(game);
    }
    /**
     * core MCTS search implementation, with legality and transposition pruning
     */
    @Override
    protected void applyMCTS(final Game game, final ActionEncoder.ActionType action) {
        if(!action.equals(ActionEncoder.ActionType.PRIORITY)) {
            allMana = false;
        }
        root.resetVirtual();
        int initialVisits = root.getVisits();
        int childVisits = getChildVisitsFromRoot().stream().mapToInt(Integer::intValue).sum();
        if (SHOW_THREAD_INFO) logger.info(String.format("STARTING ROOT VISITS: %d", initialVisits));


        double totalThinkTimeThisMove = 0;

        // Apply dirichlet noise once at the start of the search for this turn
        if(!noNoise) root.randomizeNoiseSeed();

        long startTime = System.nanoTime();
        long endTime = (long) (startTime + (searchTimeout * 1_000_000_000L));
        long maxEndTime = startTime + 60_000_000_000L;
        int simCount = 0;
        int illegalPurged = 0;
        int duplicatePurged = 0;


        while (true) {
            if(System.nanoTime() > maxEndTime) {
                logger.error("force time out after one minute - couldn't find legal move");
                break;
            }
            if(root.containsLegalNode()) { //can only exit search if the tree contains a priority node (meaning a future legal state)
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
            if(current.getParent() == null) {
                logger.error("root not pre-expanded");
            }
            if(!current.isTerminal()) {//if terminal is true current must be finalized so skip getGame()

                current.validateState();//can become terminal here

                if(!current.isTerminal()) {
                    //remove child if failed script
                    if (current.getPlayer().scriptFailed) {
                        illegalPurged++;
                        current.getParent().purge(current);
                        continue;
                    }
                    //remove child if node is already in the tree
                    MCTSNode match = current.getPlayerScope().getMatchingState(current.stateVector);
                    if(match != null) {
                        duplicatePurged++;
                        current.backpropagate(match.networkScore);
                        current.getParent().purge(current);
                        continue;
                    }
                }
            }
            double result;
            if (!current.isTerminal()) {
                // eval
                result = evaluateState(current);
                //expand
                current.expand();

            } else {
                result = current.isWinner() ? 1.0 : -1.0;
                logger.debug("found terminal node in tree");
            }
            // backprop
            current.backpropagate(result);
            simCount++;
        }
        totalSimulations += simCount;
        totalThinkTimeThisMove = (System.nanoTime() - startTime)/1e9;
        totalThinkTime += totalThinkTimeThisMove;

        if (SHOW_THREAD_INFO && !allMana) {
            logger.info(String.format("Ran %d simulations.", simCount));
            logger.info(String.format("COMPOSITE CHILDREN: %s", getChildVisitsFromRoot().toString()));
            logger.info("Player: " + name + " simulated " + simCount + " evaluations in " + totalThinkTimeThisMove
                    + " seconds - nodes in tree: " + root.size());
            logger.info("Total: simulated " + totalSimulations + " evaluations in " + totalThinkTime
                    + " seconds - Average: " + (totalThinkTime > 0 ? totalSimulations / totalThinkTime : 0));
            logger.info(illegalPurged + " illegals purged, " + duplicatePurged + " duplicates purged");
        }
    }
    int[] getActionVec(MCTSNode node, boolean isPlayer) {
        int[] out = new int[128];
        Arrays.fill(out, 0);
        for (MCTSNode child : node.getChildren()) {
            if (child.getPriorityAction() != null) {
                int idx = actionEncoder.getActionIndex(child.getPriorityAction(), isPlayer);
                int v = child.getVisits();//un normalized counts
                out[idx%128] += v;
            }
        }
        return out;
    }
    int[] getTargetActionVec(MCTSNode node, Game game) {
        int[] out = new int[128];
        Arrays.fill(out, 0);
        for (MCTSNode child : node.getChildren()) {
            int idx = actionEncoder.getTargetIndex(game.getEntityName(child.getTargetAction(), playerId));
            int v = child.getVisits();
            out[idx%128] += v;
        }
        return out;
    }
    int[] getUseActionVec(MCTSNode node) {
        int[] out = new int[128];
        Arrays.fill(out, 0);
        for (MCTSNode child : node.getChildren()) {
            int idx = child.getUseAction() ? 1 : 0;
            int v = child.getVisits();
            out[idx] += v;
        }
        return out;
    }
    @Override
    protected MCTSNode getNextAction(Game game, ActionEncoder.ActionType actionType) {

        if(stateEncoder == null) {
            RLInit(game);
        }
        if(actionEncoder == null) {
            actionEncoder = new ActionEncoder();
        }

        Game sim = createMCTSGame(game.getLastPriority());
        PlayerScript prefixScript = new PlayerScript(getPlayerHistory());
        PlayerScript opponentPrefixScript = new PlayerScript(game.getOpponent(playerId).getPlayerHistory());
        MCTSNode newRoot = new MCTSNode(this, sim, actionType, prefixScript, opponentPrefixScript);

        //do first expansion automatically
        newRoot.validateState();
        evaluateState(newRoot);
        newRoot.expand();
        if(!prefixScript.isEmpty() || !opponentPrefixScript.isEmpty() ) {
            logger.info("prefix at root: " + prefixScript);
            logger.info("opponent prefix at root: " + opponentPrefixScript);
        }
        if (root != null) {
            root = root.getMatchingState(newRoot.stateVector);
        }
        if (root == null) {
            root = newRoot;
        }
        root.emancipate();
        return calculateActions(game, actionType);
    }
    @Override
    protected MCTSNode calculateActions(Game game, ActionEncoder.ActionType action) {
        applyMCTS(game, action);

        MCTSNode best = root.bestChild(game);
        if(best == null) {
            logger.error("no best child");
            return null;
        }
        int[] actionVec = null;
        if (action == ActionEncoder.ActionType.PRIORITY) {
            actionVec = getActionVec(root, true);
        }
        else if(action == ActionEncoder.ActionType.CHOOSE_TARGET) {
            actionVec = getTargetActionVec(root, game);
        }
        else if(action == ActionEncoder.ActionType.CHOOSE_USE) {
            actionVec = getUseActionVec(root);
        }
        if(actionVec != null) stateEncoder.addLabeledState(root.stateVector, actionVec, root.getMeanScore(), action, true);

        return best;

    }
    @Override
    public boolean isMCTSComputerPlayer() {
        return true;
    }
    /**
     * Helper method to get the visit counts of the root's children for a single tree.
     */
    private List<Integer> getChildVisitsFromRoot() {
        if (root == null || root.getChildren().isEmpty()) {
            return new ArrayList<>();
        }
        return root.getChildren().stream().map(MCTSNode::getVisits).collect(Collectors.toList());
    }
}