package mage.player.ai;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import mage.constants.Zone;
import mage.abilities.Ability;
import mage.cards.Card;
import mage.game.Game;
import mage.game.GameState;
import mage.players.Player;
import mage.players.PlayerScript;
import org.apache.log4j.Logger;
import java.util.Random;
import org.apache.commons.math3.distribution.GammaDistribution;
import org.apache.commons.math3.random.JDKRandomGenerator;

import static java.lang.Math.*;

/**
 *
 * @author BetaSteward_at_googlemail.com
 * @author willwroble@gmail.com
 *
 * this refactored MCTS uses replay scripts to simulate micro decisions (CHOOSE_TARGET, MAKE_CHOICE, CHOOSE_USE).
 *
 */
public class MCTSNode {

    public static final boolean USE_ACTION_CACHE = false;
    private static final Logger logger = Logger.getLogger(MCTSNode.class);



    public int visits = 0;
    public int virtual_visits = 0;
    private int wins = 0;
    public double score = 0;
    public double virtual_score = 0;
    public double networkScore;//initial score given from value network
    private MCTSNode parent = null;
    public final List<MCTSNode> children = new ArrayList<>();

    //action fields - how the node represents the state - only one is not null at a time
    public Ability action;
    public Integer modeAction;
    public UUID chooseTargetAction;
    public String choiceAction;
    public Boolean useAction;

    public ComputerPlayerMCTS basePlayer;
    private String stateValue;
    private String fullStateValue;
    public UUID playerId;
    private boolean terminal = false;
    private boolean winner;
    public boolean isRandomTransition = false;
    public UUID targetPlayer;
    public int depth = 1;
    public float[] policy = null;
    private double prior = 1;
    public long dirichletSeed = 0;

    //the single dynamic game that is reused for all simulation logic
    public Game rootGame;
    //the fixed saved state of this node so it can be reset after use.
    public GameState rootState;
    //prefix scripts represent the sequence of actions that need to be taken since the last priority to represent this microstate
    public PlayerScript prefixScript = new PlayerScript();
    public PlayerScript opponentPrefixScript = new PlayerScript();

    //encoder derived state vector (used for the neural network)
    Set<Integer> stateVector;
    MCTSPlayer.NextAction nextAction;

    /**
     * root constructor, is mostly finalized
     * @param targetPlayer
     * @param game
     */
    public MCTSNode(ComputerPlayerMCTS targetPlayer, Game game, MCTSPlayer.NextAction nextAction) {
        rootGame = game;
        rootState = game.getState().copy();
        //rootState.pause();
        this.basePlayer = targetPlayer;
        this.targetPlayer = targetPlayer.getId();
        this.stateValue = game.getState().getValue(game, targetPlayer.getId());
        this.fullStateValue = game.getState().getValue(true, game);
        this.terminal = game.checkIfGameIsOver();
        this.winner = isWinner(game, targetPlayer.getId());
        this.action = null; //root can have null action (prev action doesn't matter)
        this.nextAction = nextAction;
        //mostly finalized still needs the nextAction, lastToAct and playerId fields; happens right before first expand
    }

    protected MCTSNode(MCTSNode parent, Ability action) {
        this.targetPlayer = parent.targetPlayer;
        this.basePlayer = parent.basePlayer;

        this.parent = parent;
        this.action = action;
        this.rootGame=parent.rootGame;

    }


    /**
     * uses a live game object to fully define the state of this node.
     * @param game
     */
    public void finalizeState(Game game) {
        setPlayer(game);
        if(parent == null) {//root only needs next action and acting player data
            return;
        }
        this.terminal = game.checkIfGameIsOver();
        this.winner = isWinner(game, targetPlayer);
        MCTSPlayer playerA = (MCTSPlayer) game.getPlayer(targetPlayer);
        MCTSPlayer playerB =  (MCTSPlayer) game.getPlayer(game.getOpponents(targetPlayer).iterator().next());
        this.prefixScript = new PlayerScript(playerA.getPlayerHistory());
        this.opponentPrefixScript = new PlayerScript(playerB.getPlayerHistory());

        if(this.terminal) return; //cant determine acting player after game has ended

        MCTSPlayer actingPlayer = (MCTSPlayer) game.getPlayer(playerId);
        if(actingPlayer.scriptFailed) {//dont calc state value for failed scripts
            return;
        }
        nextAction = actingPlayer.getNextAction();
        if(actingPlayer.getNextAction() == MCTSPlayer.NextAction.PRIORITY) {//priority point, use current state value
            this.stateValue = game.getState().getValue(game, targetPlayer);
            this.fullStateValue = game.getState().getValue(true, game);
            this.rootState = game.getState();
        } else {//micro point, use previous state value
            this.stateValue = parent.stateValue;
            this.fullStateValue = parent.fullStateValue;
            this.rootState = parent.rootState;
        }

    }
    private void setPlayer(Game game) {
        //System.out.println("this happening");
        for (Player p : game.getPlayers().values()) {
            MCTSPlayer mctsP = (MCTSPlayer) p;
            if(mctsP.lastToAct) {
                playerId = p.getId();
            }
            if(mctsP.isRandomTransition) {
                isRandomTransition = true;
            }
        }
        if(playerId != null) {
            return;
        }
        if(game.checkIfGameIsOver()) {
            logger.info("TERMINAL STATE");
            terminal = true;
            winner = isWinner(game, targetPlayer);
            return;
        }
        logger.warn("this should not happen");
    }
    /**
     * recursively backtracks through the tree until reaching the root and then resets its game to the given state
     * @param state state to reset to
     * @return freshly reset game object at root.
     */
    public Game resetRootGame(GameState state) {
        if(parent != null) {
           return parent.resetRootGame(state);
        }
        rootGame.setState(state);
        rootGame.getPlayerList().setCurrent(state.getPlayerByOrderId());
        // clear ephemeral caches / rebuild effects
        rootGame.resetLKI();
        rootGame.resetShortLivingLKI();
        //rootGame.getState().clearLookedAt();
        //rootGame.getState().clearRevealed();
        rootGame.applyEffects(); // rebuild layers/CEs
        //for sanity
        for (Player p : rootGame.getPlayers().values()) {
            MCTSPlayer mp = (MCTSPlayer) p;
            mp.lastToAct = false;
            mp.isRandomTransition = false;
            mp.scriptFailed = false;
            mp.getPlayerHistory().clear();
            mp.actionScript.clear();
            mp.chooseTargetOptions.clear();
            mp.choiceOptions.clear();
            mp.modeOptionsSize = 0;
            mp.playables.clear();
            mp.decisionText = "";
            mp.autoPassed=0;
        }
        return rootGame;
    }
    public boolean containsPriorityNode() {
        boolean found = false;
        for(MCTSNode child : children) {
            if(child.isTerminal() || (child.nextAction != null && child.nextAction.equals(MCTSPlayer.NextAction.PRIORITY))) {
                return true;
            }
            found |= child.containsPriorityNode();
        }
        return found;
    }
    public MCTSNode select(UUID targetPlayerId) {
        if(children.isEmpty()) {
            logger.error("no children available for selection");
            return null;
        }
        // Single‐child shortcut
        if (children.size() == 1) {
            return children.get(0);
        }

        boolean isTarget = playerId.equals(targetPlayerId);
        boolean allChildrenVisited = true;
        double sign = isTarget ? +1.0 : -1.0;

        MCTSNode best    = null;
        double bestVal = Double.NEGATIVE_INFINITY;

        double sqrtN = Math.sqrt(getVisits());

        for (MCTSNode child : children) {
            if(child.getVisits() == 0) allChildrenVisited = false;
            // value term: 0 if unvisited, else average reward
            double q = (child.getVisits() > 0)
                    ? (child.getMeanScore())
                    : 0.0;
            // exploration term
            double u = ComputerPlayerMCTS.C_PUCT * (child.prior) * (sqrtN / (1 + child.getVisits()));

            // combined PUCT
            double val = sign * q + u;

            if (val > bestVal) {
                bestVal = val;
                best = child;
            }
        }

        // best should never be null once visits>0 on the root
        if(best == null) {
            logger.error("no best child. best val is: " + bestVal);
        }
        //free game object from memory
        if(allChildrenVisited) {
            //rootState = null;
        }
        return best;
    }
    public List<MCTSNode> createChildren(MCTSPlayer.NextAction nextAction, MCTSPlayer player, Game game) {
        List<MCTSNode> children = new ArrayList<>();
        if(nextAction == MCTSPlayer.NextAction.PRIORITY) {
            for(Ability playable : player.playables) {
                logger.trace(game.getTurn().getValue(game.getTurnNum()) + " expanding: " + playable.toString());
                MCTSNode node = new MCTSNode(this, playable);
                children.add(node);
            }
        } else if(nextAction == MCTSPlayer.NextAction.CHOOSE_TARGET) {
            Set<UUID> targetOptions = player.chooseTargetOptions;
            for(UUID target : targetOptions) {
                logger.trace(game.getTurn().getValue(game.getTurnNum()) + " expanding: " + game.getEntityName(target));
                MCTSNode node = new MCTSNode(this, action);
                node.chooseTargetAction = target;
                children.add(node);
            }
        } else if(nextAction == MCTSPlayer.NextAction.MAKE_CHOICE) {
            Set<String> choiceOptions = player.choiceOptions;
            for(String choice : choiceOptions) {
                logger.trace(game.getTurn().getValue(game.getTurnNum()) + " expanding: " + choice);
                MCTSNode node = new MCTSNode(this, action);
                node.choiceAction = choice;
                children.add(node);
            }
        } else if(nextAction == MCTSPlayer.NextAction.CHOOSE_MODE) {
            for (int mode = 0; mode < player.modeOptionsSize; mode++) {
                logger.trace(game.getTurn().getValue(game.getTurnNum()) + " expanding: " + mode);
                MCTSNode node = new MCTSNode(this, action);
                node.modeAction = mode;
                children.add(node);
            }
        } else if(nextAction == MCTSPlayer.NextAction.CHOOSE_USE) {
            MCTSNode nodeTrue = new MCTSNode(this, action);
            MCTSNode nodeFalse = new MCTSNode(this, action);
            nodeTrue.useAction = true;
            nodeFalse.useAction = false;
            children.add(nodeTrue);
            children.add(nodeFalse);
            logger.trace(game.getTurn().getValue(game.getTurnNum()) + " expanding: true");
            logger.trace(game.getTurn().getValue(game.getTurnNum()) + " expanding: false");
        } else {
            logger.error("unknown nextAction");
        }
        return children;
    }
    private int nodeToIdx(MCTSNode node, MCTSPlayer.NextAction nextAction, Game game) {
        int idx;
        if(nextAction == MCTSPlayer.NextAction.PRIORITY) {
            idx = basePlayer.actionEncoder.getActionIndex(node.getAction(), playerId.equals(targetPlayer));
        } else if(nextAction == MCTSPlayer.NextAction.CHOOSE_TARGET) {
            idx = basePlayer.actionEncoder.getTargetIndex(game.getEntityName(node.chooseTargetAction));
        } else if(nextAction == MCTSPlayer.NextAction.CHOOSE_USE) {
            idx = node.useAction ? 1 : 0;
        } else {
            logger.error("unknown nextAction");
            idx = -1;
        }
        return idx;
    }
    public void expand(Game game) {

        MCTSPlayer player = (MCTSPlayer) game.getPlayer(playerId);
        if (player.getNextAction() == null) {
            logger.fatal("next action is null");
        }
        MCTSPlayer.NextAction nextAction = player.getNextAction();
        children.addAll(createChildren(nextAction, player, game));
        logger.debug(children.size() + " children expanded");
        for (MCTSNode node : children) {
            node.depth = depth + 1;
            node.prior = 1.0/children.size();
        }
        if (policy != null && nextAction != MCTSPlayer.NextAction.MAKE_CHOICE && nextAction != MCTSPlayer.NextAction.CHOOSE_MODE) {

            double priorTemperature = ComputerPlayerMCTS.POLICY_PRIOR_TEMP; // This controls 'spikiness' of prior distribution; higher means less spiky

            //find max logit for numeric stability
            double maxLogit = Float.NEGATIVE_INFINITY;
            for (MCTSNode node : children) {
                int idx = nodeToIdx(node, nextAction, game);
                maxLogit = Math.max(maxLogit, policy[idx]);
            }

            //compute raw exps and sum
            double sumExp = 0;
            for (MCTSNode node : children) {
                int idx = nodeToIdx(node, nextAction, game);
                double raw = Math.exp((policy[idx] - maxLogit)/priorTemperature);
                node.prior = raw;
                sumExp += raw;
            }

            // 4) normalize in place
            for (MCTSNode node : children) {
                node.prior /= sumExp;
            }


            long seed = this.dirichletSeed;
            if (seed != 0) {
                logger.warn("using dirichlet seed: " + seed);
                double alpha = 0.03;
                double eps = ComputerPlayerMCTS.DIRICHLET_NOISE_EPS;
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
                dirichletSeed = 0;
            }
        }
    }
    public void backpropagate(double result, boolean virtual) {
        if(!virtual) {
            visits++;
            score += result;
        } else {
            virtual_visits++;
            virtual_score += result;
        }
        if (parent != null) {
            double discount = 0.99;
            //if(parent.nextAction.equals(MCTSPlayer.NextAction.PRIORITY)) discount = 1;
            //if(!parent.playerId.equals(targetPlayer)) discount = 1;
            //if(parent.isRandomTransition) discount = 1;
            //if(discount > .1) logger.warn("discount: " + discount);
            parent.backpropagate(result * discount, virtual);
        }
    }
    public void resetVirtual() {
        virtual_visits = 0;
        virtual_score = 0;
        for(MCTSNode node : children) {
            node.resetVirtual();
        }
    }

    public boolean isLeaf() {
        synchronized (children) {
            return children.isEmpty();
        }
    }

    public MCTSNode bestChild(Game baseGame) {

        if (children.size() == 1) {
            logger.info("pass");
            return children.get(0);
        }
        StringBuilder sb = new StringBuilder();
        if(baseGame.getTurnStepType() == null) {
            sb.append("pre-game");
        } else {
            sb.append(baseGame.getTurnStepType().toString());
        }
        sb.append(baseGame.getStack().toString()).append(" actions: ");
        for (MCTSNode node: children) {

            if(node.chooseTargetAction != null) {
                if(baseGame.getEntityName(node.chooseTargetAction) != null) {
                    sb.append(String.format("[%s score: %.3f count: %d] ", baseGame.getEntityName(node.chooseTargetAction).toString(), node.getMeanScore(), node.getVisits()));
                } else if(baseGame.getPlayer(node.chooseTargetAction) != null){
                    sb.append(String.format("[%s score: %.3f count: %d] ", baseGame.getPlayer(node.chooseTargetAction).toString(), node.getMeanScore(), node.getVisits()));
                } else {
                    logger.error("target not found");
                }
            } else if(node.choiceAction != null) {
                sb.append(String.format("[%s score: %.3f count: %d] ", node.choiceAction, node.getMeanScore(), node.getVisits()));
            } else if(node.useAction != null) {
                sb.append(String.format("[%s score: %.3f count: %d] ", node.useAction, node.getMeanScore(), node.getVisits()));
            } else if(node.modeAction != null) {
                sb.append(String.format("[%s score: %.3f count: %d] ", node.modeAction, node.getMeanScore(), node.getVisits()));
            } else if(node.action != null){
                sb.append(String.format("[%s score: %.3f count: %d] ", node.action, node.getMeanScore(), node.getVisits()));
            }

        }
        if(!children.isEmpty()) {
            logger.info(sb.toString());
        }

        //derive temp from value
        double temperature = (1-abs(this.networkScore));

        //normal selection
        if (dirichletSeed==0 || temperature < 0.01) {
            MCTSNode best = null;
            double bestCount = -1;
            for (MCTSNode node : children) {
                if (node.getVisits() > bestCount) {
                    best = node;
                    bestCount = node.getVisits();
                }
            }
            return best;
        }

        //temp based sampling selection
        List<Double> logProbs = new ArrayList<>();
        double maxLogProb = Double.NEGATIVE_INFINITY;
        for (MCTSNode node : children) {
            double logProb = Math.log(node.getVisits()) / temperature;
            logProbs.add(logProb);
            if (logProb > maxLogProb) {
                maxLogProb = logProb;
            }
        }

        List<Double> probabilities = new ArrayList<>();
        double distributionSum = 0.0;
        for (double logProb : logProbs) {
            double probability = Math.exp(logProb - maxLogProb);
            probabilities.add(probability);
            distributionSum += probability;
        }

        for (int i = 0; i < probabilities.size(); i++) {
            probabilities.set(i, probabilities.get(i) / distributionSum);
        }

        double randomValue = new Random().nextDouble();
        double cumulativeProbability = 0.0;
        for (int i = 0; i < children.size(); i++) {
            cumulativeProbability += probabilities.get(i);
            if (randomValue <= cumulativeProbability) {
                return children.get(i);
            }
        }

        return null;
    }

    public void emancipate() {
        this.rootGame = this.getRoot().rootGame;//one shared game per tree, always
        if (parent != null) {
            this.parent.children.remove(this);
            this.parent = null;
        }
    }
    public void purge(MCTSNode node) {
        if(!children.contains(node)) {
            logger.error("invalid purge");
            return;
        }
        children.remove(node);
        node.parent=null;
        if(children.isEmpty() && parent != null) {
            parent.purge(this);
        }
    }
    public MCTSNode getRoot() {
        if(parent == null) {
            return this;
        }
        return parent.getRoot();
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

    public String getStateValue() {
        return stateValue;
    }

    public double getMeanScore() {
        if (getVisits() > 0)
            return (score+virtual_score)/((visits + virtual_visits)* 1.0);
        return -1;
    }

    public int getVisits() {
        return visits+virtual_visits;
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

    public boolean isWinner(Game game, UUID playerId) {
        if (game != null) {
            Player player = game.getPlayer(playerId);
            if (player != null && player.hasWon())
                return true;
        }
        return false;
    }
    public boolean isWinner() {
        return winner;
    }

    /**
     * * performs a breadth first search for a matching game state
     * * @param state - the game state that we are looking for
     * @return the matching state or null if no match is found
     */
    public MCTSNode getMatchingState(String state, MCTSPlayer.NextAction nextAction, PlayerScript prefixScript, PlayerScript opponentPrefixScript) {
        ArrayDeque<MCTSNode> queue = new ArrayDeque<>();
        queue.add(this);
        int showCount = 0;
        while (!queue.isEmpty()) {
            MCTSNode current = queue.remove();
            if(current.fullStateValue == null) continue;//tree can have unfinalized nodes
            if(showCount < 10) {
                logger.debug(current.prefixScript.toString() + " =should= " + prefixScript.toString());
                logger.debug(current.opponentPrefixScript.toString() + " =should= " + opponentPrefixScript.toString());
                logger.debug(current.fullStateValue + " =should= " + state);
                logger.debug(current.nextAction.toString() + " =should= " + nextAction.toString());
                showCount++;
            }
            if (current.fullStateValue.equals(state) && prefixScript.equals(current.prefixScript) && opponentPrefixScript.equals(current.opponentPrefixScript) && current.nextAction.equals(nextAction)) { //&& current.chooseTargetAction.equals(chosenTargets) && current.choiceAction.equals(chosenChoices) && current.playerId.equals(givenPlayerId)) {
                return current;
            }
            queue.addAll(current.children);
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
            this.networkScore = merge.networkScore;
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
                        if (mergeChild.action.toString().equals(child.action.toString()) && mergeChild.action.getTargets().equals(child.action.getTargets())) {
                            if (!mergeChild.stateValue.equals(child.stateValue) || !mergeChild.chooseTargetAction.equals(child.chooseTargetAction)) {
                                // Record mismatch if needed; skip merge.
                            } else {
                                // Recursively merge the matching child.
                                child.merge(mergeChild);
                                merged = true;
                                break;
                            }
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
        virtual_visits = 0;
        depth = 1;
    }
    public int getAverageVisits() {
        if(children.isEmpty()) return 0;
        return visits/children.size();
    }
    /**
     * populates action lists by back tracing through the tree (opponent player is the non-target player)
     * @param myScript
     * @param opponentScript
     * @return the game state at the root of this sequence
     */
    public GameState getActionSequence(PlayerScript myScript, PlayerScript opponentScript) {

        if(rootState != null) {//go until latest checkpoint
            myScript.append(prefixScript);
            opponentScript.append(opponentPrefixScript);
            return rootState;
        }

        GameState out = parent.getActionSequence(myScript, opponentScript);

        if(parent.nextAction.equals(MCTSPlayer.NextAction.CHOOSE_TARGET)) {
            if(parent.playerId.equals(targetPlayer)) {
                myScript.targetSequence.add(chooseTargetAction);
            } else {
                opponentScript.targetSequence.add(chooseTargetAction);
            }
        } else if (parent.nextAction.equals(MCTSPlayer.NextAction.MAKE_CHOICE)) {
            if(parent.playerId.equals(targetPlayer)) {
                myScript.choiceSequence.add(choiceAction);
            } else {
                opponentScript.choiceSequence.add(choiceAction);
            }
        } else if(parent.nextAction.equals(MCTSPlayer.NextAction.CHOOSE_USE)) {
            if(parent.playerId.equals(targetPlayer)) {
                myScript.useSequence.add(useAction);
            } else {
                opponentScript.useSequence.add(useAction);
            }
        } else if(parent.nextAction.equals(MCTSPlayer.NextAction.CHOOSE_MODE)) {
            if (parent.playerId.equals(targetPlayer)) {
                myScript.modeSequence.add(modeAction);
            } else {
                opponentScript.modeSequence.add(modeAction);
            }
        } else if(parent.nextAction.equals(MCTSPlayer.NextAction.PRIORITY)) {
            if(parent.playerId.equals(targetPlayer)) {
                myScript.prioritySequence.add(action);
            } else {
                opponentScript.prioritySequence.add(action);
            }
        } else {
            logger.error("no action found in node");
        }
        return out;
    }


    /**
     * backtracks through the MCTS tree to find the action sequence to generate an action sequence to reconstruct the game this node represents from root.
     * @return a reference to the game object representing this node until called again.
     */
    public Game getGame() {

        PlayerScript myScript = new PlayerScript();
        PlayerScript opponentScript = new PlayerScript();
        GameState rootState = getActionSequence(myScript, opponentScript);
        Game rootGame = resetRootGame(rootState.copy());
        //set base player actions
        MCTSPlayer myPlayer = (MCTSPlayer) rootGame.getPlayer(targetPlayer);
        myPlayer.actionScript = myScript;
        //set opponent actions
        UUID nonTargetPlayer = rootGame.getOpponents(targetPlayer).iterator().next();
        MCTSPlayer opponentPlayer =  (MCTSPlayer) rootGame.getPlayer(nonTargetPlayer);
        opponentPlayer.actionScript = opponentScript;
        //will run until next decision (see MCTSPlayer)
        if(rootGame.getPhase() == null) {
            rootGame.getOptions().skipInitShuffling = true;
            rootGame.getState().resume();
            rootGame.start(rootState.getChoosingPlayerId());
        } else {
            rootGame.resume();
        }
        finalizeState(rootGame);
        return rootGame;
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
    public int simulate(UUID playerId, Game game) {
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
}