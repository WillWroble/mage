package mage.player.ai;

import mage.game.Game;
import mage.player.ai.encoder.ActionEncoder;
import mage.player.ai.score.GameStateEvaluator3;
import mage.players.PlayerScript;


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
            return;
        }


        long[] nnIndices = new long[stateVector.size()];
        int k = 0;
        for (int i : stateVector)  {
            nnIndices[k++] = i;
        }

        ((ComputerPlayerMCTS2) basePlayer).nn.inferAsync(nnIndices)
                .thenAccept(out -> {
                    // This runs on the HTTP executor thread when inference completes
                    switch (actionType) {
                        case PRIORITY:
                            if (!basePlayer.noPolicyPriority) {
                                policy = playerId.equals(targetPlayer) ? out.policy_player :
                                        (!basePlayer.noPolicyOpponent ? out.policy_opponent : null);
                            }
                            break;
                        case CHOOSE_TARGET:
                            if (!basePlayer.noPolicyTarget && !basePlayer.noPolicyOpponent) {
                                policy = out.policy_target;
                            }
                            break;
                        case CHOOSE_USE:
                            if (!basePlayer.noPolicyUse) {
                                policy = out.policy_binary;
                            }
                            break;
                        default:
                            policy = null;
                    }
                    networkScore = out.value;
                    backpropagate(1 + networkScore, 0);
                    setPriors();
                    ((ComputerPlayerMCTS2)basePlayer).pendingNodes.decrementAndGet();
                    evaluationPending = false;
                })
                .exceptionally(ex -> {
                    ex.printStackTrace();
                    logger.error("REMOTE EVAL FAILURE");
                    // Still backprop something on failure so tree doesn't get stuck
                    backpropagate(0, 0);
                    ((ComputerPlayerMCTS2)basePlayer).pendingNodes.decrementAndGet();
                    evaluationPending = false;
                    return null;
                });

    }
    public void awaitEvaluation() {
        while (evaluationPending) {
            Thread.yield();
        }
    }
}
