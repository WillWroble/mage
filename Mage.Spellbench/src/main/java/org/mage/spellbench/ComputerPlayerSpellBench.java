package org.mage.spellbench;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import mage.abilities.Ability;
import mage.abilities.ActivatedAbility;
import mage.abilities.Mode;
import mage.abilities.Modes;
import mage.abilities.PlayLandAbility;
import mage.abilities.SpellAbility;
import mage.abilities.TriggeredAbility;
import mage.abilities.costs.mana.ManaCost;
import mage.abilities.mana.ActivatedManaAbilityImpl;
import mage.abilities.mana.ManaAbility;
import mage.abilities.keyword.TrampleAbility;
import mage.constants.MultiAmountType;
import mage.constants.PhaseStep;
import mage.game.combat.CombatGroup;
import mage.game.permanent.Permanent;
import mage.util.CardUtil;
import mage.util.MultiAmountMessage;
import mage.Mana;
import mage.MageObject;
import mage.cards.Card;
import mage.cards.CardWithSpellOption;
import mage.cards.Cards;
import mage.choices.Choice;
import mage.constants.Outcome;
import mage.constants.RangeOfInfluence;
import mage.constants.Zone;
import mage.game.Game;
import mage.player.ai.ComputerPlayer;
import mage.players.ChooseCreatureToBlockAbility;
import mage.players.ChooseToAttackAbility;
import mage.players.Player;
import mage.target.Target;

import java.io.FileWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The Archon's seat in the XMage game of a lockstep match. Every decision XMage asks it is the Archon's, made
 * natively in mtg-kernel, where this player hosts the same game ({@link KernelMatch}): MageZero's seat there is
 * answered from MageZero's moves in XMage ({@link MageZeroSeat}). It keeps the contract of XMage's AI players: it
 * takes the last-priority snapshot at its priority, and its dialogs answer through {@link ComputerPlayer}'s helpers,
 * whose callers record its history, which MageZero's own simulations replay.
 * <p>
 * The kernel never runs ahead of XMage: the Archon is asked only when XMage asks this player, at a decision whose game
 * state equals XMage's. A decision only the kernel asks (an order XMage makes itself) is the Archon's too, and the
 * next comparison checks that XMage did the same. The libraries take XMage's order at each matched priority, after
 * any shuffle. When the engines disagree the game is recorded as a divergence and ended.
 */
public class ComputerPlayerSpellBench extends ComputerPlayer {

    private static final long serialVersionUID = 1L;
    /** XMage's color choice for each of the protocol's mana symbols. */
    private static final Map<String, Mana> MANA = Map.of("W", Mana.WhiteMana(1), "U", Mana.BlueMana(1),
            "B", Mana.BlackMana(1), "R", Mana.RedMana(1), "G", Mana.GreenMana(1), "C", Mana.ColorlessMana(1));
    private static final Map<String, String> MANA_COLORS = Map.of("W", "White", "U", "Blue", "B", "Black", "R", "Red",
            "G", "Green");
    /** The kernel's decisions that answer an XMage yes/no. */
    private static final Set<String> USE = Set.of("choose_boolean", "optional_cost", "optional_cast",
            "choose_cost_option", "choose_option", "select_object", "choose_target");
    /** The kernel's decisions in casting a spell that it asks before paying and XMage only on resolution. */
    private static final Set<String> LATER = Set.of("choose_color");
    /** XMage's dialogs for the Archon that stand without the kernel once its game ended naturally. */
    private static final Set<String> AFTER_END = Set.of("declare_attack", "declare_block", "choose_spell_mode");
    /** The kernel's decisions in casting a spell that XMage asks before the cost. */
    private static final Set<String> CAST = Set.of("choose_target", "choose_cast_method");
    private static final Set<String> TARGETS = Set.of("choose_target", "select_object", "choose_cost_target",
            "finish_selection");

    /** The Archon's seat in the kernel, and MageZero's. */
    private transient String seat;
    private transient String other;
    private transient KernelMatch kernel;
    /** The Archon's action is cast for its alternative cost (see cost). */
    private transient boolean alternative;
    /** The mana XMage has left to pay for the Archon, while it pays it. */
    private transient ManaCost paying;
    private transient Views views;
    private transient MageZeroSeat mageZero;
    private transient String logs;
    private transient boolean over;
    /** XMage's object for each kernel object (the Archon's view's id) the Archon acted with or named. */
    private final transient Map<String, UUID> matched = new HashMap<>();
    /** Each seat's library shuffles in XMage since the kernel's game started. */
    private final transient int[] shuffles = new int[2];
    /** How the kernel's priority for the Archon differed from XMage's at the first priority it passed since a match. */
    private transient List<String> passedAt = List.of();
    /** The current combat declaration: each of the Archon's creatures with what it attacks or blocks (null: none). */
    private transient Map<UUID, UUID> declaration;
    /** This combat's blockers, either seat's, in the order the kernel declared them: its order of combat damage. */
    private final transient List<UUID> blockers = new ArrayList<>();
    /** The Archon's action being done in XMage, whose dialogs are its cast in the kernel, and its kernel candidate. */
    private transient ActivatedAbility acting;
    private transient JsonObject actingSemantic;
    private transient JsonObject actingDecision;
    private transient Views.View actingView;
    /**
     * The Archon's arrangement in the kernel (a scry, a surveil): each card's destination, its order picks, and the
     * answers it makes to XMage's dialogs for it, in their order: the cards leaving the top, then one pick each of the
     * bottom's order (topmost first) and of the top's (deepest first, as XMage places them).
     */
    private final transient List<String[]> partition = new ArrayList<>();
    private final transient List<String> ordered = new ArrayList<>();
    private transient boolean destinationChosen;
    private final transient Deque<List<String>> arrangement = new ArrayDeque<>();
    /** The arrangement's first answer is a dig's picks, which XMage may ask first as a yes/no (an optional pick). */
    private transient boolean digPicks;
    /** The Archon's order of cards it puts on its library, and the picks it answers XMage's placing dialogs with. */
    private final transient List<String> placing = new ArrayList<>();
    private final transient Deque<String> placeOrder = new ArrayDeque<>();
    /** The cards XMage asks one at a time that the kernel selected together: the next first (see putBackOrder). */
    private final transient Deque<String> putBack = new ArrayDeque<>();
    /** The Archon's answers to decisions of its cast that XMage asks later, on resolution. */
    private final transient List<JsonObject> later = new ArrayList<>();
    /** Picks the Archon made before XMage asks for them: a selection's first pick, which answered XMage's yes/no, or
     * the targets of a cast whose cost XMage asks first. */
    private final transient Deque<Pick> picks = new ArrayDeque<>();
    private transient String gameId;
    private transient Game game;

    /** One of the Archon's picks: the kernel decision, its answer, and this seat's XMage view when it was made. */
    private static final class Pick {
        final JsonObject decision;
        final JsonObject semantic;
        final Views.View view;

        Pick(JsonObject decision, JsonObject semantic, Views.View view) {
            this.decision = decision;
            this.semantic = semantic;
            this.view = view;
        }
    }

    public ComputerPlayerSpellBench(String name, RangeOfInfluence range) {
        super(name, range);
        autoTap = true;
    }

    protected ComputerPlayerSpellBench(final ComputerPlayerSpellBench player) {
        super(player);
    }

    @Override
    public ComputerPlayerSpellBench copy() {
        return new ComputerPlayerSpellBench(this);
    }

    // ------------------------------------------------------------------ the kernel game

    /** Starts the kernel's game once both opening hands are dealt: the mulligan phase, which the board skips. */
    @Override
    public boolean chooseMulligan(Game game) {
        if (!game.isSimulation() && kernel == null) {
            guard(game, () -> {
                start(game);
                return null;
            }, null);
        }
        return false;
    }

    private void start(Game game) {
        KernelMatch.Settings settings = KernelMatch.Settings.load(System.getProperty("spellbench.lockstep"));
        logs = settings.logs;
        seat = settings.seat;
        other = seat.equals("p0") ? "p1" : "p0";
        Player mageZeroPlayer = game.getOpponent(playerId);
        ComputerPlayerPolicyOnlyLockstep policy = (ComputerPlayerPolicyOnlyLockstep) mageZeroPlayer.getRealPlayer();
        kernel = new KernelMatch(settings, seat);
        gameId = game.getId().toString();
        this.game = game;
        String starting = game.getStartingPlayerId().equals(playerId) ? seat : other;
        Player p0 = seat.equals("p0") ? this : mageZeroPlayer;
        Player p1 = p0 == this ? mageZeroPlayer : this;
        kernel.start(gameId, starting, opening(game, p0), opening(game, p1));
        Map<String, Object> flags = new Gson().fromJson(kernel.observationFlags(),
                new TypeToken<Map<String, Object>>() {}.getType());
        flags.put("known_cards", false); // knowledge is not compared, so the projection leaves it out
        views = new Views(flags, p0.getId(), p1.getId(), starting);
        policy.view = g -> views.view(g, other);
        policy.blockOrder = blockers;
        policy.shuffled = g -> shuffled(g, mageZeroPlayer);
        mageZero = new MageZeroSeat(policy.moves, views, blockers);
    }

    @Override
    public void shuffleLibrary(Ability source, Game game) {
        super.shuffleLibrary(source, game);
        shuffled(game, this);
    }

    /**
     * XMage shuffled {@code player}'s library: the shuffle meets the kernel's of the same count. XMage takes the
     * kernel's order when the kernel shuffled first (it may have drawn from it already, in the same step: Lembas
     * shuffled in, then Fanatical Offering's draw); else the kernel takes XMage's when it shuffles.
     */
    void shuffled(Game game, Player player) {
        if (kernel == null || over || game.isSimulation()) {
            return;
        }
        String owner = views.seat(player.getId());
        int count = ++shuffles[owner.equals("p0") ? 0 : 1];
        List<String> made = kernel.shuffle(owner, count, library(game, player));
        List<Card> cards = new ArrayList<>(player.getLibrary().getCards(game));
        List<Card> ordered = new ArrayList<>();
        for (String name : made == null ? List.<String>of() : made) {
            cards.stream().filter(card -> card.getName().equals(name)).findFirst().ifPresent(card -> {
                cards.remove(card);
                ordered.add(card);
            });
        }
        if (made != null && cards.isEmpty()) { // the same cards: XMage's library in the kernel's order
            player.getLibrary().clear();
            for (int i = ordered.size() - 1; i >= 0; i--) {
                player.getLibrary().putOnTop(ordered.get(i), game);
            }
        }
    }

    /** A seat's opening hand then its library, top first: the order the kernel deals from. */
    private static List<String> opening(Game game, Player player) {
        List<String> order = new ArrayList<>();
        for (Card card : player.getHand().getCards(game)) {
            order.add(card.getName());
        }
        order.addAll(library(game, player));
        return order;
    }

    private static List<String> library(Game game, Player player) {
        List<String> order = new ArrayList<>();
        for (Card card : player.getLibrary().getCards(game)) {
            order.add(card.getName());
        }
        return order;
    }

    /**
     * Steps the kernel until it asks the Archon a decision of one of {@code kinds} (null: priority), feeding MageZero's
     * moves to its seat. Null when the kernel waits on a move MageZero has not made, or, asked for priority, when the
     * kernel is past it at one of the Archon's dialogs.
     */
    private JsonObject advance(Game game, Set<String> kinds) {
        JsonObject decision = next(game, kinds);
        if (decision == null || wanted(decision, kinds)) {
            return decision;
        }
        if (kinds == null && !Lockstep.isPriority(decision)) {
            return null; // the kernel is past this priority, at a dialog of the Archon's that XMage asks later
        }
        throw new LockstepDivergence("XMage asks the Archon " + (kinds == null ? "priority" : kinds)
                + "; the kernel asks it " + Lockstep.firstKind(decision)
                + "; at the Archon's first priority since the last match it differed by " + passedAt);
    }

    /**
     * Steps the kernel until it asks the Archon a decision XMage asks too, feeding MageZero's moves to its seat; a
     * decision only the kernel asks goes to the Archon on the way, unless it is one of {@code kinds}. Null when the
     * kernel waits on a move MageZero has not made.
     */
    private JsonObject next(Game game, Set<String> kinds) {
        while (kernel.terminal() == null) {
            JsonObject decision = kernel.decision();
            boolean wanted = wanted(decision, kinds);
            if (decision.getAsJsonArray("candidates").size() == 1 && !wanted || Lockstep.takesAll(decision)) {
                if (seat.equals(kernel.actingSeat()) && "choose_number".equals(Lockstep.firstKind(decision))) {
                    later.add(Lockstep.semantic(decision, 0).deepCopy()); // a forced X, which XMage asks still
                } else if (other.equals(kernel.actingSeat())) {
                    mageZero.forced(decision);
                }
                step(0); // forced: one candidate, or all to take, whoever is asked (XMage makes it unasked)
                continue;
            }
            if (other.equals(kernel.actingSeat())) {
                Integer candidate = mageZero.answer(decision, game);
                if (candidate == null) {
                    if (views.before(Lockstep.observation(decision), views.view(game, seat).json)) {
                        throw new LockstepDivergence("XMage went past the kernel's decision for MageZero; its first pass"
                                + " since the last match differed by " + mageZero.mismatch);
                    }
                    return null;
                }
                step(candidate);
                continue;
            }
            // a decision XMage makes itself: one it never asks (an order), or a dialog it is already past (a forced
            // search XMage made without asking: it is later, or gives the Archon priority with the dialog's object
            // resolved); the Archon answers it, and the next comparison checks XMage did the same
            JsonObject kernelNow = Lockstep.observation(decision);
            JsonObject now = views.view(game, seat).json;
            boolean past = !Lockstep.isPriority(decision)
                    && (views.before(kernelNow, now) || kinds == null && views.resolved(kernelNow, now));
            if (wanted || !kernelOnly(decision) && !past) {
                return decision;
            }
            step(kernel.archonChoice());
        }
        // a natural end may come before XMage's (the damage of a combat both seats passed through): until XMage gets
        // there the Archon only passes, declares nothing and casts with no form asked, and the results are compared
        // at XMage's end
        boolean natural = "natural".equals(kernel.terminal().get("classification").getAsString());
        if (!game.getState().isGameOver() && (!natural || kinds != null && !AFTER_END.containsAll(kinds))) {
            throw new LockstepDivergence("the kernel's game ended " + kernel.terminal().get("outcome").getAsString()
                    + " (" + kernel.terminal().get("reason").getAsString() + ") while XMage's goes on"
                    + (kinds == null ? "" : " and asks the Archon " + kinds));
        }
        return null;
    }

    /**
     * Steps the kernel; at a priority decision, each of its libraries is first put in XMage's order when it holds as
     * many cards: XMage owns the randomness (a shuffle is XMage's), and stepping priority may take the kernel to a draw
     * before this player is asked again. Not inside an effect, which may be bound to cards in a library.
     */
    private void step(int candidate) {
        JsonObject decision = kernel.decision();
        if (seat.equals(kernel.actingSeat())) {
            JsonObject chosen = Lockstep.semantic(decision, candidate);
            arranged(decision, chosen);
            if ("choose_option".equals(Views.text(chosen, "kind"))) {
                later.add(chosen); // an effect's option, which XMage may ask after the kernel ran on (Winding Way)
            }
        }
        for (JsonElement p : Lockstep.isPriority(decision) ? Lockstep.observation(decision).getAsJsonArray("players")
                : new JsonArray()) {
            String each = Views.text(p.getAsJsonObject(), "seat");
            List<String> library = library(game, game.getPlayer(views.player(each)));
            if (p.getAsJsonObject().get("library_count").getAsInt() == library.size()) {
                kernel.arrangeLibrary(each, library);
            }
        }
        kernel.step(candidate);
    }

    /** Records the Archon's answers to an arrangement; once complete, the answers to XMage's dialogs for it. */
    private void arranged(JsonObject decision, JsonObject chosen) {
        String kind = Views.text(chosen, "kind");
        if ("arrange_card".equals(kind)) {
            if (chosen.get("card_index").getAsInt() == 0) { // a new arrangement: one XMage did not ask about is over
                partition.clear();
                ordered.clear();
                arrangement.clear();
                destinationChosen = false;
            }
            destinationChosen |= decision.getAsJsonArray("candidates").size() > 1;
            partition.add(new String[]{Views.text(chosen.getAsJsonObject("card"), "card_name"),
                    Views.text(chosen, "destination"), Views.text(chosen, "purpose")});
        } else if ("order_pick".equals(kind) && "arrangement".equals(Views.text(chosen, "purpose"))) {
            ordered.add(Views.text(chosen.getAsJsonObject("item").getAsJsonObject("object"), "card_name"));
        } else if ("order_pick".equals(kind) && Set.of("library_top", "library_bottom").contains(Views.text(chosen, "purpose"))) {
            placed(decision, chosen);
            return;
        } else {
            return;
        }
        JsonElement group = decision.get("group");
        boolean last = group == null || group.isJsonNull() || group.getAsJsonObject().get("substep_index").getAsInt() + 1
                >= group.getAsJsonObject().get("substep_count").getAsInt();
        String purpose = partition.get(0)[2];
        if (!last || !Set.of("scry", "surveil", "dig").contains(purpose)) {
            return;
        }
        // each destination's cards in the kernel's order: its picks, then the implied last card
        List<String[]> left = new ArrayList<>(partition);
        Map<String, List<String>> by = new HashMap<>();
        for (String name : ordered) {
            String[] card = left.stream().filter(c -> c[0].equals(name)).findFirst().orElseThrow();
            left.remove(card);
            by.computeIfAbsent(card[1], d -> new ArrayList<>()).add(name);
        }
        left.forEach(card -> by.computeIfAbsent(card[1], d -> new ArrayList<>()).add(card[0]));
        // the cards XMage asks for: those leaving the top (scry, surveil), those a dig puts into the hand
        List<String> moved = new ArrayList<>();
        partition.stream().filter(c -> purpose.equals("dig") ? c[1].equals("hand") : !c[1].equals("top"))
                .forEach(c -> moved.add(c[0]));
        if (!purpose.equals("dig") || destinationChosen) { // XMage asks a dig's picks only when they are a choice
            arrangement.add(moved);
            digPicks = purpose.equals("dig");
        }
        List<String> bottom = by.getOrDefault("bottom", List.of());
        for (int i = 0; i < bottom.size() - 1; i++) {
            arrangement.add(List.of(bottom.get(i)));
        }
        List<String> top = by.getOrDefault("top", List.of());
        for (int i = top.size() - 1; i > 0; i--) {
            arrangement.add(List.of(top.get(i)));
        }
    }

    /**
     * Records the Archon's order of cards it puts on its library (Brainstorm's two back on top); once complete, the
     * answers to XMage's dialogs for it, which place the cards one at a time: on the top deepest first ("the last one
     * chosen will be topmost"), on the bottom topmost first. The kernel's last position is implied.
     */
    private void placed(JsonObject decision, JsonObject chosen) {
        int position = chosen.get("position").getAsInt();
        int count = chosen.get("count").getAsInt();
        if (position == 0) { // a new placing: one XMage did not ask about is over
            placing.clear();
            placeOrder.clear();
        }
        placing.add(Views.text(chosen.getAsJsonObject("item").getAsJsonObject("object"), "card_name"));
        if (position + 2 < count) {
            return;
        }
        for (JsonElement c : decision.getAsJsonArray("candidates")) {
            JsonObject other = c.getAsJsonObject().getAsJsonObject("semantic");
            if (!other.equals(chosen)) { // the last card, implied
                placing.add(Views.text(other.getAsJsonObject("item").getAsJsonObject("object"), "card_name"));
            }
        }
        boolean top = "library_top".equals(Views.text(chosen, "purpose"));
        for (int i = 0; i < placing.size() - 1; i++) {
            placeOrder.add(placing.get(top ? placing.size() - 1 - i : i));
        }
    }

    /**
     * A choice among library cards that the Archon's arrangement in the kernel answers (a scry's cards to the bottom,
     * then each pick of an order), or null when it is no such choice. The kernel may reach the arrangement only now.
     */
    private Boolean arranged(Target target, Game game, Cards fromCards) {
        if (fromCards == null || fromCards.getCards(game).stream()
                .noneMatch(card -> game.getState().getZone(card.getId()) == Zone.LIBRARY)) {
            return null;
        }
        if (arrangement.isEmpty()) {
            next(game, TARGETS); // the kernel's arrangement, if it asks one, is recorded on the way
        }
        if (arrangement.isEmpty()) {
            return null;
        }
        digPicks = false;
        for (String name : arrangement.poll()) {
            fromCards.getCards(game).stream()
                    .filter(card -> card.getName().equals(name) && !target.contains(card.getId()))
                    .findFirst().ifPresent(card -> target.add(card.getId(), game));
        }
        return target.isChosen(game);
    }

    /** The Archon's decision of one of {@code kinds} (null: priority). */
    private boolean wanted(JsonObject decision, Set<String> kinds) {
        return seat.equals(kernel.actingSeat()) && (kinds == null ? Lockstep.isPriority(decision)
                : kinds.contains(Lockstep.firstKind(decision)));
    }

    /**
     * A decision XMage makes itself: the order of cards it moves together (not of triggers, which it asks), an effect's
     * option XMage has no dialog for.
     */
    private static boolean kernelOnly(JsonObject decision) {
        String kind = Lockstep.firstKind(decision);
        return kind.equals("arrange_card") || kind.equals("choose_option") || kind.equals("order_pick")
                && !Lockstep.semantic(decision, 0).getAsJsonObject("item").has("trigger");
    }

    /** The Archon's answer to the kernel's pending decision; the kernel steps with it. */
    private JsonObject archon() {
        JsonObject decision = kernel.decision();
        int candidate = kernel.archonChoice();
        step(candidate);
        return Lockstep.semantic(decision, candidate).deepCopy();
    }

    // ------------------------------------------------------------------ priority

    @Override
    public boolean priority(Game game) {
        game.setLastPriority(playerId); // as every XMage AI player: MageZero's simulations replay from here
        game.getState().setPriorityPlayerId(playerId);
        game.firePriorityEvent(playerId);
        Boolean acted = guard(game, () -> act(game), false);
        if (!Boolean.TRUE.equals(acted)) {
            pass(game);
            return false;
        }
        return true;
    }

    /** The Archon's action at this point, done in XMage; false when it passes or the kernel did not ask it here. */
    private boolean act(Game game) {
        JsonObject decision = advance(game, null);
        if (decision == null) {
            return false;
        }
        JsonObject now = views.view(game, seat).json;
        List<String> diff = views.diff(Lockstep.observation(decision), now);
        if (!diff.isEmpty()) {
            if (views.before(Lockstep.observation(decision), now)) {
                throw new LockstepDivergence("XMage went past the kernel's priority for the Archon: " + diff
                        + "; at the Archon's first priority since the last match it differed by " + passedAt);
            }
            if (passedAt.isEmpty()) {
                passedAt = diff;
            }
            return false; // the kernel asks the Archon at a later point
        }
        passedAt = List.of();
        Views.View view = views.view(game, seat);
        picks.clear(); // one left from a dialog XMage resolved itself (an optional search it auto-chose); compared
        JsonObject semantic = archon();
        String kind = Views.text(semantic, "kind");
        if (kind.equals("pass")) {
            return false;
        }
        if (kind.equals("cast_spell")) {
            // the kernel asks the spell's form next, which XMage takes as which spell is cast (an omen's or an
            // adventure's half), or asks on its own as a mode: the Archon answers it now and it is kept
            JsonObject form = next(game, Set.of("choose_spell_mode"));
            if (form != null && wanted(form, Set.of("choose_spell_mode"))) {
                later.add(archon());
            }
        }
        ActivatedAbility ability = playable(game, view, decision, semantic);
        acting = ability;
        actingSemantic = semantic;
        actingDecision = decision;
        actingView = view;
        try {
            if (!activateAbility(ability, game)) {
                throw new LockstepDivergence("XMage refused the Archon's " + semantic);
            }
            if (!picks.isEmpty()) {
                throw new LockstepDivergence("XMage asks no target for the Archon's " + picks.peek().semantic);
            }
        } finally {
            acting = null;
            alternative = false;
            picks.clear();
        }
        return true;
    }

    /**
     * The cards whose spell XMage may cast for the Archon's cast of {@code card}: a card with a spell option (an omen,
     * an adventure) casts its option's half when the Archon chose the kernel's second spell form.
     */
    private static List<UUID> halves(Game game, UUID card, JsonObject form) {
        Card xmage = game.getCard(card);
        if (!(xmage instanceof CardWithSpellOption)) {
            return List.of(card);
        }
        UUID option = ((CardWithSpellOption) xmage).getSpellCard().getId();
        if (form == null) {
            return List.of(card, option); // the kernel asked no form: only one is castable, either way
        }
        return List.of(form.get("mode_index").getAsInt() == 1 ? option : card);
    }

    /**
     * XMage's playable ability for the Archon's action. Its source may be any object that looks the same as the kernel's
     * in the observation (two plotted cards, one castable this turn): the observation cannot tell them apart.
     */
    private ActivatedAbility playable(Game game, Views.View view, JsonObject decision, JsonObject semantic) {
        String kind = Views.text(semantic, "kind");
        int index = semantic.has("ability_index") ? semantic.get("ability_index").getAsInt() : 0;
        Views.Locator at = Views.locate(Lockstep.observation(decision),
                Views.text(semantic.getAsJsonObject("source"), "object_id"));
        List<ActivatedAbility> playable = getPlayable(game, true);
        // the spell's form, if the Archon chose it: XMage takes it as which half of a card with a spell option is cast
        JsonObject form = later.stream().filter(l -> "choose_spell_mode".equals(Views.text(l, "kind")))
                .findFirst().orElse(null);
        if (form != null && at != null && game.getCard(view.objects.get(Views.find(view.json, at)))
                instanceof CardWithSpellOption) {
            later.remove(form);
        }
        String kernelId = Views.text(semantic.getAsJsonObject("source"), "object_id");
        for (String id : at == null ? List.<String>of() : candidates(view, decision, at, kernelId)) {
            List<UUID> sources = halves(game, view.objects.get(id), form);
            List<Ability> matches = new ArrayList<>();
            for (ActivatedAbility ability : playable) {
                if (ofKind(kind, ability) && sources.contains(ability.getSourceId())) {
                    matches.add(ability);
                }
            }
            // a mana ability that makes the chosen color: XMage has one per color where the kernel has one (Sea Gate)
            JsonElement color = semantic.get("mana_choice");
            if (kind.equals("activate_mana_ability") && color != null && !color.isJsonNull()) {
                Mana wantedMana = MANA.get(color.getAsString());
                for (Ability match : matches) {
                    if (((ManaAbility) match).getNetMana(game).stream().anyMatch(mana -> colors(wantedMana, mana) > 0)) {
                        matched.put(kernelId, view.objects.get(id));
                        return (ActivatedAbility) match;
                    }
                }
            }
            // an activated ability's index counts all of the object's abilities of its kind, playable or not (a
            // land's cycling from the hand); a spell's or mana ability's counts those XMage can play now
            List<Ability> indexed = matches;
            if (kind.equals("activate_ability")) {
                indexed = new ArrayList<>(CardUtil.getAbilities(game.getObject(sources.get(0)), game));
                indexed.removeIf(ability -> !ofKind(kind, ability));
            }
            if (index < indexed.size()) {
                UUID wanted = indexed.get(index).getOriginalId();
                for (Ability match : matches) {
                    if (match.getOriginalId().equals(wanted)) {
                        matched.put(kernelId, view.objects.get(id));
                        return (ActivatedAbility) match;
                    }
                }
            }
        }
        throw new LockstepDivergence("XMage has no playable counterpart for the Archon's " + semantic);
    }

    /**
     * The objects of XMage's {@code view} that look like the kernel's {@code kernelId} at {@code at}: the one it was
     * matched to first, those matched to other objects of the kernel's still there last. Objects that look the same
     * differ in what was done with them (two Quirion Rangers, one already used this turn).
     */
    private List<String> candidates(Views.View view, JsonObject decision, Views.Locator at, String kernelId) {
        JsonObject observation = Lockstep.observation(decision);
        Set<UUID> others = new HashSet<>();
        matched.forEach((id, object) -> {
            if (!id.equals(kernelId) && Views.locate(observation, id) != null) {
                others.add(object);
            }
        });
        List<String> twins = new ArrayList<>(Views.twins(view.json, at));
        twins.sort(Comparator.comparingInt(id -> view.objects.get(id).equals(matched.get(kernelId)) ? 0
                : others.contains(view.objects.get(id)) ? 2 : 1));
        return twins;
    }

    /** Whether {@code ability} is of the Archon's action {@code kind}. */
    private static boolean ofKind(String kind, Ability ability) {
        switch (kind) {
            case "play_land":
                return ability instanceof PlayLandAbility;
            case "cast_spell":
                return ability instanceof SpellAbility;
            case "activate_mana_ability":
                return ability instanceof ManaAbility;
            default:
                return ability instanceof ActivatedAbility && !(ability instanceof PlayLandAbility
                        || ability instanceof SpellAbility || ability instanceof ManaAbility);
        }
    }

    // ------------------------------------------------------------------ dialogs

    @Override
    protected boolean makeChoiceHelper(Outcome outcome, Target target, Ability source, Game game, Cards fromCards) {
        JsonElement cost = acting instanceof ManaAbility ? actingSemantic.remove("cost_target") : null;
        if (cost != null && !cost.isJsonNull()) { // the object the Archon's mana ability costs, chosen with it
            return guard(game, () -> {
                JsonObject ref = cost.getAsJsonObject();
                target.add(ref.has("player") ? views.player(Views.text(ref, "player"))
                        : xmage(game, actingView, actingDecision, ref.getAsJsonObject("object")), game);
                return true;
            }, false);
        }
        Boolean arranged = guard(game, () -> arranged(target, game, fromCards), false);
        if (arranged != null) {
            return arranged;
        }
        if (!placeOrder.isEmpty() && fromCards != null && fromCards.getCards(game).stream()
                .allMatch(card -> game.getState().getZone(card.getId()) == Zone.HAND)) {
            String name = placeOrder.poll(); // a card the Archon put on its library, placed one at a time
            fromCards.getCards(game).stream().filter(card -> card.getName().equals(name)).findFirst()
                    .ifPresent(card -> target.add(card.getId(), game));
            return target.isChosen(game);
        }
        if (source instanceof ChooseToAttackAbility || source instanceof ChooseCreatureToBlockAbility) {
            return guard(game, () -> declare(game, target, source), false);
        }
        return guard(game, () -> select(game, target, source), false);
    }

    /** A selection, pick by pick as the kernel asks it (its finish ends it early). */
    private boolean select(Game game, Target target, Ability source) {
        while (true) {
            Pick pick = picks.poll();
            if (pick == null && !putBack.isEmpty()) {
                return putBack(game, target, putBack.poll());
            }
            if (pick == null) {
                JsonObject next = next(game, TARGETS);
                if ((next == null || !wanted(next, TARGETS)) && !target.getTargets().isEmpty() && target.isChosen(game)) {
                    return true; // the kernel ended the selection without asking (nothing more to take)
                }
                JsonObject decision = advance(game, TARGETS);
                if (decision == null) {
                    throw new LockstepDivergence("XMage asks the Archon a selection (" + target.getMessage(game) + ", "
                            + target.getClass().getSimpleName() + "); the kernel waits on MageZero");
                }
                if (oneByOne(target, decision)) {
                    return putBack(game, target, putBackOrder(game, decision));
                }
                Views.View view = views.view(game, seat);
                pick = new Pick(decision, archon(), view);
            }
            if (Views.text(pick.semantic, "kind").startsWith("finish_")) {
                return target.isChosen(game);
            }
            UUID chosen = picked(game, pick.view, pick.decision, pick.semantic, target.getTargets());
            if ("sacrifice".equals(Views.text(pick.semantic, "cost_kind"))) {
                tapBeforeSacrifice(game, chosen);
            }
            if (target.isNotTarget()) {
                target.add(chosen, game);
            } else {
                target.addTarget(chosen, source, game);
            }
            JsonElement group = pick.decision.get("group");
            boolean lastSubstep = group == null || group.isJsonNull()
                    || group.getAsJsonObject().get("substep_index").getAsInt() + 1
                    >= group.getAsJsonObject().get("substep_count").getAsInt();
            boolean complete = target.isChoiceCompleted(target.getAffectedAbilityControllerId(playerId), source, game, null);
            // the kernel asks a target's picks one at a time (up to two: Blood Fountain): more may follow this one
            boolean more = pick.semantic.has("maximum")
                    && pick.semantic.get("selected_count").getAsInt() + 1 < pick.semantic.get("maximum").getAsInt();
            if (lastSubstep && !more && !"select_object".equals(Views.text(pick.semantic, "kind")) || complete) {
                return target.isChosen(game);
            }
        }
    }

    /**
     * XMage asks one card of the hand at a time where the kernel selects several (Brainstorm: XMage puts each card on
     * the library as it is chosen, so the last one chosen is topmost; the kernel selects them all, then orders them).
     */
    private static boolean oneByOne(Target target, JsonObject decision) {
        JsonObject first = Lockstep.semantic(decision, 0);
        JsonObject object = MageZeroSeat.object(first);
        return target.getMaxNumberOfTargets() == 1 && "select_object".equals(Views.text(first, "kind"))
                && first.get("minimum").getAsInt() > 1 && object != null && "hand".equals(Views.text(object, "zone"));
    }

    /**
     * The Archon's whole selection in the kernel, and its order on the library if the kernel asks one (stepped on the
     * way, as XMage asks none): XMage's picks, deepest first. The first is returned, the rest kept for XMage's next.
     */
    private String putBackOrder(Game game, JsonObject decision) {
        List<String> chosen = new ArrayList<>();
        for (JsonObject d = decision; d != null && wanted(d, TARGETS) && "select_object".equals(Lockstep.firstKind(d));
             d = next(game, TARGETS)) {
            chosen.add(Views.text(MageZeroSeat.object(archon()), "card_name"));
        }
        List<String> order = new ArrayList<>(chosen); // no order asked: the selection's
        if (placing.size() == chosen.size() && new ArrayList<>(placing).containsAll(chosen)) {
            order = new ArrayList<>(placing); // its order on the library, top first
            Collections.reverse(order);
            placeOrder.clear();
        }
        putBack.addAll(order);
        return putBack.poll();
    }

    /** The card of that name in the Archon's hand, for XMage's pick of one. */
    private boolean putBack(Game game, Target target, String name) {
        getHand().getCards(game).stream().filter(card -> card.getName().equals(name)).findFirst()
                .ifPresent(card -> target.add(card.getId(), game));
        return target.isChosen(game);
    }

    /** The Archon's permanents the kernel has tapped beyond XMage's, by name. */
    private Map<String, Integer> tappedBeyond(Game game) {
        Map<String, Integer> more = new HashMap<>();
        for (JsonElement p : Lockstep.observation(kernel.decision()).getAsJsonArray("players")) {
            if (seat.equals(Views.text(p.getAsJsonObject(), "seat"))) {
                for (JsonElement b : p.getAsJsonObject().getAsJsonArray("battlefield")) {
                    JsonObject permanent = b.getAsJsonObject().getAsJsonObject("permanent");
                    if (permanent != null && permanent.get("tapped").getAsBoolean()) {
                        more.merge(Views.text(b.getAsJsonObject(), "card_name"), 1, Integer::sum);
                    }
                }
            }
        }
        for (Permanent permanent : game.getBattlefield().getAllActivePermanents(playerId)) {
            if (permanent.isTapped()) {
                more.computeIfPresent(permanent.getName(), (name, n) -> n > 1 ? n - 1 : null);
            }
        }
        return more;
    }

    /** The Archon's permanents, by name, that the kernel no longer has (it sacrificed them already). */
    private Map<String, Integer> gone(Game game) {
        Map<String, Integer> gone = new HashMap<>();
        game.getBattlefield().getAllActivePermanents(playerId).forEach(p -> gone.merge(p.getName(), 1, Integer::sum));
        for (JsonElement p : Lockstep.observation(kernel.decision()).getAsJsonArray("players")) {
            if (seat.equals(Views.text(p.getAsJsonObject(), "seat"))) {
                for (JsonElement b : p.getAsJsonObject().getAsJsonArray("battlefield")) {
                    gone.computeIfPresent(Views.text(b.getAsJsonObject(), "card_name"), (name, n) -> n > 1 ? n - 1 : null);
                }
            }
        }
        return gone;
    }

    /**
     * The kernel pays a cast's mana before its other costs, so it may tap a permanent the cast then sacrifices; XMage
     * sacrifices first. When the permanents the kernel tapped beyond XMage's do not cover the Archon's mana cost, the
     * rest came from what it sacrifices, which XMage taps for mana first (the mana floats until XMage pays).
     */
    private void tapBeforeSacrifice(Game game, UUID sacrificed) {
        Permanent permanent = game.getPermanent(sacrificed);
        if (acting == null || alternative || permanent == null || permanent.isTapped()) {
            return; // an alternative cost pays no mana (Fireblast's sacrifice is all of it)
        }
        int needed = acting.getManaCostsToPay().getMana().count() - getManaPool().getMana().count();
        int tapped = tappedBeyond(game).values().stream().mapToInt(Integer::intValue).sum();
        if (needed <= tapped) {
            return;
        }
        // the mana of a color the cost needs first (Drossforge Bridge's {B} for Fanatical Offering, not its {R})
        Mana cost = acting.getManaCostsToPay().getMana();
        List<ActivatedManaAbilityImpl> abilities = new ArrayList<>(
                permanent.getAbilities().getActivatedManaAbilities(Zone.BATTLEFIELD));
        abilities.sort(Comparator.comparingInt(ability -> ability.getNetMana(game).stream()
                .anyMatch(mana -> colors(cost, mana) > 0) ? 0 : 1));
        for (ActivatedManaAbilityImpl ability : abilities) {
            if (ability.canActivate(playerId, game).canActivate() && activateAbility(ability, game)) {
                return;
            }
        }
    }

    /** The XMage object (or player) a selection candidate names. */
    private UUID picked(Game game, Views.View view, JsonObject decision, JsonObject semantic, List<UUID> taken) {
        String player = MageZeroSeat.player(semantic);
        if (player != null) {
            return views.player(player);
        }
        return xmage(game, view, decision, MageZeroSeat.object(semantic), taken);
    }

    /**
     * The Archon's mana payment is the kernel's (engine autopay, no decision): while XMage pays for the Archon (its
     * action, or a cost on resolution: Nihil Spellbomb's {B}), its producers are the permanents the kernel tapped and
     * XMage has not, by name (permanents of a name are alike).
     * XMage's own payment picks the mana from them; with none, it pays as it would, and the next comparison checks it.
     */
    @Override
    public boolean playMana(Ability ability, ManaCost unpaid, String promptText, Game game) {
        if (acting != null && !over && kernel.terminal() == null) {
            guard(game, () -> {
                // the kernel pays at the end of the cast: its decisions XMage asks only later (a color on resolution)
                // are the Archon's now, and kept for XMage's dialogs
                for (JsonObject d = next(game, LATER); d != null && wanted(d, LATER); d = next(game, LATER)) {
                    later.add(archon());
                }
                return null;
            }, null);
        }
        paying = !over && kernel.terminal() == null ? unpaid : null;
        try {
            return super.playMana(ability, unpaid, promptText, game);
        } finally {
            paying = null;
        }
    }

    /**
     * While paying for the Archon, the mana of the permanents the kernel tapped: each colored (or {C}) symbol first,
     * from the permanent that makes the fewest of the colors still needed, then the generic rest. XMage's AI pays the
     * generic first from what it sorts first (a Guildgate the color needs), and is left only the kernel's colorless
     * source for the color. With none of these fitting (a filter's mana), XMage pays as it would.
     */
    @Override
    protected boolean playManaHandling(Ability ability, ManaCost unpaid, Game game) {
        ActivatedManaAbilityImpl kernels = paying == null ? null : kernelMana(game, unpaid.getMana());
        return kernels != null ? activateAbility(kernels, game) : super.playManaHandling(ability, unpaid, game);
    }

    private ActivatedManaAbilityImpl kernelMana(Game game, Mana need) {
        Map<String, Integer> beyond = tappedBeyond(game);
        gone(game).forEach((name, n) -> beyond.merge(name, n, Integer::sum)); // tapped, then sacrificed by the cast
        boolean specific = colors(need, need) > 0;
        ActivatedManaAbilityImpl best = null;
        int scarcest = Integer.MAX_VALUE;
        for (Permanent permanent : game.getBattlefield().getAllActivePermanents(playerId)) {
            if (!beyond.containsKey(permanent.getName())) {
                continue;
            }
            List<ActivatedManaAbilityImpl> free = new ArrayList<>();
            Mana makes = new Mana();
            for (ActivatedManaAbilityImpl ability : permanent.getAbilities()
                    .getAvailableActivatedManaAbilities(Zone.BATTLEFIELD, playerId, game)) {
                if (ability.getManaCosts().isEmpty()) {
                    free.add(ability);
                    ability.getNetMana(game).forEach(makes::add);
                }
            }
            int options = colors(need, makes);
            for (ActivatedManaAbilityImpl ability : free) {
                boolean fits = ability.getNetMana(game).stream()
                        .anyMatch(mana -> specific ? colors(need, mana) > 0 : mana.count() > 0);
                if (fits && options < scarcest) {
                    best = ability;
                    scarcest = options;
                }
            }
        }
        return best;
    }

    /** How many of the colors (and {C}) {@code need} asks for {@code mana} has. */
    private static int colors(Mana need, Mana mana) {
        int n = 0;
        n += need.getWhite() > 0 && mana.getWhite() > 0 ? 1 : 0;
        n += need.getBlue() > 0 && mana.getBlue() > 0 ? 1 : 0;
        n += need.getBlack() > 0 && mana.getBlack() > 0 ? 1 : 0;
        n += need.getRed() > 0 && mana.getRed() > 0 ? 1 : 0;
        n += need.getGreen() > 0 && mana.getGreen() > 0 ? 1 : 0;
        n += need.getColorless() > 0 && mana.getColorless() > 0 ? 1 : 0;
        return n;
    }

    /**
     * While paying for the Archon, a mana ability that costs mana itself (Heap Gate's filter) only when the kernel
     * filtered too: when it tapped more permanents than the mana left to pay. XMage's payer may pick it otherwise.
     */
    @Override
    public boolean activateAbility(ActivatedAbility ability, Game game) {
        if (paying != null && ability instanceof ManaAbility && !ability.getManaCosts().isEmpty()
                && tappedBeyond(game).values().stream().mapToInt(Integer::intValue).sum() <= paying.getMana().count()) {
            return false;
        }
        return super.activateAbility(ability, game);
    }

    @Override
    public List<MageObject> getAvailableManaProducers(Game game) {
        return paying != null ? kernelTapped(game, super.getAvailableManaProducers(game)) : super.getAvailableManaProducers(game);
    }

    @Override
    public List<Permanent> getAvailableManaProducersWithCost(Game game) {
        List<Permanent> producers = super.getAvailableManaProducersWithCost(game);
        return paying != null ? kernelTapped(game, producers) : producers;
    }

    /** Of {@code producers}, those the kernel tapped beyond XMage, by name; all of them when the kernel tapped none. */
    private <T extends MageObject> List<T> kernelTapped(Game game, List<T> producers) {
        Map<String, Integer> more = tappedBeyond(game);
        List<T> chosen = new ArrayList<>();
        for (T producer : producers) {
            if (more.containsKey(producer.getName())) {
                chosen.add(producer);
                more.computeIfPresent(producer.getName(), (name, n) -> n > 1 ? n - 1 : null);
            }
        }
        return chosen.isEmpty() ? producers : chosen;
    }

    /** The trigger the Archon puts on the stack next, from its order in the kernel (position 0 goes there first). */
    @Override
    public TriggeredAbility chooseTriggeredAbility(List<TriggeredAbility> abilities, Game game) {
        return guard(game, () -> {
            JsonObject decision = advance(game, Set.of("order_pick"));
            while (decision != null && !Lockstep.semantic(decision, 0).getAsJsonObject("item").has("trigger")) {
                step(kernel.archonChoice()); // an order of cards before it (a discard's), which XMage makes itself
                decision = advance(game, Set.of("order_pick"));
            }
            if (decision == null) {
                throw new LockstepDivergence("XMage asks the Archon an order of triggers; the kernel does not");
            }
            Views.View view = views.view(game, seat);
            JsonObject semantic = archon();
            JsonElement source = semantic.getAsJsonObject("item").getAsJsonObject("trigger").get("source");
            UUID id = source.isJsonObject() ? xmage(game, view, decision, source.getAsJsonObject()) : null;
            for (TriggeredAbility ability : abilities) {
                if (ability.getSourceId().equals(id)) {
                    return ability;
                }
            }
            throw new LockstepDivergence("XMage has no trigger for the Archon's " + semantic);
        }, abilities.get(0));
    }

    /** One creature of a combat declaration; the first one asked takes the Archon's whole declaration. */
    private boolean declare(Game game, Target target, Ability source) {
        if (declaration == null) {
            collect(game, source instanceof ChooseToAttackAbility);
        }
        UUID into = declaration.get(source.getSourceId());
        if (into == null) {
            return false;
        }
        target.add(into, game); // a declaration targets nothing, though XMage asks it as a target
        return true;
    }

    /** The Archon's whole declaration in the kernel: each creature with what it attacks or blocks (null: none). */
    private void collect(Game game, boolean attack) {
        declaration = new HashMap<>();
        String kind = attack ? "declare_attack" : "declare_block";
        if (!attack) {
            blockers.clear();
        }
        do {
            JsonObject decision = next(game, Set.of(kind));
            if (decision == null || !wanted(decision, Set.of(kind))) {
                // the kernel went past this declaration without asking: no creature of the Archon's can attack or
                // block there, so it declares none (XMage's one-at-a-time loop offers every attacker regardless)
                JsonObject now = views.view(game, seat).json;
                if (kernel.terminal() != null || !views.before(Lockstep.observation(kernel.decision()), now)) {
                    break;
                }
                throw new LockstepDivergence("XMage asks the Archon to " + kind + "; the kernel waits on MageZero"
                        + " at an earlier point");
            }
            Views.View view = views.view(game, seat);
            JsonObject semantic = archon();
            UUID creature = xmage(game, view, decision, semantic.getAsJsonObject(attack ? "attacker" : "blocker"));
            JsonElement into = semantic.get(attack ? "defender" : "attacker");
            if (!attack && !into.isJsonNull()) {
                blockers.add(creature);
            }
            declaration.put(creature, into.isJsonNull() ? null
                    : attack ? views.player(Views.text(into.getAsJsonObject(), "player"))
                    : xmage(game, view, decision, into.getAsJsonObject()));
        } while (kernel.terminal() == null && seat.equals(kernel.actingSeat())
                && kind.equals(Lockstep.firstKind(kernel.decision())) && Lockstep.substep(kernel.decision()) > 0);
    }

    /**
     * The Archon's declaration is taken from the kernel whole, before XMage asks it creature by creature (XMage may
     * offer no creature the kernel declares, and then asks nothing); each creature it declares must then be declared.
     */
    private void declareAll(Game game, boolean attack, Runnable xmage) {
        guard(game, () -> {
            collect(game, attack);
            return null;
        }, null);
        xmage.run();
        guard(game, () -> {
            for (Map.Entry<UUID, UUID> e : declaration.entrySet()) {
                if (!attack && e.getValue() != null && !game.getCombat().getBlockers().contains(e.getKey())) {
                    // XMage's AI offers a block only as a target: never a hexproof attacker (Saiba Cryptomancer),
                    // which a block does not target; declared as XMage's loop declares its choices
                    declareBlocker(playerId, e.getKey(), e.getValue(), game);
                }
                boolean declared = attack ? game.getCombat().getAttackers().contains(e.getKey())
                        : game.getCombat().getBlockers().contains(e.getKey());
                if (e.getValue() != null && !declared) {
                    throw new LockstepDivergence("XMage did not declare the Archon's " + (attack ? "attacker " : "blocker ")
                            + game.getPermanent(e.getKey()));
                }
            }
            return null;
        }, null);
    }

    @Override
    public void selectAttackers(Game game, UUID attackingPlayerId) {
        declareAll(game, true, () -> selectAttackersOneAtATime(game, attackingPlayerId));
    }

    @Override
    public void selectBlockers(Ability source, Game game, UUID defendingPlayerId) {
        declareAll(game, false, () -> selectBlockersOneAtATime(source, game, defendingPlayerId));
    }

    @Override
    public boolean chooseUse(Outcome outcome, String message, String secondMessage, String trueText, String falseText,
                             Ability source, Game game) {
        boolean use = guard(game, () -> use(game, trueText), false);
        getPlayerHistory().useSequence.add(use); // as ComputerPlayer records a yes/no
        return use;
    }

    /**
     * A yes/no: the Archon's boolean, optional cost or optional cast; or its option of an optional effect; or its choice of costs where XMage asks one as yes for the first; or, where the
     * kernel asks one selection whose finish is the no (an optional search), whether the Archon picks, and that pick
     * then answers XMage's selection.
     */
    private boolean use(Game game, String trueText) {
        for (JsonObject option : later) {
            if ("choose_option".equals(Views.text(option, "kind"))) {
                later.remove(option);
                return answerUse(option, trueText);
            }
        }
        JsonObject decision = next(game, USE);
        if (digPicks) { // XMage asks first whether the Archon takes any of a dig's cards (Lead the Stampede)
            digPicks = false;
            if (arrangement.peek().isEmpty()) {
                arrangement.poll(); // none: XMage asks no picks
                return false;
            }
            return true;
        }
        if (decision == null || !wanted(decision, USE)) {
            // the kernel has passed this point (it waits on MageZero, or asks the Archon something later): it offers an
            // option only when it can be taken (a payment the Archon can make), and did not offer this one
            if (decision != null && views.before(Lockstep.observation(decision), views.view(game, seat).json)) {
                throw new LockstepDivergence("XMage asks the Archon yes/no; the kernel asks it "
                        + Lockstep.firstKind(decision) + " before");
            }
            return false;
        }
        Views.View view = views.view(game, seat);
        JsonObject semantic = archon();
        String kind = Views.text(semantic, "kind");
        Boolean bool = MageZeroSeat.bool(semantic);
        if (bool != null) {
            return bool;
        }
        if (kind.equals("choose_option")) {
            later.remove(semantic); // kept as it was stepped, and answered here
            return answerUse(semantic, trueText);
        }
        if (kind.equals("choose_cost_option")) { // XMage asks a choice of two costs as yes: the first, no: the second
            return Lockstep.names(trueText, Views.text(semantic, "choice"));
        }
        if (kind.startsWith("finish_")) {
            return false;
        }
        picks.add(new Pick(decision, semantic, view));
        return true;
    }

    /** XMage's yes/no as the Archon's effect option answers it. */
    private static boolean answerUse(JsonObject option, String trueText) {
        Boolean may = Lockstep.may(option, trueText);
        if (may == null) {
            throw new LockstepDivergence("XMage asks the Archon yes/no; the kernel asks it " + option);
        }
        return may;
    }

    @Override
    public Mode chooseModeHelper(Modes modes, Ability source, Game game) {
        return guard(game, () -> {
            JsonObject semantic = answer(game, "choose_spell_mode", "a mode");
            return new ArrayList<>(modes.values()).get(semantic.get("mode_index").getAsInt());
        }, null);
    }

    /** The Archon's X, as the kernel asks it (XMage's AI caps it by the mana in its pool, made only after). */
    @Override
    public int announceX(int min, int max, String message, Game game, Ability source, boolean isManaPay) {
        return makeChoiceAmount(min, max, game, source, isManaPay);
    }

    @Override
    protected int makeChoiceAmountHelper(int min, int max, Game game, Ability source, boolean isManaPay) {
        return guard(game, () -> answer(game, "choose_number", "a number").get("value").getAsInt(), min);
    }

    @Override
    public boolean chooseHelper(Outcome outcome, Choice choice, Game game) {
        if (paying != null) {
            return super.chooseHelper(outcome, choice, game); // the kernel paid without a decision: XMage pays as it would
        }
        JsonElement mana = acting instanceof ManaAbility ? actingSemantic.get("mana_choice") : null;
        if (mana != null && !mana.isJsonNull()) { // the color the Archon's mana ability makes, chosen with it
            choice.setChoice(MANA_COLORS.get(mana.getAsString()));
            return true;
        }
        if (acting instanceof SpellAbility && choice.isKeyChoice()) {
            return guard(game, () -> {
                choice.setChoiceByKey(cost(game, choice));
                return true;
            }, false);
        }
        return guard(game, () -> {
            String color = Views.text(answer(game, "choose_color", "\"" + choice.getMessage() + "\" " + choice.getChoices()), "color");
            choice.setChoice(color.substring(0, 1).toUpperCase(Locale.ROOT) + color.substring(1));
            return true;
        }, false);
    }

    /**
     * XMage's key for the cost the Archon casts with. XMage asks it first, keying a spell's alternative costs from 1,
     * then the cast without one; the kernel asks the Archon's targets first, then how it pays, and only when it does not
     * choose itself. So the kernel's cast is run through here, its targets kept for XMage's target dialogs; when the
     * kernel does not ask, its payment tells: the alternative cost when it tapped nothing (Land Grant's revealed hand,
     * Fireblast's sacrifice), else the mana cost.
     */
    private String cost(Game game, Choice choice) {
        if (choice.getKeyChoices().size() != 2) {
            throw new LockstepDivergence("XMage offers the Archon's cast the costs " + choice.getKeyChoices().values());
        }
        while (true) {
            JsonObject decision = next(game, CAST);
            String kind = decision == null ? null : Lockstep.firstKind(decision);
            if ("choose_target".equals(kind)) {
                Views.View view = views.view(game, seat);
                picks.add(new Pick(decision, archon(), view));
                continue;
            }
            if ("choose_cast_method".equals(kind)) {
                alternative = "alternative".equals(Views.text(archon(), "method"));
                return alternative ? "1" : "2";
            }
            alternative = tappedBeyond(game).isEmpty();
            return alternative ? "1" : "2";
        }
    }

    /** Combat damage among blockers, which the kernel assigns itself: see {@link #engineOrder}. */
    @Override
    public List<Integer> getMultiAmountWithIndividualConstraints(Outcome outcome, List<MultiAmountMessage> messages,
                                                                 int totalMin, int totalMax, MultiAmountType type,
                                                                 Game game) {
        return guard(game, () -> {
            List<Integer> amounts = engineOrder(game, playerId, blockers, messages, totalMax);
            if (amounts == null) {
                throw new LockstepDivergence("XMage asks the Archon a division the kernel does not: " + type);
            }
            return amounts;
        }, MultiAmountType.prepareDefaultValues(messages, totalMin, totalMax));
    }

    /**
     * The only division XMage asks a player of the lockstep game: the combat damage of its attacker among blockers,
     * which the kernel assigns itself (spec 7.6, {@code engine_order}): lethal damage to each blocker in the order the
     * blocks were declared there, the rest to the last, or with trample to the defender. In XMage's order of the
     * blockers, which its {@code messages} name one each; null when XMage's division is not that.
     */
    static List<Integer> engineOrder(Game game, UUID attacking, List<UUID> blockOrder, List<MultiAmountMessage> messages,
                                     int damage) {
        CombatGroup group = game.getCombat().getGroups().stream()
                .filter(g -> !g.getAttackers().isEmpty() && attacking.equals(game.getControllerId(g.getAttackers().get(0)))
                        && names(game, g.getBlockers(), messages))
                .findFirst().orElse(null);
        if (game.getTurnStepType() != PhaseStep.COMBAT_DAMAGE || group == null
                || !blockOrder.containsAll(group.getBlockers())) {
            return null;
        }
        List<UUID> blockers = group.getBlockers();
        Permanent attacker = game.getPermanent(group.getAttackers().get(0));
        boolean trample = attacker.getAbilities().containsKey(TrampleAbility.getInstance().getId());
        List<UUID> order = new ArrayList<>(blockOrder);
        order.retainAll(blockers);
        Map<UUID, Integer> assigned = new HashMap<>();
        int remaining = damage;
        for (UUID blocker : order) {
            int lethal = game.getPermanent(blocker).getLethalDamage(attacker.getId(), game);
            int amount = trample || !blocker.equals(order.get(order.size() - 1)) ? Math.min(remaining, lethal) : remaining;
            assigned.put(blocker, amount);
            remaining -= amount;
        }
        List<Integer> amounts = new ArrayList<>();
        blockers.forEach(blocker -> amounts.add(assigned.get(blocker)));
        return amounts;
    }

    /** Whether XMage's division {@code messages} are for {@code blockers}: each message starts with its log name. */
    private static boolean names(Game game, List<UUID> blockers, List<MultiAmountMessage> messages) {
        if (blockers.size() != messages.size()) {
            return false;
        }
        for (int i = 0; i < blockers.size(); i++) {
            Permanent blocker = game.getPermanent(blockers.get(i));
            if (blocker == null || !messages.get(i).message.startsWith(blocker.getLogName() + ",")) {
                return false;
            }
        }
        return true;
    }

    /** The Archon's answer to the kernel's next decision of {@code kind}, for XMage's {@code dialog}. */
    private JsonObject answer(Game game, String kind, String dialog) {
        for (JsonObject semantic : later) {
            if (kind.equals(Views.text(semantic, "kind"))) {
                later.remove(semantic);
                return semantic;
            }
        }
        if (kernel.terminal() == null && seat.equals(kernel.actingSeat()) && !kind.equals(Lockstep.firstKind(kernel.decision()))
                && !Lockstep.isPriority(kernel.decision()) && kernel.decision().getAsJsonArray("candidates").size() > 1
                && !kernelOnly(kernel.decision())) {
            throw new LockstepDivergence("XMage asks the Archon " + dialog + "; the kernel asks it "
                    + Lockstep.firstKind(kernel.decision()));
        }
        if (advance(game, Set.of(kind)) == null) {
            throw new LockstepDivergence("XMage asks the Archon " + kind + "; the kernel waits on MageZero");
        }
        return archon();
    }

    // ------------------------------------------------------------------ objects across engines

    /** The XMage object for a kernel object reference in {@code decision}, as this seat's view shows it. */
    private UUID xmage(Game game, Views.View view, JsonObject decision, JsonObject reference) {
        return xmage(game, view, decision, reference, List.of());
    }

    /** The same, where a card known by name only (in a library or a hand) is one not already {@code taken}. */
    private UUID xmage(Game game, Views.View view, JsonObject decision, JsonObject reference, List<UUID> taken) {
        Views.Locator at = Views.locate(Lockstep.observation(decision), Views.text(reference, "object_id"));
        String zone = Views.text(reference, "zone");
        if ("library".equals(zone) || "hand".equals(zone)) {
            // a card in a library, or in a hand (unordered: cards of a name are alike there, and XMage may have moved
            // an earlier pick of the same choice already, as Brainstorm's put-backs; one the Archon cannot see is
            // revealed to it, as Mesmeric Fiend's choice), is any card of that name there
            Player owner = game.getPlayer(views.player(Views.text(reference, "owner_seat")));
            for (Card card : "library".equals(zone) ? owner.getLibrary().getCards(game) : owner.getHand().getCards(game)) {
                if (card.getName().equals(Views.text(reference, "card_name")) && !taken.contains(card.getId())) {
                    return card.getId();
                }
            }
        }
        String kernelId = Views.text(reference, "object_id");
        List<String> ids = at == null ? List.of() : candidates(view, decision, at, kernelId);
        UUID object = ids.isEmpty() ? null : view.objects.get(ids.get(0));
        if (object == null) {
            throw new LockstepDivergence("XMage shows no " + (at == null ? reference : at));
        }
        matched.put(kernelId, object);
        return object;
    }

    // ------------------------------------------------------------------ the end of the game

    /** Runs a step of the lockstep game; a divergence is recorded, the game ended and {@code otherwise} returned. */
    private <T> T guard(Game game, Supplier<T> step, T otherwise) {
        if (over || game.isSimulation()) {
            return otherwise;
        }
        try {
            return step.get();
        } catch (LockstepDivergence e) {
            if (!over) { // a divergence inside another step surfaces again in the outer one: record it once
                dump(game);
                finish(e.getMessage());
                game.end();
            }
            return otherwise;
        }
    }

    /** XMage's game is over: the kernel's must be too, with the same result. */
    @Override
    public void abort() {
        super.abort();
        if (kernel != null && !over) {
            finish(ending());
        }
    }

    /** How the kernel's end differs from XMage's, once MageZero's last moves are given to it; null when it agrees. */
    private String ending() {
        try {
            advance(game, null);
        } catch (LockstepDivergence e) {
            return e.getMessage();
        }
        JsonObject terminal = kernel.terminal();
        String winner = winner();
        if (terminal == null) {
            return "XMage's game is over (winner " + winner + "); the kernel asks " + kernel.actingSeat() + " "
                    + Lockstep.firstKind(kernel.decision());
        }
        if (!"natural".equals(terminal.get("classification").getAsString())) {
            return "the kernel's game ended " + terminal.get("outcome").getAsString() + ": " + terminal.get("reason");
        }
        String kernelWinner = terminal.get("winner").isJsonNull() ? null : terminal.get("winner").getAsString();
        return Objects.equals(winner, kernelWinner) ? null
                : "XMage's winner is " + winner + ", the kernel's " + kernelWinner;
    }

    /** The seat of XMage's winner, null for none: XMage ends the game once a player lost, before naming the winner. */
    private String winner() {
        String winner = null;
        for (Player player : game.getState().getPlayers().values()) {
            if (!player.hasLost()) {
                if (winner != null) {
                    return null;
                }
                winner = views.seat(player.getId());
            }
        }
        return winner;
    }

    /** For a divergence: the kernel's pending decision and both seats' XMage views, side by side. */
    private void dump(Game game) {
        JsonObject dump = new JsonObject();
        dump.add("kernel", kernel.terminal() == null ? kernel.decision() : kernel.terminal());
        dump.add("xmage_" + other, views.view(game, other).json);
        dump.add("xmage_" + seat, views.view(game, seat).json);
        try (FileWriter out = new FileWriter(logs + "/" + gameId + "-divergence.json")) {
            out.write(dump.toString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void finish(String divergence) {
        over = true;
        JsonObject record = new JsonObject();
        record.addProperty("game_id", gameId);
        record.addProperty("archon_seat", seat);
        record.addProperty("divergence", divergence);
        record.add("kernel", kernel == null ? null : kernel.terminal());
        record.addProperty("turn", game == null ? null : game.getTurnNum());
        record.addProperty("xmage_winner", game == null ? null : winner());
        if (kernel != null) {
            kernel.close();
        }
        try (FileWriter out = new FileWriter(logs + "/lockstep-games.jsonl", true)) {
            out.write(record + "\n");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
