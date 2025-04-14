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
//            if(simCount > 300) {
//                return true;
//            }
            current = root;
            simCount++;
            // Selection: traverse until a leaf node is reached.
            int testCount = 0;
            while (!current.isLeaf()) {
                current = current.select(this.playerId);
                testCount++;
                if(testCount > 1000) {
                    System.out.println("stuck in selection");
                }
            }
            // Don't stop to eval state until stack is empty and there are multiple children
            int traverseCount = 0;
            while (!current.isTerminal() && traverseCount < 10
                    //&& (current.getNumChildren() == 1
                    //|| current.getGame().getTurnPhaseType() == TurnPhase.COMBAT
                    && !current.getGame().getStack().isEmpty()) {
                traverseCount++;
                current.expand();
                current = current.select(this.playerId);
            }
            int result;
            if (!current.isTerminal()) {
                // Expansion:
                result = rollout(current);
                current.expand();
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
