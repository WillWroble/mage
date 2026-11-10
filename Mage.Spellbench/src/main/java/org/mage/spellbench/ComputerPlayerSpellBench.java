package org.mage.spellbench;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import mage.abilities.Ability;
import mage.abilities.ActivatedAbility;
import mage.abilities.Mode;
import mage.abilities.Modes;
import mage.abilities.PlayLandAbility;
import mage.abilities.SpellAbility;
import mage.abilities.mana.ManaAbility;
import mage.cards.Card;
import mage.cards.Cards;
import mage.choices.Choice;
import mage.constants.Outcome;
import mage.constants.RangeOfInfluence;
import mage.game.Game;
import mage.player.ai.ComputerPlayer;
import mage.players.ChooseCreatureToBlockAbility;
import mage.players.ChooseToAttackAbility;
import mage.players.Player;
import mage.target.Target;

import java.io.FileWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
    /** The Archon's seat in the kernel; MageZero's is p0. */
    private static final String SEAT = "p1";
    private static final String OTHER = "p0";
    private static final Set<String> TARGETS = Set.of("choose_target", "select_object", "choose_cost_target",
            "finish_selection");

    private transient KernelMatch kernel;
    private transient Views views;
    private transient MageZeroSeat mageZero;
    private transient String logs;
    private transient boolean over;
    /** The current combat declaration: each of the Archon's creatures with what it attacks or blocks (null: none). */
    private transient Map<UUID, UUID> declaration;
    /** A selection's first pick, which the Archon made to answer XMage's yes/no before the selection. */
    private transient Pick pendingPick;
    private transient String gameId;

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
        Player other = game.getOpponent(playerId);
        ComputerPlayerPolicyOnlyLockstep policy = (ComputerPlayerPolicyOnlyLockstep) other.getRealPlayer();
        kernel = new KernelMatch(settings, SEAT);
        gameId = game.getId().toString();
        kernel.start(gameId, game.getStartingPlayerId().equals(playerId) ? SEAT : OTHER,
                opening(game, other), opening(game, this));
        Map<String, Object> flags = new Gson().fromJson(kernel.observationFlags(),
                new TypeToken<Map<String, Object>>() {}.getType());
        flags.put("known_cards", false); // knowledge is not compared, so the projection leaves it out
        views = new Views(flags, other.getId(), playerId);
        policy.view = g -> views.view(g, OTHER);
        mageZero = new MageZeroSeat(policy.moves, views);
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
     * moves to its seat. A decision only the kernel asks goes to the Archon on the way. Null when the kernel waits on
     * a move MageZero has not made.
     */
    private JsonObject advance(Game game, Set<String> kinds) {
        while (kernel.terminal() == null) {
            JsonObject decision = kernel.decision();
            if (OTHER.equals(kernel.actingSeat())) {
                Integer candidate = mageZero.answer(decision, game);
                if (candidate == null) {
                    return null;
                }
                kernel.step(candidate);
                continue;
            }
            boolean wanted = kinds == null ? Lockstep.isPriority(decision)
                    : kinds.contains(Lockstep.firstKind(decision));
            if (wanted) {
                return decision;
            }
            if (Lockstep.isPriority(decision) || !kernelOnly(decision)) {
                throw new LockstepDivergence("XMage asks the Archon " + (kinds == null ? "priority" : kinds)
                        + "; the kernel asks it " + Lockstep.firstKind(decision));
            }
            kernel.step(kernel.archonChoice());
        }
        return null;
    }

    /** A decision XMage makes itself: the order of cards it moves together, an effect's option XMage has no dialog for. */
    private static boolean kernelOnly(JsonObject decision) {
        return Set.of("order_pick", "arrange_card", "choose_option").contains(Lockstep.firstKind(decision));
    }

    /** The Archon's answer to the kernel's pending decision; the kernel steps with it. */
    private JsonObject archon() {
        JsonObject decision = kernel.decision();
        int candidate = kernel.archonChoice();
        kernel.step(candidate);
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
        List<String> diff = Views.diff(Lockstep.observation(decision), views.view(game, SEAT).json);
        if (!diff.isEmpty()) {
            if (later(game, Lockstep.observation(decision))) {
                throw new LockstepDivergence("XMage went past the kernel's priority for the Archon: " + diff);
            }
            return false; // the kernel asks the Archon at a later point
        }
        for (String seat : new String[]{OTHER, SEAT}) {
            kernel.arrangeLibrary(seat, library(game, game.getPlayer(views.player(seat))));
        }
        Views.View view = views.view(game, SEAT);
        JsonObject semantic = archon();
        String kind = Views.text(semantic, "kind");
        if (kind.equals("pass")) {
            return false;
        }
        ActivatedAbility ability = playable(game, view, decision, semantic);
        if (!activateAbility(ability, game)) {
            throw new LockstepDivergence("XMage refused the Archon's " + semantic);
        }
        return true;
    }

    /** XMage's game is in a later turn or step than {@code observation}. */
    private static boolean later(Game game, JsonObject observation) {
        int turn = observation.get("turn").getAsInt();
        return game.getTurnNum() > turn;
    }

    /** XMage's playable ability for the Archon's action. */
    private ActivatedAbility playable(Game game, Views.View view, JsonObject decision, JsonObject semantic) {
        String kind = Views.text(semantic, "kind");
        UUID source = xmage(game, view, decision, semantic.getAsJsonObject("source"));
        List<ActivatedAbility> matches = new ArrayList<>();
        for (ActivatedAbility ability : getPlayable(game, true)) {
            boolean ofKind = kind.equals("play_land") ? ability instanceof PlayLandAbility
                    : kind.equals("cast_spell") ? ability instanceof SpellAbility
                    : kind.equals("activate_mana_ability") ? ability instanceof ManaAbility
                    : !(ability instanceof PlayLandAbility || ability instanceof SpellAbility || ability instanceof ManaAbility);
            if (ofKind && ability.getSourceId().equals(source)) {
                matches.add(ability);
            }
        }
        int index = semantic.has("ability_index") ? semantic.get("ability_index").getAsInt() : 0;
        if (index >= matches.size()) {
            throw new LockstepDivergence("XMage has no playable counterpart for the Archon's " + semantic);
        }
        return matches.get(index);
    }

    // ------------------------------------------------------------------ dialogs

    @Override
    protected boolean makeChoiceHelper(Outcome outcome, Target target, Ability source, Game game, Cards fromCards) {
        if (source instanceof ChooseToAttackAbility || source instanceof ChooseCreatureToBlockAbility) {
            return guard(game, () -> declare(game, target, source), false);
        }
        return guard(game, () -> select(game, target, source), false);
    }

    /** A selection, pick by pick as the kernel asks it (its finish ends it early). */
    private boolean select(Game game, Target target, Ability source) {
        while (true) {
            Pick pick = pendingPick;
            pendingPick = null;
            if (pick == null) {
                JsonObject decision = advance(game, TARGETS);
                if (decision == null) {
                    throw new LockstepDivergence("XMage asks the Archon a selection; the kernel waits on MageZero");
                }
                Views.View view = views.view(game, SEAT);
                pick = new Pick(decision, archon(), view);
            }
            if (Views.text(pick.semantic, "kind").startsWith("finish_")) {
                return target.isChosen(game);
            }
            UUID chosen = picked(game, pick.view, pick.decision, pick.semantic);
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
            if (lastSubstep && !"select_object".equals(Views.text(pick.semantic, "kind")) || complete) {
                return target.isChosen(game);
            }
        }
    }

    /** The XMage object (or player) a selection candidate names. */
    private UUID picked(Game game, Views.View view, JsonObject decision, JsonObject semantic) {
        String player = MageZeroSeat.player(semantic);
        if (player != null) {
            return views.player(player);
        }
        return xmage(game, view, decision, MageZeroSeat.object(semantic));
    }

    /** One creature of a combat declaration; the first one asked takes the Archon's whole declaration. */
    private boolean declare(Game game, Target target, Ability source) {
        if (declaration == null) {
            declaration = new HashMap<>();
            boolean attack = source instanceof ChooseToAttackAbility;
            String kind = attack ? "declare_attack" : "declare_block";
            do {
                JsonObject decision = advance(game, Set.of(kind));
                if (decision == null) {
                    throw new LockstepDivergence("XMage asks the Archon to " + kind + "; the kernel waits on MageZero");
                }
                Views.View view = views.view(game, SEAT);
                JsonObject semantic = archon();
                UUID creature = xmage(game, view, decision, semantic.getAsJsonObject(attack ? "attacker" : "blocker"));
                JsonElement into = semantic.get(attack ? "defender" : "attacker");
                declaration.put(creature, into.isJsonNull() ? null
                        : attack ? views.player(Views.text(into.getAsJsonObject(), "player"))
                        : xmage(game, view, decision, into.getAsJsonObject()));
            } while (kernel.terminal() == null && SEAT.equals(kernel.actingSeat())
                    && kind.equals(Lockstep.firstKind(kernel.decision())) && Lockstep.substep(kernel.decision()) > 0);
        }
        UUID into = declaration.get(source.getSourceId());
        if (into == null) {
            return false;
        }
        target.addTarget(into, source, game);
        return true;
    }

    @Override
    public void selectAttackers(Game game, UUID attackingPlayerId) {
        declaration = null;
        selectAttackersOneAtATime(game, attackingPlayerId);
    }

    @Override
    public void selectBlockers(Ability source, Game game, UUID defendingPlayerId) {
        declaration = null;
        selectBlockersOneAtATime(source, game, defendingPlayerId);
    }

    @Override
    public boolean chooseUse(Outcome outcome, String message, String secondMessage, String trueText, String falseText,
                             Ability source, Game game) {
        boolean use = guard(game, () -> use(game), false);
        getPlayerHistory().useSequence.add(use); // as ComputerPlayer records a yes/no
        return use;
    }

    /**
     * A yes/no: the Archon's boolean, or, where the kernel asks one selection whose finish is the no (an optional
     * search), whether the Archon picks; that pick then answers XMage's selection.
     */
    private boolean use(Game game) {
        JsonObject decision = advance(game, Set.of("choose_boolean", "optional_cost", "select_object", "choose_target"));
        if (decision == null) {
            throw new LockstepDivergence("XMage asks the Archon yes/no; the kernel waits on MageZero");
        }
        Views.View view = views.view(game, SEAT);
        JsonObject semantic = archon();
        String kind = Views.text(semantic, "kind");
        if (kind.equals("choose_boolean") || kind.equals("optional_cost")) {
            return semantic.has("value") ? semantic.get("value").getAsBoolean() : semantic.get("pay").getAsBoolean();
        }
        if (kind.startsWith("finish_")) {
            return false;
        }
        pendingPick = new Pick(decision, semantic, view);
        return true;
    }

    @Override
    public Mode chooseModeHelper(Modes modes, Ability source, Game game) {
        return guard(game, () -> {
            JsonObject semantic = answer(game, "choose_spell_mode");
            return new ArrayList<>(modes.values()).get(semantic.get("mode_index").getAsInt());
        }, null);
    }

    @Override
    protected int makeChoiceAmountHelper(int min, int max, Game game, Ability source, boolean isManaPay) {
        return guard(game, () -> answer(game, "choose_number").get("value").getAsInt(), min);
    }

    @Override
    public boolean chooseHelper(Outcome outcome, Choice choice, Game game) {
        return guard(game, () -> {
            String color = Views.text(answer(game, "choose_color"), "color");
            choice.setChoice(color.substring(0, 1).toUpperCase(Locale.ROOT) + color.substring(1));
            return true;
        }, false);
    }

    /** The Archon's answer to the kernel's next decision of {@code kind}. */
    private JsonObject answer(Game game, String kind) {
        if (advance(game, Set.of(kind)) == null) {
            throw new LockstepDivergence("XMage asks the Archon " + kind + "; the kernel waits on MageZero");
        }
        return archon();
    }

    // ------------------------------------------------------------------ objects across engines

    /** The XMage object for a kernel object reference in {@code decision}, as this seat's view shows it. */
    private UUID xmage(Game game, Views.View view, JsonObject decision, JsonObject reference) {
        if ("library".equals(Views.text(reference, "zone"))) {
            // a library card is shown by name only: any card of that name in that library
            Player owner = game.getPlayer(views.player(Views.text(reference, "owner_seat")));
            for (Card card : owner.getLibrary().getCards(game)) {
                if (card.getName().equals(Views.text(reference, "card_name"))) {
                    return card.getId();
                }
            }
        }
        Views.Locator at = Views.locate(Lockstep.observation(decision), Views.text(reference, "object_id"));
        String id = at == null ? null : Views.find(view.json, at);
        UUID object = id == null ? null : view.objects.get(id);
        if (object == null) {
            throw new LockstepDivergence("XMage shows no " + (at == null ? reference : at));
        }
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
            finish(game, e.getMessage());
            game.end();
            return otherwise;
        }
    }

    /** XMage's game is over: the kernel's must be too, with the same result. */
    @Override
    public void abort() {
        super.abort();
        if (kernel != null && !over) {
            finish(null, null);
        }
    }

    private void finish(Game game, String divergence) {
        over = true;
        JsonObject record = new JsonObject();
        record.addProperty("game_id", gameId);
        record.addProperty("divergence", divergence);
        JsonObject terminal = kernel == null ? null : kernel.terminal();
        record.add("kernel", terminal);
        record.addProperty("archon_won", hasWon());
        record.addProperty("archon_lost", hasLost());
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
