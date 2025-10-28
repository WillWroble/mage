package mage.player.ai;

import mage.MageObject;
import mage.abilities.Ability;
import mage.abilities.ActivatedAbility;
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
import mage.game.GameImpl;
import mage.game.combat.Combat;
import mage.game.combat.CombatGroup;
import mage.player.ai.MCTSPlayer.NextAction;
import mage.players.Player;
import mage.players.PlayerScript;
import mage.target.Target;
import mage.target.TargetCard;
import mage.util.RandomUtil;
import mage.util.ThreadUtils;
import mage.util.XmageThreadFactory;
import org.apache.log4j.Logger;

import java.io.Serializable;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * traditional MCTS (Monte Carlo Tree Search), expanded to incorporate micro decisions
 *
 * @author BetaSteward_at_googlemail.com
 * @author WillWroble
 *
 */
public class ComputerPlayerMCTS extends ComputerPlayer {

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
    //mcts tree doesn't save states if true; makes search slower but more memory efficient
    //public static boolean USE_STATELESS_NODES =  false;
    //tree search will now completely ignore states where passing is the only option. still logs the state in the base game for training
    //public static boolean SKIP_TRANSITION_STATES = true;
    //dirichlet noise is applied once to the priors of the root node; this represents how much of those priors should be noise
    public static double DIRICHLET_NOISE_EPS = 0;//was 0.15
    //how spiky the dirichlet noise will be
    public static double POLICY_PRIOR_TEMP = 1.5;
    public static boolean ROUND_ROBIN_MODE = false;
    //adjust based on available RAM and threads running
    public static int MAX_TREE_NODES = 800;

    public transient MCTSNode root;
    protected int maxThinkTime;
    protected static final Logger logger = Logger.getLogger(ComputerPlayerMCTS.class);
    public int poolSize = 2;
    protected transient ExecutorService threadPoolSimulations = null;

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
        //TODO: make more robust filtering
        //(some mana abilities have other effects)
        List<ActivatedAbility> playableAbilities = getPlayable(game, true).stream().filter(a -> !(a instanceof ManaAbility)).collect(Collectors.toList());
        if(playableAbilities.isEmpty() &&
                !(game.getTurnStepType().equals(PhaseStep.DECLARE_ATTACKERS) || game.getTurnStepType().equals(PhaseStep.DECLARE_BLOCKERS))) {//declare attackers and blockers are always checkpoint for perf reasons
            pass(game);
            return false;
        }
        game.setLastPriority(playerId);
        getNextAction(game, NextAction.PRIORITY);

        Ability ability = root.getAction();
        if (ability == null)
            logger.fatal("null ability");
        activateAbility((ActivatedAbility) ability, game);
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
            if(game.getEntity(root.getAction().getTargets().getFirstTarget()) != null) {
                logger.info(game.getTurn().getValue(game.getTurnNum()) + "choose action:" + root.getAction() + "(targeting " + game.getEntity(root.getAction().getTargets().getFirstTarget()).toString() + ") success ratio: " + root.getScoreRatio());
            } else if (game.getPlayer(root.getAction().getTargets().getFirstTarget()) != null) {
                logger.info(game.getTurn().getValue(game.getTurnNum()) + "choose action:" + root.getAction() + "(targeting " + game.getPlayer(root.getAction().getTargets().getFirstTarget()).toString() + ") success ratio: " + root.getScoreRatio());
            } else {
                logger.fatal("no target found");
            }
        }
        return true;
    }
    protected void calculateActions(Game game, NextAction action) {
        if (root == null) {
            Game sim = createMCTSGame(game.getLastPriority());
            MCTSPlayer player = (MCTSPlayer) sim.getPlayer(playerId);
            player.setNextAction(action);//can remove this
            root = new MCTSNode(playerId, sim);
            root.prefixScript = new PlayerScript(getPlayerHistory());
            root.opponentPrefixScript = new PlayerScript(game.getPlayer(game.getOpponents(playerId).iterator().next()).getPlayerHistory());
            logger.info("prefix at root: " + root.prefixScript.toString());
            logger.info("opponent prefix at root: " + root.opponentPrefixScript.toString());
        }
        applyMCTS(game, action);

        if (root != null && root.bestChild(game) != null) {
            root = root.bestChild(game);
            root.emancipate();
        } else {
            logger.fatal("no root found");
        }
    }

    protected void getNextAction(Game game, NextAction nextAction) {
        MCTSNode newRoot;
        if (root != null) {
            newRoot = root.getMatchingState(game.getLastPriority().getState().getValue(true, game.getLastPriority()), getPlayerHistory(), game.getPlayer(game.getOpponents(playerId).iterator().next()).getPlayerHistory());
            if (newRoot != null) {
                newRoot.emancipate();
                 //when we are using stateless nodes, even if no new tree is needed we still should establish this game as the new anchor for MCTS
                newRoot.rootGame = createMCTSGame(game.getLastPriority());
                newRoot.rootState = newRoot.rootGame.getState().copy();
            } else {
                logger.info("unable to find matching state");
            }
            root = newRoot;
        }
        calculateActions(game, nextAction);
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
    public boolean chooseTarget(Outcome outcome, Target target, Ability source, Game game) {
        logger.info("base choose target " + (source == null ? "null" : source.toString()));
        Set<UUID> possible = target.possibleTargets(getId(), game);
        logger.info("possible targets: " + possible.size());
        if(possible.size() == 1) {
            //if only one possible just choose it and leave
            target.addTarget(possible.iterator().next(), source, game);
            return true;
        }
        chooseTargetOptions.clear();
        MCTSPlayer.getAllPossible(chooseTargetOptions, possible, target.copy(), source, game, getId());
        if(chooseTargetOptions.isEmpty()) {
            logger.info("no possible targets found");
            return false;
        }
        getNextAction(game, NextAction.CHOOSE_TARGET);
        Set<UUID> choice = root.chooseTargetAction;
        for(UUID targetId : choice) {
            Set<UUID> chosen = new HashSet<>();
            target.addTarget(targetId, source, game);
            chosen.add(targetId);
            logger.info(String.format("Targeting %s", game.getEntity(targetId).toString()));

            getPlayerHistory().targetSequence.add(chosen);
        }
        return target.isChosen(game);
    }
    @Override
    public boolean choose(Outcome outcome, Target target, Ability source, Game game, Map<String, Serializable> options) {
        if(game.getTurnNum()>1) {
            //reroute to mcts player
            return chooseTarget(outcome, target, source, game);
        } else {
            //reroute to default
            logger.info("falling back to default choose target");
            return super.choose(outcome, target, source, game, options);
        }
    }
    @Override
    public boolean chooseTarget(Outcome outcome, Cards cards, TargetCard target, Ability source, Game game) {
        //for tutoring
        return chooseTarget(outcome, target, source, game);
    }
    @Override
    public boolean choose(Outcome outcome, Choice choice, Game game) {
        if(outcome == Outcome.PutManaInPool || choice.getChoices().size() == 1) {
            return super.choose(outcome, choice, game);
        }
        logger.info("base make choice " + choice.toString());
        choiceOptions = new HashSet<>(choice.getChoices());
        if(choiceOptions.isEmpty()) {
            logger.info("choice is empty, spell fizzled");
            return false;
        }
        getNextAction(game, NextAction.MAKE_CHOICE);
        String chosen = root.choiceAction;
        logger.info(String.format("Choosing %s", chosen));
        getPlayerHistory().choiceSequence.add(chosen);
        choice.setChoice(chosen);

        return true;
    }
    @Override
    public boolean chooseUse(Outcome outcome, String message, String secondMessage, String trueText, String falseText, Ability source, Game game) {
        logger.info("base choose use " + message);

        getNextAction(game, NextAction.CHOOSE_USE);
        Boolean chosen = root.useAction;
        logger.info("use " + message + ": " + chosen);
        getPlayerHistory().useSequence.add(chosen);
        return chosen;
    }
    protected double totalThinkTime = 0;
    protected long totalSimulations = 0;

    protected void applyMCTS(final Game game, final NextAction action) {

        int thinkTime = calculateThinkTime(game, action);
        //thinkTime = 5;
        if (thinkTime > 0) {
            if (USE_MULTIPLE_THREADS) {
                if (this.threadPoolSimulations == null) {
                    // same params as Executors.newFixedThreadPool
                    // no needs errors check in afterExecute here cause that pool used for FutureTask with result check already
                    this.threadPoolSimulations = new ThreadPoolExecutor(
                            poolSize,
                            poolSize,
                            0L,
                            TimeUnit.MILLISECONDS,
                            new LinkedBlockingQueue<>(),
                            new XmageThreadFactory(ThreadUtils.THREAD_PREFIX_AI_SIMULATION_MCTS) // TODO: add player/game to thread name?
                    );
                }

                List<MCTSExecutor> tasks = new ArrayList<>();
                for (int i = 0; i < poolSize; i++) {
                    Game sim = createMCTSGame(game.getLastPriority());
                    MCTSPlayer player = (MCTSPlayer) sim.getPlayer(playerId);
                    player.setNextAction(action);
                    MCTSExecutor exec = new MCTSExecutor(sim, playerId, thinkTime);
                    tasks.add(exec);
                }
                try {
                    List<Future<Boolean>> runningTasks = threadPoolSimulations.invokeAll(tasks, thinkTime, TimeUnit.SECONDS);
                    for (Future<Boolean> runningTask : runningTasks) {
                        runningTask.get();
                    }
                } catch (InterruptedException | CancellationException e) {
                    logger.warn("applyMCTS timeout");
                } catch (ExecutionException e) {
                    // real games: must catch and log
                    // unit tests: must raise again for fast fail
                    if (this.isTestMode() && this.isFastFailInTestMode()) {
                        throw new IllegalStateException("One of the simulated games raise the error: " + e, e);
                    }
                }

                int simCount = 0;
                for (MCTSExecutor task : tasks) {
                    simCount += task.getSimCount();
                    root.merge(task.getRoot());
                    task.clear();
                }
                tasks.clear();
                totalThinkTime += thinkTime;
                totalSimulations += simCount;
                logger.info("Player: " + name + " Simulated " + simCount + " games in " + thinkTime + " seconds - nodes in tree: " + root.size());
                logger.info("Total: Simulated " + totalSimulations + " games in " + totalThinkTime + " seconds - Average: " + totalSimulations / totalThinkTime);
                MCTSNode.logHitMiss();
            } else {
                long startTime = System.nanoTime();
                long endTime = startTime + (thinkTime * 1000000000l);
                MCTSNode current;
                int simCount = 0;
                while (true) {
                    long currentTime = System.nanoTime();
                    if (currentTime > endTime)
                        break;
                    current = root;

                    // Selection
                    while (!current.isLeaf()) {
                        current = current.select(this.playerId);
                    }

                    int result;
                    if (!current.isTerminal()) {
                        Game tempGame = current.getGame();

                        // Simulation
                        result = current.simulate(this.playerId, game);
                        // Expansion
                        current.expand(game);
                        simCount++;
                    } else {
                        //logger.info("Terminal State Reached!");
                        result = current.isWinner() ? 100000000 : -100000000;
                    }
                    // Backpropagation
                    current.backpropagate(result);
                }
                logger.info("Simulated " + simCount + " games - nodes in tree: " + root.size());
            }
//            displayMemory();
        }

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
