package spellbench.kit.xmage;

import mage.game.Game;
import mage.players.Player;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One rebuilt world (design Section 3.1): an XMage game built from the viewer's permitted inputs and one sample of
 * what is hidden, the map between v2 object ids and the world's UUIDs, and the world's flags (Section 3.3).
 */
public final class World {

    public final Game game;
    public final String viewer;
    public final int index;
    public final KitRandom random;
    /** "p0", "p1" to the world's player ids. */
    public final Map<String, UUID> seatPlayer = new LinkedHashMap<>();
    /** v2 object id to the world's UUID (cards, permanents, stack objects, looked-at cards). */
    public final Map<String, UUID> idToUuid = new LinkedHashMap<>();
    /** World UUID (including faces and halves of multi-part cards, and a spell's card) to the v2 object id. */
    public final Map<UUID, String> uuidToId = new LinkedHashMap<>();
    /** Every flag of this world: approximate:..., unsupported:..., roundtrip:... */
    public final List<String> flags = new ArrayList<>();
    /** Library cards placed at known positions (pins), and the other seat's known hand names (H3 knowledge). */
    public final List<UUID> pinnedLibrary = new ArrayList<>();
    public final List<String> knownHandNames = new ArrayList<>();
    /** The sample this world was built from (sampler JSON), for the evidence. */
    public Map<String, Object> sample;

    World(Game game, String viewer, int index, KitRandom random) {
        this.game = game;
        this.viewer = viewer;
        this.index = index;
        this.random = random;
    }

    public UUID player(String seat) {
        return seatPlayer.get(seat);
    }

    public Player viewerPlayer() {
        return game.getPlayer(seatPlayer.get(viewer));
    }

    public String seatOf(UUID playerId) {
        for (Map.Entry<String, UUID> e : seatPlayer.entrySet()) {
            if (e.getValue().equals(playerId)) {
                return e.getKey();
            }
        }
        return null;
    }

    void bind(String objectId, UUID uuid) {
        if (objectId == null || uuid == null) {
            return;
        }
        idToUuid.put(objectId, uuid);
        uuidToId.put(uuid, objectId);
    }

    void alias(UUID part, String objectId) {
        if (part != null && objectId != null && !uuidToId.containsKey(part)) {
            uuidToId.put(part, objectId);
        }
    }

    public boolean approximate() {
        for (String f : flags) {
            if (f.startsWith("approximate:") || f.startsWith("unsupported:")) {
                return true;
            }
        }
        return false;
    }
}
