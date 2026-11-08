package spellbench.kit.xmage;

import mage.abilities.Ability;
import mage.abilities.Mode;
import mage.abilities.Modes;
import mage.cards.Cards;
import mage.choices.Choice;
import mage.constants.Outcome;
import mage.game.Game;
import mage.game.stack.StackObject;
import mage.players.Player;
import mage.players.PlayerImpl;
import mage.target.Target;
import mage.target.TargetAmount;
import mage.target.TargetCard;
import spellbench.kit.core.Json;
import spellbench.kit.core.ObsCompare;
import spellbench.kit.core.Sampler;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * Saved-anchor continuation (design Section 5.1). The anchor is the viewer's last priority decision at which the
 * resolving object S was on top of the stack; the front keeps its inputs (observation and world seed), so an anchor
 * survives a runner restart (it is rebuilt, never held in runner memory). Steps:
 * <ol>
 * <li>condition the anchor's hidden zones on what the current decision and this resolution's earlier dialogs show:
 * cards that entered the viewer's hand since the anchor are pinned on top of its library, in order (the effect drew
 * them from there), and library cards those decisions showed are pinned in that library;</li>
 * <li>rebuild the anchor world, let both seats pass, and resolve S (nothing is re-executed from a later snapshot, so no
 * effect is repeated);</li>
 * <li>the resolution's earlier dialogs are answered from this seat's own recorded answers (the front sends them, one
 * entry per logical dialog, in order), mapped to the world through the ids those decisions showed;</li>
 * <li>at the current dialog, compare the world's projected observation with the received one modulo ids; on equality
 * the bot answers there and its answer is the plan of the whole logical dialog, else the caller takes the
 * current-dialog path.</li>
 * </ol>
 * Dialog kinds: target and selection dialogs, yes/no, numbers and modes are replayed and planned; an arrangement is
 * planned (partition from the bot's choice, order recomputed by the front) but not replayed; a choice among named
 * options, divided amounts and library orderings are not continued (mismatch, so the current dialog answers).
 */
public final class Continuation {

    private Continuation() {
    }

    /** Ends the resolution once the current dialog is answered or found different. */
    static final class Stop extends Error {
        private static final long serialVersionUID = 1L;

        Stop() {
            super("continuation stop", null, false, false);
        }
    }

    /** The decider during a continuation: replays the earlier dialogs and intercepts the current one. */
    static final class Continuer extends KitPolicy {
        private static final long serialVersionUID = 1L;
        transient boolean armed;
        transient boolean finish;
        transient Map<String, Object> decision;
        transient Map<String, Object> current;
        transient Map<String, Object> flags;
        transient List<Object> earlier = new ArrayList<>();
        transient World w;
        transient Map<String, Object> result;
        /** XMage dialogs met so far in this resolution (outermost calls of the live game). */
        transient int index;
        transient int depth;
        /** The target of the last selection dialog: a repeated call with it continues that dialog. */
        transient Object lastTarget;

        Continuer(String name, int skill) {
            super(name, skill);
        }

        private Continuer(final Continuer c) {
            super(c);
        }

        @Override
        public Continuer copy() {
            return new Continuer(this);
        }

        private boolean active(Game game) {
            return armed && depth == 0 && game != null && !game.isSimulation();
        }

        private void fail(String why) {
            result.put("match", false);
            result.put("diff", Collections.singletonList(why));
            result.put("replayed", (long) Math.min(index, earlier.size()));
            throw new Stop();
        }

        private boolean callBot(BooleanSupplier bot) {
            depth++;
            try {
                return bot.getAsBoolean();
            } finally {
                depth--;
            }
        }

        /** The current dialog: the world's projection must equal the received observation (modulo ids). */
        private void compare(Game game) {
            result.put("hand_at_dialog", (long) w.viewerPlayer().getHand().size());
            result.put("library_at_dialog", (long) w.viewerPlayer().getLibrary().size());
            result.put("replayed", (long) earlier.size());
            bindLooks(game, Json.arr(current, "known"));
            Map<String, Object> projected;
            try {
                projected = RoundTrip.project(w, flags, Json.str(current, "priority_seat"), Json.arr(current, "known"));
            } catch (Exception e) {
                fail("projection failed: " + e);
                return;
            }
            List<String> diff = ObsCompare.diff(current, projected, 40);
            result.put("diff", new ArrayList<Object>(diff));
            if (!diff.isEmpty()) {
                result.put("match", false);
                throw new Stop();
            }
            result.put("match", true);
            result.put("align", ObsCompare.alignIds(projected, current));
            result.put("projected", projected);
        }

        /**
         * The cards a decision shows (known entries with ids) are the world's cards at those places: a positional
         * library entry is the card at that position, a searched one the first unbound card of that name in that
         * library, another seat's hand entry the first unbound card of that name in that hand. An id already bound
         * keeps its card.
         */
        private void bindLooks(Game game, List<Object> known) {
            Set<UUID> used = new HashSet<>(w.idToUuid.values());
            for (Object o : known) {
                Map<String, Object> k = Json.obj(o);
                String oid = Json.str(k, "object_id");
                if (oid == null || w.idToUuid.containsKey(oid) || Json.str(k, "owner_seat") == null) {
                    continue;
                }
                Player owner = game.getPlayer(w.player(Json.str(k, "owner_seat")));
                if (owner == null) {
                    continue;
                }
                List<mage.cards.Card> zone = "library".equals(Json.str(k, "zone"))
                        ? owner.getLibrary().getCards(game) : new ArrayList<>(owner.getHand().getCards(game));
                mage.cards.Card pick = null;
                Object top = k.get("position_from_top");
                Object bottom = k.get("position_from_bottom");
                if (top instanceof Number && ((Number) top).intValue() < zone.size()) {
                    pick = zone.get(((Number) top).intValue());
                } else if (bottom instanceof Number && ((Number) bottom).intValue() < zone.size()) {
                    pick = zone.get(zone.size() - 1 - ((Number) bottom).intValue());
                } else {
                    for (mage.cards.Card c : zone) {
                        if (!used.contains(c.getId()) && c.getName().equals(Json.str(k, "card_name"))) {
                            pick = c;
                            break;
                        }
                    }
                }
                if (pick != null && pick.getName().equals(Json.str(k, "card_name"))) {
                    w.bind(oid, pick.getId());
                    used.add(pick.getId());
                }
            }
        }

        private List<Object> picksOf(int i) {
            return Json.arr(Json.obj(earlier.get(i)), "picks");
        }

        // ------------------------------------------------------------------ target and selection dialogs

        private boolean targetDialog(Target target, Ability source, Game game, BooleanSupplier bot) {
            return targetDialog(target, null, source, game, bot);
        }

        private boolean targetDialog(Target target, Cards cards, Ability source, Game game, BooleanSupplier bot) {
            if (target == lastTarget) {
                // XMage asks again with the same target until nothing is added (TargetCardInLibrary.choose, the
                // library-order loops): one logical dialog, already answered in full, so this call adds nothing
                return false;
            }
            lastTarget = target;
            int i = index++;
            if (i < earlier.size()) {
                Map<String, Object> dialog = Json.obj(earlier.get(i));
                String fam = Json.str(dialog, "family");
                if ("arrange".equals(fam)) {
                    fail("unsupported replay: an arrangement");
                }
                bindLooks(game, Json.arr(dialog, "known"));
                for (Object p : picksOf(i)) {
                    Map<String, Object> sem = Json.obj(p);
                    String kind = Json.str(sem, "kind");
                    if (kind.startsWith("finish_")) {
                        continue;
                    }
                    if (!"select_object".equals(kind) && !"choose_target".equals(kind) && !"choose_cost_target".equals(kind)) {
                        fail("replay: a " + kind + " answer at a selection dialog");
                    }
                    UUID u = Dialogs.uuidOf(w, sem);
                    if (u == null) {
                        fail("replay: a pick the anchor world cannot name");
                    }
                    if (target.isNotTarget()) {
                        target.add(u, game);
                    } else {
                        target.addTarget(u, source, game);
                    }
                }
                return true;
            }
            if (i == earlier.size()) {
                if (target instanceof TargetAmount) {
                    fail("unsupported continuation: divided amounts");
                }
                String kind = Front_firstKind(decision);
                if (!"select_object".equals(kind) && !"choose_target".equals(kind) && !"choose_cost_target".equals(kind)
                        && !"arrange_card".equals(kind)) {
                    fail("dialog kinds differ: the world asks a selection, the decision is " + kind);
                }
                compare(game);
                boolean r = callBot(bot);
                result.put("picks", "arrange_card".equals(kind) ? arrangement(target, cards) : refs(target, game));
                if (!finish) {
                    throw new Stop();
                }
                return r;
            }
            return callBot(bot);
        }

        /** The current arrangement decision's plan: the bot's chosen cards leave the top; order by candidate order. */
        private List<Object> arrangement(Target target, Cards cards) {
            List<String> ids = new ArrayList<>();
            String purpose = null;
            if (cards != null) {
                // the looked-at cards themselves, top first (an arrangement decision shows one card at a time)
                for (UUID u : cards) {
                    String oid = w.uuidToId.get(u);
                    if (oid == null) {
                        fail("arrangement: a looked-at card has no current id");
                    }
                    ids.add(oid);
                }
            }
            for (Object c : Json.arr(decision, "candidates")) {
                Map<String, Object> sem = Json.obj(Json.obj(c), "semantic");
                if (!"arrange_card".equals(Json.str(sem, "kind"))) {
                    continue;
                }
                purpose = Json.str(sem, "purpose");
                String oid = Json.str(Json.obj(sem, "card"), "object_id");
                if (cards == null && oid != null && !ids.contains(oid)) {
                    ids.add(oid);
                }
            }
            String away = "surveil".equals(purpose) ? "graveyard" : "bottom";
            Map<String, Object> dest = new LinkedHashMap<>();
            List<Object> order = new ArrayList<>();
            for (String oid : ids) {
                UUID u = w.idToUuid.get(oid);
                dest.put(oid, u != null && target.getTargets().contains(u) ? away : "top");
                order.add(oid);
            }
            List<Object> picks = new ArrayList<>();
            picks.add(Json.map("arrangement", true, "dest", dest, "order", order));
            return picks;
        }

        private List<Object> refs(Target target, Game game) {
            @SuppressWarnings("unchecked")
            Map<String, String> align = (Map<String, String>) result.get("align");
            List<Object> out = new ArrayList<>();
            for (UUID id : target.getTargets()) {
                Map<String, Object> ref = Mapping.targetRef(w, id);
                if (ref != null && (ref.get("object_id") != null || ref.get("player") != null)) {
                    out.add(ref); // an object the world bound to a current id, or a player
                    continue;
                }
                // an object the world created as hidden: find it through the projection
                String projectedId = projectedIdOf(game, id);
                String currentId = projectedId == null || align == null ? null : align.get(projectedId);
                out.add(currentId == null ? null : Json.map("object_id", currentId));
            }
            return out;
        }

        private String projectedIdOf(Game game, UUID id) {
            try {
                mage.player.spellbench.observe.ObservationBuilder b = mage.player.spellbench.observe.ObservationBuilder
                        .forSession(game, new byte[32], flags);
                return Json.str(b.build(w.viewer, Json.str(current, "priority_seat"), new ArrayList<>()).reference(id), "object_id");
            } catch (Exception e) {
                return null;
            }
        }

        @Override
        public boolean choose(Outcome outcome, Target target, Ability source, Game game, Map<String, Serializable> options) {
            if (!active(game)) {
                return super.choose(outcome, target, source, game, options);
            }
            return targetDialog(target, source, game, () -> super.choose(outcome, target, source, game, options));
        }

        @Override
        public boolean choose(Outcome outcome, Cards cards, TargetCard target, Ability source, Game game) {
            if (!active(game)) {
                return super.choose(outcome, cards, target, source, game);
            }
            return targetDialog(target, cards, source, game, () -> super.choose(outcome, cards, target, source, game));
        }

        @Override
        public boolean chooseTarget(Outcome outcome, Cards cards, TargetCard target, Ability source, Game game) {
            if (!active(game)) {
                return super.chooseTarget(outcome, cards, target, source, game);
            }
            return targetDialog(target, cards, source, game, () -> super.chooseTarget(outcome, cards, target, source, game));
        }

        @Override
        public boolean chooseTarget(Outcome outcome, Target target, Ability source, Game game) {
            if (!active(game)) {
                return super.chooseTarget(outcome, target, source, game);
            }
            return targetDialog(target, source, game, () -> super.chooseTarget(outcome, target, source, game));
        }

        @Override
        public boolean chooseTargetAmount(Outcome outcome, TargetAmount target, Ability source, Game game) {
            if (!active(game)) {
                return super.chooseTargetAmount(outcome, target, source, game);
            }
            if (index++ <= earlier.size()) {
                fail("unsupported continuation: divided amounts");
            }
            return callBot(() -> super.chooseTargetAmount(outcome, target, source, game));
        }

        // ------------------------------------------------------------------ yes/no, numbers, modes, options

        @Override
        public boolean chooseUse(Outcome outcome, String message, String secondMessage, String trueText, String falseText,
                                 Ability source, Game game) {
            if (!active(game)) {
                return super.chooseUse(outcome, message, secondMessage, trueText, falseText, source, game);
            }
            int i = index++;
            if (i < earlier.size()) {
                Object v = valueOf(picksOf(i), "choose_boolean", "optional_cost");
                if (!(v instanceof Boolean)) {
                    fail("replay: no yes/no answer for a yes/no dialog");
                }
                return (Boolean) v;
            }
            if (i == earlier.size()) {
                String kind = Front_firstKind(decision);
                if (!"choose_boolean".equals(kind) && !"optional_cost".equals(kind)) {
                    fail("dialog kinds differ: the world asks yes/no, the decision is " + kind);
                }
                compare(game);
                boolean r = callBot(() -> super.chooseUse(outcome, message, secondMessage, trueText, falseText, source, game));
                List<Object> picks = new ArrayList<>();
                picks.add(r);
                result.put("picks", picks);
                if (!finish) {
                    throw new Stop();
                }
                return r;
            }
            return callBot(() -> super.chooseUse(outcome, message, secondMessage, trueText, falseText, source, game));
        }

        private int number(Game game, java.util.function.IntSupplier bot) {
            int i = index++;
            if (i < earlier.size()) {
                Object v = valueOf(picksOf(i), "choose_number", null);
                if (!(v instanceof Number)) {
                    fail("replay: no number for a number dialog");
                }
                return ((Number) v).intValue();
            }
            if (i == earlier.size()) {
                String kind = Front_firstKind(decision);
                if (!"choose_number".equals(kind)) {
                    fail("dialog kinds differ: the world asks a number, the decision is " + kind);
                }
                compare(game);
                depth++;
                int r;
                try {
                    r = bot.getAsInt();
                } finally {
                    depth--;
                }
                List<Object> picks = new ArrayList<>();
                picks.add((long) r);
                result.put("picks", picks);
                if (!finish) {
                    throw new Stop();
                }
                return r;
            }
            depth++;
            try {
                return bot.getAsInt();
            } finally {
                depth--;
            }
        }

        @Override
        public int getAmount(int min, int max, String message, Ability source, Game game) {
            if (!active(game)) {
                return super.getAmount(min, max, message, source, game);
            }
            return number(game, () -> super.getAmount(min, max, message, source, game));
        }

        @Override
        public int announceX(int min, int max, String message, Game game, Ability source, boolean isManaPay) {
            if (!active(game)) {
                return super.announceX(min, max, message, game, source, isManaPay);
            }
            return number(game, () -> super.announceX(min, max, message, game, source, isManaPay));
        }

        @Override
        public Mode chooseMode(Modes modes, Ability source, Game game) {
            if (!active(game)) {
                return super.chooseMode(modes, source, game);
            }
            int i = index++;
            List<Mode> all = new ArrayList<>(modes.values());
            if (i < earlier.size()) {
                Object v = valueOf(picksOf(i), "choose_spell_mode", null);
                if (!(v instanceof Number) || ((Number) v).intValue() >= all.size()) {
                    fail("replay: no mode for a mode dialog");
                }
                return all.get(((Number) v).intValue());
            }
            if (i == earlier.size()) {
                if (!"choose_spell_mode".equals(Front_firstKind(decision))) {
                    fail("dialog kinds differ: the world asks a mode, the decision is " + Front_firstKind(decision));
                }
                compare(game);
                depth++;
                Mode m;
                try {
                    m = super.chooseMode(modes, source, game);
                } finally {
                    depth--;
                }
                List<Object> picks = new ArrayList<>();
                picks.add((long) all.indexOf(m));
                result.put("picks", picks);
                if (!finish) {
                    throw new Stop();
                }
                return m;
            }
            depth++;
            try {
                return super.chooseMode(modes, source, game);
            } finally {
                depth--;
            }
        }

        @Override
        public boolean choose(Outcome outcome, Choice choice, Game game) {
            if (!active(game)) {
                return super.choose(outcome, choice, game);
            }
            if (index++ <= earlier.size()) {
                fail("unsupported continuation: a choice among named options");
            }
            return callBot(() -> super.choose(outcome, choice, game));
        }

        /** The value of a recorded dialog's first answer of these kinds (value, pay or mode_index). */
        private static Object valueOf(List<Object> picks, String kind, String altKind) {
            for (Object p : picks) {
                Map<String, Object> sem = Json.obj(p);
                String k = Json.str(sem, "kind");
                if (k.equals(kind) || k.equals(altKind)) {
                    if (sem.containsKey("value")) {
                        return sem.get("value");
                    }
                    if (sem.containsKey("pay")) {
                        return sem.get("pay");
                    }
                    return sem.get("mode_index");
                }
            }
            return null;
        }
    }

    static String Front_firstKind(Map<String, Object> d) {
        for (Object o : Json.arr(d, "candidates")) {
            String k = Json.str(Json.obj(Json.obj(o), "semantic"), "kind");
            if (!k.equals("pass") && !k.startsWith("finish_")) {
                return k;
            }
        }
        List<Object> c = Json.arr(d, "candidates");
        return c.isEmpty() ? null : Json.str(Json.obj(Json.obj(c.get(0)), "semantic"), "kind");
    }

    /**
     * Runs one continuation. {@code anchor} = {decision, world_seeds, seat_step}; {@code earlier} = this resolution's
     * earlier logical dialogs of this seat, in order ({family, picks, known}); returns {match, picks, diff, flags,
     * replayed}.
     */
    public static Map<String, Object> run(Map<String, Object> gameStart, byte[] idSeed, Map<String, Object> anchor,
                                          List<Object> earlier, Map<String, Object> decision, int skill) {
        return run(gameStart, idSeed, anchor, earlier, decision, skill, false);
    }

    /**
     * With {@code finish}, the bot's answer at the current dialog does not end the resolution: it completes (later
     * dialogs answered by the bot), and the result carries the world's observation after it ("after"), with priority
     * to the active seat: the fixtures' resolution transition (design Section 7.1).
     */
    public static Map<String, Object> run(Map<String, Object> gameStart, byte[] idSeed, Map<String, Object> anchor,
                                          List<Object> earlier, Map<String, Object> decision, int skill, boolean finish) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (anchor == null) {
            out.put("match", false);
            out.put("diff", Collections.singletonList("no anchor"));
            return out;
        }
        Map<String, Object> anchorDecision = Json.obj(anchor, "decision");
        @SuppressWarnings("unchecked")
        Map<String, Object> aobs = (Map<String, Object>) Json.copy(Json.obj(anchorDecision, "observation"));
        Map<String, Object> current = Json.obj(decision, "observation");
        String viewer = Json.str(current, "viewer");
        String source = Json.str(Json.obj(Json.obj(decision, "context"), "source"), "object_id");
        // 1. conditioning: cards new in the viewer's hand were drawn from the top of its library
        Set<String> before = new LinkedHashSet<>();
        for (Object p : Json.arr(aobs, "players")) {
            Map<String, Object> pm = Json.obj(p);
            if (viewer.equals(Json.str(pm, "seat"))) {
                for (Object o : Json.arr(pm, "hand")) {
                    before.add(Json.str(Json.obj(o), "object_id"));
                }
            }
        }
        List<String> drawn = new ArrayList<>();
        Set<String> handNow = new HashSet<>();
        for (Object p : Json.arr(current, "players")) {
            Map<String, Object> pm = Json.obj(p);
            if (viewer.equals(Json.str(pm, "seat"))) {
                for (Object o : Json.arr(pm, "hand")) {
                    Map<String, Object> rec = Json.obj(o);
                    handNow.add(Json.str(rec, "object_id"));
                    if (!before.contains(Json.str(rec, "object_id"))) {
                        drawn.add(Json.str(rec, "card_name"));
                    }
                }
            }
        }
        List<Object> known = new ArrayList<>(Json.arr(aobs, "known"));
        for (int i = 0; i < drawn.size(); i++) {
            known.add(Json.map("owner_seat", viewer, "zone", "library", "card_name", drawn.get(i), "object_id", null,
                    "position_from_top", (long) i, "position_from_bottom", null, "how", "looked_at"));
        }
        // what the earlier dialogs and the current decision show of the viewer's library is pinned too, at the
        // anchor's positions (the drawn cards were above it then), with its ids so the world's cards answer to them
        Set<String> pinnedIds = new HashSet<>();
        List<Object> shown = new ArrayList<>();
        for (Object e : earlier) {
            shown.addAll(Json.arr(Json.obj(e), "known"));
        }
        shown.addAll(Json.arr(current, "known"));
        for (Object o : shown) {
            Map<String, Object> k = Json.obj(o);
            String oid = Json.str(k, "object_id");
            if (!"library".equals(Json.str(k, "zone")) || !viewer.equals(Json.str(k, "owner_seat"))
                    || (oid != null && (pinnedIds.contains(oid) || handNow.contains(oid)))) {
                continue;
            }
            if (oid != null) {
                pinnedIds.add(oid);
            }
            Object top = k.get("position_from_top");
            Map<String, Object> pin = new LinkedHashMap<>(k);
            if (top instanceof Number) {
                pin.put("position_from_top", ((Number) top).longValue() + drawn.size());
            }
            known.add(pin);
        }
        aobs.put("known", known);
        out.put("conditioned_drawn", new ArrayList<Object>(drawn));
        // 2. the anchor world
        byte[] seed = spellbench.kit.core.Seeds.unhex((String) Json.arr(anchor, "world_seeds").get(0));
        KitContext.reset();
        KitRandom.installBoot();
        List<String> nameFlags = WorldBuilder.restoreVisibleNames(aobs, Json.obj(anchorDecision, "x_history"));
        KitRandom random = KitRandom.install(seed, idSeed);
        Sampler.Sample sample = Sampler.sample(gameStart, aobs, random.stream("sampler"));
        WorldBuilder.Spec spec = new WorldBuilder.Spec();
        spec.gameStart = gameStart;
        spec.observation = aobs;
        spec.sample = sample;
        spec.random = random;
        spec.mode = WorldBuilder.Mode.PRIORITY;
        spec.history = Json.obj(anchorDecision, "x_history");
        final Continuer[] decider = new Continuer[1];
        spec.viewerFactory = seat -> {
            decider[0] = new Continuer(seat, skill);
            return decider[0];
        };
        spec.otherFactory = Puppet::new;
        World w = WorldBuilder.build(spec);
        w.flags.addAll(nameFlags);
        decider[0].attach(w);
        out.put("flags", new ArrayList<Object>(w.flags));
        Game game = w.game;
        UUID s = w.idToUuid.get(source);
        StackObject top = game.getStack().getFirstOrNull();
        if (s == null || top == null || !top.getId().equals(s)) {
            out.put("match", false);
            out.put("diff", Collections.singletonList("the anchor's top object is not the source"));
            return out;
        }
        if (KitContext.horizon.contains(top.getId())) {
            // design 5.1 step 4: an approximate source takes the current-dialog path
            out.put("match", false);
            out.put("diff", Collections.singletonList("the source is flagged approximate (horizon)"));
            return out;
        }
        for (String f : w.flags) {
            if (f.startsWith("unsupported:")) {
                out.put("match", false);
                out.put("diff", Collections.singletonList("unsupported state: " + f));
                return out;
            }
        }
        Continuer c = decider[0];
        c.w = w;
        c.decision = decision;
        c.current = current;
        c.flags = RoundTrip.flagsFrom(decision);
        c.result = out;
        c.earlier = new ArrayList<>(earlier);
        c.armed = true;
        c.finish = finish;
        // 3. both seats pass; S resolves (ComputerPlayer6.resolve, without the search hints)
        for (UUID pid : w.seatPlayer.values()) {
            Player p = game.getPlayer(pid);
            p.pass(game);
            Reflect.set(PlayerImpl.class, p, "passed", true);
        }
        int handBefore = w.viewerPlayer().getHand().size();
        int libraryBefore = w.viewerPlayer().getLibrary().size();
        try {
            top.resolve(game);
            if (!out.containsKey("match")) {
                out.put("match", false);
                out.put("diff", Collections.singletonList(c.index < earlier.size()
                        ? "the resolution ended before the earlier dialogs were replayed"
                        : "resolution ended without the current dialog"));
                out.put("replayed", (long) Math.min(c.index, earlier.size()));
            }
            if (finish && Boolean.TRUE.equals(out.get("match"))) {
                c.armed = false;
                out.put("after", Resolver.finishAndProject(w, top, RoundTrip.flagsFrom(decision)));
            }
        } catch (Stop stop) {
            // the current dialog was reached, or the replay failed
        }
        out.put("hand_before", (long) handBefore);
        out.put("library_before", (long) libraryBefore);
        out.remove("align");
        out.remove("projected");
        return out;
    }
}
