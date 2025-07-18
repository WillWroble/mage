
package mage.player.ai;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import mage.MageInt;
import mage.constants.PhaseStep;
import mage.constants.Zone;
import mage.abilities.Ability;
import mage.abilities.PlayLandAbility;
import mage.abilities.common.PassAbility;
import mage.cards.Card;
import mage.game.Game;
import mage.game.GameState;
import mage.game.combat.Combat;
import mage.game.turn.Step.StepPart;
import mage.players.Player;
import mage.util.RandomUtil;
import org.apache.log4j.Logger;
import java.util.Random;
import org.apache.commons.math3.distribution.GammaDistribution;
import org.apache.commons.math3.distribution.GammaDistribution;
import org.apache.commons.math3.random.JDKRandomGenerator;

import static java.lang.Math.*;

/**
 *
 * @author BetaSteward_at_googlemail.com
 */
public class MCTSNode {

    public static final boolean USE_ACTION_CACHE = false;
    private static final double selectionCoefficient = Math.sqrt(2.0);
    private static final double passRatioTolerance = 0.0;
    private static final Logger logger = Logger.getLogger(MCTSNode.class);


    private boolean stackIsEmpty = true;

    public int visits = 0;
    private int wins = 0;
    public double score = 0;
    private MCTSNode parent = null;
    public final List<MCTSNode> children = new ArrayList<>();
    public Ability action;
    public List<Set<UUID>> chooseTargetAction = new ArrayList<>();
    private Game game;//only contains shared game

    public Combat combat;
    private final String stateValue;
    private final String fullStateValue;
    public UUID playerId;
    private boolean terminal = false;
    public UUID targetPlayer;
    public int depth = 1;
    public float[] policy = null;
    private double prior = 1;

    private static int nodeCount;

    public MCTSNode(UUID targetPlayer, Game game) {
        this.targetPlayer = targetPlayer;
        this.game = game;
        this.stateValue = game.getState().getValue(game, targetPlayer);
        this.fullStateValue = game.getState().getValue(true, game);
        this.stackIsEmpty = game.getStack().isEmpty();
        this.terminal = game.checkIfGameIsOver();
        this.action = game.getPlayer(game.getLastPriorityPlayerId()).getLastActivated();
        if(this.action == null) {
            logger.error("action in node is null\n");
        }
        setPlayer();
        nodeCount = 1;
//        logger.info(this.stateValue);
    }    

    protected MCTSNode(MCTSNode parent, Game game, Ability action) {
        this.targetPlayer = parent.targetPlayer;
        this.game = game;
        this.stateValue = game.getState().getValue(game, targetPlayer);
        this.fullStateValue = game.getState().getValue(true, game);
        this.stackIsEmpty = game.getStack().isEmpty();
        this.terminal = game.checkIfGameIsOver();
        this.parent = parent;
        this.action = action;

        setPlayer();
        nodeCount++;
//        logger.info(this.stateValue);
    }

    protected MCTSNode(MCTSNode parent, Game game, Combat combat) {
        this.targetPlayer = parent.targetPlayer;
        this.game = game;
        //this.gameState = game.getState().copy();
        this.combat = combat;
        this.stateValue = game.getState().getValue(game, targetPlayer);
        this.fullStateValue = game.getState().getValue(true, game);
        this.stackIsEmpty = game.getStack().isEmpty();
        this.terminal = game.checkIfGameIsOver();
        this.parent = parent;

        setPlayer();
        nodeCount++;
//        logger.info(this.stateValue);
    }
    //dont use
    protected MCTSNode(MCTSNode node) {
        combat = null; action = null; game = null;
        if(node.combat != null) combat = node.combat.copy();
        if(node.action != null) action = node.action.copy();
        if(node.game != null) game = node.game.copy();
        playerId = node.playerId;
        targetPlayer = node.targetPlayer;
        stackIsEmpty = node.stackIsEmpty;
        terminal = node.terminal;
        stateValue = node.stateValue;
        fullStateValue = node.fullStateValue;
        depth = node.depth;
        visits = node.visits;
        score = node.score;
        wins = node.wins;
        parent = null;
        nodeCount++;
        List<MCTSNode> listCopy = new ArrayList<>(node.children);
        //List<MCTSNode> toAdd = new ArrayList<>();
        for (MCTSNode child : listCopy) {
            MCTSNode newChild = new MCTSNode(child);
            newChild.parent = this;
            children.add(newChild);
        }

    }
    private void setPlayer() {
        //System.out.println("this happening");
        for (Player p : game.getPlayers().values()) {
            MCTSPlayer mctsP = (MCTSPlayer) p;
            if(mctsP.lastToAct) {
                playerId = p.getId();
                return;
            }
        }
        if(game.checkIfGameIsOver()) {
            logger.info("TERMINAL STATE\n");
            return;
        }
        System.out.println("this should not happen");
        assert (false);
//        if (game.getStep().getStepPart() == StepPart.PRIORITY) {
//            playerId = game.getPriorityPlayerId();
//        } else {
//            if (game.getTurnStepType() == PhaseStep.DECLARE_BLOCKERS) {
//                playerId = game.getCombat().getDefenders().iterator().next();
//            } else {
//                playerId = game.getActivePlayerId();
//            }
//        }
    }
    public MCTSNode select(UUID targetPlayerId) {
        // Single‐child shortcut
        if (children.size() == 1) {
            return children.get(0);
        }

        boolean isTarget = playerId.equals(targetPlayerId);
        double sign = isTarget ? +1.0 : -1.0;

        MCTSNode best    = null;
        double bestVal = Double.NEGATIVE_INFINITY;

        double sqrtN = Math.sqrt(visits);
        double c = selectionCoefficient;
        synchronized (children) {
            for (MCTSNode child : children) {
                // value term: 0 if unvisited, else average reward
                double q = (child.visits > 0)
                        ? (child.score / child.visits)
                        : 0.0;
                double passBonus = child.getAction() instanceof PassAbility ? 0.05 : 0;
                // exploration term still blows up when visits==0
                double u = 1 * (child.prior + 0.3 + passBonus) * (sqrtN / (1 + child.visits));

                // combined PUCT
                double val = sign * q + u;

                if (val > bestVal) {
                    bestVal = val;
                    best = child;
                }
            }
        }
        // best should never be null once visits>0 on the root
        assert best != null;
        return best;
    }

    public void expand() {
        MCTSPlayer player = (MCTSPlayer) game.getPlayer(playerId);
        if (player.getNextAction() == null) {
            logger.fatal("next action is null");
        }
        synchronized (children) {
            children.addAll(MCTSNextActionFactory.createNextAction(player.getNextAction()).performNextAction(this, player, game, fullStateValue));
            for (MCTSNode node : children) {
                node.depth = depth + 1;
                node.prior = 1.0;///children.size();
            }
            if (policy != null && player.getNextAction() != MCTSPlayer.NextAction.CHOOSE_TARGET) {
                // 2) find max logit for numeric stability
                double maxLogit = Double.NEGATIVE_INFINITY;
                for (MCTSNode node : children) {
                    if (node.action == null) continue;
                    int idx = ActionEncoder.getAction(node.getAction());
                    maxLogit = Math.max(maxLogit, policy[idx]);
                }

                // 3) compute raw exps and sum
                double sumExp = 0;
                for (MCTSNode node : children) {
                    if (node.action == null) continue;
                    int idx = ActionEncoder.getAction(node.action);
                    double raw = Math.exp(policy[idx] - maxLogit);
                    node.prior = raw;     // assume you’ve added `public double prior;` to MCTSNode
                    sumExp += raw;
                }

                // 4) normalize in place
                for (MCTSNode node : children) {
                    node.prior /= sumExp;
                }
                long seed = player.dirichletSeed;
                if (seed != 0) {
                    double alpha = 0.03, eps = 0;//no noise for now
                    int K = children.size();
                    double[] dir = new double[K];
                    double sum = 0;

                    // 1) create a Commons-Math RNG and seed it
                    JDKRandomGenerator rg = new JDKRandomGenerator();
                    rg.setSeed(seed);

                    // 2) pass it into the GammaDistribution
                    GammaDistribution gd = new GammaDistribution(rg, alpha, 1.0);

                    // 3) sample & mix exactly as before
                    for (int i = 0; i < K; i++) {
                        dir[i] = gd.sample();
                        sum += dir[i];
                    }
                    for (int i = 0; i < K; i++) {
                        dir[i] /= sum;
                        children.get(i).prior = (1 - eps) * children.get(i).prior + eps * dir[i];
                    }

                    // 4) mark done
                    player.dirichletSeed = 0;
                }
            }
            if (!children.isEmpty()) {
                game = null;
            }
        }
    }

    public int simulate(UUID playerId) {
//        long startTime = System.nanoTime();
        Game sim = createSimulation(game, playerId);
        sim.resume();
//        long duration = System.nanoTime() - startTime;
        int retVal = -1;  //anything other than a win is a loss
        for (Player simPlayer: sim.getPlayers().values()) {
//            logger.info(simPlayer.getName() + " calculated " + ((SimulatedPlayerMCTS)simPlayer).getActionCount() + " actions in " + duration/1000000000.0 + "s");
            if (simPlayer.getId().equals(playerId) && simPlayer.hasWon()) {
                logger.info("AI won the simulation");
                retVal = 1;
            }
        }
        return retVal;
    }

    public void backpropagate(double result) {
        visits++;
        score += result;
        if (parent != null)
            parent.backpropagate(result);
    }

    public boolean isLeaf() {
        synchronized (children) {
            return children.isEmpty();
        }
    }

    public MCTSNode bestChild() {
        if (children.size() == 1)
            return children.get(0);
        double bestCount = -1;
        double bestRatio = 0;
        boolean bestIsPass = false;
        MCTSNode bestChild = null;
        System.out.print("actions: ");

        for (MCTSNode node: children) {
            if(node.action != null) {
                System.out.printf("[%s score: %.3f count: %d] ", node.action.toString(), node.getWinRatio(), node.visits);
            }
            if(node.combat != null && !node.combat.getAttackers().isEmpty()) System.out.printf("[%s score: %.3f count: %d] ", node.combat.toString(), node.getWinRatio(), node.visits);
            //favour passing vs any other action except for playing land if ratio is close
            if (node.visits > bestCount) {
//                if (bestIsPass) {
//                    double ratio = node.score/(node.visits * 1.0);
//                    if (ratio < bestRatio + passRatioTolerance)
//                        continue;
//                }
                bestChild = node;
                bestCount = node.visits;
                bestRatio = node.score/(node.visits * 1.0);
                bestIsPass = false;
            }
//            else if (node.action instanceof PassAbility && node.visits > 10 && !(bestChild.action instanceof PlayLandAbility)) {
//                //favour passing vs any other action if ratio is close
//                double ratio = node.score/(node.visits * 1.0);
//                if (ratio > bestRatio - passRatioTolerance) {
//                    logger.info("choosing pass over " + bestChild.getAction());
//                    bestChild = node;
//                    bestCount = node.visits;
//                    bestRatio = ratio;
//                    bestIsPass = true;
//                }
//            }
        }
        if(!children.isEmpty()) System.out.println();
        return bestChild;
    }

    public void emancipate() {
        if (parent != null) {
            this.parent.children.remove(this);
            this.parent = null;
        }
    }

    public Ability getAction() {
        return action;
    }

    public int getNumChildren() {
        return children.size();
    }

    public MCTSNode getParent() {
        return parent;
    }

    public Combat getCombat() {
        return combat;
    }

    public int getNodeCount() {
        return nodeCount;
    }

    public String getStateValue() {
        return stateValue;
    }

    public double getWinRatio() {
        if (visits > 0)
            return (score*1.0)/(visits * 1.0);
        return -666;
    }

    public int getVisits() {
        return visits;
    }

    /**
     * Copies game and replaces all players in copy with simulated players
     * Shuffles each players library so that there is no knowledge of its order
     *
     * @param game
     * @return a new game object with simulated players
     */
    protected Game createSimulation(Game game, UUID playerId) {
        Game sim = game.createSimulationForAI();

        for (Player oldPlayer: sim.getState().getPlayers().values()) {
            Player origPlayer = game.getState().getPlayers().get(oldPlayer.getId()).copy();
            SimulatedPlayerMCTS newPlayer = new SimulatedPlayerMCTS(oldPlayer, true);
            newPlayer.restore(origPlayer);
            sim.getState().getPlayers().put(oldPlayer.getId(), newPlayer);
        }
        randomizePlayers(sim, playerId);
        return sim;
    }

    /*
     * Shuffles each players library so that there is no knowledge of its order
     * Swaps all other players hands with random cards from the library so that
     * there is no knowledge of what cards are in opponents hands
     */
    protected void randomizePlayers(Game game, UUID playerId) {
        for (Player player: game.getState().getPlayers().values()) {
            if (!player.getId().equals(playerId)) {
                int handSize = player.getHand().size();
                player.getLibrary().addAll(player.getHand().getCards(game), game);
                player.getHand().clear();
                player.getLibrary().shuffle();
                for (int i = 0; i < handSize; i++) {
                    Card card = player.getLibrary().drawFromTop(game);
                    card.setZone(Zone.HAND, game);
                    player.getHand().add(card);
                }
            }
            else {
                player.getLibrary().shuffle();                
            }
        }
    }

    public boolean isTerminal() {
        return terminal;
    }

    public boolean isWinner(UUID playerId) {
        if (game != null) {
            Player player = game.getPlayer(playerId);
            if (player != null && player.hasWon())
                return true;
        }
        return false;
    }

    /**
     * 
     * performs a breadth first search for a matching game state
     * 
     * @param state - the game state that we are looking for
     * @return the matching state or null if no match is found
     */
    public MCTSNode getMatchingState(String state, List<Set<UUID>> chosen) {
        ArrayDeque<MCTSNode> queue = new ArrayDeque<>();
        queue.add(this);

        while (!queue.isEmpty()) {
            MCTSNode current = queue.remove();
            if (current.stateValue.equals(state) && current.chooseTargetAction.equals(chosen))
                return current;
            //System.out.printf("MISMATCH: %s\n %s\n",current.stateValue, state);
            for (MCTSNode child: current.children) {
                queue.add(child);
            }
        }
        return null;
    }

    public void merge(MCTSNode merge) {
        // Check that states match; if not, no merge occurs.
        if (!stateValue.equals(merge.stateValue) || !merge.chooseTargetAction.equals(chooseTargetAction)) {
            logger.info("mismatched merge states at root");
            return;
        }

        // Update accumulated statistics atomically.
        synchronized (this) {
            this.visits += merge.visits;
            this.wins += merge.wins;
            this.score += merge.score;
        }

        // Make a snapshot of the merge node's children.
        List<MCTSNode> mergeChildren;
        synchronized (merge.children) {
            mergeChildren = new ArrayList<>(merge.children);
        }

        // Synchronize on this.children for safe merging.
        synchronized (this.children) {
            Iterator<MCTSNode> iterator = mergeChildren.iterator();
            while (iterator.hasNext()) {
                MCTSNode mergeChild = iterator.next();
                boolean merged = false;
                // Iterate over our children.
                List<MCTSNode> tempChildren = new ArrayList<>(this.children);
                for (MCTSNode child : tempChildren) {
                    if (mergeChild.action != null && child.action != null) {
                        if (mergeChild.action.toString().equals(child.action.toString())) {
                            if (!mergeChild.stateValue.equals(child.stateValue) || !mergeChild.chooseTargetAction.equals(child.chooseTargetAction)) {
                                // Record mismatch if needed; skip merge.
                            } else {
                                // Recursively merge the matching child.
                                child.merge(mergeChild);
                                merged = true;
                                break;
                            }
                        }
                    } else if (mergeChild.combat != null && child.combat != null &&
                            mergeChild.combat.getValue().equals(child.combat.getValue())) {
                        if (!mergeChild.stateValue.equals(child.stateValue) || !mergeChild.chooseTargetAction.equals(child.chooseTargetAction)) {
                            // Record mismatch if needed.

                        } else {
                            child.merge(mergeChild);
                            merged = true;
                            break;
                        }
                    }
                }
                if (merged) {
                    iterator.remove();
                }
            }

            // Any remaining merge children that weren't merged get added as new children.
            for (MCTSNode child : mergeChildren) {
                child.parent = this;
                this.children.add(child);
            }
        }
    }

//    public void print(int depth) {
//        String indent = String.format("%1$-" + depth + "s", "");
//        StringBuilder sb = new StringBuilder();
//        MCTSPlayer player = (MCTSPlayer) game.getPlayer(playerId);
//        sb.append(indent).append(player.getName()).append(" ").append(visits).append(":").append(wins).append(" - ");
//        if (action != null)
//            sb.append(action.toString());
//        System.out.println(sb.toString());
//        for (MCTSNode child: children) {
//            child.print(depth + 1);
//        }
//    }

    public int size() {
        int num = 1;
        synchronized (children) {
            for (MCTSNode child : children) {
                num += child.size();
            }
        }
        return num;
    }
    private static final ConcurrentHashMap<String, List<Ability>> playablesCache = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, List<List<UUID>>> attacksCache = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, List<List<List<UUID>>>> blocksCache = new ConcurrentHashMap<>();

    private static long playablesHit = 0;
    private static long playablesMiss = 0;
    private static long attacksHit = 0;
    private static long attacksMiss = 0;
    private static long blocksHit = 0;
    private static long blocksMiss = 0;

    protected static List<Ability> getPlayables(MCTSPlayer player, String state, Game game) {
        if (playablesCache.containsKey(state)) {
            playablesHit++;
            return playablesCache.get(state);
        }
        else {
            playablesMiss++;
            List<Ability> abilities = player.getPlayableOptions(game);
            playablesCache.put(state, abilities);
            return abilities;
        }
    }

    protected static List<List<UUID>> getAttacks(MCTSPlayer player, String state, Game game) {
        if (attacksCache.containsKey(state)) {
            attacksHit++;
            return attacksCache.get(state);
        }
        else {
            attacksMiss++;
            List<List<UUID>> attacks = player.getAttacks(game);
            attacksCache.put(state, attacks);
            return attacks;
        }
    }
    
    protected static List<List<List<UUID>>> getBlocks(MCTSPlayer player, String state, Game game) {
        if (blocksCache.containsKey(state)) {
            blocksHit++;
            return blocksCache.get(state);
        }
        else {
            blocksMiss++;
            List<List<List<UUID>>> blocks = player.getBlocks(game);
            blocksCache.put(state, blocks);
            return blocks;
        }
    }
    
    public static int cleanupCache(int turnNum) {
        Set<String> playablesKeys = playablesCache.keySet();
        Iterator<String> playablesIterator = playablesKeys.iterator();
        int count = 0;
        while(playablesIterator.hasNext()) {
            String next = playablesIterator.next();
            int cacheTurn = Integer.parseInt(next.split(":", 2)[0].substring(1));
            if (cacheTurn < turnNum) {
                playablesIterator.remove();
                count++;
            }
        }

        Set<String> attacksKeys = attacksCache.keySet();
        Iterator<String> attacksIterator = attacksKeys.iterator();
        while(attacksIterator.hasNext()) {
            int cacheTurn = Integer.parseInt(attacksIterator.next().split(":", 2)[0].substring(1));
            if (cacheTurn < turnNum) {
                attacksIterator.remove();
                count++;
            }
        }
        
        Set<String> blocksKeys = blocksCache.keySet();
        Iterator<String> blocksIterator = blocksKeys.iterator();
        while(blocksIterator.hasNext()) {
            int cacheTurn = Integer.parseInt(blocksIterator.next().split(":", 2)[0].substring(1));
            if (cacheTurn < turnNum) {
                blocksIterator.remove();
                count++;
            }
        }

        return count;
    }
    public void reset() {
        children.clear();
        score = 0;
        wins = 0;
        visits = 0;
        depth = 1;
    }
    public int getAverageVisits() {
        if(children.isEmpty()) return 0;
        return visits/children.size();
    }
    public Game getGame() {
        //game.getState().restore(gameState);
        return game;
    }
    public static void logHitMiss() {
        if (USE_ACTION_CACHE) {
            StringBuilder sb = new StringBuilder();
            sb.append("Playables Cache -- Hits: ").append(playablesHit).append(" Misses: ").append(playablesMiss).append('\n');
            sb.append("Attacks Cache -- Hits: ").append(attacksHit).append(" Misses: ").append(attacksMiss).append('\n');
            sb.append("Blocks Cache -- Hits: ").append(blocksHit).append(" Misses: ").append(blocksMiss).append('\n');
            logger.info(sb.toString());
        }
    }
    public static void clearCaches() {
        playablesCache.clear();
        attacksCache.clear();
        blocksCache.clear();
    }
}
