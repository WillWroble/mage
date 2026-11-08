package mage.player.spellbench.rng;

import mage.player.spellbench.Secrets;
import mage.util.RandomUtil;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.UUID;

/**
 * The stream router behind X-P1 to X-P3 (protocol v2 Section 11.6). One instance per game, installed into
 * {@link RandomUtil} before any card, player or game object of that game is created.
 * <ul>
 * <li>Tagged draws use stream {@code scope:purpose}, seeded HMAC-SHA256(game_secret,
 * "spellbench/v2/rng:scope:purpose:0"), where scope is the owner's seat ("p0", "p1") or "shared".</li>
 * <li>Every untagged draw (a RandomUtil call site that names no owner or purpose) takes its own stream
 * {@code shared:untagged:k}, so no stream can feed both seats' hidden zones.</li>
 * <li>Object ids come from stream {@code shared:object_id}.</li>
 * </ul>
 * Engine processes run one game at a time (design D3); between games the boot router is reinstalled.
 */
public final class GameRandom implements RandomUtil.Source {

    private static final byte[] BOOT_SECRET = Secrets.sha256("spellbench/xmage/boot-ids".getBytes(StandardCharsets.US_ASCII));

    private static final boolean TRACE_CLINIT = Boolean.getBoolean("spellbench.trace.clinit");
    /** Diagnostic: a file that gets every game id draw with its callers (-Dspellbench.trace.ids=PATH). */
    private static final String TRACE_IDS = System.getProperty("spellbench.trace.ids");
    /** Test hook for the X5 paired-world leak tests; see {@link #worldPurpose}. */
    private static final String WORLD_SALT = System.getProperty("spellbench.test.worldSalt");

    private final byte[] gameSecret;
    private final boolean boot;
    private final Map<UUID, String> seats = new HashMap<>();
    private final Map<String, HmacStream> streams = new HashMap<>();
    private long untaggedDraws;

    private final Random untagged = new Random(0L) {
        private static final long serialVersionUID = 1L;

        @Override
        public void setSeed(long seed) {
        }

        @Override
        protected int next(int bits) {
            return drawUntagged(bits);
        }
    };

    private GameRandom(byte[] gameSecret, boolean boot) {
        this.gameSecret = gameSecret.clone();
        this.boot = boot;
    }

    /**
     * Installs a router for one game; call before creating the game, its players and their cards.
     */
    public static GameRandom install(byte[] gameSecret) {
        if (gameSecret.length != 32) {
            throw new IllegalArgumentException("game_secret must be 32 bytes");
        }
        GameRandom router = new GameRandom(gameSecret, false);
        RandomUtil.setSource(router);
        trace("game " + Secrets.toHex(Secrets.sha256(gameSecret)).substring(0, 16));
        return router;
    }

    /**
     * Installs the fixed boot router: ids minted outside any game (class initialization, warm-up) are then
     * the same in every process.
     */
    public static GameRandom installBoot() {
        GameRandom router = new GameRandom(BOOT_SECRET, true);
        RandomUtil.setSource(router);
        return router;
    }

    public synchronized void assignSeat(UUID playerId, String seat) {
        seats.put(playerId, seat);
    }

    @Override
    public Random untagged() {
        return untagged;
    }

    @Override
    public synchronized Random forPlayer(UUID playerId, String purpose) {
        String seat = playerId == null ? null : seats.get(playerId);
        return stream(seat == null ? "shared" : seat, purpose);
    }

    @Override
    public synchronized Random shared(String purpose) {
        return stream("shared", purpose);
    }

    @Override
    public synchronized UUID newId() {
        if (TRACE_CLINIT && !boot) {
            traceClassInit();
        }
        if (TRACE_IDS != null && !boot) {
            StringBuilder sb = new StringBuilder("id");
            int n = 0;
            for (StackTraceElement e : new Throwable().getStackTrace()) {
                String c = e.getClassName();
                if (c.startsWith("mage.player.spellbench.rng") || c.startsWith("mage.util.RandomUtil")) {
                    continue;
                }
                sb.append(' ').append(c.substring(c.lastIndexOf('.') + 1)).append('.').append(e.getMethodName())
                        .append(':').append(e.getLineNumber());
                if (++n == 14) {
                    break;
                }
            }
            trace(sb.toString());
        }
        byte[] b = new byte[16];
        stream("shared", "object_id").nextBytes(b);
        b[6] = (byte) ((b[6] & 0x0f) | 0x40); // version 4 layout, as UUID.randomUUID()
        b[8] = (byte) ((b[8] & 0x3f) | 0x80);
        long msb = 0;
        long lsb = 0;
        for (int i = 0; i < 8; i++) {
            msb = (msb << 8) | (b[i] & 0xff);
            lsb = (lsb << 8) | (b[i + 8] & 0xff);
        }
        return new UUID(msb, lsb);
    }

    /**
     * Draw counts per stream, for reports.
     */
    public synchronized Map<String, Long> usage() {
        Map<String, Long> out = new TreeMap<>();
        for (Map.Entry<String, HmacStream> e : streams.entrySet()) {
            out.put(e.getKey(), e.getValue().draws());
        }
        out.put("shared:untagged:*", untaggedDraws);
        return out;
    }

    /**
     * Diagnostic (-Dspellbench.trace.clinit=true): reports an id drawn from a game's stream inside a static
     * initializer. Such a draw happens only in the first game of a JVM and shifts every later id.
     */
    private void traceClassInit() {
        for (StackTraceElement e : new Throwable().getStackTrace()) {
            if ("<clinit>".equals(e.getMethodName())) {
                System.err.println("spellbench.trace.clinit: id drawn during static init of " + e.getClassName());
                return;
            }
        }
    }

    private static void trace(String line) {
        if (TRACE_IDS == null) {
            return;
        }
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(TRACE_IDS, true)) {
            out.write((line + '\n').getBytes(StandardCharsets.UTF_8));
        } catch (java.io.IOException e) {
            // diagnostic only
        }
    }

    private HmacStream stream(String scope, String purpose) {
        return streams.computeIfAbsent(scope + ":" + purpose,
                k -> new HmacStream(Secrets.streamSeed(gameSecret, scope, worldPurpose(scope, purpose), 0)));
    }

    /**
     * The purpose a stream is seeded with: the purpose itself, except under the X5 paired-world test hook
     * ({@code -Dspellbench.test.worldSalt=SEAT:SALT}, a no-op unless set), where every stream of that seat's
     * scope gets {@code ":world:SALT"} appended. Two runs of one game secret then differ only in that seat's
     * hidden randomness (its library order and its random cards); shared streams and object ids are untouched.
     */
    private String worldPurpose(String scope, String purpose) {
        if (boot || WORLD_SALT == null || "shared".equals(scope) || !WORLD_SALT.startsWith(scope + ":")) {
            return purpose;
        }
        return purpose + ":world:" + WORLD_SALT.substring(scope.length() + 1);
    }

    private synchronized int drawUntagged(int bits) {
        HmacStream one = new HmacStream(Secrets.streamSeed(gameSecret, "shared", "untagged", untaggedDraws++));
        return one.next(bits);
    }
}
