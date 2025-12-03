package mage.player.ai;

import mage.abilities.*;
import mage.abilities.common.PassAbility;
import mage.cards.Cards;
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

import java.util.*;
import java.util.stream.Collectors;

/**
 * Dummy player for MCTS sims. replays through micro-decisions with deterministic action scripts created by
 * controlling/thinking player.
 *
 * @author willwroble@gmail.com
 */
public class MCTSPlayer extends ComputerPlayer {

    public boolean lastToAct = false;
    //flag meaning the script given to this player wasn't followable
    public boolean scriptFailed = false;
    private NextAction nextAction;
    private static final Logger logger = Logger.getLogger(MCTSPlayer.class);

    //the script of actions this dummy player is supposed to follow to catch up to the latest decision
    public PlayerScript actionScript = new PlayerScript();
    //additional text for state encoder that describes the decision the player is currently making
    public String decisionText;



    public static boolean PRINT_CHOOSE_DIALOGUES = true;


    public enum NextAction {
        PRIORITY, SELECT_ATTACKERS, SELECT_BLOCKERS, CHOOSE_TARGET, MAKE_CHOICE, CHOOSE_USE
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
        if(game.isPaused() || game.checkIfGameIsOver()) return false;
        if(!actionScript.prioritySequence.isEmpty()) {
            game.getState().setPriorityPlayerId(playerId);
            //game.firePriorityEvent(playerId);
            ActivatedAbility ability = (ActivatedAbility) actionScript.prioritySequence.pollFirst().copy();
            boolean success = activateAbility(ability, game);
            if(!success && !lastToAct) {//if decision costs need to be resolved let them simulate out
                logger.debug(game.getTurn().getValue(game.getTurnNum()) + " INVALID SCRIPT AT: " + ability.toString() + "STATE: " + game.getState().getValue(true, game));
                scriptFailed = true;
                game.pause();
                lastToAct = true;
                return false;
                //do something here to alert the main process (parent resume call) but handle gracefully
            }
            return !(ability instanceof PassAbility);
            //priority history is handled in base player activateAbility()
        }
        playables = getPlayableOptions(game);
        if(playables.size() == 1 && !game.isCheckPoint()) {//forced checkpoint at start
            pass(game);
            return false;
        }
        game.setLastPriority(playerId);
        decisionText = "priority";
        game.pause();
        lastToAct = true;
        nextAction = NextAction.PRIORITY;
        return false;
    }
    @Deprecated
    @Override
    public void selectAttackers(Game game, UUID attackingPlayerId) {
        if(game.isPaused() || game.checkIfGameIsOver()) return;
        if(ComputerPlayerMCTS.SIMULATE_ATTACKERS_ONE_AT_A_TIME) {
            selectAttackersOneAtATime(game, attackingPlayerId);
            return;
        }
        if(!actionScript.combatSequence.isEmpty()) {
            Combat combat = actionScript.combatSequence.pollFirst().copy();
            UUID opponentId = game.getCombat().getDefenders().iterator().next();
            for (UUID attackerId : combat.getAttackers()) {
                if(game.getPermanent(attackerId) == null) continue;
                this.declareAttacker(attackerId, opponentId, game, false);
            }
            getPlayerHistory().combatSequence.add(game.getCombat().copy());
            return;
        }
        decisionText = "select attackers";
        game.pause();
        lastToAct = true;
        nextAction = NextAction.SELECT_ATTACKERS;
    }
    @Deprecated
    @Override
    public void selectBlockers(Ability source, Game game, UUID defendingPlayerId) {
        if(game.isPaused() || game.checkIfGameIsOver()) return;
        if(ComputerPlayerMCTS.SIMULATE_BLOCKERS_ONE_AT_A_TIME) {
            selectBlockersOneAtATime(source, game, defendingPlayerId);
            return;
        }
        if(!actionScript.combatSequence.isEmpty()) {
            Combat simulatedCombat = actionScript.combatSequence.pollFirst().copy();
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
        decisionText = "select blockers";
        game.pause();
        lastToAct = true;
        nextAction = NextAction.SELECT_BLOCKERS;
    }

    @Override
    protected boolean makeChoice(Outcome outcome, Target target, Ability source, Game game, Cards fromCards) {
        if (game.isPaused() || game.checkIfGameIsOver())
            return makeChoiceHelper(outcome, target, source, game, fromCards); //if game is already paused don't overwrite last decision

        // choose itself for starting player all the time
        if (target.getMessage(game).equals("Select a starting player")) {
            target.add(this.getId(), game);
            return true;
        }

        // nothing to choose
        if (fromCards != null && fromCards.isEmpty()) {
            logger.debug("no cards to choose from");
            return false;
        }

        UUID abilityControllerId = target.getAffectedAbilityControllerId(getId());

        // nothing to choose, e.g. X=0
        if (target.isChoiceCompleted(abilityControllerId, source, game, fromCards)) {
            return false;
        }

        Set<UUID> possible = target.possibleTargets(abilityControllerId, source, game, fromCards).stream().filter(id -> !target.contains(id)).collect(Collectors.toSet());

        // nothing to choose, e.g. no valid targets
        if (possible.isEmpty()) {
            logger.debug("none possible - fizzle");
            return false;
        }
        if(target.isChosen(game)) {
            possible.add(ComputerPlayerMCTS.STOP_CHOOSING);//finish choosing early flag
        }

        if(possible.size()==1) {
            //if only one possible just choose it and leave
            UUID id = possible.iterator().next();
            target.addTarget(id, source, game); //id can never be STOP_CHOOSING here
            return true;
        }

        if (!actionScript.targetSequence.isEmpty()) {
            UUID choice = actionScript.targetSequence.pollFirst();
            if(PRINT_CHOOSE_DIALOGUES) logger.debug(String.format("tried target: %s ", game.getEntityName(choice).toString()));
            getPlayerHistory().targetSequence.add(choice);
            if(!choice.equals(ComputerPlayerMCTS.STOP_CHOOSING)) {
                target.addTarget(choice, source, game);
                //choose another?
                makeChoice(outcome, target, source, game, fromCards);
            }
            return target.isChosen(game) && !target.getTargets().isEmpty();
        }
        StringBuilder sb = new StringBuilder();
        chooseTargetOptions = possible;
        if(source == null) {
            logger.debug("choose target source is null");
            sb.append("null");
        } else {
            sb.append(source.getRule());
        }
        sb.append(":Choose a target:").append(target.getTargetName());
        decisionText = sb.toString();
        game.pause();
        lastToAct = true;
        nextAction = NextAction.CHOOSE_TARGET;
        return makeChoiceHelper(outcome, target, source, game, fromCards);//continue with default target until able to pause
    }
    @Override
    public boolean choose(Outcome outcome, Choice choice, Game game) {
        if (outcome.equals(Outcome.PutManaInPool) || choice.getChoices().size() == 1 || game.isPaused() || game.checkIfGameIsOver()) {
            return chooseHelper(outcome, choice, game);
        }
        if (choice.getMessage() != null && (choice.getMessage().equals("Choose creature type") || choice.getMessage().equals("Choose a creature type"))) {
            if (chooseCreatureType(outcome, choice, game)) {
                return true;
            }
        }
        //for choosing colors/types etc
        if (PRINT_CHOOSE_DIALOGUES) logger.debug("CALLING MAKE CHOICE: " + choice.toString());
        if (!actionScript.choiceSequence.isEmpty()) {
            String chosen = actionScript.choiceSequence.pollFirst();
            choice.setChoice(chosen);
            getPlayerHistory().choiceSequence.add(chosen);
            if (PRINT_CHOOSE_DIALOGUES) logger.debug(String.format("tried choice: %s ", chosen));
            return true;
        }
        choiceOptions = new HashSet<>(choice.getChoices());
        if(choiceOptions.isEmpty()) {
            logger.debug("no choice options - fizzle");
            return false; //fizzle
        }
        decisionText = choice.toString();
        game.pause();
        lastToAct = true;
        nextAction = NextAction.MAKE_CHOICE;
        return chooseHelper(outcome, choice, game);
    }
    @Override
    public boolean chooseUse(Outcome outcome, String message, String secondMessage, String trueText, String falseText, Ability source, Game game) {
        if(game.isPaused() || game.checkIfGameIsOver()) {
            return false;
        }
        if(!actionScript.useSequence.isEmpty()) {
            Boolean chosen =  actionScript.useSequence.pollFirst();
            getPlayerHistory().useSequence.add(chosen);
            if (PRINT_CHOOSE_DIALOGUES) logger.debug(String.format("tried use: %s ", chosen));
            return chosen;
        }
        decisionText = message;
        //logger.info("decisionText: " + decisionText);
        game.pause();
        lastToAct = true;
        nextAction = NextAction.CHOOSE_USE;
        return false; //defaults to try to avoid infinite use issues from poorly implemented abilities
    }
    @Override
    public void illegalGameState(Game game) {
        scriptFailed = true;
        game.pause();
    }
}

