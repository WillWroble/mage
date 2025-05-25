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

        // Get targets for the current ability
        for (Set<UUID> targets: player.chooseTargetOptions) {
            //create node to add option to
            Game sim = node.macroState.createSimulationForAI();
            MCTSPlayer simPlayer2 = (MCTSPlayer) sim.getPlayer(player.getId());
            MCTSPlayer simPlayer1 = (MCTSPlayer) sim.getPlayer(node.macroPlayerId);
            simPlayer2.chooseTargetAction = new ArrayList<>(node.chooseTargetAction);
            simPlayer2.chooseTargetAction.add(targets);
            simPlayer1.activateAbility((ActivatedAbility) node.getAction(), sim);
            sim.resume();
            MCTSNode newNode = new MCTSNode(node, sim, node.getAction());
            newNode.combat = node.combat;
            newNode.chooseTargetAction = new ArrayList<>(node.chooseTargetAction);
            newNode.chooseTargetAction.add(targets);
            children.add(newNode);
        }
        return children;
    }
}
