package mage.player.cabt;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * CABT bridge observation: the pending prompt as it will appear under
 * "select" — who is choosing, how many options must be picked, and the
 * ordered option list.
 */
public final class MagicSelectView {

    private final int playerIndex;
    private final String playerId;
    private final String type;
    private final int minCount;
    private final int maxCount;
    private final List<MagicOptionView> option;

    public MagicSelectView(int playerIndex, String playerId, String type,
                           int minCount, int maxCount, List<MagicOptionView> option) {
        this.playerIndex = playerIndex;
        this.playerId = playerId;
        this.type = type;
        this.minCount = minCount;
        this.maxCount = maxCount;
        this.option = Collections.unmodifiableList(new ArrayList<MagicOptionView>(option));
    }

    public int getPlayerIndex() {
        return playerIndex;
    }

    public String getPlayerId() {
        return playerId;
    }

    public String getType() {
        return type;
    }

    public int getMinCount() {
        return minCount;
    }

    public int getMaxCount() {
        return maxCount;
    }

    public List<MagicOptionView> getOption() {
        return option;
    }
}
