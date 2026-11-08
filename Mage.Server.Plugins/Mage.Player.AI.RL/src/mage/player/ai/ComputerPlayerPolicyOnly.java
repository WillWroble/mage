package mage.player.ai;

import mage.abilities.Ability;
import mage.abilities.ActivatedAbility;
import mage.abilities.Mode;
import mage.abilities.Modes;
import mage.abilities.common.PassAbility;
import mage.abilities.costs.mana.ManaCost;
import mage.cards.Cards;
import mage.choices.Choice;
import mage.constants.Outcome;
import mage.constants.PhaseStep;
import mage.constants.RangeOfInfluence;
import mage.game.Game;
import mage.player.ai.encoder.ActionEncoder;
import mage.player.ai.encoder.FeatureGraph;
import mage.players.PlayerScript;
import mage.target.Target;
import mage.util.RandomUtil;
import org.apache.commons.math3.distribution.GammaDistribution;
import org.apache.commons.math3.random.JDKRandomGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

import static mage.target.TargetImpl.STOP_CHOOSING;

/**
 * Policy only. requires a policy net
 */
public class ComputerPlayerPolicyOnly extends ComputerPlayerMCTS2 {
    private static final Logger logger = LoggerFactory.getLogger(ComputerPlayerPolicyOnly.class);

    public ComputerPlayerPolicyOnly(String name, RangeOfInfluence range, int skill) {
        super(name, range, skill);
        human = false;
    }
    protected ComputerPlayerPolicyOnly(UUID id) {
        super(id);
    }
    protected ComputerPlayerPolicyOnly(ComputerPlayerPolicyOnly computerPlayerPolicyOnly) {
        super(computerPlayerPolicyOnly);
    }

    @Override
    public ComputerPlayerPolicyOnly copy() {
        return new ComputerPlayerPolicyOnly(this);
    }


    public UUID bestAction(MCTSNode2 node, Game game) {
        if(node.isTerminal() || node.policy == null) {
            return null;
        }
        Map<UUID, Float> policy = new HashMap<>();
        for(MCTSNode child : node.children) {
            UUID id = child.getActionId(game);
            policy.put(id, node.policy.get(id));
        }
        double priorTemperature = priorTemp; // This controls 'spikiness' of prior distribution; higher means less spiky

        //find max logit for numeric stability
        double maxLogit = Float.NEGATIVE_INFINITY;
        UUID maxAction = null;
        for (UUID id: policy.keySet()) {
            if(policy.get(id) > maxLogit) {
                maxAction = id;
                maxLogit = policy.get(id);
            }
        }
        if(noNoise) {
            return maxAction;
        }

        Map<UUID, Double> normalizedPolicy = new HashMap<>();
        //compute raw exps and sum
        double sumExp = 0;
        for (UUID id : policy.keySet()) {
            double raw = Math.exp((policy.get(id) - maxLogit)/priorTemperature);
            normalizedPolicy.put(id, raw);
            sumExp += raw;
        }
        for (UUID id : normalizedPolicy.keySet()) {
            double norm = normalizedPolicy.get(id)/sumExp;
            normalizedPolicy.put(id, norm);
        }
        if (!noNoise) {
            long seed = ThreadLocalRandom.current().nextLong();
            double alpha = ComputerPlayerMCTS.DIRICHLET_NOISE_ALPHA;
            double eps = dirichletNoiseEps;
            int K = normalizedPolicy.size();
            double[] dir = new double[K];
            double sum = 0;

            JDKRandomGenerator rg = new JDKRandomGenerator();
            rg.setSeed(seed);

            GammaDistribution gd = new GammaDistribution(rg, alpha, 1.0);

            for (int i = 0; i < K; i++) {
                dir[i] = gd.sample();
                sum += dir[i];
            }
            int i = 0;
            for (UUID id : normalizedPolicy.keySet()) {
                dir[i] /= sum;
                double adjusted = (1 - eps) * normalizedPolicy.get(id) + eps * dir[i];
                normalizedPolicy.put(id, adjusted);
                i++;
            }
        }
        return sample(normalizedPolicy);
    }

    @Override
    public boolean priority(Game game) {


        List<ActivatedAbility> playableAbilities = getPlayableAbilities(game);

        game.setLastPriority(playerId);
        if (game.getTurnStepType() == PhaseStep.UPKEEP) {
            if (!lastPhase.equals(game.getTurn().getValue(game.getTurnNum()))) {
                logList(game.getTurn().getValue(game.getTurnNum()) + name + " hand: ", new ArrayList<>(hand.getCards(game)));
                lastPhase = game.getTurn().getValue(game.getTurnNum());
            }
        }
        game.getState().setPriorityPlayerId(playerId);
        game.firePriorityEvent(playerId);
        if(playableAbilities.size() < 2) {
            logger.info("auto pass");
            pass(game);
            return false;
        }
        allMana = true;
        for(ActivatedAbility ability : playableAbilities) {
            allMana &= (ability.isManaActivatedAbility() || ability instanceof PassAbility);
        }
        MCTSNode2 newRoot = getNextAction(game, ActionEncoder.ActionType.PRIORITY);

        UUID bestAction = bestAction(newRoot, game);
        ActivatedAbility ability = null;
        for(ActivatedAbility aa : playableAbilities) {
            if(aa.getId().equals(bestAction)) {
                ability = aa;
            }
        }
        if(ability == null) {
            pass(game);
            return false;
        }
        boolean success = activateAbility(ability, game);
        if(!success) {
            logger.error("failed to activate chosen ability: {}", ability.toString());
            throw new IllegalStateException("failed to activate chosen ability - game failure");
        }
        if (ability instanceof PassAbility) {
            return false;
        }
        if(!allMana) {
            logger.info("playable abilities: {}", playableAbilities);
            logger.info("{}chose action:{} success ratio: {}", game.getTurn().getValue(game.getTurnNum()), ability, newRoot.getMeanScore());
            logLife(game);
            printBattlefieldScore(game, playerId);
        } else if(!getManaPool().isEmpty()) {
            logger.info("pool={}", getManaPool());
        }

        return true;
    }

    /**
     *no search version of getNextAction. only use priors of root.
     * @param game
     * @param actionType
     * @return
     */
    @Override
    protected MCTSNode2 getNextAction(Game game, ActionEncoder.ActionType actionType) {


        Game sim = createMCTSGame(game);
        PlayerScript prefixScript = new PlayerScript(getPlayerHistory());
        PlayerScript opponentPrefixScript = new PlayerScript(game.getOpponent(playerId).getPlayerHistory());
        MCTSNode2 newRoot = new MCTSNode2(this, sim, actionType, prefixScript, opponentPrefixScript);

        //do first expansion automatically
        newRoot.validateState();
        newRoot.expand();
        newRoot.evaluate();
        while (pendingNodes.get() > 0) {
            Thread.yield();
        }
        Map<UUID, Integer> actionMap = getActionMap(newRoot, game);

        if(actionMap != null && newRoot.featureGraph != null) stateEncoder.addLabeledState(newRoot.featureGraph, actionMap, newRoot.getMeanScore(), actionType, true, game, playerId);

        return newRoot;
    }

    @Override
    public boolean playManaHandling (Ability ability, ManaCost unpaid, final Game game) {
        if(autoTap) {
            return super.playManaHandling(ability, unpaid, game);
        } else {
            return autoPayFromPool(ability, unpaid, game);
        }
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
        logger.info("base choose target " + (source == null ? "null" : source.getGameLogMessage(game)));
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
            if(target.contains(id)) {
                getPlayerHistory().targetSequence.add(id);
                return true;
            }
            return false;
        }

        MCTSNode2 newRoot = getNextAction(game, ActionEncoder.ActionType.CHOOSE_TARGET);
        if(newRoot == null) {
            return super.makeChoice( outcome,  target,  source,  game,  fromCards);
        }
        UUID targetId = bestAction(newRoot, game);
        if(targetId == null) {
            logger.error("target id is null");
            return false;
        }
        logger.info(String.format("Targeting %s", game.getEntityName(targetId)));

        if(!targetId.equals(STOP_CHOOSING)) {
            target.addTarget(targetId, source, game);
            if(target.contains(targetId)) {
                getPlayerHistory().targetSequence.add(targetId);
                makeChoice(outcome, target, source, game, fromCards);
            }
        } else {
            getPlayerHistory().targetSequence.add(STOP_CHOOSING);
        }

        return target.isChosen(game);

    }

    @Override
    public boolean choose(Outcome outcome, Choice choice, Game game) {
        return super.choose(outcome, choice, game);
    }

    @Override
    protected int makeChoiceAmount(int min, int max, Game game, Ability source, boolean isManaPay) {
        return super.makeChoiceAmount(min, max, game, source, isManaPay);
    }

    public Mode chooseMode(Modes modes, Ability source, Game game) {
        return super.chooseMode(modes, source, game);
    }

    @Override
    public boolean chooseUse(Outcome outcome, String message, String secondMessage, String trueText, String falseText, Ability source, Game game) {
        logger.info("base choose use " + message);
        if(game.isSimulation()) {
            return false;
        }
        MCTSNode2 newRoot = getNextAction(game, ActionEncoder.ActionType.CHOOSE_USE);
        if(newRoot == null) {
            getPlayerHistory().useSequence.add(false);
            return false;
        }
        UUID chosenId = bestAction(newRoot, game);
        boolean chosen = false;
        if(chosenId != null) {
            if(chosenId.equals(FeatureGraph.USE_TRUE_ID)) {
                chosen = true;
            } else if (!chosenId.equals(FeatureGraph.USE_FALSE_ID)) {
                logger.error("chosen id is neither true or false for binary decision");
            }
        } else {
            logger.error("chosenId is null");
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
    static UUID sample(Map<UUID, Double> weights) {
        double r = ThreadLocalRandom.current().nextDouble()
                * weights.values().stream().mapToDouble(Double::doubleValue).sum();
        UUID last = null;
        for (Map.Entry<UUID, Double> e : weights.entrySet()) {
            last = e.getKey();
            if ((r -= e.getValue()) < 0) return last;
        }
        return last;
    }
}
