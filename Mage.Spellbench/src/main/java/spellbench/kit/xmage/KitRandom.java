package spellbench.kit.xmage;

import mage.util.RandomUtil;
import spellbench.kit.core.Seeds;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.UUID;

/**
 * The kit's {@link RandomUtil.Source} (X-P1 with X-P3's {@code newId()}): every draw a world makes comes from a
 * stream of that world's seed (design Section 4.5), every object id from the current id scope. One world at a time
 * per runner (Section 5.4): the source is installed before a world is built and stays until the next one.
 * <p>
 * Id scopes: a visible object takes its UUIDs from a stream keyed by its {@code object_id} and the game key, so the
 * same object has the same UUID in every world and at every decision while it stays in its zone (attack and block
 * questions come in UUID order). Hidden cards and everything created during play take ids from the world's own
 * stream.
 */
public final class KitRandom implements RandomUtil.Source {

    private static final byte[] BOOT = Seeds.hmac("spellbench-xmage-kit/v1".getBytes(StandardCharsets.US_ASCII), "boot");

    private final byte[] worldSeed;
    private final byte[] idSeed;
    private final Map<UUID, String> seats = new HashMap<>();
    private final Map<String, Seeds.Stream> streams = new HashMap<>();
    private Seeds.Stream idStream;
    private String idScope = "world";
    private long draws;

    private final Random untagged;

    private KitRandom(byte[] worldSeed, byte[] idSeed) {
        this.worldSeed = worldSeed.clone();
        this.idSeed = idSeed.clone();
        this.untagged = stream("untagged");
        this.idStream = stream("ids:world");
    }

    /** A source for one world: {@code idSeed} keys the visible-object id scopes (shared by all worlds of a game). */
    public static KitRandom install(byte[] worldSeed, byte[] idSeed) {
        KitRandom r = new KitRandom(worldSeed, idSeed);
        RandomUtil.setSource(r);
        return r;
    }

    /** The fixed boot source: ids minted outside any world (class initialization, warm-up) are the same everywhere. */
    public static KitRandom installBoot() {
        return install(BOOT, BOOT);
    }

    public synchronized void assignSeat(UUID playerId, String seat) {
        seats.put(playerId, seat);
    }

    /** Ids drawn from now on belong to the visible object {@code objectId} (game-wide stream). */
    public synchronized void scopeObject(String objectId) {
        idScope = "object:" + objectId;
        idStream = new Seeds.Stream(Seeds.streamSeed(idSeed, "ids:object:" + objectId, 0));
    }

    /** Ids drawn from now on come from the world's stream {@code ids:<name>}. */
    public synchronized void scopeWorld(String name) {
        idScope = "world:" + name;
        idStream = stream("ids:" + name);
    }

    public synchronized String idScope() {
        return idScope;
    }

    /** A named stream of this world (sampler, bot, library order). */
    public synchronized Seeds.Stream stream(String purpose) {
        Seeds.Stream s = streams.get(purpose);
        if (s == null) {
            s = new Seeds.Stream(Seeds.streamSeed(worldSeed, purpose, 0));
            streams.put(purpose, s);
        }
        return s;
    }

    @Override
    public Random untagged() {
        draws++;
        return untagged;
    }

    @Override
    public synchronized Random forPlayer(UUID playerId, String purpose) {
        String seat = playerId == null ? null : seats.get(playerId);
        return stream("engine:" + (seat == null ? "shared" : seat) + ":" + purpose);
    }

    @Override
    public synchronized Random shared(String purpose) {
        return stream("engine:shared:" + purpose);
    }

    @Override
    public synchronized UUID newId() {
        byte[] b = new byte[16];
        idStream.nextBytes(b);
        b[6] = (byte) ((b[6] & 0x0f) | 0x40);
        b[8] = (byte) ((b[8] & 0x3f) | 0x80);
        long msb = 0;
        long lsb = 0;
        for (int i = 0; i < 8; i++) {
            msb = (msb << 8) | (b[i] & 0xff);
            lsb = (lsb << 8) | (b[i + 8] & 0xff);
        }
        return new UUID(msb, lsb);
    }

    /** Draw counts per stream, for the evidence. */
    public synchronized Map<String, Long> usage() {
        Map<String, Long> out = new TreeMap<>();
        for (Map.Entry<String, Seeds.Stream> e : streams.entrySet()) {
            out.put(e.getKey(), e.getValue().draws());
        }
        out.put("untagged_calls", draws);
        return out;
    }
}
