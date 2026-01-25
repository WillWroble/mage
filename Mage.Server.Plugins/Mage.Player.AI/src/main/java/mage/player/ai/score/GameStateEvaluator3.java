package mage.player.ai.score;

import mage.game.Game;
import mage.game.permanent.Permanent;
import mage.players.Player;

import java.util.UUID;
/**
 * simpler heuristic for static/offline MCTS. Can be used to bootstrap RL.
 * @author WillWroble
 * */
public final class GameStateEvaluator3 {

    private static final double UNTAPPED_BONUS = 0;
    private static final double LIFE_TOTAL_VALUE = 0.6;
    private static final double HAND_CARD_VALUE = 1.0;
    private static final double PERM_BASE_VALUE = 1.0;
    private static final double NORMALIZATION_FACTOR = 15.0; // tunable

    public static double evaluateNormalized(UUID playerId, Game game) {
        UUID opponentId = game.getOpponents(playerId, false).stream().findFirst().orElse(null);
        if (opponentId == null) return 1.0;

        // Terminal states
        if (game.checkIfGameIsOver()) {
            Player player = game.getPlayer(playerId);
            Player opponent = game.getPlayer(opponentId);
            if (player.hasLost() || opponent.hasWon()) return -1.0;
            if (opponent.hasLost() || player.hasWon()) return 1.0;
        }

        double myScore = evaluateResources(playerId, game);
        double oppScore = evaluateResources(opponentId, game);

        return Math.tanh((myScore - oppScore) / NORMALIZATION_FACTOR);
    }

    private static double evaluateResources(UUID playerId, Game game) {
        Player player = game.getPlayer(playerId);
        if (player == null) return 0;

        double score = 0;

        // Life (harmonic)
        //score += harmonic(Math.max(0, player.getLife()));
        score += Math.max(0, player.getLife())*LIFE_TOTAL_VALUE;

        // Cards in hand (harmonic)
        score += harmonic(player.getHand().size())*HAND_CARD_VALUE;

        // Permanents
        for (Permanent perm : game.getBattlefield().getAllActivePermanents(playerId)) {
            score += PERM_BASE_VALUE + perm.getManaValue();
            if (!perm.isTapped()) {
                score += UNTAPPED_BONUS;
            }
        }
        return score;
    }

    private static double harmonic(int n) {
        double sum = 0;
        for (int i = 1; i <= n; i++) {
            sum += 1.0 / i;
        }
        return sum;
    }
}