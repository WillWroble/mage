package mage.player.ai;

import java.util.UUID;
import java.util.concurrent.Callable;

import mage.constants.TurnPhase;
import mage.game.Game;
import org.apache.log4j.Logger;

public class MCTSExecutor implements Callable<Boolean> {

    protected transient MCTSNode root;
    protected int thinkTime;
    protected UUID playerId;
    protected int simCount;
    public boolean reachedTerminalState = false;

    private static final Logger logger = Logger.getLogger(ComputerPlayerMCTS.class);

    public MCTSExecutor(Game sim, UUID playerId, int thinkTime) {
        this.playerId = playerId;
        this.thinkTime = thinkTime;
        root = new MCTSNode(playerId, sim);
    }

    @Override
    public Boolean call() {
        simCount = 0;
        MCTSNode current;
        // This loop termination is controlled externally by timeout.
        while (true) {
            if(simCount > 2000) {
                return true;
            }
            current = root;
            simCount++;
            // Selection: traverse until a leaf node is reached.
            while (!current.isLeaf()) {
                current = current.select(this.playerId);
            }
            // Don't stop to eval state until combat is over and there are multiple children
            while (!current.isTerminal() && (current.getNumChildren() == 1 || current.getGame().getTurnPhaseType() == TurnPhase.COMBAT || !current.getGame().getStack().isEmpty())) {
                current.expand();
                current = current.select(this.playerId);
            }
            int result;
            if (!current.isTerminal()) {
                // Expansion:
                current.expand();
                result = rollout(current);

            } else {
                reachedTerminalState = true;
                result = current.isWinner(this.playerId) ? 100000000 : -100000000;
            }
            // Backpropagation:
            current.backpropagate(result);
        }
    }

    /**
     * The rollout method encapsulates the simulation/evaluation step.
     * By default, it calls node.simulate(playerId). You can override this method in a subclass.
     *
     * @param node the leaf node to evaluate
     * @return an integer evaluation of the node's state
     */
    protected int rollout(MCTSNode node) {
        System.out.println("you should never see this");
        return node.simulate(this.playerId);
    }

    public MCTSNode getRoot() {
        return root;
    }

    public void clear() {
        root = null;
    }

    public int getSimCount() {
        return simCount;
    }
}
