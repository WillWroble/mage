package mage.player.ai;

import java.util.UUID;
import java.util.concurrent.Callable;
import mage.game.Game;
import org.apache.log4j.Logger;

public class MCTSExecutor implements Callable<Boolean> {

    protected transient MCTSNode root;
    protected int thinkTime;
    protected UUID playerId;
    protected int simCount;

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
            current = root;
            // Selection: traverse until a leaf node is reached.
            while (!current.isLeaf()) {
                current = current.select(this.playerId);
            }
            int result;
            if (!current.isTerminal()) {
                // Expansion:
                current.expand();
                // If multiple children exist, choose one to evaluate.
                if (current.getNumChildren() > 1) {
                    current = current.select(this.playerId);
                    result = rollout(current);
                    simCount++;
                } else {
                    current = current.select(this.playerId);
                    result = 0;
                }
            } else {
                //System.out.println("Reached Terminal State!");
                result = current.isWinner(this.playerId) ? 1 : -1;
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
