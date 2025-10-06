package mage.player.ai;

import mage.abilities.*;
import mage.abilities.common.PassAbility;
import mage.abilities.costs.mana.GenericManaCost;
import mage.choices.Choice;
import mage.constants.Outcome;
import mage.game.Game;
import mage.game.combat.Combat;
import mage.game.combat.CombatGroup;
import mage.game.permanent.Permanent;
import mage.players.Player;
import mage.players.PlayerScript;
import mage.target.Target;
import org.apache.log4j.Logger;

import java.io.Serializable;
import java.util.*;

/**
 * AI: server side bot with monte carlo logic (experimental, the latest version)
 * <p>
 * Simple implementation for random play, outdate and do not support,
 * see <a href="https://github.com/magefree/mage/issues/7075">more details here</a>
 *
 * @author BetaSteward_at_googlemail.com
 */
public class MCTSPlayer extends ComputerPlayer {

    public boolean lastToAct = false;
    //flag meaning the script given to this player wasn't followable
    public boolean scriptFailed = false;
    private NextAction nextAction;
    private static final Logger logger = Logger.getLogger(MCTSPlayer.class);

    //the script of actions this dummy player is supposed to follow to catch up to the latest decision
    public PlayerScript actionScript = new PlayerScript();



    public static boolean PRINT_CHOOSE_DIALOGUES = true;


    public enum NextAction {
        PRIORITY, SELECT_ATTACKERS, SELECT_BLOCKERS, CHOOSE_TARGET, MAKE_CHOICE
    }

    public MCTSPlayer(UUID id) {
        super(id);
    }

    public MCTSPlayer(final MCTSPlayer player) {
        super(player);
        this.nextAction = player.nextAction;
    }

    @Override
    public MCTSPlayer copy() {
        return new MCTSPlayer(this);
    }

    protected List<ActivatedAbility> getPlayableAbilities(Game game) {
        List<ActivatedAbility> playables = getPlayable(game, true);
        List<ActivatedAbility> out = new ArrayList<>();
        for (ActivatedAbility aa : playables) {
            if (!aa.isManaAbility()) {
                out.add(aa);
            }
        }
        //playables.add(new PassAbility());
        out.add(new PassAbility());
        return out;
    }

    public List<Ability> getPlayableOptions(Game game) {
        //if(true) return simulatePriority(game);
        List<Ability> all = new ArrayList<>();
        List<ActivatedAbility> playables = getPlayableAbilities(game);
        for (ActivatedAbility ability : playables) {
            List<Ability> options = game.getPlayer(playerId).getPlayableOptions(ability, game);
            if (options.isEmpty()) {
                if (!ability.getManaCosts().getVariableCosts().isEmpty()) {
                    simulateVariableCosts(ability, all, game);
                } else {
                    all.add(ability);
                }
            } else {
                for (Ability option : options) {
                    if (!ability.getManaCosts().getVariableCosts().isEmpty()) {
                        simulateVariableCosts(option, all, game);
                    } else {
                        all.add(option);
                    }
                }
            }
        }
        return all;
    }

    protected void simulateVariableCosts(Ability ability, List<Ability> options, Game game) {
        int numAvailable = getAvailableManaProducers(game).size() - ability.getManaCosts().manaValue();
        int start = 0;
        if (!(ability instanceof SpellAbility)) {
            //only use x=0 on spell abilities
            if (numAvailable == 0)
                return;
            else
                start = 1;
        }
        for (int i = start; i < numAvailable; i++) {
            Ability newAbility = ability.copy();
            newAbility.addManaCostsToPay(new GenericManaCost(i));
            options.add(newAbility);
        }
    }

    public List<List<UUID>> getAttacks(Game game) {
        List<List<UUID>> engagements = new ArrayList<>();
        List<Permanent> attackersList = super.getAvailableAttackers(game);
        //use binary digits to calculate powerset of attackers
        int powerElements = (int) Math.pow(2, attackersList.size());
        StringBuilder binary = new StringBuilder();
        for (int i = powerElements - 1; i >= 0; i--) {
            binary.setLength(0);
            binary.append(Integer.toBinaryString(i));
            while (binary.length() < attackersList.size()) {
                binary.insert(0, '0');
            }
            List<UUID> engagement = new ArrayList<>();
            for (int j = 0; j < attackersList.size(); j++) {
                if (binary.charAt(j) == '1') {
                    engagement.add(attackersList.get(j).getId());
                }
            }
            engagements.add(engagement);
        }
        return engagements;
    }

    public List<List<List<UUID>>> getBlocks(Game game) {
        List<List<List<UUID>>> engagements = new ArrayList<>();
        int numGroups = game.getCombat().getGroups().size();
        if (numGroups == 0) {
            return engagements;
        }

        //add a node with no blockers
        List<List<UUID>> engagement = new ArrayList<>();
        for (int i = 0; i < numGroups; i++) {
            engagement.add(new ArrayList<UUID>());
        }
        engagements.add(engagement);

        List<Permanent> blockers = getAvailableBlockers(game);
        addBlocker(game, engagement, blockers, engagements);

        return engagements;
    }

    private List<List<UUID>> copyEngagement(List<List<UUID>> engagement) {
        List<List<UUID>> newEngagement = new ArrayList<>();
        for (List<UUID> group : engagement) {
            newEngagement.add(new ArrayList<>(group));
        }
        return newEngagement;
    }

    protected void addBlocker(Game game, List<List<UUID>> engagement, List<Permanent> blockers, List<List<List<UUID>>> engagements) {
        if (blockers.isEmpty())
            return;
        int numGroups = game.getCombat().getGroups().size();
        //try to block each attacker with each potential blocker
        Permanent blocker = blockers.get(0);
//        if (logger.isDebugEnabled())
//            logger.debug("simulating -- block:" + blocker);
        List<Permanent> remaining = remove(blockers, blocker);
        for (int i = 0; i < numGroups; i++) {
            if (game.getCombat().getGroups().get(i).canBlock(blocker, game)) {
                List<List<UUID>> newEngagement = copyEngagement(engagement);
                newEngagement.get(i).add(blocker.getId());
                engagements.add(newEngagement);
//                    logger.debug("simulating -- found redundant block combination");
                addBlocker(game, newEngagement, remaining, engagements);  // and recurse minus the used blocker
            }
        }
        addBlocker(game, engagement, remaining, engagements);
    }

    public NextAction getNextAction() {
        return nextAction;
    }

    public void setNextAction(NextAction action) {
        this.nextAction = action;
    }

    @Override
    public void restore(Player player) {
        // simulated player can be created from any player type
        super.restore(player.getRealPlayer());
    }

    @Override
    public boolean priority(Game game) {
        if(game.isPaused()) return false;
        if(!actionScript.prioritySequence.isEmpty()) {
            game.getState().setPriorityPlayerId(playerId);
            //game.firePriorityEvent(playerId);
            ActivatedAbility ability = (ActivatedAbility) actionScript.prioritySequence.pollFirst();

            boolean success = activateAbility(ability, game);
            if(!success) {
                logger.warn(game.getTurn().getValue(game.getTurnNum()) + " INVALID MCTS NODE AT: " + ability.toString());
                scriptFailed = true;
                game.pause();
                lastToAct = true;
                return false;
                //do something here to alert the main process (parent resume call) but handle gracefully
            }
            return !(ability instanceof PassAbility);
            //priority history is handled in base player activateAbility()
        }
        game.pause();
        lastToAct = true;
        nextAction = NextAction.PRIORITY;
        return false;
    }

    @Override
    public void selectAttackers(Game game, UUID attackingPlayerId) {
        if(game.isPaused()) return;
        if(!actionScript.combatSequence.isEmpty()) {
            Combat combat = actionScript.combatSequence.pollFirst();
            UUID opponentId = game.getCombat().getDefenders().iterator().next();
            for (UUID attackerId : combat.getAttackers()) {
                if(game.getPermanent(attackerId) == null) continue;
                this.declareAttacker(attackerId, opponentId, game, false);
            }
            getPlayerHistory().combatSequence.add(game.getCombat().copy());
            return;
        }
        game.pause();
        lastToAct = true;
        nextAction = NextAction.SELECT_ATTACKERS;
    }

    @Override
    public void selectBlockers(Ability source, Game game, UUID defendingPlayerId) {
        if(game.isPaused()) return;
        if(!actionScript.combatSequence.isEmpty()) {
            Combat simulatedCombat = actionScript.combatSequence.pollFirst();
            List<CombatGroup> currentGroups = game.getCombat().getGroups();
            for (int i = 0; i < currentGroups.size(); i++) {
                if (i < simulatedCombat.getGroups().size()) {
                    CombatGroup currentGroup = currentGroups.get(i);
                    CombatGroup simulatedGroup = simulatedCombat.getGroups().get(i);
                    if(currentGroup.getAttackers().isEmpty()) {
                        logger.info("Attacker not found - skipping");
                        continue;
                    }
                    for (UUID blockerId : simulatedGroup.getBlockers()) {
                        // blockers can be added automaticly by requirement effects, so we must add only missing blockers
                        if (!currentGroup.getBlockers().contains(blockerId)) {
                            this.declareBlocker(this.getId(), blockerId, currentGroup.getAttackers().get(0), game);
                        }
                    }
                }
            }
            getPlayerHistory().combatSequence.add(game.getCombat().copy());
            return;
        }
        game.pause();
        lastToAct = true;
        nextAction = NextAction.SELECT_BLOCKERS;
    }

    public static void getAllPossible(Set<Set<UUID>> out, Set<UUID> possible, Target target, Ability source, Game game, UUID myID) {
        if (target.isChosen(game)) out.add(new HashSet<>(target.getTargets()));
        for (UUID id : possible) {
            if (!target.canTarget(myID, id, source, game)) continue;
            target.add(id, game);
            if (out.contains(new HashSet<>(target.getTargets()))) {
                target.remove(id);
                continue;
            }
            Set<UUID> copy = new HashSet<>(possible);
            copy.remove(id);
            getAllPossible(out, copy, target, source, game, myID);
            target.remove(id);
        }
    }

    @Override
    public boolean chooseTarget(Outcome outcome, Target target, Ability source, Game game) {
        if (game.isPaused())
            return super.chooseTarget(outcome, target, source, game); //if game is already paused don't overwrite last decision
        //for choosing targets of triggered abilities
        if (PRINT_CHOOSE_DIALOGUES)
            logger.info("CALLING CHOOSE TARGET: " + (source == null ? "null" : source.toString()));
        if (!actionScript.targetSequence.isEmpty()) {
            StringBuilder sb = PRINT_CHOOSE_DIALOGUES ? new StringBuilder() : null;
            Set<UUID> targets = actionScript.targetSequence.pollFirst();
            for (UUID id : targets) {
                if (!target.canTarget(getId(), id, source, game)) {
                    logger.error("target choice failed - skipping");
                    continue;
                }
                target.addTarget(id, source, game);
                if (sb != null) {
                    sb.append(String.format("tried target: %s ", game.getObject(id).toString()));
                }
            }
            if (sb != null) {
                logger.info(sb.toString());
            }
            getPlayerHistory().targetSequence.add(targets);
            return true;
        }
        Set<UUID> possible = target.possibleTargets(getId(), game);
        chooseTargetOptions.clear();
        getAllPossible(chooseTargetOptions, possible, target.copy(), source, game, getId());
        if(chooseTargetOptions.isEmpty()) {
            return false; //fizzle
        }
        game.pause();
        lastToAct = true;
        nextAction = NextAction.CHOOSE_TARGET;
        return super.chooseTarget(outcome, target, source, game);//continue with default target until able to pause
    }

    @Override
    public boolean choose(Outcome outcome, Target target, Ability source, Game game, Map<String, Serializable> options) {
        //for discarding
        return chooseTarget(outcome, target, source, game);

    }

    @Override
    public boolean choose(Outcome outcome, Choice choice, Game game) {
        if (outcome == Outcome.PutManaInPool || game.isPaused()) {
            return super.choose(outcome, choice, game);
        }
        //for choosing colors/types etc
        if (PRINT_CHOOSE_DIALOGUES) logger.info("CALLING MAKE CHOICE: " + choice.toString());
        if (!actionScript.choiceSequence.isEmpty()) {
            String chosen = actionScript.choiceSequence.pollFirst();
            choice.setChoice(chosen);
            getPlayerHistory().choiceSequence.add(chosen);
            if (PRINT_CHOOSE_DIALOGUES) logger.info(String.format("tried choice: %s ", chosen));
            return true;
        }
        choiceOptions = new HashSet<>(choice.getChoices());
        if(choiceOptions.isEmpty()) {
            return false; //fizzle
        }
        game.pause();
        lastToAct = true;
        nextAction = NextAction.MAKE_CHOICE;
        return super.choose(outcome, choice, game);
    }

}

