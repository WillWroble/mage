package mage.player.ai;

import mage.abilities.Ability;
import mage.abilities.ActivatedAbility;
import mage.constants.RangeOfInfluence;
import mage.game.Game;
import mage.game.events.GameEvent;
import mage.target.Target;

import java.util.UUID;

public class ComputerPlayer8 extends ComputerPlayer7{
    //public static boolean saveMinimaxScore = true;
    private transient StateEncoder encoder;
    public ComputerPlayer8(ComputerPlayer7 player) {
        super(player);
    }

    public ComputerPlayer8(String name, RangeOfInfluence range, int skill) {
        super(name, range, skill);
    }

    public void setEncoder(StateEncoder enc) {
        this.encoder = enc;
    }
    public StateEncoder getEncoder() {return encoder;}

    @Override
    public boolean priority(Game game) {
        game.resumeTimer(getTurnControlledBy());
        boolean result = priorityPlay(game);
        game.pauseTimer(getTurnControlledBy());
        return result;
    }
    private boolean priorityPlay(Game game) {
        game.getState().setPriorityPlayerId(playerId);
        game.firePriorityEvent(playerId);
        //process every priority
        //encoder.processState(game);
        //state learning testing
        //encoder.processState(game);
        //printBattlefieldScore(game, "PRIORITY====================");

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
    double [] getActionVec(Ability a) {
        double[] out = new double[128];
        out[ActionEncoder.getAction(a)] = 1.0;
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
                // example: ===> SELECTED ACTION for PlayerA: Play Swamp
                System.out.println(String.format("===> SELECTED ACTION for %s: %s",
                        getName(),
                        getAbilityAndSourceInfo(game, ability, true)
                ));
                //save action vector
                encoder.addAction(getActionVec(ability));
                //save state vector
                encoder.processMacroState(game, getId());
                //add scores
                double perspectiveFactor = getId() == encoder.getMyPlayerID() ? 1.0 : -1.0;
                encoder.stateScores.add(perspectiveFactor*Math.tanh(root.score*1.0/20000));
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
