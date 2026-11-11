package org.mage.spellbench;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import mage.abilities.Ability;
import mage.abilities.ActivatedAbility;
import mage.abilities.PlayLandAbility;
import mage.abilities.SpellAbility;
import mage.abilities.mana.ManaAbility;
import mage.game.Game;
import mage.cards.SpellOptionCard;
import mage.constants.SpellAbilityCastMode;
import org.mage.spellbench.ComputerPlayerPolicyOnlyLockstep.Move;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * The kernel's seat for MageZero: each decision the kernel asks it is answered from what MageZero did in XMage, in
 * the order it did it. A priority decision is matched to MageZero's move at the same game state (a pass the kernel
 * never asked for is dropped); a dialog takes MageZero's next dialog move of its kind, one pick per substep.
 */
final class MageZeroSeat {

    private final Deque<Move> moves;
    private final Views views;
    /** The selection being given to the kernel pick by pick: its move and the picks left. */
    private Move selection;
    private Deque<UUID> picks;
    /** How many cards the arrangement being given keeps on top. */
    private int kept;
    /** The combat declaration being given creature by creature. */
    private Move declaration;
    /** This combat's blockers in the order the kernel declared them: its order of combat damage (spec 7.6). */
    private final List<UUID> blockers;
    /** MageZero's last action (the half of a card with a spell option it cast, or how it cast it). */
    private ActivatedAbility cast;
    /** The places in XMage's library of the cards an order has taken so far. */
    private final Set<Integer> ordered = new HashSet<>();
    /** MageZero's action whose mana the kernel is making first (see action). */
    private Move floating;
    /** MageZero's cost of its cast, passed over for the targets the kernel asks first. */
    private Move cost;
    /** How the kernel's priority differed from MageZero's first pass dropped since the last match. */
    String mismatch = "";

    MageZeroSeat(Deque<Move> moves, Views views, List<UUID> blockers) {
        this.moves = moves;
        this.views = views;
        this.blockers = blockers;
    }

    /** MageZero's move kinds for the kernel's decision kinds that XMage may ask too. */
    private static final Map<String, Move.Kind> DIALOGS = Map.of("choose_target", Move.Kind.TARGETS,
            "select_object", Move.Kind.TARGETS, "choose_cost_target", Move.Kind.TARGETS, "choose_number", Move.Kind.NUMBER,
            "choose_spell_mode", Move.Kind.MODE, "choose_color", Move.Kind.CHOICE, "choose_boolean", Move.Kind.USE,
            "optional_cost", Move.Kind.USE, "choose_option", Move.Kind.USE);

    /**
     * A decision of MageZero's the kernel made itself (one candidate): XMage's answer to the same dialog, if it asked
     * one there (the same game), is consumed.
     */
    void forced(JsonObject decision) {
        Move.Kind kind = DIALOGS.get(Lockstep.firstKind(decision));
        Move move = moves.stream().filter(m -> m.kind != Move.Kind.PASS).findFirst().orElse(null);
        if (kind != null && move != null && move.kind == kind
                && views.diff(Lockstep.observation(decision), move.view.json).isEmpty()) {
            moves.remove(move);
        }
    }

    /** The candidate MageZero's moves answer {@code decision} with, or null when MageZero has not reached it. */
    Integer answer(JsonObject decision, Game game) {
        String kind = Lockstep.firstKind(decision);
        if (Lockstep.isPriority(decision)) {
            return priority(decision, game);
        }
        switch (kind) {
            case "choose_target":
            case "select_object":
            case "choose_cost_target":
            case "finish_selection":
                return pick(decision, game);
            case "choose_boolean":
            case "optional_cost":
            case "optional_cast":
                return value(decision, Move.Kind.USE,
                        (c, v) -> Objects.equals(bool(c), ((ComputerPlayerPolicyOnlyLockstep.Use) v).value));
            case "choose_option":
                return value(decision, Move.Kind.USE,
                        (c, v) -> Objects.equals(Lockstep.may(c, ((ComputerPlayerPolicyOnlyLockstep.Use) v).trueText),
                                ((ComputerPlayerPolicyOnlyLockstep.Use) v).value));
            case "choose_cost_option":
                return value(decision, Move.Kind.USE,
                        (c, v) -> Lockstep.names(((ComputerPlayerPolicyOnlyLockstep.Use) v).option, Views.text(c, "choice")));
            case "choose_spell_mode":
                if (upcoming() != Move.Kind.MODE && cast != null) {
                    // the spell's form, which XMage takes as which half MageZero cast (an omen's, an adventure's) or
                    // how (bestowed)
                    int form = game.getCard(cast.getSourceId()) instanceof SpellOptionCard || cast instanceof SpellAbility
                            && ((SpellAbility) cast).getSpellAbilityCastMode() == SpellAbilityCastMode.BESTOW ? 1 : 0;
                    return candidate(decision, c -> Objects.equals(number(c, "mode_index"), form), "form " + form);
                }
                return value(decision, Move.Kind.MODE, (c, v) -> Objects.equals(number(c, "mode_index"), v));
            case "choose_number":
                return value(decision, Move.Kind.NUMBER, (c, v) -> Objects.equals(number(c, "value"), v));
            case "choose_cast_method":
                return value(decision, Move.Kind.COST,
                        (c, v) -> "alternative".equals(Views.text(c, "method")) == "1".equals(v));
            case "choose_color":
                return value(decision, Move.Kind.CHOICE,
                        (c, v) -> String.valueOf(v).equalsIgnoreCase(Views.text(c, "color")));
            case "declare_attack":
            case "declare_block":
                return declare(decision, kind.equals("declare_attack"));
            case "arrange_card":
                return arrange(decision, game);
            case "order_pick":
                return order(decision, game);
            default:
                throw new LockstepDivergence("XMage asks MageZero nothing for the kernel's " + kind);
        }
    }

    // ------------------------------------------------------------------ priority

    private Integer priority(JsonObject decision, Game game) {
        while (!moves.isEmpty()) {
            Move move = moves.peek();
            if (emptyDeclaration(move) || move.kind == Move.Kind.USE || move.kind == Move.Kind.COST
                    || move.kind == Move.Kind.NUMBER) {
                // nothing declared where the kernel asks nothing (no creature of MageZero's could), or a yes/no it did
                // not offer: it offers an option only when it can be taken (a payment MageZero cannot make); a cast's
                // cost or X the kernel set itself (one way to pay, an X of 0)
                moves.poll();
                continue;
            }
            if (move.kind != Move.Kind.PASS && move.kind != Move.Kind.ACTION) {
                throw new LockstepDivergence("the kernel asks MageZero priority; its next move in XMage is " + move);
            }
            // while the kernel makes the mana of MageZero's next action, the games differ by that mana only
            List<String> diff = move == floating ? List.of() : views.diff(Lockstep.observation(decision), move.view.json);
            if (diff.isEmpty()) {
                moves.poll();
                mismatch = "";
                return move.kind == Move.Kind.PASS
                        ? candidate(decision, c -> "pass".equals(Views.text(c, "kind")), "pass")
                        : action(decision, move, game);
            }
            if (move.kind == Move.Kind.ACTION) {
                throw new LockstepDivergence("MageZero's " + move + " in XMage is where the kernel asks nothing: " + diff
                        + "; its first pass since the last match differed by " + mismatch);
            }
            moves.poll(); // a pass where the kernel did not ask
            if (mismatch.isEmpty()) {
                mismatch = diff.toString();
            }
        }
        return null;
    }

    private int action(JsonObject decision, Move move, Game game) {
        ActivatedAbility ability = (ActivatedAbility) move.value;
        cast = ability;
        // the half of a card with a spell option (an omen's, Roost Seek) is cast from the card the observation shows
        UUID card = game.getCard(ability.getSourceId()) instanceof SpellOptionCard
                ? ((SpellOptionCard) game.getCard(ability.getSourceId())).getParentCard().getId() : ability.getSourceId();
        String source = kernelId(decision, move.view, card);
        String kind = ability instanceof PlayLandAbility ? "play_land"
                : ability instanceof SpellAbility ? "cast_spell"
                : ability instanceof ManaAbility ? "activate_mana_ability" : "activate_ability";
        // a cast with an alternative cost is a candidate of its own, which MageZero's moves do not answer yet
        Predicate<JsonObject> same = c -> kind.equals(Views.text(c, "kind")) && source != null
                && source.equals(Views.text(c.getAsJsonObject("source"), "object_id"))
                && (!c.has("method") || c.get("method").isJsonNull());
        String step = Views.text(Lockstep.observation(decision), "phase_step");
        boolean oneAction = "declare_attackers".equals(step) || "declare_blockers".equals(step);
        if (!move.paid.isEmpty() && (!oneAction || index(decision, same) < 0)) {
            // the mana XMage paid the action with is made first, the action kept for after it: the kernel's own payment
            // may tap other permanents, and does not count every source as castable (Elves of Deep Shadow's). Not in
            // a declare step unless it must: the kernel gives one action a round there (ninjutsu's)
            for (ComputerPlayerPolicyOnlyLockstep.Paid paid : move.paid) {
                List<String> alike = kernelIds(decision, move.view, paid.source); // identical lands: any untapped
                int mana = index(decision, c -> "activate_mana_ability".equals(Views.text(c, "kind"))
                        && alike.contains(Views.text(c.getAsJsonObject("source"), "object_id"))
                        && (paid.color == null || c.get("mana_choice") == null || c.get("mana_choice").isJsonNull()
                            || paid.color.equals(Views.text(c, "mana_choice"))));
                if (mana >= 0) {
                    move.paid.remove(paid);
                    floating = move;
                    moves.addFirst(move);
                    return mana;
                }
            }
        }
        floating = null;
        return candidate(decision, same, move + " as " + kind);
    }

    // ------------------------------------------------------------------ dialogs

    private Integer pick(JsonObject decision, Game game) {
        if (Lockstep.selected(decision) == 0 || selection == null) {
            if (upcoming() == Move.Kind.USE && offersFinish(decision)) {
                // an optional selection XMage asks first as a yes/no (you may search): no is the kernel's finish
                Move may = next(Move.Kind.USE);
                if (!((ComputerPlayerPolicyOnlyLockstep.Use) may.value).value) {
                    return candidate(decision, c -> Views.text(c, "kind").startsWith("finish_"), "no to " + may);
                }
            }
            Move.Kind upcoming = upcoming();
            if (upcoming != null && upcoming != Move.Kind.TARGETS) {
                // XMage asked MageZero nothing here (it offers fewer choices, Snap only tapped lands): nothing chosen
                selection = null;
                return candidate(decision, c -> Views.text(c, "kind").startsWith("finish_"),
                        "XMage's choice of nothing (its next move is " + upcoming + ")");
            }
            selection = next(Move.Kind.TARGETS);
            if (selection == null) {
                return null;
            }
            @SuppressWarnings("unchecked")
            List<UUID> chosen = (List<UUID>) selection.value;
            picks = new ArrayDeque<>(chosen);
        }
        if (picks.isEmpty() && !offersFinish(decision) && upcoming() == Move.Kind.TARGETS) {
            // XMage asks the selection's cards one at a time (Brainstorm's two back on the library): its next pick
            selection = next(Move.Kind.TARGETS);
            @SuppressWarnings("unchecked")
            List<UUID> chosen = (List<UUID>) selection.value;
            picks = new ArrayDeque<>(chosen);
        }
        if (picks.isEmpty()) {
            return candidate(decision, c -> Views.text(c, "kind").startsWith("finish_"), "finish of " + selection);
        }
        UUID pick = picks.poll();
        String what = "pick of " + selection;
        if (game.getPlayer(pick) != null) {
            String seat = views.seat(pick);
            return candidate(decision, c -> seat.equals(player(c)), what);
        }
        boolean handCards = decision.getAsJsonArray("candidates").asList().stream().anyMatch(c -> {
            JsonObject object = object(c.getAsJsonObject().getAsJsonObject("semantic"));
            return object != null && "hand".equals(Views.text(object, "zone"));
        });
        if (game.getCard(pick) != null && (Lockstep.offersLibraryCards(decision) || handCards)) {
            // a library or hand card is matched by name: in a library it is shown by name only (and XMage may have
            // moved MageZero's pick already, a searched land onto the battlefield), a hand of the Archon's MageZero
            // does not see (Mesmeric Fiend's choice), and cards of a name are alike in a hand
            String name = game.getCard(pick).getName();
            String in = Lockstep.offersLibraryCards(decision) ? "library" : "hand";
            return candidate(decision, c -> {
                JsonObject object = object(c);
                return object != null && in.equals(Views.text(object, "zone"))
                        && name.equals(Views.text(object, "card_name"));
            }, what);
        }
        // of the kernel's objects that look like MageZero's pick, the first offered (two attacking Humbling Elders:
        // a blocked attacker shows no mark of it, and only the unblocked one can return for ninjutsu)
        String id = kernelIds(decision, selection.view, pick).stream()
                .filter(k -> decision.getAsJsonArray("candidates").asList().stream()
                        .anyMatch(c -> k.equals(objectId(c.getAsJsonObject().getAsJsonObject("semantic")))))
                .findFirst().orElse(null);
        return candidate(decision, c -> id != null && id.equals(objectId(c)), what);
    }

    /**
     * A scry or surveil, which the kernel asks card by card and XMage as one selection of the cards that leave the top:
     * a card MageZero selected takes the other destination, the rest stay on top. Library cards are shown by name.
     * XMage then asks the order of each destination one card at a time (the last card of each is forced); those picks
     * are consumed with the last card, since the kernel's order picks are answered from where XMage put the cards.
     */
    private Integer arrange(JsonObject decision, Game game) {
        if (Lockstep.substep(decision) == 0 || selection == null) {
            selection = next(Move.Kind.TARGETS);
            if (selection == null) {
                return null;
            }
            picks = new ArrayDeque<>();
            @SuppressWarnings("unchecked")
            List<UUID> chosen = (List<UUID>) selection.value;
            picks.addAll(chosen);
            kept = 0;
        }
        String purpose = Views.text(Lockstep.semantic(decision, 0), "purpose");
        if (!"scry".equals(purpose) && !"surveil".equals(purpose)) {
            throw new LockstepDivergence("no XMage counterpart for the kernel's arrange_card for " + purpose);
        }
        String name = Views.text(Lockstep.semantic(decision, 0).getAsJsonObject("card"), "card_name");
        UUID picked = null;
        for (UUID pick : picks) {
            if (game.getCard(pick).getName().equals(name)) {
                picked = pick;
                break;
            }
        }
        picks.remove(picked);
        String destination = picked == null ? "top" : "scry".equals(purpose) ? "bottom" : "graveyard";
        if (picked == null) {
            kept++;
        }
        JsonObject card = Lockstep.semantic(decision, 0);
        int count = card.get("card_count").getAsInt();
        if (card.get("card_index").getAsInt() == count - 1) {
            int moved = count - kept;
            int orders = Math.max(0, kept - 1) + ("scry".equals(purpose) ? Math.max(0, moved - 1) : 0);
            for (int i = 0; i < orders; i++) {
                next(Move.Kind.TARGETS);
            }
        }
        return candidate(decision, c -> destination.equals(Views.text(c, "destination")), name + " to " + destination);
    }

    /**
     * The order of an arrangement's destinations, which XMage has already made: the kernel orders the cards kept on
     * top, then the ones put on the bottom, each top to bottom, so each pick takes the card of those left that is
     * highest in XMage's library. The kernel's library then needs no reordering, which would move cards it knows.
     * A card XMage moved out of the library already (a dig's, to the hand or graveyard) is in any order, as is a
     * graveyard's (XMage keeps the order cards arrive in).
     */
    private Integer order(JsonObject decision, Game game) {
        JsonObject first = Lockstep.semantic(decision, 0);
        if (first.getAsJsonObject("item").has("trigger")) {
            return trigger(decision);
        }
        JsonObject object = first.getAsJsonObject("item").getAsJsonObject("object");
        if (!"library".equals(Views.text(object, "zone")) || !"arrangement".equals(Views.text(first, "purpose"))) {
            return 0; // not an arrangement: cards moved together elsewhere (Balustrade Spy's mill, to a graveyard)
        }
        List<mage.cards.Card> library = game.getPlayer(views.player(Views.text(object, "owner_seat")))
                .getLibrary().getCards(game);
        int count = first.get("count").getAsInt();
        if (first.get("position").getAsInt() == 0) {
            ordered.clear();
        }
        // where the arrangement's cards are in XMage: its library's top and bottom (count cards each)
        Set<Integer> window = new TreeSet<>();
        for (int i = 0; i < Math.min(count, library.size()); i++) {
            window.add(i);
            window.add(library.size() - 1 - i);
        }
        window.removeAll(ordered);
        JsonArray candidates = decision.getAsJsonArray("candidates");
        int best = -1;
        int highest = Integer.MAX_VALUE;
        for (int c = 0; c < candidates.size(); c++) {
            String name = Views.text(candidates.get(c).getAsJsonObject().getAsJsonObject("semantic")
                    .getAsJsonObject("item").getAsJsonObject("object"), "card_name");
            int at = window.stream().filter(i -> library.get(i).getName().equals(name)).findFirst().orElse(-1);
            if (at < 0) {
                return c; // moved out already: any order
            }
            if (at < highest) {
                best = c;
                highest = at;
            }
        }
        ordered.add(highest);
        return best;
    }

    /**
     * A trigger put on the stack: the one MageZero's XMage put there next (position 0 is put there first). The
     * initiative's taking on combat damage (no source, or the kernel's stand-in: the Archon's Avenging Hunter) XMage
     * gives the player who had the initiative, so it asks MageZero no order with it: it goes last, after the non-active player's in XMage (APNAP), and MageZero's own
     * last trigger, which XMage puts without asking, before it.
     */
    private Integer trigger(JsonObject decision) {
        JsonArray candidates = decision.getAsJsonArray("candidates");
        List<Integer> own = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            JsonObject trigger = candidates.get(i).getAsJsonObject().getAsJsonObject("semantic")
                    .getAsJsonObject("item").getAsJsonObject("trigger");
            JsonElement source = trigger.get("source");
            if (!trigger.get("source_name").isJsonNull() && (!source.isJsonObject()
                    || decision.get("acting_seat").getAsString().equals(Views.text(source.getAsJsonObject(), "controller_seat")))) {
                own.add(i);
            }
        }
        if (own.size() < 2 && own.size() < candidates.size()) {
            return own.isEmpty() ? 0 : own.get(0);
        }
        if (upcoming() != null && upcoming() != Move.Kind.TRIGGER) {
            return 0; // XMage put them on the stack without asking (alike triggers): any order
        }
        Move move = next(Move.Kind.TRIGGER);
        if (move == null) {
            return null;
        }
        String source = kernelId(decision, move.view, ((Ability) move.value).getSourceId());
        return candidate(decision, c -> {
            JsonElement ref = c.getAsJsonObject("item").getAsJsonObject("trigger").get("source");
            return source != null && ref.isJsonObject() && source.equals(Views.text(ref.getAsJsonObject(), "object_id"));
        }, move.toString());
    }

    private interface Match {
        boolean test(JsonObject semantic, Object value);
    }

    private Integer value(JsonObject decision, Move.Kind kind, Match match) {
        Move move = next(kind);
        return move == null ? null : candidate(decision, c -> match.test(c, move.value), move.toString());
    }

    private Integer declare(JsonObject decision, boolean attack) {
        if (Lockstep.substep(decision) == 0 || declaration == null) {
            declaration = next(attack ? Move.Kind.ATTACKERS : Move.Kind.BLOCKERS);
            if (declaration == null) {
                return null;
            }
            if (!attack) {
                blockers.clear();
            }
        }
        JsonObject first = Lockstep.semantic(decision, 0);
        UUID creature = declaration.view.objects.get(Views.find(declaration.view.json,
                Views.locate(Lockstep.observation(decision), objectId(first, attack ? "attacker" : "blocker"))));
        @SuppressWarnings("unchecked")
        List<UUID> into = ((Map<UUID, List<UUID>>) declaration.value).get(creature);
        if (into == null || into.isEmpty()) {
            return candidate(decision, c -> c.get(attack ? "defender" : "attacker").isJsonNull(), "no declaration");
        }
        if (!attack) {
            blockers.add(creature);
        }
        String target = attack ? views.seat(into.get(0)) : kernelId(decision, declaration.view, into.get(0));
        return candidate(decision, c -> {
            JsonElement other = c.get(attack ? "defender" : "attacker");
            return !other.isJsonNull() && target.equals(attack ? Views.text(other.getAsJsonObject(), "player")
                    : Views.text(other.getAsJsonObject(), "object_id"));
        }, "declaration of " + declaration);
    }

    /** The kind of MageZero's next move after its passes and empty declarations, or null when it has made none. */
    private Move.Kind upcoming() {
        return moves.stream().filter(m -> m.kind != Move.Kind.PASS && m.kind != Move.Kind.COST && !emptyDeclaration(m))
                .findFirst()
                .map(m -> m.kind).orElse(null);
    }

    /** Nothing declared: where the kernel asks nothing, as no creature of MageZero's could attack or block. */
    private static boolean emptyDeclaration(Move move) {
        return (move.kind == Move.Kind.ATTACKERS || move.kind == Move.Kind.BLOCKERS) && ((Map<?, ?>) move.value).isEmpty();
    }

    /**
     * MageZero's next move, which must be a {@code kind} dialog; null when it has not answered yet. Passes before it
     * were at priority windows the kernel does not open (it is asking a dialog now).
     */
    private Move next(Move.Kind kind) {
        boolean declaration = kind == Move.Kind.ATTACKERS || kind == Move.Kind.BLOCKERS;
        while (!moves.isEmpty() && (moves.peek().kind == Move.Kind.PASS
                || declaration && moves.peek().kind == Move.Kind.USE || !declaration && emptyDeclaration(moves.peek())
                || kind != Move.Kind.COST && moves.peek().kind == Move.Kind.COST
                || kind != Move.Kind.NUMBER && moves.peek().kind == Move.Kind.NUMBER)) {
            // passes where the kernel opens no priority; at combat, yes/no's before it the kernel did not offer; out of
            // combat, declarations of nothing the kernel did not ask; a cast's cost, which the kernel asks after the
            // targets (kept for it) or not at all
            Move skipped = moves.poll();
            if (skipped.kind == Move.Kind.COST) {
                cost = skipped;
            }
        }
        if (kind == Move.Kind.COST && cost != null) {
            Move kept = cost;
            cost = null;
            return kept;
        }
        Move move = moves.poll();
        if (move != null && move.kind != kind) {
            throw new LockstepDivergence("the kernel asks MageZero a " + kind + " dialog; its next move is " + move);
        }
        return move;
    }

    // ------------------------------------------------------------------ objects and candidates

    /** The kernel's id, in {@code decision}, for the XMage object {@code id} as MageZero's view showed it. */
    private static String kernelId(JsonObject decision, Views.View view, UUID id) {
        List<String> ids = kernelIds(decision, view, id);
        return ids.isEmpty() ? null : ids.get(0);
    }

    /** The kernel's objects in {@code decision} that look like XMage's object {@code id}: its own place first. */
    private static List<String> kernelIds(JsonObject decision, Views.View view, UUID id) {
        for (Map.Entry<String, UUID> e : view.objects.entrySet()) {
            if (e.getValue().equals(id)) {
                Views.Locator at = Views.locate(view.json, e.getKey());
                JsonObject observation = Lockstep.observation(decision);
                String own = at == null ? null : Views.find(observation, at);
                if (own == null) {
                    return List.of();
                }
                List<String> ids = new ArrayList<>(List.of(own));
                Views.twins(observation, at).stream().filter(t -> !t.equals(own)).forEach(ids::add);
                return ids;
            }
        }
        return List.of();
    }

    private static int candidate(JsonObject decision, Predicate<JsonObject> semantic, String what) {
        int i = index(decision, semantic);
        if (i < 0) {
            throw new LockstepDivergence("the kernel offers no candidate for MageZero's " + what);
        }
        return i;
    }

    private static boolean offersFinish(JsonObject decision) {
        return index(decision, c -> Views.text(c, "kind").startsWith("finish_")) >= 0;
    }

    private static int index(JsonObject decision, Predicate<JsonObject> semantic) {
        JsonArray candidates = decision.getAsJsonArray("candidates");
        for (int i = 0; i < candidates.size(); i++) {
            if (semantic.test(candidates.get(i).getAsJsonObject().getAsJsonObject("semantic"))) {
                return i;
            }
        }
        return -1;
    }

    /** The object a selection candidate names (a target, a choice or a cost candidate), or null. */
    static JsonObject object(JsonObject semantic) {
        for (String field : new String[]{"target", "choice"}) {
            JsonElement ref = semantic.get(field);
            if (ref != null && ref.isJsonObject() && ref.getAsJsonObject().has("object")) {
                return ref.getAsJsonObject().getAsJsonObject("object");
            }
        }
        JsonElement candidate = semantic.get("candidate");
        return candidate == null || candidate.isJsonNull() ? null : candidate.getAsJsonObject();
    }

    /** The seat a selection candidate names as its target, or null. */
    static String player(JsonObject semantic) {
        JsonElement target = semantic.get("target");
        return target != null && target.isJsonObject() ? Views.text(target.getAsJsonObject(), "player") : null;
    }

    private static String objectId(JsonObject semantic) {
        JsonObject object = object(semantic);
        return object == null ? null : Views.text(object, "object_id");
    }

    private static String objectId(JsonObject semantic, String field) {
        JsonElement ref = semantic.get(field);
        return ref == null || ref.isJsonNull() ? null : Views.text(ref.getAsJsonObject(), "object_id");
    }

    /** The yes/no a candidate answers: a boolean, an optional cost paid, an optional cast made. */
    static Boolean bool(JsonObject semantic) {
        for (String field : new String[]{"value", "pay", "cast_it"}) {
            if (semantic.has(field)) {
                return semantic.get(field).getAsBoolean();
            }
        }
        return null;
    }

    private static Object number(JsonObject semantic, String field) {
        return semantic.has(field) ? semantic.get(field).getAsInt() : null;
    }
}
