package mage.player.cabt;

import mage.MageObject;
import mage.game.Game;

import java.util.UUID;

/**
 * CABT bridge: shared source-name resolution for option payloads. Resolves
 * the way XMage's own UI does — game.getObject first, falling back to
 * game.getCard for cards not currently in an object zone (e.g. hand cards
 * being announced).
 */
final class CabtSourceNames {

    private CabtSourceNames() {
    }

    static String sourceName(Game game, UUID sourceId) {
        if (game == null || sourceId == null) {
            return null;
        }
        MageObject object = game.getObject(sourceId);
        if (object == null) {
            object = game.getCard(sourceId);
        }
        return object == null ? null : object.getName();
    }
}
