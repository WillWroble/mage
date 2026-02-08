package mage.player.ai;

import mage.abilities.Ability;
import mage.abilities.ActivatedAbility;
import mage.constants.RangeOfInfluence;
import mage.game.Game;
import mage.game.events.GameEvent;
import mage.player.ai.encoder.ActionEncoder;
import mage.player.ai.encoder.StateEncoder;
import mage.players.Player;
import mage.target.Target;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * minimax player designed for being the opponent in RL vs minimax games.
 * logs priority decisions (as one hot vectors) and state values (as minimax derived score of root normalized to -1,1)
 * against a RL MCTS player will use that players search tree to create blended visit distributions instead of one hots and also use their MCTS derived value score for the root node (if states match)
 */
public class ComputerPlayer8 extends ComputerPlayer7{
    private static final Logger log = LoggerFactory.getLogger(ComputerPlayer8.class);
    private transient StateEncoder encoder;
    private transient ActionEncoder actionEncoder = null;

    public ComputerPlayer8(String name, RangeOfInfluence range, int skill) {
        super(name, range, skill);
        autoTap = true;
    }

    public void setEncoder(StateEncoder enc) {
        this.encoder = enc;
    }

    public void actionsInit(Game game) {
        actionEncoder = new ActionEncoder();
        //make action maps
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
        playableAbilities = playableAbilities.stream().filter(a -> !a.isManaAbility()).collect(Collectors.toList());

        if(playableAbilities.isEmpty() && !game.isCheckPoint(playerId)) {//just pass when only option
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
                if (actions.isEmpty()) {
                    calculateActions(game);
                } else {
                    // TODO: is it possible non empty actions without calculation?!
                    throw new IllegalStateException("wtf");
                }
                act(game);
                return true;
            case DECLARE_BLOCKERS:
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

                Player opponent = game.getOpponent(playerId);
                Set<Integer> stateVector = encoder.processState(game, playerId);
                if(opponent.getRealPlayer() instanceof ComputerPlayerMCTS2) { //encode opponent plays to the neural network for RL MCTS players
                    ComputerPlayerMCTS2 mcts2 = (ComputerPlayerMCTS2)opponent.getRealPlayer();
                    MCTSNode2 root = mcts2.root;
                    if(root != null) root = (MCTSNode2) root.getMatchingState(stateVector);
                    if (root != null) {
                        log.info("found matching root with {} visits", root.getVisits());
                        root.emancipate();
                        int[] visits = mcts2.getActionVec(root, game);
                        visits[actionEncoder.getActionIndex(ability, false)] += 100; //add 100 virtual visits of the actual action to the MCTS distribution
                        encoder.addLabeledState(root.stateVector, visits, root.getMeanScore(), ActionEncoder.ActionType.PRIORITY, name.equals("PlayerA"));
                        //update root for the mcts player too
                        mcts2.root = root;
                    }
                } else {
                    if (!getPlayable(game, true).isEmpty()) {//only log decision states
                        log.info("logged: {} for {}", ability, name);
                        //save action vector
                        int[] actionVec = getActionVec(ability);
                        //add scores
                        double perspectiveFactor = getId() == encoder.getMyPlayerId() ? 1.0 : -1.0;
                        double score = perspectiveFactor * Math.tanh(root.score * 1.0 / 20000);
                        encoder.addLabeledState(stateVector, actionVec, score, ActionEncoder.ActionType.PRIORITY, name.equals("PlayerA"));
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
