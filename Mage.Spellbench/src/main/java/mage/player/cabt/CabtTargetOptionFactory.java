package mage.player.cabt;

import mage.MageObject;
import mage.game.Game;
import mage.players.Player;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * CABT bridge: builds one option per possible target. Targets resolve the
 * same way XMage's UI resolves them — players via game.getPlayer, everything
 * else via game.getObject / game.getCard — never by inferring from serialized
 * board state.
 */
public final class CabtTargetOptionFactory {

    static final String PAYLOAD_TARGET_ID = "targetId";
    static final String PAYLOAD_TARGET_NAME = "targetName";
    static final String PAYLOAD_TARGET_CLASS = "targetClass";
    static final String PAYLOAD_ZONE = "zone";
    static final String PAYLOAD_ALREADY_CHOSEN = "alreadyChosen";

    private CabtTargetOptionFactory() {
    }

    /**
     * Option for a Target prompt: a player becomes PROMPT_PLAYER, any other
     * game object (permanent, stack object, card) becomes PROMPT_OBJECT.
     */
    public static MagicOption targetOption(Game game, UUID targetId,
                                           boolean targeted, boolean alreadyChosen) {
        Player player = game.getPlayer(targetId);
        if (player != null) {
            return MagicOptionFactory.promptPlayer(
                    label(targeted, player.getName()),
                    payload(game, targetId, player.getName(),
                            player.getClass().getSimpleName(), alreadyChosen));
        }
        MageObject object = game.getObject(targetId);
        String name = object == null ? null : object.getName();
        String targetClass = object == null ? null : object.getClass().getSimpleName();
        return MagicOptionFactory.promptObject(
                label(targeted, name == null ? targetId.toString() : name),
                payload(game, targetId, name, targetClass, alreadyChosen));
    }

    /**
     * Option for a TargetCard prompt: always PROMPT_CARD; resolves through
     * game.getCard first, falling back to game.getObject.
     */
    public static MagicOption cardOption(Game game, UUID cardId,
                                         boolean targeted, boolean alreadyChosen) {
        MageObject object = game.getCard(cardId);
        if (object == null) {
            object = game.getObject(cardId);
        }
        String name = object == null ? null : object.getName();
        String targetClass = object == null ? null : object.getClass().getSimpleName();
        return MagicOptionFactory.promptCard(
                label(targeted, name == null ? cardId.toString() : name),
                payload(game, cardId, name, targetClass, alreadyChosen));
    }

    private static String label(boolean targeted, String name) {
        return (targeted ? "Target " : "Choose ") + name;
    }

    private static Map<String, Object> payload(Game game, UUID targetId, String name,
                                               String targetClass, boolean alreadyChosen) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put(PAYLOAD_TARGET_ID, targetId.toString());
        payload.put(PAYLOAD_TARGET_NAME, name);
        payload.put(PAYLOAD_TARGET_CLASS, targetClass);
        payload.put(PAYLOAD_ZONE, zoneName(game, targetId));
        payload.put(PAYLOAD_ALREADY_CHOSEN, alreadyChosen);
        return payload;
    }

    private static String zoneName(Game game, UUID objectId) {
        if (game.getState() == null) {
            return null;
        }
        return game.getState().getZone(objectId) == null
                ? null
                : game.getState().getZone(objectId).name();
    }
}
