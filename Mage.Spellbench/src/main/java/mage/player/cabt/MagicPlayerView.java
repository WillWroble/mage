package mage.player.cabt;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * CABT bridge observation: state of one player.
 * <p>
 * Hidden-information contract: hidden zones stay counts-only by default.
 * {@code hand} is populated for the selecting player only; for every other
 * player it is an empty list and only {@code handCount} is visible.
 * {@code revealedHand} carries opponent hand cards only when the engine has
 * made them known/revealed (always empty until reveal tracking is added).
 * Graveyard and per-player exile are public zones.
 */
public final class MagicPlayerView {

    private final int playerIndex;
    private final String playerId;
    private final String name;
    private final int life;
    private final int handCount;
    private final int libraryCount;
    private final int graveyardCount;
    private final boolean passed;
    private final boolean inGame;
    private final List<MagicObjectView> graveyard;
    private final List<MagicObjectView> exile;
    private final List<MagicObjectView> revealedHand;
    private final List<MagicObjectView> hand;

    public MagicPlayerView(int playerIndex, String playerId, String name, int life,
                           int handCount, int libraryCount, int graveyardCount,
                           boolean passed, boolean inGame,
                           List<MagicObjectView> graveyard,
                           List<MagicObjectView> exile,
                           List<MagicObjectView> revealedHand,
                           List<MagicObjectView> hand) {
        this.playerIndex = playerIndex;
        this.playerId = playerId;
        this.name = name;
        this.life = life;
        this.handCount = handCount;
        this.libraryCount = libraryCount;
        this.graveyardCount = graveyardCount;
        this.passed = passed;
        this.inGame = inGame;
        this.graveyard = copyOf(graveyard);
        this.exile = copyOf(exile);
        this.revealedHand = copyOf(revealedHand);
        this.hand = copyOf(hand);
    }

    private static List<MagicObjectView> copyOf(List<MagicObjectView> values) {
        return values == null
                ? Collections.<MagicObjectView>emptyList()
                : Collections.unmodifiableList(new ArrayList<MagicObjectView>(values));
    }

    public int getPlayerIndex() {
        return playerIndex;
    }

    public String getPlayerId() {
        return playerId;
    }

    public String getName() {
        return name;
    }

    public int getLife() {
        return life;
    }

    public int getHandCount() {
        return handCount;
    }

    public int getLibraryCount() {
        return libraryCount;
    }

    public int getGraveyardCount() {
        return graveyardCount;
    }

    public boolean isPassed() {
        return passed;
    }

    public boolean isInGame() {
        return inGame;
    }

    public List<MagicObjectView> getGraveyard() {
        return graveyard;
    }

    public List<MagicObjectView> getExile() {
        return exile;
    }

    public List<MagicObjectView> getRevealedHand() {
        return revealedHand;
    }

    public List<MagicObjectView> getHand() {
        return hand;
    }
}
