package mage.player.ai;

import mage.game.Game;
import mage.player.ai.encoder.ActionEncoder;
import mage.player.ai.score.GameStateEvaluator3;
import mage.players.PlayerScript;

/**
 * async version of MCTSNode. uses virtual visits like original AlphaZero paper.
 */
public class MCTSNode2 extends MCTSNode {
    public volatile boolean evaluationPending = false;

    public MCTSNode2(ComputerPlayerMCTS targetPlayer, Game game, ActionEncoder.ActionType actionType, PlayerScript prefixA, PlayerScript prefixB) {
        super(targetPlayer, game, actionType, prefixA, prefixB);
    }
    protected MCTSNode2(MCTSNode2 parent) {
        super(parent);
    }

    @Override
    protected MCTSNode2 createChild() {
        return new MCTSNode2(this);
    }

    /**
     * async call. wait for network result than finalize the node.
     */
    public void evaluate() {
        evaluationPending = true;
        ((ComputerPlayerMCTS2)basePlayer).pendingNodes.incrementAndGet();
        Game game = getGame();
        if(((ComputerPlayerMCTS2)basePlayer).offlineMode) {
            policy = null;
            if(actionType.equals(ActionEncoder.ActionType.PRIORITY)) {
                networkScore = GameStateEvaluator3.evaluateNormalized(targetPlayer, game);
            } else {
                if(parent != null) {
                    networkScore = getParent().networkScore;
                } else {
                    networkScore = 0;
                }
            }
            backpropagate(1 + networkScore, 0);
            evaluationPending = false;
            ((ComputerPlayerMCTS2)basePlayer).pendingNodes.decrementAndGet();
            return;
        }
        while (((ComputerPlayerMCTS2)basePlayer).pendingNodes.get() > ComputerPlayerMCTS2.MAX_PENDING) {
            Thread.yield();
        }
        ((ComputerPlayerMCTS2) basePlayer).nn.inferAsync(featureGraph)
                .thenAccept(out -> {
                    synchronized (basePlayer) {
                        // This runs on the HTTP executor thread when inference completes
                        switch (actionType) {
                            case PRIORITY:
                                if(basePlayer.noPolicyPriority) break;
                                if (targetPlayer.equals(playerId) || !basePlayer.noPolicyOpponent) {
                                    policy = out.policyPriority;
                                }
                                break;
                            case CHOOSE_TARGET:
                                if(basePlayer.noPolicyTarget) break;
                                if (targetPlayer.equals(playerId) || !basePlayer.noPolicyOpponent) {
                                    policy = out.policyTarget;
                                }
                                break;
                            case CHOOSE_USE:
                                if(basePlayer.noPolicyUse) break;
                                if (targetPlayer.equals(playerId) || !basePlayer.noPolicyOpponent) {
                                    policy = out.policyUse;
                                }
                                break;
                            default:
                                policy = null;
                        }

                        networkScore = out.value;
                        backpropagate(1 + networkScore, 0);
                        setPriors();
                        ((ComputerPlayerMCTS2) basePlayer).pendingNodes.decrementAndGet();
                        evaluationPending = false;
                    }
                })
                .exceptionally(ex -> {
                    ex.printStackTrace();
                    logger.error("REMOTE EVAL FAILURE");
                    // Still backprop something on failure so tree doesn't get stuck
                    backpropagate(0, 0);
                    ((ComputerPlayerMCTS2)basePlayer).pendingNodes.decrementAndGet();
                    evaluationPending = false;
                    throw new RuntimeException("REMOTE EVAL FAILURE");
                });

    }
    public void awaitEvaluation() {
        while (evaluationPending) {
            Thread.yield();
        }
    }
}
