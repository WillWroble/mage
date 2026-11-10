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
import mage.abilities.TriggeredAbility;
import mage.abilities.mana.ManaAbility;
import mage.abilities.keyword.TrampleAbility;
import mage.constants.MultiAmountType;
import mage.constants.PhaseStep;
import mage.game.combat.CombatGroup;
import mage.game.permanent.Permanent;
import mage.util.MultiAmountMessage;
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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
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
    /** The Archon's seat in the kernel; MageZero's is p0. */
    private static final String SEAT = "p1";
    private static final String OTHER = "p0";
    /** The kernel's decisions in casting a spell that XMage asks before the cost. */
    private static final Set<String> CAST = Set.of("choose_target", "choose_cast_method");
    private static final Set<String> TARGETS = Set.of("choose_target", "select_object", "choose_cost_target",
            "finish_selection");

    private transient KernelMatch kernel;
    private transient Views views;
    private transient MageZeroSeat mageZero;
    private transient String logs;
    private transient boolean over;
    /** How the kernel's priority for the Archon differed from XMage's at the Archon's last priority it passed. */
    private transient List<String> passedAt = List.of();
    /** The current combat declaration: each of the Archon's creatures with what it attacks or blocks (null: none). */
    private transient Map<UUID, UUID> declaration;
    /** The Archon's action being done in XMage, whose dialogs are its cast in the kernel. */
    private transient ActivatedAbility acting;
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
        Player other = game.getOpponent(playerId);
        ComputerPlayerPolicyOnlyLockstep policy = (ComputerPlayerPolicyOnlyLockstep) other.getRealPlayer();
        kernel = new KernelMatch(settings, SEAT);
        gameId = game.getId().toString();
        this.game = game;
        String starting = game.getStartingPlayerId().equals(playerId) ? SEAT : OTHER;
        kernel.start(gameId, starting, opening(game, other), opening(game, this));
        Map<String, Object> flags = new Gson().fromJson(kernel.observationFlags(),
                new TypeToken<Map<String, Object>>() {}.getType());
        flags.put("known_cards", false); // knowledge is not compared, so the projection leaves it out
        views = new Views(flags, other.getId(), playerId, starting);
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
                + "; at the Archon's last priority it differed by " + passedAt);
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
            if (decision.getAsJsonArray("candidates").size() == 1 && !wanted) {
                kernel.step(0); // forced: one candidate, whoever is asked (an engine may make it without asking)
                continue;
            }
            if (OTHER.equals(kernel.actingSeat())) {
                Integer candidate = mageZero.answer(decision, game);
                if (candidate == null) {
                    if (views.before(Lockstep.observation(decision), views.view(game, SEAT).json)) {
                        throw new LockstepDivergence("XMage went past the kernel's decision for MageZero; its last pass"
                                + " differed by " + mageZero.mismatch);
                    }
                    return null;
                }
                kernel.step(candidate);
                continue;
            }
            if (wanted || !kernelOnly(decision)) {
                return decision;
            }
            kernel.step(kernel.archonChoice());
        }
        // a natural end may come before XMage's (the damage of a combat both seats passed through): until XMage gets
        // there the Archon only passes, and the results are compared at XMage's end
        boolean natural = "natural".equals(kernel.terminal().get("classification").getAsString());
        if (!game.getState().isGameOver() && (!natural || kinds != null)) {
            throw new LockstepDivergence("the kernel's game ended " + kernel.terminal().get("outcome").getAsString()
                    + " (" + kernel.terminal().get("reason").getAsString() + ") while XMage's goes on"
                    + (kinds == null ? "" : " and asks the Archon " + kinds));
        }
        return null;
    }

    /** The Archon's decision of one of {@code kinds} (null: priority). */
    private boolean wanted(JsonObject decision, Set<String> kinds) {
        return SEAT.equals(kernel.actingSeat()) && (kinds == null ? Lockstep.isPriority(decision)
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
        JsonObject now = views.view(game, SEAT).json;
        List<String> diff = views.diff(Lockstep.observation(decision), now);
        if (!diff.isEmpty()) {
            if (views.before(Lockstep.observation(decision), now)) {
                throw new LockstepDivergence("XMage went past the kernel's priority for the Archon: " + diff
                        + "; at the Archon's last priority it differed by " + passedAt);
            }
            passedAt = diff;
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
        acting = ability;
        try {
            debugCosts("before", ability, game);
            if (!activateAbility(ability, game)) {
                debugCosts("refused", ability, game);
                throw new LockstepDivergence("XMage refused the Archon's " + semantic);
            }
            if (!picks.isEmpty()) {
                throw new LockstepDivergence("XMage asks no target for the Archon's " + picks.peek().semantic);
            }
        } finally {
            acting = null;
            picks.clear();
        }
        return true;
    }

    private void debugCosts(String when, ActivatedAbility ability, Game game) {
        if (when.equals("before")) {
            for (String n : new String[]{"mage.abilities", "mage.players", "mage.target", "mage.game.stack"}) {
                org.apache.log4j.Logger l = org.apache.log4j.Logger.getLogger(n);
                l.setLevel(org.apache.log4j.Level.DEBUG);
                if (!l.getAllAppenders().hasMoreElements()) {
                    l.addAppender(new org.apache.log4j.ConsoleAppender(new org.apache.log4j.PatternLayout("LOG %p %c{1}.%M %m%n"), "System.err"));
                }
            }
        }
        mage.cards.Card card = game.getCard(ability.getSourceId());
        if (card == null) return;
        for (Ability a : card.getAbilities(game)) {
            if (a instanceof mage.abilities.costs.AlternativeCostSourceAbility) {
                java.util.List<mage.abilities.costs.Cost> all = new ArrayList<>();
                try {
                    java.lang.reflect.Field f = mage.abilities.costs.AlternativeCostSourceAbility.class.getDeclaredField("alternateCosts");
                    f.setAccessible(true);
                    for (Object alt : (Iterable<?>) f.get(a)) for (Object c : (Iterable<?>) alt) all.add((mage.abilities.costs.Cost) c);
                } catch (Exception e) { System.err.println("DEBUG reflect " + e); }
                for (mage.abilities.costs.Cost c : all) {
                    for (Target t : c.getTargets()) {
                        System.err.println("DEBUG " + when + " alt " + a + " cost " + c + " selected=" + t.isChoiceSelected() + " chosen=" + t.isChosen(game) + " targets=" + t.getTargets() + " canChoose=" + t.canChoose(playerId, ability, game) + " canRespond=" + canRespond());
                    }
                }
            }
        }
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
        for (String id : at == null ? List.<String>of() : Views.twins(view.json, at)) {
            UUID source = view.objects.get(id);
            List<ActivatedAbility> matches = new ArrayList<>();
            for (ActivatedAbility ability : playable) {
                boolean ofKind = kind.equals("play_land") ? ability instanceof PlayLandAbility
                        : kind.equals("cast_spell") ? ability instanceof SpellAbility
                        : kind.equals("activate_mana_ability") ? ability instanceof ManaAbility
                        : !(ability instanceof PlayLandAbility || ability instanceof SpellAbility || ability instanceof ManaAbility);
                if (ofKind && ability.getSourceId().equals(source)) {
                    matches.add(ability);
                }
            }
            if (index < matches.size()) {
                return matches.get(index);
            }
        }
        throw new LockstepDivergence("XMage has no playable counterpart for the Archon's " + semantic);
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
            Pick pick = picks.poll();
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

    /** The trigger the Archon puts on the stack next, from its order in the kernel (position 0 goes there first). */
    @Override
    public TriggeredAbility chooseTriggeredAbility(List<TriggeredAbility> abilities, Game game) {
        return guard(game, () -> {
            JsonObject decision = advance(game, Set.of("order_pick"));
            if (decision == null || !Lockstep.semantic(decision, 0).getAsJsonObject("item").has("trigger")) {
                throw new LockstepDivergence("XMage asks the Archon an order of triggers; the kernel does not");
            }
            Views.View view = views.view(game, SEAT);
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
            declaration = new HashMap<>();
            boolean attack = source instanceof ChooseToAttackAbility;
            String kind = attack ? "declare_attack" : "declare_block";
            do {
                JsonObject decision = next(game, Set.of(kind));
                if (decision == null || !wanted(decision, Set.of(kind))) {
                    // the kernel went past this declaration without asking: no creature of the Archon's can attack or
                    // block there, so it declares none (XMage's one-at-a-time loop offers every attacker regardless)
                    JsonObject now = views.view(game, SEAT).json;
                    if (kernel.terminal() != null || !views.before(Lockstep.observation(kernel.decision()), now)) {
                        break;
                    }
                    throw new LockstepDivergence("XMage asks the Archon to " + kind + "; the kernel waits on MageZero"
                            + " at an earlier point");
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
        JsonObject decision = advance(game, Set.of("choose_boolean", "optional_cost", "optional_cast",
                "choose_cost_option", "choose_option", "select_object", "choose_target"));
        if (decision == null) {
            // the kernel waits on MageZero, so it has passed this point: it offers an option only when it can be taken
            // (a payment the Archon can make), and did not offer this one
            return false;
        }
        Views.View view = views.view(game, SEAT);
        JsonObject semantic = archon();
        String kind = Views.text(semantic, "kind");
        Boolean bool = MageZeroSeat.bool(semantic);
        if (bool != null) {
            return bool;
        }
        if (kind.equals("choose_option")) {
            Boolean may = Lockstep.may(semantic);
            if (may == null) {
                throw new LockstepDivergence("XMage asks the Archon yes/no; the kernel asks it " + semantic);
            }
            return may;
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

    @Override
    public Mode chooseModeHelper(Modes modes, Ability source, Game game) {
        return guard(game, () -> {
            JsonObject semantic = answer(game, "choose_spell_mode", "a mode");
            return new ArrayList<>(modes.values()).get(semantic.get("mode_index").getAsInt());
        }, null);
    }

    @Override
    protected int makeChoiceAmountHelper(int min, int max, Game game, Ability source, boolean isManaPay) {
        return guard(game, () -> answer(game, "choose_number", "a number").get("value").getAsInt(), min);
    }

    @Override
    public boolean chooseHelper(Outcome outcome, Choice choice, Game game) {
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
     * then the cast without one; the kernel asks the Archon's targets first, then how it pays, and only when it can pay
     * both ways. So the kernel's cast is run through here, its targets kept for XMage's target dialogs; when the kernel
     * does not ask, only one way is payable and XMage's choice is forced: the regular mana cost if the Archon has the
     * mana for it.
     */
    private String cost(Game game, Choice choice) {
        if (choice.getKeyChoices().size() != 2) {
            throw new LockstepDivergence("XMage offers the Archon's cast the costs " + choice.getKeyChoices().values());
        }
        while (true) {
            JsonObject decision = next(game, CAST);
            String kind = decision == null ? null : Lockstep.firstKind(decision);
            if ("choose_target".equals(kind)) {
                Views.View view = views.view(game, SEAT);
                picks.add(new Pick(decision, archon(), view));
                continue;
            }
            if ("choose_cast_method".equals(kind)) {
                return "alternative".equals(Views.text(archon(), "method")) ? "1" : "2";
            }
            return getManaAvailable(game).enough(acting.getManaCosts().getMana()) ? "2" : "1";
        }
    }

    /**
     * The only division XMage asks the Archon is its combat damage among blockers, which the kernel assigns itself
     * (spec 7.6, {@code engine_order}): lethal damage to each blocker in the order the kernel declared them, the rest
     * to the last, or with trample to the defender.
     */
    @Override
    public List<Integer> getMultiAmountWithIndividualConstraints(Outcome outcome, List<MultiAmountMessage> messages,
                                                                 int totalMin, int totalMax, MultiAmountType type,
                                                                 Game game) {
        return guard(game, () -> {
            List<CombatGroup> groups = new ArrayList<>();
            for (CombatGroup group : game.getCombat().getGroups()) {
                if (group.getBlockers().size() == messages.size()
                        && playerId.equals(game.getControllerId(group.getAttackers().get(0)))) {
                    groups.add(group);
                }
            }
            if (game.getTurnStepType() != PhaseStep.COMBAT_DAMAGE || groups.size() != 1
                    || !mageZero.blockers.containsAll(groups.get(0).getBlockers())) {
                throw new LockstepDivergence("XMage asks the Archon a division the kernel does not: " + type);
            }
            List<UUID> blockers = groups.get(0).getBlockers();
            Permanent attacker = game.getPermanent(groups.get(0).getAttackers().get(0));
            boolean trample = attacker.getAbilities().containsKey(TrampleAbility.getInstance().getId());
            List<UUID> order = new ArrayList<>(mageZero.blockers);
            order.retainAll(blockers);
            Map<UUID, Integer> damage = new HashMap<>();
            int remaining = totalMax;
            for (UUID blocker : order) {
                int lethal = game.getPermanent(blocker).getLethalDamage(attacker.getId(), game);
                int assigned = trample || !blocker.equals(order.get(order.size() - 1)) ? Math.min(remaining, lethal) : remaining;
                damage.put(blocker, assigned);
                remaining -= assigned;
            }
            List<Integer> amounts = new ArrayList<>();
            blockers.forEach(blocker -> amounts.add(damage.get(blocker)));
            return amounts;
        }, MultiAmountType.prepareDefaultValues(messages, totalMin, totalMax));
    }

    /** The Archon's answer to the kernel's next decision of {@code kind}, for XMage's {@code dialog}. */
    private JsonObject answer(Game game, String kind, String dialog) {
        if (kernel.terminal() == null && SEAT.equals(kernel.actingSeat()) && !kind.equals(Lockstep.firstKind(kernel.decision()))
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
        dump.add("xmage_" + OTHER, views.view(game, OTHER).json);
        dump.add("xmage_" + SEAT, views.view(game, SEAT).json);
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
