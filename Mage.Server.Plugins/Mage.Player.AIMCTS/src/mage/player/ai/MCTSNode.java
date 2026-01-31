package mage.player.ai;

import java.util.*;

import mage.constants.Zone;
import mage.abilities.Ability;
import mage.cards.Card;
import mage.game.Game;
import mage.game.GameState;
import mage.player.ai.encoder.ActionEncoder;
import mage.players.Player;
import mage.players.PlayerScript;
import mage.util.RandomUtil;
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
 * this expanded MCTS system uses replay scripts to simulate micro decisions (CHOOSE_TARGET, MAKE_CHOICE, CHOOSE_AMOUNT, CHOOSE_USE).
 *
 */
public class MCTSNode {

    private static final Logger logger = Logger.getLogger(MCTSNode.class);


    //neural network fields
    public float[] policy = null;
    public double networkScore;//initial score given from value network

    //shared (per tree)
    private Game rootGame; //single game is reused with different states
    private final ComputerPlayerMCTS basePlayer;
    private final UUID targetPlayer;

    //node statistics
    private int visits = 0;
    private int virtualVisits = 0;
    private int depth = 1;
    private long dirichletSeed = 0;
    private double prior = 1;
    private double score = 0;
    private double virtual_score = 0;

    //action fields - how the node represents the state - only one is not null at a time
    private Ability priorityAction;
    private Integer amountAction;
    private UUID targetAction;
    private String choiceAction;
    private Boolean useAction;

    //structure
    private final List<MCTSNode> children = new ArrayList<>();
    private MCTSNode parent = null;

    //validation (these fields are populated in validateState)
    private UUID playerId;
    private boolean terminal = false;
    private boolean winner;
    private boolean isRandomTransition = false;
    Set<Integer> stateVector; //encoder derived state vector (used for ML and validation)
    ActionEncoder.ActionType actionType;
    private GameState state; //the saved logical game state of this node. Should always be a stable priority window
    //prefix scripts represent the sequence of actions that need to be taken since the last priority to represent this microstate
    private PlayerScript prefixScript = new PlayerScript();
    private PlayerScript opponentPrefixScript = new PlayerScript();



    /**
     * root constructor, mostly finalized, needs to be validated and pre-expanded externally before use.
     * @param targetPlayer
     * @param game
     */
    public MCTSNode(ComputerPlayerMCTS targetPlayer, Game game, ActionEncoder.ActionType actionType, PlayerScript prefixA, PlayerScript prefixB) {
        this.rootGame = game;
        this.state = game.getState().copy();
        this.basePlayer = targetPlayer;
        this.targetPlayer = targetPlayer.getId();
        this.terminal = game.checkIfGameIsOver();
        this.winner = isWinner(game, targetPlayer.getId());
        this.priorityAction = null; //root can have null action (prev action doesn't matter)
        this.actionType = actionType;
        this.prefixScript = prefixA;
        this.opponentPrefixScript = prefixB;
    }

    protected MCTSNode(MCTSNode parent) {
        this.parent = parent;
        this.targetPlayer = parent.targetPlayer;
        this.basePlayer = parent.basePlayer;
        this.rootGame= parent.rootGame;

    }
    public Ability getPriorityAction() {
        return priorityAction;
    }
    public UUID getTargetAction() {
        return targetAction;
    }
    public String getChoiceAction() {
        return  choiceAction;
    }
    public boolean getUseAction() {
        return useAction;
    }
    public int getAmountAction() {
        return  amountAction;
    }
    public MCTSNode getMatchingState(Set<Integer> state) {
        ArrayDeque<MCTSNode> queue = new ArrayDeque<>();
        queue.add(this);
        while (!queue.isEmpty()) {
            MCTSNode current = queue.remove();
            if(current.children.isEmpty()) continue; //tree can have unfinalized nodes
            if(current.stateVector.equals(state)) {
                return current;
            }
            queue.addAll(current.children);
        }
        return null;
    }
    public List<MCTSNode> getChildren() {
        return children;
    }
    public MCTSNode getParent() {
        return parent;
    }
    public MCTSNode getRoot() {
        if(parent == null) {
            return this;
        }
        return parent.getRoot();
    }
    public MCTSNode getPlayerScope() {
        return getPlayerScope(playerId);
    }
    public MCTSNode getPlayerScope(UUID currentId) {
        if(parent == null) {
            return this;
        }
        if(!parent.playerId.equals(currentId) && parent.children.size() > 1) {
            return this;
        }
        return parent.getPlayerScope(currentId);
    }
    public Game getGame() {
        return rootGame;
    }
    /**
     * @return the player to act at this node
     */
    public MCTSPlayer getPlayer() {
        return (MCTSPlayer) rootGame.getPlayer(playerId);
    }
    public double getMeanScore() {
        if (getVisits() > 0)
            return (score+virtual_score)/((visits + virtualVisits)* 1.0);
        return -1;
    }
    public int getVisits() {
        return visits + virtualVisits;
    }
    public int getAverageVisits() {
        if(children.isEmpty()) return 0;
        return visits/children.size();
    }
    public boolean isRandomTransition() { return isRandomTransition; }


    /**
     * @apiNote must be called directly before evaluation and expansion
     * central engine call of MCTS system. uses XMage to validate the game state at this node and populates necessary fields
     */
    public void validateState() {
        PlayerScript myScript = new PlayerScript();
        PlayerScript opponentScript = new PlayerScript();
        populateActionScripts(myScript, opponentScript);
        GameState baseState;
        if(parent == null) {
            baseState = state.copy();
        } else {
            baseState = parent.state.copy();
        }
        resetRootGame(baseState);
        MCTSPlayer playerA = (MCTSPlayer) rootGame.getPlayer(targetPlayer);
        MCTSPlayer playerB =  (MCTSPlayer) rootGame.getOpponent(targetPlayer);
        playerA.actionScript = myScript; //set base player actions
        playerB.actionScript = opponentScript; //set opponent actions
        //will run until next decision (see MCTSPlayer)
        if(rootGame.getPhase() == null) {
            rootGame.getOptions().skipInitShuffling = true;
            rootGame.getState().resume();
            rootGame.start(rootGame.getState().getChoosingPlayerId());
        } else {
            rootGame.resume();
        }
        setPlayer(rootGame); //populates playerId with the player whose decision it is at this node

        this.terminal = rootGame.checkIfGameIsOver();
        this.winner = isWinner(rootGame, targetPlayer);
        this.prefixScript = new PlayerScript(playerA.getPlayerHistory());
        this.opponentPrefixScript = new PlayerScript(playerB.getPlayerHistory());

        if(this.terminal) return; //cant determine acting player after game has ended

        MCTSPlayer actingPlayer = (MCTSPlayer) rootGame.getPlayer(playerId);

        if(actingPlayer.scriptFailed) return; //dont calc state value and vector for failed scripts

        actionType = actingPlayer.getNextAction();
        stateVector = actingPlayer.getStateVector();
        if(parent != null) {
            if (actingPlayer.getNextAction() == ActionEncoder.ActionType.PRIORITY) {//priority point, use current state value
                this.state = rootGame.getState();
            } else {//micro point, use previous state value
                this.state = parent.state;
            }
        }
    }
    private void setPlayer(Game game) {
        for (Player p : game.getPlayers().values()) {
            MCTSPlayer mctsP = (MCTSPlayer) p;
            if(mctsP.isLastToAct()) {
                playerId = p.getId();
            }
            if(mctsP.isRandomTransition()) {
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
            mp.clearFields();
        }
    }
    /**
     * populates action lists by back tracing through the tree (opponent player is the non-target player)
     * @param myScript
     * @param opponentScript
     */
    public void populateActionScripts(PlayerScript myScript, PlayerScript opponentScript) {

        if(parent == null) {
            myScript.append(prefixScript);
            opponentScript.append(opponentPrefixScript);
            return;
        }
        myScript.append(parent.prefixScript);
        opponentScript.append(parent.opponentPrefixScript);

        if(parent.actionType.equals(ActionEncoder.ActionType.CHOOSE_TARGET)) {
            if(parent.playerId.equals(targetPlayer)) {
                myScript.targetSequence.add(targetAction);
            } else {
                opponentScript.targetSequence.add(targetAction);
            }
        } else if (parent.actionType.equals(ActionEncoder.ActionType.MAKE_CHOICE)) {
            if(parent.playerId.equals(targetPlayer)) {
                myScript.choiceSequence.add(choiceAction);
            } else {
                opponentScript.choiceSequence.add(choiceAction);
            }
        } else if(parent.actionType.equals(ActionEncoder.ActionType.CHOOSE_USE)) {
            if(parent.playerId.equals(targetPlayer)) {
                myScript.useSequence.add(useAction);
            } else {
                opponentScript.useSequence.add(useAction);
            }
        } else if(parent.actionType.equals(ActionEncoder.ActionType.CHOOSE_NUM)) {
            if (parent.playerId.equals(targetPlayer)) {
                myScript.numSequence.add(amountAction);
            } else {
                opponentScript.numSequence.add(amountAction);
            }
        } else if(parent.actionType.equals(ActionEncoder.ActionType.PRIORITY)) {
            if(parent.playerId.equals(targetPlayer)) {
                myScript.prioritySequence.add(priorityAction);
            } else {
                opponentScript.prioritySequence.add(priorityAction);
            }
        } else {
            logger.error("no action found in node");
        }
    }
    public boolean containsLegalNode() {
        boolean found = false;
        for(MCTSNode child : children) {
            if(child.isLegalState()) {
                return true;
            }
            found |= child.containsLegalNode();
        }
        return found;
    }
    public void randomizeNoiseSeed() {
        dirichletSeed = RandomUtil.nextInt();
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
    public List<MCTSNode> createChildren(ActionEncoder.ActionType actionType, MCTSPlayer player, Game game) {
        List<MCTSNode> children = new ArrayList<>();
        if(actionType == ActionEncoder.ActionType.PRIORITY) {
            for(Ability playable : player.playables) {
                logger.trace(game.getTurn().getValue(game.getTurnNum()) + " expanding: " + playable.toString());
                MCTSNode node = new MCTSNode(this);
                node.priorityAction = playable;
                children.add(node);
            }
            children.sort(Comparator.comparing(n -> n.priorityAction.toString()));
        } else if(actionType == ActionEncoder.ActionType.CHOOSE_TARGET) {
            Set<UUID> targetOptions = player.chooseTargetOptions;
            for(UUID target : targetOptions) {
                logger.trace(game.getTurn().getValue(game.getTurnNum()) + " expanding: " + game.getEntityName(target, targetPlayer));
                MCTSNode node = new MCTSNode(this);
                node.targetAction = target;
                children.add(node);
            }
            children.sort(Comparator.comparing(n -> game.getEntityName(n.targetAction, targetPlayer)));
        } else if(actionType == ActionEncoder.ActionType.MAKE_CHOICE) {
            Set<String> choiceOptions = player.choiceOptions;
            for(String choice : choiceOptions) {
                logger.trace(game.getTurn().getValue(game.getTurnNum()) + " expanding: " + choice);
                MCTSNode node = new MCTSNode(this);
                node.choiceAction = choice;
                children.add(node);
            }
            children.sort(Comparator.comparing(n -> n.choiceAction));
        } else if(actionType == ActionEncoder.ActionType.CHOOSE_NUM) {
            for (int mode = 0; mode < player.numOptionsSize; mode++) {
                logger.trace(game.getTurn().getValue(game.getTurnNum()) + " expanding: " + mode);
                MCTSNode node = new MCTSNode(this);
                node.amountAction = mode;
                children.add(node);
            }
        } else if(actionType == ActionEncoder.ActionType.CHOOSE_USE) {
            MCTSNode nodeTrue = new MCTSNode(this);
            MCTSNode nodeFalse = new MCTSNode(this);
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
    private int nodeToIdx(MCTSNode node, ActionEncoder.ActionType actionType, Game game) {
        int idx;
        if(actionType == ActionEncoder.ActionType.PRIORITY) {
            idx = basePlayer.actionEncoder.getActionIndex(node.getPriorityAction(), playerId.equals(targetPlayer));
        } else if(actionType == ActionEncoder.ActionType.CHOOSE_TARGET) {
            idx = basePlayer.actionEncoder.getTargetIndex(game.getEntityName(node.targetAction, targetPlayer));
        } else if(actionType == ActionEncoder.ActionType.CHOOSE_USE) {
            idx = node.useAction ? 1 : 0;
        } else {
            logger.error("unknown nextAction");
            idx = -1;
        }
        return idx;
    }
    public void expand() {

        MCTSPlayer player = (MCTSPlayer) rootGame.getPlayer(playerId);
        if (player.getNextAction() == null) {
            logger.fatal("next action is null");
        }
        ActionEncoder.ActionType actionType = player.getNextAction();
        children.addAll(createChildren(actionType, player, rootGame));
        logger.debug(children.size() + " children expanded");
        for (MCTSNode node : children) {
            node.depth = depth + 1;
            node.prior = 1.0/children.size();
        }
        if (policy != null && actionType != ActionEncoder.ActionType.MAKE_CHOICE && actionType != ActionEncoder.ActionType.CHOOSE_NUM) {

            double priorTemperature = ComputerPlayerMCTS.PRIOR_TEMP; // This controls 'spikiness' of prior distribution; higher means less spiky

            //find max logit for numeric stability
            double maxLogit = Float.NEGATIVE_INFINITY;
            for (MCTSNode node : children) {
                int idx = nodeToIdx(node, actionType, rootGame);
                maxLogit = Math.max(maxLogit, policy[idx]);
            }

            //compute raw exps and sum
            double sumExp = 0;
            for (MCTSNode node : children) {
                int idx = nodeToIdx(node, actionType, rootGame);
                double raw = Math.exp((policy[idx] - maxLogit)/priorTemperature);
                node.prior = raw;
                sumExp += raw;
            }

            // 4) normalize in place
            for (MCTSNode node : children) {
                node.prior /= sumExp;
                node.prior += ComputerPlayerMCTS.PRIOR_BONUS;
            }

            long seed = this.dirichletSeed;
            if (seed != 0) {
                logger.warn("using dirichlet seed: " + seed);
                double alpha = ComputerPlayerMCTS.DIRICHLET_NOISE_ALPHA;
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
            virtualVisits++;
            virtual_score += result;
        }
        if (parent != null) {
            parent.backpropagate(result * ComputerPlayerMCTS.BACKPROP_DISCOUNT, virtual);
        }
    }
    public void resetVirtual() {
        virtualVisits = 0;
        virtual_score = 0;
        for(MCTSNode node : children) {
            node.resetVirtual();
        }
    }
    public MCTSNode bestChild(Game baseGame) {
        ComputerPlayerMCTS myPlayer = (ComputerPlayerMCTS) baseGame.getPlayer(playerId);
        if (children.size() == 1) {
            return children.get(0);
        }
        StringBuilder sb = new StringBuilder();
        if(baseGame.getTurnStepType() == null) {
            sb.append("pre-game");
        } else {
            sb.append(baseGame.getTurnStepType().toString());
        }
        sb.append(baseGame.getStack().toString());
        sb.append("pool=").append(myPlayer.getManaPool().getMana());
        sb.append(" actions: ");
        for (MCTSNode node: children) {
            if(node.targetAction != null) {
                sb.append(String.format("[%s score: %.3f count: %d] ", baseGame.getEntityName(node.targetAction, targetPlayer), node.getMeanScore(), node.getVisits()));
            } else if(node.choiceAction != null) {
                sb.append(String.format("[%s score: %.3f count: %d] ", node.choiceAction, node.getMeanScore(), node.getVisits()));
            } else if(node.useAction != null) {
                sb.append(String.format("[%s score: %.3f count: %d] ", node.useAction, node.getMeanScore(), node.getVisits()));
            } else if(node.amountAction != null) {
                sb.append(String.format("[%s score: %.3f count: %d] ", node.amountAction, node.getMeanScore(), node.getVisits()));
            } else if(node.priorityAction != null){
                sb.append(String.format("[%s score: %.3f count: %d] ", node.priorityAction, node.getMeanScore(), node.getVisits()));
            } else {
                logger.error("no action in node");
            }
        }
        if(!children.isEmpty() && !myPlayer.allMana) {
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
                        && (node.isTerminal() || node.actionType.equals(ActionEncoder.ActionType.PRIORITY) || node.containsLegalNode())) {
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
    public boolean isLeaf() {
        synchronized (children) {
            return children.isEmpty();
        }
    }
    public boolean isLegalState() {
        if(this.terminal) return true;
        if(children.isEmpty()) return false;
        return actionType.equals(ActionEncoder.ActionType.PRIORITY);
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
        virtualVisits = 0;
        depth = 1;
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