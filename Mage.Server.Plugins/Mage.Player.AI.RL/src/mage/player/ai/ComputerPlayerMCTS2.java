package mage.player.ai;

import ai.onnxruntime.OrtException;
import mage.constants.PhaseStep;
import mage.constants.RangeOfInfluence;
import mage.game.Game;
import mage.player.ai.MCTSPlayer.NextAction;
import mage.players.PlayerScript;
import mage.util.RandomUtil;
import org.apache.log4j.Logger;

import java.util.*;
import java.util.stream.Collectors;

/**
 * ComputerPlayerMCTS2 extends ComputerPlayerMCTS and uses a value function at leaf nodes.
 * It replaces full rollout simulations with a call to evaluateState() on the leaf game state.
 */
public class ComputerPlayerMCTS2 extends ComputerPlayerMCTS {

    private static final Logger logger = Logger.getLogger(ComputerPlayerMCTS2.class);

    private transient StateEncoder encoder = null;
    private transient ReplayBuffer buffer = null;
    private static final int MAX_MCTS_CYCLES = 6;//number of additional cycles the search is allowed to run
    private static final int BASE_THREAD_TIMEOUT = 4;//seconds
    private static final int MIN_TREE_VISITS_PER_CHILD = 100;//per child per thread
    private static final int MAX_TREE_VISITS = 300;//per thread
    private static final int MAX_TREE_NODES = 800;//per thread

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
    protected double evaluateState(MCTSNode node, Game game) {
        MCTSPlayer myPlayer = (MCTSPlayer)game.getPlayer(node.playerId);
        //int microDecision = myPlayer.getNextAction()==MCTSPlayer.NextAction.CHOOSE_TARGET || myPlayer.getNextAction() == NextAction.MAKE_CHOICE ? 1 + myPlayer.chooseTargetCount + myPlayer.makeChoiceCount : 0;
        int microDecision = 0;
        Set<Integer> featureSet = encoder.processState(game, node.playerId, microDecision);

        int keep = 0;
        for (int i : featureSet) if (!StateEncoder.globalIgnore.contains(i)) keep++;
        long[] onnxIndices = new long[keep];
        int k = 0;
        for (int i : featureSet) if (!StateEncoder.globalIgnore.contains(i)) onnxIndices[k++] = i;

        NeuralNetEvaluator.InferenceResult out = nn.infer(onnxIndices);

        node.policy = out.policy; //combat decisions don't get priors but get filtered out in expand()
        node.initialScore = out.value;

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
     * A single-threaded version of applyMCTS that preserves the custom logic for
     * value function evaluation and dynamic termination conditions.
     */
    @Override
    protected void applyMCTS(final Game game, final NextAction action) {
        int initialVisits = root.getVisits();
        if (SHOW_THREAD_INFO) logger.info(String.format("STARTING ROOT VISITS: %d", initialVisits));


        double totalThinkTimeThisMove = 0;

        // Apply dirichlet noise once at the start of the search for this turn
        root.dirichletSeed = RandomUtil.nextInt();


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
                //if(current.action != null)logger.info("selected: " + current.action.toString());
            }
            double result;
            //temporary reference to a game that represents this nodes state
            Game tempGame = null;
            if(!current.isTerminal()) {//if terminal is true current must be finalized so skip getGame()
                tempGame = current.getGame();
            }
            if (!current.isTerminal()) {
                // eval
                result = evaluateState(current, tempGame);
                //expand
                current.expand(tempGame);

            } else {
                result = current.isWinner() ? 1.0 : -1.0;
                logger.info("found terminal node in tree");
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
    double[] getActionVec() {
        double tau = 1.0;            // your temperature hyperparam
        int    A   = 128;
        double[] out = new double[A];
        Arrays.fill(out, 0.0);
        double sum = 0;
        // 1) accumulate visits^(1/tau)
        for (MCTSNode child : root.children) {
            if (child.getAction() != null) {
                int idx = ActionEncoder.getAction(child.getAction());
                double v = child.visits;
                // apply temperature
                double vt = Math.pow(v, 1.0 / tau);
                out[idx] += vt;
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
    double[] getMicroActionVec(NextAction nextAction, Game game) {
        double tau = 1.0;            // your temperature hyperparam
        int    A   = 128;
        double[] out = new double[A];
        Arrays.fill(out, 0.0);
        double sum = 0;
        // 1) accumulate visits^(1/tau)
        for (MCTSNode child : root.children) {
            if (child.getAction() != null) {
                int idx = -1;
                if(nextAction == NextAction.CHOOSE_TARGET) {
                    idx = ActionEncoder.getMicroAction(game.getObject(child.chooseTargetAction.iterator().next()).getName());
                } else if(nextAction == NextAction.MAKE_CHOICE) {
                    idx = ActionEncoder.getMicroAction(child.choiceAction);
                } else {
                    logger.error("not a recognized micro action");
                }
                double v = child.visits;
                // apply temperature
                double vt = Math.pow(v, 1.0 / tau);
                out[idx%128] += vt;
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
            Game sim = createMCTSGame(game.getLastPriority());
            MCTSPlayer player = (MCTSPlayer) sim.getPlayer(playerId);
            player.setNextAction(action);
            //this creates a new root with its own saved state to replay from.
            root = new MCTSNode(playerId, sim);
            root.prefixScript = new PlayerScript(getPlayerHistory());
            root.opponentPrefixScript = new PlayerScript(game.getPlayer(game.getOpponents(playerId).iterator().next()).getPlayerHistory());
            logger.info("prefix at root: " + root.prefixScript.toString());
            logger.info("opponent prefix at root: " + root.opponentPrefixScript.toString());
        }
        applyMCTS(game, action);
        if (root != null) {
            MCTSNode best = root.bestChild(game);
            if(best == null) return;

            if (action == NextAction.PRIORITY) {
                encoder.processMacroState(game, getId());
                encoder.addAction(getActionVec());
                encoder.stateScores.add(root.getScoreRatio());
            }
//            else if(action == NextAction.CHOOSE_TARGET || action == NextAction.MAKE_CHOICE) {
//                encoder.processMicroState(game, getId(), 1+chooseTargetAction.size() + choiceAction.size());
//                encoder.addAction(getMicroActionVec(action, game));
//                encoder.stateScores.add(root.getScoreRatio());
//            }
            root = best;
            root.emancipate();
        } else {
            logger.error("no root found somehow?");
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