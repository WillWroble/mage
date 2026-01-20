package mage.player.ai;

import mage.abilities.Ability;
import mage.abilities.ActivatedAbility;
import mage.abilities.mana.ManaAbility;
import mage.constants.RangeOfInfluence;
import mage.game.Game;
import mage.game.GameException;
import mage.game.events.GameEvent;
import mage.players.Player;
import mage.target.Target;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static mage.player.ai.ComputerPlayerMCTS2.createAllActionsFromDeck;
import static mage.player.ai.ComputerPlayerMCTS2.createAllTargetsFromDecks;

/**
 * minimax player that logs priority decisions (as one hot vectors) and state values (as minimax derived score of root normalized to -1,1)
 * against a RL MCTS player will use that players search tree to create blended visit distributions instead of one hots and also use their MCTS derived value score for the root node (if states match)
 */
public class ComputerPlayer8 extends ComputerPlayer7{
    private static final Logger log = LoggerFactory.getLogger(ComputerPlayer8.class);
    private transient StateEncoder encoder;
    private transient ActionEncoder actionEncoder = null;

    public ComputerPlayer8(String name, RangeOfInfluence range, int skill) {
        super(name, range, skill);
    }

    public void setEncoder(StateEncoder enc) {
        this.encoder = enc;
    }
    public StateEncoder getEncoder() {return encoder;}

    public void actionsInit(Game game) {
        Player opponent = game.getPlayer(game.getOpponents(playerId).iterator().next());
        actionEncoder = new ActionEncoder();
        //make action maps
//        try {
//            createAllActionsFromDeck(getMatchPlayer().getDeck(), actionEncoder.opponentActionMap);
//            createAllActionsFromDeck(opponent.getMatchPlayer().getDeck(), actionEncoder.playerActionMap);
//            createAllTargetsFromDecks(opponent.getMatchPlayer().getDeck(), getMatchPlayer().getDeck(), actionEncoder.targetMap, opponent.getName(), getName());
//        } catch (GameException e) {
//            throw new RuntimeException(e);
//        }
    }

    @Override
    public boolean priority(Game game) {
        game.resumeTimer(getTurnControlledBy());
        boolean result = priorityPlay(game);
        game.pauseTimer(getTurnControlledBy());
        return result;
    }
    private boolean priorityPlay(Game game) {
        if(actionEncoder == null) {
            actionsInit(game);
        }
        game.getState().setPriorityPlayerId(playerId);
        game.firePriorityEvent(playerId);

        List<ActivatedAbility> playableAbilities = getPlayable(game, true);
        //List<ActivatedAbility> playableAbilities = getPlayable(game, true).stream().filter(a -> !(a instanceof ManaAbility)).collect(Collectors.toList());

        if(playableAbilities.isEmpty() && !game.isCheckPoint()) {//just pass when only option
            pass(game);
            return false;
        }
        game.setLastPriority(playerId);

        switch (game.getTurnStepType()) {
            case UPKEEP:

            case DRAW:
                pass(game);
                return false;
            case PRECOMBAT_MAIN:
                // 09.03.2020:
                // in old version it passes opponent's pre-combat step (game.isActivePlayer(playerId) -> pass(game))
                // why?!


                //printBattlefieldScore(game, "Sim PRIORITY on MAIN 1");

                if (actions.isEmpty()) {
                    calculateActions(game);
                } else {
                    // TODO: is it possible non empty actions without calculation?!
                    throw new IllegalStateException("wtf");
                }
                act(game);
                return true;
            case BEGIN_COMBAT:
                pass(game);
                return false;
            case DECLARE_ATTACKERS:
                //printBattlefieldScore(game, "Sim PRIORITY on DECLARE ATTACKERS");
                if (actions.isEmpty()) {
                    calculateActions(game);
                } else {
                    // TODO: is it possible non empty actions without calculation?!
                    throw new IllegalStateException("wtf");
                }
                act(game);
                return true;
            case DECLARE_BLOCKERS:
                log.info("DECLARE_BLOCKERS CP8");
                //printBattlefieldScore(game, "Sim PRIORITY on DECLARE BLOCKERS");
                if (actions.isEmpty()) {
                    calculateActions(game);
                } else {
                    // TODO: is it possible non empty actions without calculation?!
                    throw new IllegalStateException("wtf");
                }
                act(game);
                return true;
            case FIRST_COMBAT_DAMAGE:
            case COMBAT_DAMAGE:
            case END_COMBAT:
                pass(game);
                return false;
            case POSTCOMBAT_MAIN:
                //printBattlefieldScore(game, "Sim PRIORITY on MAIN 2");
                if (actions.isEmpty()) {
                    calculateActions(game);
                } else {
                    // TODO: is it possible non empty actions without calculation?!
                    throw new IllegalStateException("wtf");
                }
                act(game);
                return true;
            case END_TURN:
                //state learning testing only check state at end of its turns
                if(game.getActivePlayerId() == getId()) {
                    //encoder.processState(game);
                    printBattlefieldScore(game, "END STEP====================");
                }
            case CLEANUP:
                actionCache.clear();
                pass(game);
                return false;
        }
        return false;
    }
    int [] getActionVec(Ability a) {
        int[] out = new int[128];
        out[actionEncoder.getActionIndex(a, false)] = 1;
        return out;
    }
    @Override
    protected void act(Game game) {
        if (actions == null
                || actions.isEmpty()) {
            pass(game);
        } else {
            boolean usedStack = false;
            while (actions.peek() != null) {
                Ability ability = actions.poll();
                log.info("===> SELECTED ACTION for {}: {}", getName(), getAbilityAndSourceInfo(game, ability, true));

                Player opponent = game.getPlayer(game.getOpponents(playerId).iterator().next());
                if(opponent.getRealPlayer() instanceof ComputerPlayerMCTS2) { //encode opponent plays to the neural network for RL MCTS players
                    ComputerPlayerMCTS2 mcts2 = (ComputerPlayerMCTS2)opponent.getRealPlayer();
                    MCTSNode root = mcts2.root;
                    if(root != null) root = root.getMatchingState(game.getLastPriority().getState().getValue(true, game.getLastPriority()), MCTSPlayer.NextAction.PRIORITY, getPlayerHistory(), game.getPlayer(game.getOpponents(playerId).iterator().next()).getPlayerHistory());
                    if (root != null) {
                        log.info("found matching root with {} visits", root.visits);
                        root.emancipate();
                        int[] visits = mcts2.getActionVec(root, false);
                        visits[actionEncoder.getActionIndex(ability, false)] += 100; //add 100 virtual visits of the actual action to the MCTS distribution
                        encoder.addLabeledState(root.stateVector, visits, root.getMeanScore(), MCTSPlayer.NextAction.PRIORITY, name.equals("PlayerA"));
                        //update root for the mcts player too
                        mcts2.root = root;
                    }
                } else {
                    if (!getPlayable(game, true).isEmpty()) {//only log decision states
                        log.info("logged: {} for {}", ability.toString(), name);
                        //save action vector
                        int[] actionVec = getActionVec(ability);
                        //save state vector
                        Set<Integer> stateVector = encoder.processState(game, getId());
                        //add scores
                        double perspectiveFactor = getId() == encoder.getMyPlayerID() ? 1.0 : -1.0;
                        double score = perspectiveFactor * Math.tanh(root.score * 1.0 / 20000);
                        encoder.addLabeledState(stateVector, actionVec, score, MCTSPlayer.NextAction.PRIORITY, name.equals("PlayerA"));
                    }
                }
                if (!ability.getTargets().isEmpty()) {
                    for (Target target : ability.getTargets()) {
                        for (UUID id : target.getTargets()) {
                            target.updateTarget(id, game);
                            if (!target.isNotTarget()) {
                                game.addSimultaneousEvent(GameEvent.getEvent(GameEvent.EventType.TARGETED, id, ability, ability.getControllerId()));
                            }
                        }
                    }
                }
                this.activateAbility((ActivatedAbility) ability, game);
                if(ability instanceof ManaAbility) {//automatically add manual mana activations
                    getPlayerHistory().prioritySequence.add(ability.copy());
                }
                if (ability.isUsesStack()) {
                    usedStack = true;
                }
            }
            if (usedStack) {
                pass(game);
            }
        }
    }
}
