package mage.player.ai;

import com.j256.ormlite.stmt.query.In;
import mage.abilities.Ability;
import mage.abilities.ActivatedAbility;
import mage.abilities.Mode;
import mage.abilities.Modes;
import mage.abilities.common.PassAbility;
import mage.abilities.costs.mana.ManaCost;
import mage.cards.Cards;
import mage.choices.Choice;
import mage.constants.Outcome;
import mage.constants.RangeOfInfluence;
import mage.game.Game;
import mage.game.events.GameEvent;
import mage.player.ai.encoder.ActionEncoder;
import mage.player.ai.encoder.FeatureGraph;
import mage.player.ai.encoder.StateEncoder;
import mage.players.Player;
import mage.players.PlayerScript;
import mage.target.Target;
import mage.util.RandomUtil;
import org.apache.commons.math3.distribution.GammaDistribution;
import org.apache.commons.math3.random.JDKRandomGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Policy only. requires a policy net
 */
public class ComputerPlayerPolicyOnly extends ComputerPlayerMCTS {
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


    public ActivatedAbility bestPriority(Map<UUID, Float> policy, List<ActivatedAbility> playables) {
        double priorTemperature = priorTemp; // This controls 'spikiness' of prior distribution; higher means less spiky

        if(selectionTemperature != 0) {

        }
        //find max logit for numeric stability
        double maxLogit = Float.NEGATIVE_INFINITY;
        ActivatedAbility maxAbility = null;
        for (ActivatedAbility ability : playables) {
            UUID id = ability.getId();
            if(policy.get(id) > maxLogit) {
                maxAbility = ability;
                maxLogit = policy.get(id);
            }
        }
        if(selectionTemperature == 0) {
            return maxAbility;
        }

        Map<UUID, Double> normalizedPolicy = new HashMap<>();
        //compute raw exps and sum
        double sumExp = 0;
        for (ActivatedAbility ability : playables) {
            UUID id = ability.getId();
            double raw = Math.exp((policy.get(id) - maxLogit)/priorTemperature);
            normalizedPolicy.put(id, raw);
            sumExp += raw;
        }
        for (UUID id : normalizedPolicy.keySet()) {
            double norm = normalizedPolicy.get(id)/sumExp;
            normalizedPolicy.put(id, norm);
        }


        if (!noNoise) {
            long seed = RandomUtil.nextInt();
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
            }
        }
        double maxScore = Double.NEGATIVE_INFINITY;
        maxAbility = null;
        for(ActivatedAbility ability : playables) {
            UUID id = ability.getId();
            if(normalizedPolicy.get(id) > maxScore) {
                maxScore = normalizedPolicy.get(id);
                maxAbility = ability;
            }
        }
        return maxAbility;
    }

    @Override
    public boolean priority(Game game) {

        Game sim = createMCTSGame(game);
        PlayerScript prefixScript = new PlayerScript(getPlayerHistory());
        PlayerScript opponentPrefixScript = new PlayerScript(game.getOpponent(playerId).getPlayerHistory());
        MCTSNode2 newRoot = new MCTSNode2(this, sim, ActionEncoder.ActionType.PRIORITY, prefixScript, opponentPrefixScript);

        //do first expansion automatically
        newRoot.validateState();
        newRoot.expand();
        newRoot.evaluate();

        List<ActivatedAbility> playableAbilities = getPlayableAbilities(game);
        allMana = true;
        for(ActivatedAbility ability : playableAbilities) {
            allMana &= (ability.isManaActivatedAbility() || ability instanceof PassAbility);
        }

        ActivatedAbility ability = bestPriority(newRoot.policy,  playableAbilities);
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
            logger.info("{}chose action:{} success ratio: {}", game.getTurn().getValue(game.getTurnNum()), ability, root.getMeanScore());
            logLife(game);
            printBattlefieldScore(game, playerId);
        } else if(!getManaPool().isEmpty()) {
            logger.info("pool={}", getManaPool());
        }

        return true;
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
        return false;
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
        return false;
    }
    @Override
    public boolean chooseMulligan(Game game) {
        if(getHand().size() < 6 || !allowMulligans) {//TODO: make this toggleable
            return false;
        }
        logger.info(getHand().getCards(game).toString());
        return chooseUse(Outcome.Neutral, "Mulligan Hand?", null, game);
    }
}
