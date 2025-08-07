package mage.player.ai;

import mage.abilities.Ability;
import mage.abilities.ActivatedAbility;
import mage.game.Game;
import mage.target.Target;

import java.util.*;

public class ChooseTargetNextAction implements MCTSNodeNextAction {

    @Override
    public List<MCTSNode> performNextAction(MCTSNode node, MCTSPlayer player, Game game, String fullStateValue) {
        List<MCTSNode> children = new ArrayList<>();
        if(MCTSPlayer.PRINT_CHOOSE_DIALOGUES) System.out.println("expanding choose target");
        // Get targets for the current ability
        for (Set<UUID> targets: player.chooseTargetOptions) {
            //create node to add option to
            Game sim = game.getLastPriority().createSimulationForAI();
            MCTSPlayer simPlayer2 = (MCTSPlayer) sim.getPlayer(player.getId());
            MCTSPlayer simPlayer1 = (MCTSPlayer) sim.getPlayer(game.getLastPriorityPlayerId());
            simPlayer2.chooseTargetAction.add(targets);
            simPlayer1.activateAbility((ActivatedAbility) node.getAction().copy(), sim);
            sim.resume();
            MCTSNode newNode = new MCTSNode(node, sim, node.getAction().copy());
            newNode.chooseTargetAction = new ArrayList<>(node.chooseTargetAction);
            newNode.chooseTargetAction.add(targets);
            children.add(newNode);
        }
        return children;
    }

    @Override
    public void applyAction(MCTSNode node, MCTSPlayer player, Game game) {
        //do nothing for now
    }
}
