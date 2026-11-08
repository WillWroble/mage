package mage.player.spellbench.decide;

import mage.game.Game;
import mage.game.combat.Combat;
import mage.game.combat.CombatGroup;
import mage.game.permanent.Permanent;
import mage.players.Player;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The combat completability oracle (Section 7.1 no dead ends, Section 7.5 Combat; design 3.8; brief 5.4 risk 2).
 * <p>
 * A declaration is posed one decision per slot (an attacker, or one block of a blocker). A candidate is offered only
 * when some choice for the later slots completes the declaration into one XMage accepts unchanged. Each complete
 * declaration is tried on a fresh simulation copy of the game ({@link Game#createSimulationForAI}): declared there
 * through XMage's own combat code, then put through XMage's own checks, the ones its declare-attackers and
 * declare-blockers loops run after a player answers ({@code Combat.checkAttackRestrictions};
 * {@code checkBlockRestrictions}, {@code checkBlockRequirementsAfter}, {@code checkBlockRestrictionsAfter}). XMage
 * either rejects a bad declaration or, for a computer player such as the seats, repairs it by removing or adding
 * attackers and blockers; the oracle counts a declaration legal only when the checks pass and leave it unchanged.
 * <p>
 * The copy is callback-free: it is a simulation, so any seat callback a check or replacement effect reaches throws
 * ({@link SeatPlayer} refuses simulation games), and the oracle counts that declaration as not legal. The copy's
 * combat shares some sets with the live game's (XMage's copy constructor copies those maps shallowly); they are
 * detached first, so nothing the oracle does reaches the live game.
 * <p>
 * Attack requirements (CR 508.1d) are counted as XMage computes them ({@code Combat.getCreaturesForcedToAttack}):
 * a declaration must obey as many as any legal declaration does. Block requirements (CR 509.1c) are part of XMage's
 * block checks.
 */
final class CombatOracle {

    /** Complete declarations tried per question; past it the candidate is not offered (counted). */
    static final int LEAF_BUDGET = 3000;

    private static final Method CHECK_ATTACK = method(Combat.class, "checkAttackRestrictions", Player.class,
            Game.class);
    private static final Field ATTACKED_BY = field(Combat.class, "numberCreaturesDefenderAttackedBy");
    private static final Field MUST_BLOCK = field(Combat.class, "creatureMustBlockAttackers");
    private static final Field FORCED_ATTACK = field(Combat.class, "creaturesForcedToAttack");
    private static final Field BLOCKING_GROUPS = field(Combat.class, "blockingGroups");

    private final Game game;
    private final boolean attack;
    private final UUID player;
    /** The creature each slot declares for. */
    final List<UUID> creatures = new ArrayList<>();
    /** Each slot's choices in posed order; null is "does not attack" or "does not block". */
    final List<List<UUID>> options = new ArrayList<>();
    /** Requirements obeyed by a slot's choice (attacks only). */
    private final List<int[]> gains = new ArrayList<>();
    private final Exchange.Stats stats;
    private final Map<String, Boolean> leaves = new LinkedHashMap<>();
    private int budget;
    /** The most requirements a legal declaration obeys. */
    private int target;

    private CombatOracle(Game game, boolean attack, UUID player, Exchange.Stats stats) {
        this.game = game;
        this.attack = attack;
        this.player = player;
        this.stats = stats;
    }

    /** An attack: one slot per creature, choices null (when it may stay home) and each defender it can attack. */
    static CombatOracle attack(Game game, UUID attackingPlayer, List<Permanent> attackers, List<UUID> defenders,
                               Exchange.Stats stats) {
        CombatOracle o = new CombatOracle(game, true, attackingPlayer, stats);
        Map<UUID, Set<UUID>> forced = game.getCombat().getCreaturesForcedToAttack();
        for (Permanent p : attackers) {
            List<UUID> choices = new ArrayList<>();
            choices.add(null);
            for (UUID d : defenders) {
                if (p.canAttack(d, game)) {
                    choices.add(d);
                }
            }
            int[] gain = new int[choices.size()];
            Set<UUID> must = forced.get(p.getId());
            if (must != null) {
                for (int k = 1; k < choices.size(); k++) {
                    gain[k] = must.isEmpty() || must.contains(choices.get(k)) ? 1 : 0;
                }
            }
            o.creatures.add(p.getId());
            o.options.add(choices);
            o.gains.add(gain);
        }
        o.target = o.best();
        return o;
    }

    /**
     * A block: {@code blocks[i]} slots for blocker i (one per block it may make, Section 7.5), choices null and
     * each attacker it can block. A blocker's later slots repeat no attacker, and after a null offer only null.
     */
    static CombatOracle block(Game game, UUID defendingPlayer, List<Permanent> blockers, List<Integer> blocks,
                              List<UUID> attackers, Exchange.Stats stats) {
        CombatOracle o = new CombatOracle(game, false, defendingPlayer, stats);
        for (int i = 0; i < blockers.size(); i++) {
            Permanent p = blockers.get(i);
            List<UUID> choices = new ArrayList<>();
            choices.add(null);
            for (UUID a : attackers) {
                CombatGroup g = game.getCombat().findGroup(a);
                if (g != null && g.canBlock(p, game)) {
                    choices.add(a);
                }
            }
            for (int j = 0; j < blocks.get(i); j++) {
                o.creatures.add(p.getId());
                o.options.add(choices);
                o.gains.add(new int[choices.size()]);
            }
        }
        o.target = o.best();
        return o;
    }

    int slots() {
        return creatures.size();
    }

    /**
     * The choices for slot {@code slot} after {@code prefix} (its first {@code slot} entries) that complete into a
     * legal declaration, each with one completion (the full declaration), in posed order.
     */
    Map<Integer, UUID[]> completable(UUID[] prefix, int slot) {
        Map<Integer, UUID[]> out = new LinkedHashMap<>();
        List<UUID> choices = options.get(slot);
        for (int k = 0; k < choices.size(); k++) {
            UUID c = choices.get(k);
            if (!allowed(prefix, slot, c)) {
                continue;
            }
            UUID[] decl = Arrays.copyOf(prefix, slots());
            decl[slot] = c;
            budget = LEAF_BUDGET;
            UUID[] found = complete(decl, slot + 1, score(decl, slot + 1));
            if (found != null) {
                out.put(k, found);
            } else if (budget <= 0) {
                stats.add("combat_oracle_budget:" + (attack ? "attack" : "block"));
            }
        }
        return out;
    }

    /** A blocker's later slots: no attacker twice, nothing after a null. */
    private boolean allowed(UUID[] decl, int slot, UUID choice) {
        UUID creature = creatures.get(slot);
        for (int s = 0; s < slot; s++) {
            if (!creatures.get(s).equals(creature)) {
                continue;
            }
            if (decl[s] == null) {
                return choice == null;
            }
            if (decl[s].equals(choice)) {
                return false;
            }
        }
        return true;
    }

    private int score(UUID[] decl, int upTo) {
        int s = 0;
        for (int i = 0; i < upTo; i++) {
            s += gains.get(i)[options.get(i).indexOf(decl[i])];
        }
        return s;
    }

    private int bound(int from) {
        int b = 0;
        for (int i = from; i < slots(); i++) {
            int m = 0;
            for (int g : gains.get(i)) {
                m = Math.max(m, g);
            }
            b += m;
        }
        return b;
    }

    /** A legal completion of {@code decl} from slot {@code from} that obeys {@link #target} requirements, or null. */
    private UUID[] complete(UUID[] decl, int from, int score) {
        if (score + bound(from) < target) {
            return null;
        }
        if (from == slots()) {
            return score == target && legal(decl) ? decl.clone() : null;
        }
        List<UUID> choices = options.get(from);
        for (int k = 0; k < choices.size() && budget > 0; k++) {
            UUID c = choices.get(k);
            if (!allowed(decl, from, c)) {
                continue;
            }
            decl[from] = c;
            UUID[] found = complete(decl, from + 1, score + gains.get(from)[k]);
            decl[from] = null;
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /** The most requirements any legal declaration obeys (branch and bound over complete declarations). */
    private int best() {
        target = 0;
        budget = LEAF_BUDGET * 4;
        int best = -1;
        UUID[] decl = new UUID[slots()];
        // raise the target one requirement at a time: each search either finds a declaration or proves none
        for (int t = bound(0); t >= 0; t--) {
            target = t;
            if (complete(decl, 0, 0) != null) {
                best = t;
                break;
            }
            if (budget <= 0) {
                stats.add("combat_oracle_budget:requirements");
                break;
            }
        }
        if (best < 0) {
            // no legal declaration found: the caller halts (a dead end it cannot pose around)
            stats.add("combat_oracle_no_declaration:" + (attack ? "attack" : "block"));
        }
        return Math.max(best, 0);
    }

    /** Whether XMage accepts this complete declaration unchanged (class comment). */
    private boolean legal(UUID[] decl) {
        String key = Arrays.toString(decl);
        Boolean known = leaves.get(key);
        if (known != null) {
            return known;
        }
        budget--;
        boolean ok;
        try {
            ok = attack ? attackLegal(decl) : blockLegal(decl);
        } catch (RuntimeException e) {
            stats.add("combat_oracle_callback_or_error:" + (attack ? "attack" : "block"));
            ok = false;
        }
        stats.add("combat_oracle_leaf");
        leaves.put(key, ok);
        return ok;
    }

    private Game copy() {
        Game sim = game.createSimulationForAI();
        detach(sim.getCombat());
        return sim;
    }

    private boolean attackLegal(UUID[] decl) {
        Game sim = copy();
        Combat c = sim.getCombat();
        for (UUID a : new ArrayList<>(c.getAttackers())) {
            c.removeAttacker(a, sim);
        }
        Map<UUID, UUID> want = new LinkedHashMap<>();
        for (int i = 0; i < decl.length; i++) {
            if (decl[i] == null) {
                continue;
            }
            Permanent p = sim.getPermanent(creatures.get(i));
            if (p == null || !p.canAttack(decl[i], sim) || !c.declareAttacker(p.getId(), decl[i], player, sim)) {
                return false;
            }
            want.put(p.getId(), decl[i]);
        }
        if (!attackers(c).equals(want)) {
            return false;
        }
        boolean ok;
        try {
            ok = (Boolean) CHECK_ATTACK.invoke(c, sim.getPlayer(player), sim);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        } catch (InvocationTargetException e) {
            throw e.getCause() instanceof RuntimeException ? (RuntimeException) e.getCause()
                    : new IllegalStateException(e.getCause());
        }
        return ok && attackers(c).equals(want);
    }

    private boolean blockLegal(UUID[] decl) {
        Game sim = copy();
        Combat c = sim.getCombat();
        for (UUID b : new ArrayList<>(c.getBlockers())) {
            c.removeBlocker(b, sim);
        }
        Player defender = sim.getPlayer(player);
        if (defender == null) {
            return false;
        }
        for (int i = 0; i < decl.length; i++) {
            if (decl[i] == null) {
                continue;
            }
            // as PlayerImpl.declareBlocker: the group's own check, its DECLARE_BLOCKER event, the blocking group
            Permanent b = sim.getPermanent(creatures.get(i));
            CombatGroup g = c.findGroup(decl[i]);
            if (b == null || g == null || !g.canBlock(b, sim)) {
                return false;
            }
            g.addBlocker(b.getId(), player, sim);
            c.addBlockingGroup(b.getId(), decl[i], player, sim);
        }
        Set<String> want = blocks(decl);
        if (!blocks(c).equals(want)) {
            return false;
        }
        boolean ok = c.checkBlockRestrictions(defender, sim)
                && c.checkBlockRequirementsAfter(defender, defender, sim)
                && c.checkBlockRestrictionsAfter(defender, defender, sim);
        return ok && blocks(c).equals(want);
    }

    /** Attacker -> defender, as XMage's combat holds them. */
    static Map<UUID, UUID> attackers(Combat c) {
        Map<UUID, UUID> out = new LinkedHashMap<>();
        for (CombatGroup group : c.getGroups()) {
            for (UUID a : group.getAttackers()) {
                out.put(a, group.getDefenderId());
            }
        }
        return out;
    }

    /** "blocker>attacker" pairs, as XMage's combat holds them, sorted (a set compared, never iterated out). */
    static Set<String> blocks(Combat c) {
        List<String> out = new ArrayList<>();
        for (CombatGroup group : c.getGroups()) {
            for (UUID b : group.getBlockers()) {
                for (UUID a : group.getAttackers()) {
                    out.add(b + ">" + a);
                }
            }
        }
        java.util.Collections.sort(out);
        return new LinkedHashSet<>(out);
    }

    private Set<String> blocks(UUID[] decl) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < decl.length; i++) {
            if (decl[i] != null) {
                out.add(creatures.get(i) + ">" + decl[i]);
            }
        }
        java.util.Collections.sort(out);
        return new LinkedHashSet<>(out);
    }

    /** Gives the copy's combat its own copies of the collections XMage's copy constructor shares by reference. */
    @SuppressWarnings("unchecked")
    private static void detach(Combat c) {
        try {
            for (Field f : new Field[]{ATTACKED_BY, MUST_BLOCK, FORCED_ATTACK}) {
                for (Map.Entry<UUID, Set<UUID>> e : ((Map<UUID, Set<UUID>>) f.get(c)).entrySet()) {
                    e.setValue(new LinkedHashSet<>(e.getValue()));
                }
            }
            for (Map.Entry<UUID, CombatGroup> e : ((Map<UUID, CombatGroup>) BLOCKING_GROUPS.get(c)).entrySet()) {
                e.setValue(e.getValue().copy());
            }
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Method method(Class<?> owner, String name, Class<?>... types) {
        try {
            Method m = owner.getDeclaredMethod(name, types);
            m.setAccessible(true);
            return m;
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Field field(Class<?> owner, String name) {
        try {
            Field f = owner.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (NoSuchFieldException e) {
            throw new IllegalStateException(e);
        }
    }
}
