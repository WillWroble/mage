package mage.player.ai;

import java.util.ArrayList;
import java.util.HashSet;
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
    private volatile boolean stateUpdatesComplete = false;

    private static final Logger logger = Logger.getLogger(ComputerPlayerMCTS.class);

    public MCTSExecutor(Game sim, UUID playerId, int thinkTime) {
        this.playerId = playerId;
        this.thinkTime = thinkTime;
        this.simCount = 0;
        root = new MCTSNode(playerId, sim);
        root.chooseTargetAction = new ArrayList<>(((MCTSPlayer) sim.getPlayer(playerId)).chooseTargetAction);
    }
    public MCTSExecutor(Game sim, UUID playerId, int thinkTime, MCTSNode givenRoot) {
        this.playerId = playerId;
        this.thinkTime = thinkTime;
        this.simCount = 0;
        root = new MCTSNode(playerId, sim);
        if(givenRoot != null) root.chooseTargetAction = new ArrayList<>(givenRoot.chooseTargetAction);
    }
    public MCTSExecutor(UUID playerId, int thinkTime, MCTSNode givenRoot) {
        this.playerId = playerId;
        this.thinkTime = thinkTime;
        this.simCount = 0;
        root = new MCTSNode(givenRoot);
    }
    public MCTSExecutor(MCTSExecutor exec) {
        this.playerId = exec.playerId;
        this.thinkTime = exec.thinkTime;
        this.simCount = exec.simCount;
        this.reachedTerminalState = exec.reachedTerminalState;
        root = new MCTSNode(exec.root);
    }

    @Override
    public Boolean call() {
        //simCount = 0;
        MCTSNode current;
        // This loop termination is controlled externally by timeout.
        long deadline = System.currentTimeMillis() + thinkTime * 1000L;
        while (simCount <= 300 && !Thread.currentThread().isInterrupted()
                && System.currentTimeMillis() < deadline) {
            simCount++;
            synchronized (this) {
                current = root;
                // Selection: traverse until a leaf node is reached.
                int testCount = 0;
                while (!current.isLeaf()) {
                    current = current.select(this.playerId);
                    testCount++;
                    if (testCount > 1000) {
                        System.out.println("stuck in selection");
                        break;
                    }
                }
                double result;
                if (!current.isTerminal()) {
                    // Expansion:
                    result = rollout(current);
                    current.expand();
                } else {
                    reachedTerminalState = true;
                    result = current.isWinner(this.playerId) ? 1 : -1;
                }
                // Backpropagation:
                current.backpropagate(result);
                this.stateUpdatesComplete = true;
            }
        }
        return true;
    }

    /**
     * The rollout method encapsulates the simulation/evaluation step.
     * By default, it calls node.simulate(playerId). You can override this method in a subclass.
     *
     * @param node the leaf node to evaluate
     * @return an integer evaluation of the node's state
     */
    protected double rollout(MCTSNode node) {
        return 0;//node.simulate(this.playerId);//-1 or 1
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
