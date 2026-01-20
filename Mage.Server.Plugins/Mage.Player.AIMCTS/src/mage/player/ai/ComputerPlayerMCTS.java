package mage.player.ai;

import mage.MageObject;
import mage.abilities.Ability;
import mage.abilities.ActivatedAbility;
import mage.abilities.Mode;
import mage.abilities.Modes;
import mage.abilities.common.PassAbility;
import mage.abilities.mana.ManaAbility;
import mage.cards.Card;
import mage.cards.Cards;
import mage.choices.Choice;
import mage.constants.Outcome;
import mage.constants.PhaseStep;
import mage.constants.RangeOfInfluence;
import mage.constants.Zone;
import mage.game.Game;
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

    public double searchTimeout = 4;//seconds
    public int searchBudget = 300;//per thread
    //how many game turns ahead MCTS can look ahead
    public transient ActionEncoder actionEncoder = null;
    //these flags should be set through fields in ParallelDataGenerator.java
    public boolean noNoise = true;
    public boolean noPolicyPriority = false;
    public boolean noPolicyTarget = true;
    public boolean noPolicyUse = false;
    public boolean noPolicyOpponent = false;


    //if true will factorize each combat decision into sequences of micro decisions (chooseUse and chooseTarget)
    //dirichlet noise is applied once to the priors of the root node; this represents how much of those priors should be noise
    public static double DIRICHLET_NOISE_EPS = 0;//was 0.15
    //how confident to be in network policy priors (lower = less confident)
    public static double POLICY_PRIOR_TEMP = 1.5;
    //exploration constant
    public static double C_PUCT = 1;
    //adjust based on available RAM and threads running
    public static int MAX_TREE_NODES = 100000;

    public final static UUID STOP_CHOOSING = new UUID(0, "stop choosing flag".hashCode());

    public transient MCTSNode root;
    protected static final Logger logger = Logger.getLogger(ComputerPlayerMCTS.class);
    public int poolSize = 2;
    protected transient ExecutorService threadPoolSimulations = null;

    public ComputerPlayerMCTS(String name, RangeOfInfluence range, int skill) {
        super(name, range);
        human = false;
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
        game.setLastPriority(playerId);
        Ability ability = null;
        MCTSNode best = getNextAction(game, NextAction.PRIORITY);
        //root.emancipate();
        boolean success = false;
        if(best != null && best.getAction() != null) {
            ability = best.getAction().copy();
            success = activateAbility((ActivatedAbility) ability, game);
            root = best;
        }
        if(ability instanceof ManaAbility) {//automatically add manual mana activations
            getPlayerHistory().prioritySequence.add(ability.copy());
        }
        if(!success) {
            logger.error("failed to activate chosen ability - passing instead");
            return false;
        }
        if(getPlayerHistory().prioritySequence.isEmpty()) {
            logger.error("priority sequence update failure");
        }
        if (ability instanceof PassAbility)
            return false;
        logLife(game);
        printBattlefieldScore(game, playerId);
        if(root.getAction().getTargets().isEmpty()) {
            logger.info(game.getTurn().getValue(game.getTurnNum()) + "choose action:" + root.getAction() + " success ratio: " + root.getMeanScore());
        } else {
            if(game.getEntityName(root.getAction().getTargets().getFirstTarget()) != null) {
                logger.info(game.getTurn().getValue(game.getTurnNum()) + "choose action:" + root.getAction() + "(targeting " + game.getEntityName(root.getAction().getTargets().getFirstTarget()).toString() + ") success ratio: " + root.getMeanScore());
            } else if (game.getPlayer(root.getAction().getTargets().getFirstTarget()) != null) {
                logger.info(game.getTurn().getValue(game.getTurnNum()) + "choose action:" + root.getAction() + "(targeting " + game.getPlayer(root.getAction().getTargets().getFirstTarget()).toString() + ") success ratio: " + root.getMeanScore());
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
            root = new MCTSNode(this, sim, nextAction);
            root.prefixScript = new PlayerScript(getPlayerHistory());
            root.opponentPrefixScript = new PlayerScript(game.getPlayer(game.getOpponents(playerId).iterator().next()).getPlayerHistory());
            logger.info("prefix at root: " + root.prefixScript.toString());
            logger.info("opponent prefix at root: " + root.opponentPrefixScript.toString());
        }
        root.emancipate();
        return calculateActions(game, nextAction);
    }

    @Override
    public void selectAttackers(Game game, UUID attackingPlayerId) {
        selectAttackersOneAtATime(game, attackingPlayerId);
    }

    @Override
    public void selectBlockers(Ability source, Game game, UUID defendingPlayerId) {
        selectBlockersOneAtATime(source, game, defendingPlayerId);
    }
    @Override
    protected boolean makeChoice(Outcome outcome, Target target, Ability source, Game game, Cards fromCards) {
        // choose itself for starting player all the time
        if (target.getMessage(game).equals("Select a starting player")) {
            target.add(this.getId(), game);
            return true;
        }
        if(game.isSimulation()) {
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
            UUID id = possible.iterator().next();
            target.addTarget(id, source, game);
            getPlayerHistory().targetSequence.add(id);
            return true;
        }

        root = getNextAction(game, NextAction.CHOOSE_TARGET);
        if(root == null) {
            return super.makeChoice( outcome,  target,  source,  game,  fromCards);
        }
        UUID targetId = root.chooseTargetAction;
        if(targetId == null) {
            logger.error("target id is null");
        }
        logger.info(String.format("Targeting %s", game.getEntityName(targetId)));
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
            choiceOptions = choice.getKeyChoices().keySet();
        }
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
        if(!choice.getChoices().isEmpty()) {
            choice.setChoice(chosen);
        } else {
            choice.setChoiceByKey(chosen);
        }

        return true;
    }
    public Mode chooseMode(Modes modes, Ability source, Game game) {
        List<Mode> modeOptions = modes.getAvailableModes(source, game).stream()
                .filter(mode -> !modes.getSelectedModes().contains(mode.getId()))
                .filter(mode -> mode.getTargets().canChoose(source.getControllerId(), source, game)).collect(Collectors.toList());
        if(modes.getMinModes() == 0) modeOptions.add(null);
        modeOptionsSize = modeOptions.size();
        if(modeOptions.isEmpty()) {
            logger.info("choice is empty, spell fizzled");
            return null;
        }
        if(modeOptions.size() == 1) {
            return modeOptions.iterator().next();
        }
        logger.info("base choose mode " + modes.toString());
        root = getNextAction(game, NextAction.CHOOSE_MODE);
        if(root == null) {
            return null;
        }
        int chosenMode = root.modeAction;
        logger.info(String.format("Choosing mode %s", chosenMode));
        getPlayerHistory().modeSequence.add(chosenMode);

        return modeOptions.get(chosenMode);
    }
    @Override
    public boolean chooseUse(Outcome outcome, String message, String secondMessage, String trueText, String falseText, Ability source, Game game) {
        logger.info("base choose use " + message);
        if(game.isSimulation()) {
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
    @Override
    public boolean chooseMulligan(Game game) {
        if(getHand().size() < 6 || !allowMulligans) {//TODO: make this toggleable
            return false;
        }
        logger.info(getHand().getCards(game).toString());
        return chooseUse(Outcome.Neutral, "Mulligan Hand?", null, game);
    }
    protected double totalThinkTime = 0;
    protected long totalSimulations = 0;

    protected void applyMCTS(final Game game, final NextAction action) {
        //TODO: implement. right now only RL version supported

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
            MCTSPlayer newPlayer = new MCTSPlayer(copyPlayer.getId(), getId());
            newPlayer.restore(origPlayer);
            newPlayer.setMatchPlayer(origPlayer.getMatchPlayer());
            //dont shuffle here
            mcts.getState().getPlayers().put(copyPlayer.getId(), newPlayer);
        }
        mcts.pause();
        mcts.setMCTSSimulation(true);
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
