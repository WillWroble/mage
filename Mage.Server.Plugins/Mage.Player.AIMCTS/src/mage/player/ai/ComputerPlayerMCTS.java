package mage.player.ai;

import mage.MageObject;
import mage.abilities.Ability;
import mage.abilities.ActivatedAbility;
import mage.abilities.common.PassAbility;
import mage.cards.Card;
import mage.cards.Cards;
import mage.choices.Choice;
import mage.constants.Outcome;
import mage.constants.PhaseStep;
import mage.constants.RangeOfInfluence;
import mage.constants.Zone;
import mage.game.Game;
import mage.game.combat.Combat;
import mage.game.combat.CombatGroup;
import mage.player.ai.MCTSPlayer.NextAction;
import mage.players.Player;
import mage.players.PlayerScript;
import mage.target.Target;
import org.apache.log4j.Logger;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * traditional MCTS (Monte Carlo Tree Search), expanded to incorporate micro decisions
 *
 * @author BetaSteward_at_googlemail.com, WillWroble
 *
 */
public class ComputerPlayerMCTS extends ComputerPlayer {

    public static final int BASE_THREAD_TIMEOUT = 4;//seconds
    public static final int MAX_TREE_VISITS = 150;//per thread
    //these aren't used for RL, see ComputerPlayerMCTS2
    protected static final int THINK_MIN_RATIO = 100; //was originally 40
    protected static final int THINK_MAX_RATIO = 140; //was 80
    protected static final double THINK_TIME_MULTIPLIER = 1.0;
    protected static final boolean USE_MULTIPLE_THREADS = true;
    //these flags should be set in ParallelDataGenerator.java
    public static boolean NO_NOISE = false;
    public static boolean NO_POLICY = false;
    //if true will factorize each combat decision into sequences of micro decisions (chooseUse and chooseTarget)
    public static boolean SIMULATE_ATTACKERS_ONE_AT_A_TIME = true;
    public static boolean SIMULATE_BLOCKERS_ONE_AT_A_TIME = true;
    //dirichlet noise is applied once to the priors of the root node; this represents how much of those priors should be noise
    public static double DIRICHLET_NOISE_EPS = 0;//was 0.15
    //how spiky the dirichlet noise will be
    public static double POLICY_PRIOR_TEMP = 1.5;
    public static boolean ROUND_ROBIN_MODE = false;
    //adjust based on available RAM and threads running
    public static int MAX_TREE_NODES = 10000;

    public final static UUID STOP_CHOOSING = new UUID(0, "stop choosing flag".hashCode());

    public transient MCTSNode root;
    protected int maxThinkTime;
    protected static final Logger logger = Logger.getLogger(ComputerPlayerMCTS.class);
    public int poolSize = 2;
    protected transient ExecutorService threadPoolSimulations = null;
    public int maxVisits = MAX_TREE_VISITS;

    public ComputerPlayerMCTS(String name, RangeOfInfluence range, int skill) {
        super(name, range);
        human = false;
        maxThinkTime = (int) (skill * THINK_TIME_MULTIPLIER);
        //poolSize = 64;//Runtime.getRuntime().availableProcessors();
    }

    protected ComputerPlayerMCTS(UUID id) {
        super(id);
    }

    public ComputerPlayerMCTS(final ComputerPlayerMCTS player) {
        super(player);
    }

    @Override
    public ComputerPlayerMCTS copy() {
        return new ComputerPlayerMCTS(this);
    }

    protected String lastPhase = "";

    @Override
    public boolean priority(Game game) {
        if (game.getTurnStepType() == PhaseStep.UPKEEP) {
            if (!lastPhase.equals(game.getTurn().getValue(game.getTurnNum()))) {
                logList(game.getTurn().getValue(game.getTurnNum()) + name + " hand: ", new ArrayList(hand.getCards(game)));
                lastPhase = game.getTurn().getValue(game.getTurnNum());
                if (MCTSNode.USE_ACTION_CACHE) {
                    int count = MCTSNode.cleanupCache(game.getTurnNum());
                    if (count > 0)
                        logger.info("Removed " + count + " cache entries");
                }
            }
        }
        game.getState().setPriorityPlayerId(playerId);
        game.firePriorityEvent(playerId);
        //(some mana abilities have other effects)
        List<Ability> playableAbilities = getPlayableOptions(game);
        if(playableAbilities.size() < 2 && !game.isCheckPoint()) {
            logger.info("auto pass");
            pass(game);
            return false;
        }
        if(game.getTurnStepType().equals(PhaseStep.DECLARE_BLOCKERS)) {
            logger.info("DECLARE_BLOCKERS CPMCTS");
        }
        game.setLastPriority(playerId);
        Ability ability = null;
        MCTSNode best = getNextAction(game, NextAction.PRIORITY);
        MCTSNode oldRoot = root;
        root.emancipate();
        boolean success = false;
        while (!success) {
            if(best != null && best.getAction() != null) {
                ability = best.getAction().copy();
                success = activateAbility((ActivatedAbility) ability, game);
            }
            if (ability == null) {
                logger.fatal("null ability");
                return false;
            }
            if(!success) {
                logger.info("Failed to resolve micro decisions for ability looking again for legal path");
                root = oldRoot;
                getPlayerHistory().clear();
                getPlayerHistory().append(root.prefixScript);
                Player opponent = game.getPlayer(game.getOpponents(playerId).iterator().next());
                opponent.getPlayerHistory().clear();
                opponent.getPlayerHistory().append(root.opponentPrefixScript);
                maxVisits +=150;
                best = calculateActions(game, NextAction.PRIORITY);
            }
        }
        root = best;
        maxVisits =MAX_TREE_VISITS;

        if(getPlayerHistory().prioritySequence.isEmpty()) {
            logger.error("priority sequence update failure");
        }
        if (ability instanceof PassAbility)
            return false;
        logLife(game);
        printBattlefieldScore(game, playerId);
        if(root.getAction().getTargets().isEmpty()) {
            logger.info(game.getTurn().getValue(game.getTurnNum()) + "choose action:" + root.getAction() + " success ratio: " + root.getScoreRatio());
        } else {
            if(game.getEntityName(root.getAction().getTargets().getFirstTarget()) != null) {
                logger.info(game.getTurn().getValue(game.getTurnNum()) + "choose action:" + root.getAction() + "(targeting " + game.getEntityName(root.getAction().getTargets().getFirstTarget()).toString() + ") success ratio: " + root.getScoreRatio());
            } else if (game.getPlayer(root.getAction().getTargets().getFirstTarget()) != null) {
                logger.info(game.getTurn().getValue(game.getTurnNum()) + "choose action:" + root.getAction() + "(targeting " + game.getPlayer(root.getAction().getTargets().getFirstTarget()).toString() + ") success ratio: " + root.getScoreRatio());
            } else {
                logger.fatal("no target found");
            }
        }
        return true;
    }
    protected MCTSNode calculateActions(Game game, NextAction action) {

        applyMCTS(game, action);

        if (root != null && root.bestChild(game) != null) {
            return root.bestChild(game);
            //root.emancipate();
        } else {
            logger.fatal("no root found");
            return null;
        }
    }

    protected MCTSNode getNextAction(Game game, NextAction nextAction) {
        if (root != null) {
            root = root.getMatchingState(game.getLastPriority().getState().getValue(true, game.getLastPriority()), nextAction, getPlayerHistory(), game.getPlayer(game.getOpponents(playerId).iterator().next()).getPlayerHistory());
        }
        if (root == null || root.getStateValue() == null) {
            Game sim = createMCTSGame(game.getLastPriority());
            MCTSPlayer player = (MCTSPlayer) sim.getPlayer(playerId);
            player.setNextAction(nextAction);//can remove this
            root = new MCTSNode(playerId, sim, nextAction);
            root.prefixScript = new PlayerScript(getPlayerHistory());
            root.opponentPrefixScript = new PlayerScript(game.getPlayer(game.getOpponents(playerId).iterator().next()).getPlayerHistory());
            logger.info("prefix at root: " + root.prefixScript.toString());
            logger.info("opponent prefix at root: " + root.opponentPrefixScript.toString());
        }
        return calculateActions(game, nextAction);
    }

    @Override
    public void selectAttackers(Game game, UUID attackingPlayerId) {
        if(ComputerPlayerMCTS.SIMULATE_ATTACKERS_ONE_AT_A_TIME) {
            selectAttackersOneAtATime(game, attackingPlayerId);
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(game.getTurn().getValue(game.getTurnNum())).append(" player ").append(name).append(" attacking with: ");
        getNextAction(game, NextAction.SELECT_ATTACKERS);
        Combat combat = root.getCombat();
        UUID opponentId = game.getCombat().getDefenders().iterator().next();
        for (UUID attackerId : combat.getAttackers()) {
            if(game.getPermanent(attackerId) == null) continue;
            this.declareAttacker(attackerId, opponentId, game, false);
            sb.append(game.getPermanent(attackerId).getName()).append(',');
        }
        getPlayerHistory().combatSequence.add(game.getCombat().copy());
        logger.info(sb.toString());
    }

    @Override
    public void selectBlockers(Ability source, Game game, UUID defendingPlayerId) {
        if(ComputerPlayerMCTS.SIMULATE_BLOCKERS_ONE_AT_A_TIME) {
            selectBlockersOneAtATime(source, game, defendingPlayerId);
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(game.getTurn().getValue(game.getTurnNum())).append(" player ").append(name).append(" blocking: ");
        getNextAction(game, NextAction.SELECT_BLOCKERS);
        Combat simulatedCombat = root.getCombat();
        List<CombatGroup> currentGroups = game.getCombat().getGroups();
        for (int i = 0; i < currentGroups.size(); i++) {
            if (i < simulatedCombat.getGroups().size()) {
                CombatGroup currentGroup = currentGroups.get(i);
                CombatGroup simulatedGroup = simulatedCombat.getGroups().get(i);
                if(currentGroup.getAttackers().isEmpty()) {
                    logger.info("Attacker not found - skipping");
                    continue;
                }
                sb.append(game.getPermanent(currentGroup.getAttackers().get(0)).getName()).append(" with: ");
                for (UUID blockerId : simulatedGroup.getBlockers()) {
                    // blockers can be added automaticly by requirement effects, so we must add only missing blockers
                    if (!currentGroup.getBlockers().contains(blockerId)) {
                        this.declareBlocker(this.getId(), blockerId, currentGroup.getAttackers().get(0), game);
                        sb.append(game.getPermanent(blockerId).getName()).append(',');
                    }
                }
                sb.append('|');
            }
        }
        getPlayerHistory().combatSequence.add(game.getCombat().copy());
        logger.info(sb.toString());
    }
    @Override
    protected boolean makeChoice(Outcome outcome, Target target, Ability source, Game game, Cards fromCards) {
        // choose itself for starting player all the time
        if (target.getMessage(game).equals("Select a starting player")) {
            target.add(this.getId(), game);
            return true;
        }
        if(game.getPhase() == null || game.isSimulation()) {//TODO: implement pre-game decisions properly
            return super.makeChoice(outcome, target, source, game, fromCards);
        }

        // nothing to choose
        if (fromCards != null && fromCards.isEmpty()) {
            return false;
        }

        UUID abilityControllerId = target.getAffectedAbilityControllerId(getId());

        // nothing to choose, e.g. X=0
        if (target.isChoiceCompleted(abilityControllerId, source, game, fromCards)) {
            return false;
        }
        logger.info("base choose target " + (source == null ? "null" : source.toString()));
        Set<UUID> possible = target.possibleTargets(abilityControllerId, source, game, fromCards).stream().filter(id -> !target.contains(id)).collect(Collectors.toSet());
        logger.info("possible targets: " + possible.size());
        // nothing to choose, e.g. no valid targets
        if (possible.isEmpty()) {
            return false;
        }
        int n = possible.size();
        n += target.isChosen(game) ? 1 : 0;

        if (n == 1) {
            //if only one possible just choose it and leave
            target.addTarget(possible.iterator().next(), source, game);
            return true;
        }

        root = getNextAction(game, NextAction.CHOOSE_TARGET);
        if(root == null) {
            return false;
        }

        UUID targetId = root.chooseTargetAction;
        if(targetId == null) {
            logger.error("target id is null");
        }
        logger.info(String.format("Targeting %s", game.getEntityName(targetId).toString()));
        getPlayerHistory().targetSequence.add(targetId);

        if(!targetId.equals(STOP_CHOOSING)) {
            target.addTarget(targetId, source, game);
            makeChoice(outcome, target, source, game, fromCards);
        }

        return target.isChosen(game) && !target.getTargets().isEmpty();

    }
    @Override
    public boolean choose(Outcome outcome, Choice choice, Game game) {
        if(outcome.equals(Outcome.PutManaInPool) || choice.getChoices().size() == 1 || game.isSimulation()) {
            return chooseHelper(outcome, choice, game);
        }
        if (choice.getMessage() != null && (choice.getMessage().equals("Choose creature type") || choice.getMessage().equals("Choose a creature type"))) {
            if (chooseCreatureType(outcome, choice, game)) {
                return true;
            }
        }
        logger.info("base make choice " + choice.toString());
        choiceOptions = new HashSet<>(choice.getChoices());
        if(choiceOptions.isEmpty()) {
            logger.info("choice is empty, spell fizzled");
            return false;
        }
        root = getNextAction(game, NextAction.MAKE_CHOICE);
        if(root == null) {
            return false;
        }
        String chosen = root.choiceAction;
        logger.info(String.format("Choosing %s", chosen));
        getPlayerHistory().choiceSequence.add(chosen);
        choice.setChoice(chosen);

        return true;
    }
    @Override
    public boolean chooseUse(Outcome outcome, String message, String secondMessage, String trueText, String falseText, Ability source, Game game) {
        logger.info("base choose use " + message);
        if(game.getPhase() == null || game.isSimulation()) {//TODO: implement pre-game decisions properly
            return true;
        }
        root = getNextAction(game, NextAction.CHOOSE_USE);
        if(root == null) {
            return false;
        }
        Boolean chosen = root.useAction;
        if(chosen == null) {
            logger.error("chosen is null");
        }
        logger.info("use " + message + ": " + chosen);
        getPlayerHistory().useSequence.add(chosen);
        return chosen;
    }
    protected double totalThinkTime = 0;
    protected long totalSimulations = 0;

    protected void applyMCTS(final Game game, final NextAction action) {
        //TODO: implement. right now only

    }

    //try to ensure that there are at least THINK_MIN_RATIO simulations per node at all times
    protected int calculateThinkTime(Game game, NextAction action) {
        int thinkTime;
        int nodeSizeRatio = 0;
        if (root.getNumChildren() > 0)
            nodeSizeRatio = root.getVisits() / root.getNumChildren();
//        logger.info("Ratio: " + nodeSizeRatio);
        PhaseStep curStep = game.getTurnStepType();
        if (action == NextAction.SELECT_ATTACKERS || action == NextAction.SELECT_BLOCKERS) {
            if (nodeSizeRatio < THINK_MIN_RATIO) {
                thinkTime = maxThinkTime*5;
            } else if (nodeSizeRatio >= THINK_MAX_RATIO) {
                thinkTime = 0;
                //thinkTime = maxThinkTime*3/2;
            } else {
                thinkTime = maxThinkTime*5 / 2;
            }
        } else if (game.isActivePlayer(playerId) && (curStep == PhaseStep.PRECOMBAT_MAIN || curStep == PhaseStep.POSTCOMBAT_MAIN) && game.getStack().isEmpty()) {
            if (nodeSizeRatio < THINK_MIN_RATIO) {
                thinkTime = 3*maxThinkTime;
            } else if (nodeSizeRatio >= THINK_MAX_RATIO) {
                thinkTime = 0;
            } else {
                thinkTime = maxThinkTime/2;
            }
            //thinkTime = maxThinkTime;
        } else {
            if (nodeSizeRatio < THINK_MIN_RATIO) {
                thinkTime = 2*maxThinkTime;
            } else if (nodeSizeRatio >= THINK_MAX_RATIO) {
                thinkTime = 0;
                thinkTime = maxThinkTime/4;
            } else {
                thinkTime = maxThinkTime/2;
            }
        }
        return thinkTime;
    }

    /**
     * Copies game and replaces all players in copy with mcts players
     * Shuffles each players library so that there is no knowledge of its order
     * Swaps all other players hands with random cards from the library so that
     * there is no knowledge of what cards are in opponents hands
     * The most knowledge that is known is what cards are in an opponents deck
     *
     * @param game
     * @return a new game object with simulated players
     */

    protected Game createMCTSGame(Game game) {
        Game mcts = game.createSimulationForAI();
        for (Player copyPlayer : mcts.getState().getPlayers().values()) {
            Player origPlayer = game.getState().getPlayers().get(copyPlayer.getId());
            MCTSPlayer newPlayer = new MCTSPlayer(copyPlayer.getId());
            newPlayer.restore(origPlayer);
            newPlayer.setMatchPlayer(origPlayer.getMatchPlayer());
            //dont shuffle here
            mcts.getState().getPlayers().put(copyPlayer.getId(), newPlayer);
        }
        //mcts.setLastPriority(null);//never use lastPriortiy in sim games
        mcts.pause();
        return mcts;
    }
    public static void shuffleUnknowns(Game mcts, UUID playerId) {
        for (Player newPlayer : mcts.getState().getPlayers().values()) {
            if (!newPlayer.getId().equals(playerId)) {
                int handSize = newPlayer.getHand().size();
                newPlayer.getLibrary().addAll(newPlayer.getHand().getCards(mcts), mcts);
                newPlayer.getHand().clear();
                newPlayer.getLibrary().shuffle();
                for (int i = 0; i < handSize; i++) {
                    Card card = newPlayer.getLibrary().drawFromTop(mcts);
                    assert (newPlayer.getLibrary().size() != 0);
                    assert (card != null);
                    card.setZone(Zone.HAND, mcts);
                    newPlayer.getHand().add(card);
                }
            } else {
                newPlayer.getLibrary().shuffle();
            }
        }
    }
    protected void displayMemory() {
        long heapSize = Runtime.getRuntime().totalMemory();
        long heapMaxSize = Runtime.getRuntime().maxMemory();
        long heapFreeSize = Runtime.getRuntime().freeMemory();
        long heapUsedSize = heapSize - heapFreeSize;
        long mb = 1024 * 1024;

        logger.info("Max heap size: " + heapMaxSize / mb + " Heap size: " + heapSize / mb + " Used: " + heapUsedSize / mb);
    }

    protected void logLife(Game game) {
        StringBuilder sb = new StringBuilder();
        sb.append(game.getTurn().getValue(game.getTurnNum()));
        for (Player player : game.getPlayers().values()) {
            sb.append("[player ").append(player.getName()).append(':').append(player.getLife()).append(']');
        }
        logger.info(sb.toString());
    }
    protected void printBattlefieldScore(Game game, UUID playerId) {
        // hand
        Player player = game.getPlayer(playerId);
        logger.info("[" + game.getPlayer(playerId).getName() + "]" +
                ", life = " + player.getLife());
        String cardsInfo = player.getHand().getCards(game).stream()
                .map(MageObject::getName)
                .collect(Collectors.joining("; "));
        StringBuilder sb = new StringBuilder("-> Hand: [")
                .append(cardsInfo)
                .append("]");
        logger.info(sb.toString());
        for(Player myPlayer : game.getPlayers().values()) {
            // battlefield
            sb.setLength(0);
            String ownPermanentsInfo = game.getBattlefield().getAllPermanents().stream()
                    .filter(p -> p.isOwnedBy(myPlayer.getId()))
                    .map(p -> p.getName()
                            + (p.isTapped() ? ",tapped" : "")
                            + (p.isAttacking() ? ",attacking" : "")
                            + (p.getBlocking() > 0 ? ",blocking" : ""))
                    .collect(Collectors.joining("; "));
            sb.append("-> Permanents: [").append(ownPermanentsInfo).append("]");
            logger.info(sb.toString());
        }

    }
    public void clearTree() {
        root = null;
    }
}
