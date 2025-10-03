package mage.player.ai;

import mage.abilities.Ability;
import mage.abilities.ActivatedAbility;
import mage.game.Game;
import mage.target.Target;

import java.util.*;

import static mage.player.ai.ComputerPlayerMCTS.logger;

public class ChooseTargetNextAction implements MCTSNodeNextAction {

    @Override
    public List<MCTSNode> performNextAction(MCTSNode node, MCTSPlayer player, Game game, String fullStateValue) {
        List<MCTSNode> children = new ArrayList<>();
        if(MCTSPlayer.PRINT_CHOOSE_DIALOGUES) logger.info("expanding choose target: " + player.chooseTargetOptions.size() + " " + ((ComputerPlayer)game.getPlayer(game.getOpponents(player.getId()).iterator().next())).chooseTargetOptions.size());
        // Get targets for the current ability
        for (Set<UUID> targets: player.chooseTargetOptions) {
            //create node to add option to
            //assert (game.getLastPriority().getLastPriority() == game.getLastPriority());
            Game sim = game.getLastPriority().createSimulationForAI();
            MCTSPlayer simPlayer2 = (MCTSPlayer) sim.getPlayer(player.getId());
            MCTSPlayer simPlayer1 = (MCTSPlayer) sim.getPlayer(game.getLastPriorityPlayerId());
            simPlayer2.chooseTargetAction = new ArrayList<>(node.chooseTargetAction);//for stability
            simPlayer2.chooseTargetAction.add(targets);
            simPlayer1.activateAbility((ActivatedAbility) node.getAction().copy(), sim);
            sim.resume();

            if(simPlayer2.getNextAction() != MCTSPlayer.NextAction.PRIORITY) System.out.println("DIDNT MAKE IT TO PRIORITY");
            MCTSNode newNode = new MCTSNode(node, sim, node.getAction().copy());
            newNode.chooseTargetAction = new ArrayList<>(node.chooseTargetAction);
            newNode.chooseTargetAction.add(targets);
            children.add(newNode);
        }
        return children;
    }
}
