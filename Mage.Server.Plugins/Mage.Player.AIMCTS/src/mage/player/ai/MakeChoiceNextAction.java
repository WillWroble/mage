package mage.player.ai;

import mage.abilities.Ability;
import mage.abilities.ActivatedAbility;
import mage.game.Game;
import mage.target.Target;

import java.util.*;

public class MakeChoiceNextAction implements MCTSNodeNextAction {

    @Override
    public List<MCTSNode> performNextAction(MCTSNode node, MCTSPlayer player, Game game, String fullStateValue) {
        List<MCTSNode> children = new ArrayList<>();
        if(MCTSPlayer.PRINT_CHOOSE_DIALOGUES) System.out.println("expanding make choice");
        // Get targets for the current ability
        for (String chosen : player.choiceOptions) {
            //create node to add option to
            Game sim = game.getLastPriority().createSimulationForAI();
            MCTSPlayer simPlayer2 = (MCTSPlayer) sim.getPlayer(player.getId());
            MCTSPlayer simPlayer1 = (MCTSPlayer) sim.getPlayer(game.getLastPriorityPlayerId());
            //simPlayer2.choiceAction.add(chosen);
            simPlayer1.activateAbility((ActivatedAbility) node.getAction().copy(), sim);
            sim.resume();
            MCTSNode newNode = new MCTSNode(node, node.getAction().copy());
            newNode.choiceAction = chosen;
            children.add(newNode);
        }
        return children;
    }

}
