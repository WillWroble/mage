package mage.player.ai;

import mage.abilities.ActivatedAbility;
import mage.game.Game;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class ChooseTriggeredAbilityNextAction implements MCTSNodeNextAction {

    @Override
    public List<MCTSNode> performNextAction(MCTSNode node, MCTSPlayer player, Game game, String fullStateValue) {
        List<MCTSNode> children = new ArrayList<>();
        // Get targets for the current ability
        for (Set<UUID> targets: player.chooseTriggeredOptions) {
            //create node to add option to
            Game sim = game.getLastPriority().createSimulationForAI();
            MCTSPlayer simPlayer2 = (MCTSPlayer) sim.getPlayer(player.getId());
            MCTSPlayer simPlayer1 = (MCTSPlayer) sim.getPlayer(game.getLastPriorityPlayerId());
            simPlayer2.copyDialogues(player);
            simPlayer2.chooseTriggeredAction.add(targets);
            simPlayer1.activateAbility((ActivatedAbility) node.getAction().copy(), sim);
            sim.resume();
            MCTSNode newNode = new MCTSNode(node, sim, node.getAction().copy());
            newNode.combat = node.combat;
            newNode.chooseTriggeredAction = new ArrayList<>(node.chooseTriggeredAction);
            newNode.chooseTriggeredAction.add(targets);
            children.add(newNode);
        }
        return children;
    }

    @Override
    public void applyAction(MCTSNode node, MCTSPlayer player, Game game) {
        //do nothing for now
    }
}
