package mage.player.spellbench.ids;

import mage.player.spellbench.Secrets;

import java.util.Arrays;
import java.util.UUID;

/**
 * Per-viewer object ids, the recommended construction of protocol v2 Section 5.3:
 * <pre>
 * id_key    = HMAC-SHA256(game_secret, "spellbench/v2/object-id")
 * object_id = "o-" + hex(first 8 bytes of HMAC-SHA256(id_key, M))
 * M         = "viewer:internal key"            (zones visible to the viewer)
 * M         = "viewer:internal key:look:n"     (zones hidden from the viewer, n-th look)
 * </pre>
 * The internal key is "uuid:z<zone change counter>", so an object gets a fresh id on every zone change.
 * The game secret, id_key and internal keys never leave the engine.
 */
public final class ObjectIds {

    private final byte[] idKey;

    public ObjectIds(byte[] gameSecret) {
        this.idKey = Secrets.hmac(gameSecret, "spellbench/v2/object-id");
    }

    public static String internalKey(UUID objectId, int zoneChangeCounter) {
        return objectId + ":z" + zoneChangeCounter;
    }

    public String visible(String viewer, String internalKey) {
        return mint(viewer + ":" + internalKey);
    }

    public String look(String viewer, String internalKey, int n) {
        return mint(viewer + ":" + internalKey + ":look:" + n);
    }

    public String idKeyHex() {
        return Secrets.toHex(idKey);
    }

    private String mint(String message) {
        return "o-" + Secrets.toHex(Arrays.copyOf(Secrets.hmac(idKey, message), 8));
    }
}
