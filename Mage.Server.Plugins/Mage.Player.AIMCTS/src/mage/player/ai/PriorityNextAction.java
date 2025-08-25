package mage.player.ai;

import mage.abilities.Ability;
import mage.abilities.ActivatedAbility;
import mage.game.Game;
import mage.game.GameState;
import mage.players.Player;

import java.util.ArrayList;
import java.util.List;

public class PriorityNextAction implements MCTSNodeNextAction{

    @Override
    public List<MCTSNode> performNextAction(MCTSNode node, MCTSPlayer player, Game game, String fullStateValue) {
        List<MCTSNode> children = new ArrayList<>();
        List<Ability> abilities;
        if (!MCTSNode.USE_ACTION_CACHE)
            abilities = player.getPlayableOptions(game);
        else
            abilities = MCTSNode.getPlayables(player, fullStateValue, game);
        int optionCount = 0;
        for (Ability ability: abilities) {
            Game sim;
            if(false && optionCount==abilities.size()-1) {
                sim = game;//no copy necessary
                for(Player p : sim.getPlayers().values()) {
                    MCTSPlayer mctsPlayer = (MCTSPlayer) p;
                    mctsPlayer.lastToAct = false;
                    mctsPlayer.chooseTargetCount = 0;
                    mctsPlayer.makeChoiceCount = 0;
                }
            } else {
                sim = game.createSimulationForAI();
                optionCount++;
            }
            MCTSPlayer simPlayer = (MCTSPlayer) sim.getPlayer(player.getId());
            boolean success = simPlayer.activateAbility((ActivatedAbility)ability.copy(), sim);

            if(!success) {
                if(MCTSPlayer.PRINT_CHOOSE_DIALOGUES) System.out.println("PRIORITY FAILSAFE TRIGGERED: " + ability.toString());
                continue;//failsafe
            }
            sim.resume();
            //ComputerPlayerMCTS.shuffleUnknowns(sim, node.targetPlayer);
            children.add(new MCTSNode(node, sim, ability.copy()));
        }

        return children;
    }
}
