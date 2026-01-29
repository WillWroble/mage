package mage.player.ai;

import java.util.*;

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

    private static final Logger logger = Logger.getLogger(MCTSNode.class);



    public int visits = 0;
    public int virtual_visits = 0;
    public boolean isFinalized = false;
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
    public GameState state;
    //prefix scripts represent the sequence of actions that need to be taken since the last priority to represent this microstate
    public PlayerScript prefixScript = new PlayerScript();
    public PlayerScript opponentPrefixScript = new PlayerScript();

    //encoder derived state vector (used for the neural network)
    Set<Integer> stateVector;
    MCTSPlayer.NextAction nextAction;

    /**
     * root constructor, mostly finalized, needs to be pre-expanded outside before use.
     * @param targetPlayer
     * @param game
     */
    public MCTSNode(ComputerPlayerMCTS targetPlayer, Game game, MCTSPlayer.NextAction nextAction) {
        rootGame = game;
        state = game.getState().copy();
        this.basePlayer = targetPlayer;
        this.targetPlayer = targetPlayer.getId();
        this.terminal = game.checkIfGameIsOver();
        this.winner = isWinner(game, targetPlayer.getId());
        this.action = null; //root can have null action (prev action doesn't matter)
        this.nextAction = nextAction;
        //mostly finalized still needs the nextAction, lastToAct, stateVector, Policy, and playerId fields; happens right before first expand
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
            this.state = game.getState();
        } else {//micro point, use previous state value
            this.state = parent.state;
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
     * resets root game to the given state
     * @param state state to reset to
     */
    public void resetRootGame(GameState state) {

        rootGame.setState(state);
        rootGame.getPlayerList().setCurrent(state.getPlayerByOrderId());
        // clear ephemeral caches / rebuild effects
        rootGame.resetLKI();
        rootGame.resetShortLivingLKI();
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
            mp.numOptionsSize = 0;
            mp.playables.clear();
            mp.decisionText = "";
        }
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
        double sign = isTarget ? +1.0 : -1.0;

        MCTSNode best    = null;
        double bestVal = Double.NEGATIVE_INFINITY;

        double sqrtN = Math.sqrt(getVisits());

        for (MCTSNode child : children) {
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
            children.sort(Comparator.comparing(n -> n.action.toString()));
        } else if(nextAction == MCTSPlayer.NextAction.CHOOSE_TARGET) {
            Set<UUID> targetOptions = player.chooseTargetOptions;
            for(UUID target : targetOptions) {
                logger.trace(game.getTurn().getValue(game.getTurnNum()) + " expanding: " + game.getEntityName(target));
                MCTSNode node = new MCTSNode(this, action);
                node.chooseTargetAction = target;
                children.add(node);
            }
            children.sort(Comparator.comparing(n -> game.getEntityName(n.chooseTargetAction)));
        } else if(nextAction == MCTSPlayer.NextAction.MAKE_CHOICE) {
            Set<String> choiceOptions = player.choiceOptions;
            for(String choice : choiceOptions) {
                logger.trace(game.getTurn().getValue(game.getTurnNum()) + " expanding: " + choice);
                MCTSNode node = new MCTSNode(this, action);
                node.choiceAction = choice;
                children.add(node);
            }
            children.sort(Comparator.comparing(n -> n.choiceAction));
        } else if(nextAction == MCTSPlayer.NextAction.CHOOSE_NUM) {
            for (int mode = 0; mode < player.numOptionsSize; mode++) {
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
            idx = basePlayer.actionEncoder.getActionIndex(node.getActionSequence(), playerId.equals(targetPlayer));
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
        if (policy != null && nextAction != MCTSPlayer.NextAction.MAKE_CHOICE && nextAction != MCTSPlayer.NextAction.CHOOSE_NUM) {

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

                JDKRandomGenerator rg = new JDKRandomGenerator();
                rg.setSeed(seed);

                GammaDistribution gd = new GammaDistribution(rg, alpha, 1.0);

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
    public void backpropagate(double result) {
        backpropagate(result, false);
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
            parent.backpropagate(result * ComputerPlayerMCTS.BACKPROP_DISCOUNT, virtual);
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
        sb.append(baseGame.getStack().toString());
        sb.append("pool= ").append(baseGame.getPlayer(targetPlayer).getManaPool().getMana());
        sb.append(" actions: ");
        for (MCTSNode node: children) {

            if(node.chooseTargetAction != null) {
                if(baseGame.getEntityName(node.chooseTargetAction) != null) {
                    sb.append(String.format("[%s score: %.3f count: %d] ", baseGame.getEntityName(node.chooseTargetAction), node.getMeanScore(), node.getVisits()));
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
            } else {
                logger.error("no action in node");
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
                if (node.getVisits() > bestCount
                        && (node.isTerminal() || node.nextAction.equals(MCTSPlayer.NextAction.PRIORITY) || node.containsPriorityNode())) {
                    best = node;
                    bestCount = node.getVisits();
                }
            }
            return best;
        }

        //temp-based sampling selection
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

    public Ability getActionSequence() {
        return action;
    }

    public int getNumChildren() {
        return children.size();
    }

    public MCTSNode getParent() {
        return parent;
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
    public MCTSNode getMatchingState(Set<Integer> state) {
        ArrayDeque<MCTSNode> queue = new ArrayDeque<>();
        queue.add(this);
        while (!queue.isEmpty()) {
            MCTSNode current = queue.remove();
            if(!current.isFinalized || current.stateVector==null) continue;//tree can have unfinalized nodes

            if(current.stateVector.equals(state)) {
                return current;
            }
            queue.addAll(current.children);
        }
        return null;
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

    public void reset() {
        children.clear();
        score = 0;
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
     */
    public void getActionSequence(PlayerScript myScript, PlayerScript opponentScript) {

        if(parent == null) {
            myScript.append(prefixScript);
            opponentScript.append(opponentPrefixScript);
            return;
        }
        myScript.append(parent.prefixScript);
        opponentScript.append(parent.opponentPrefixScript);

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
        } else if(parent.nextAction.equals(MCTSPlayer.NextAction.CHOOSE_NUM)) {
            if (parent.playerId.equals(targetPlayer)) {
                myScript.numSequence.add(modeAction);
            } else {
                opponentScript.numSequence.add(modeAction);
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
    }


    /**
     * backtracks through the MCTS tree to find the action sequence to generate an action sequence to reconstruct the game this node represents from root.
     * @return a reference to the game object representing this node until called again.
     */
    public Game getGame() {

        PlayerScript myScript = new PlayerScript();
        PlayerScript opponentScript = new PlayerScript();
        getActionSequence(myScript, opponentScript);
        GameState baseState;
        if(parent == null) {
            baseState = state.copy();
        } else {
            baseState = parent.state.copy();
        }
        resetRootGame(baseState);
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
            rootGame.start(rootGame.getState().getChoosingPlayerId());
        } else {
            rootGame.resume();
        }
        finalizeState(rootGame);
        return rootGame;
    }
    /**
     * Copies game and replaces all players in copy with simulated players
     * Shuffles each players library so that there is no knowledge of its order
     *
     * @param game
     * @return a new game object with simulated players
     */
    @Deprecated
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
    @Deprecated
    public int simulate(UUID playerId, Game game) {
        Game sim = createSimulation(game, playerId);
        sim.resume();
        int retVal = -1;  //anything other than a win is a loss
        for (Player simPlayer: sim.getPlayers().values()) {
            if (simPlayer.getId().equals(playerId) && simPlayer.hasWon()) {
                logger.info("AI won the simulation");
                retVal = 1;
            }
        }
        return retVal;
    }
}