package org.mage.spellbench;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import mage.abilities.ActivatedAbility;
import mage.abilities.PlayLandAbility;
import mage.abilities.SpellAbility;
import mage.abilities.mana.ManaAbility;
import mage.constants.Zone;
import mage.game.Game;
import org.mage.spellbench.ComputerPlayerPolicyOnlyLockstep.Move;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
    /** How the kernel's last unanswered priority differed from MageZero's last pass before it. */
    String mismatch = "";

    MageZeroSeat(Deque<Move> moves, Views views) {
        this.moves = moves;
        this.views = views;
    }

    /** The candidate MageZero's moves answer {@code decision} with, or null when MageZero has not reached it. */
    Integer answer(JsonObject decision, Game game) {
        String kind = Lockstep.firstKind(decision);
        if (Lockstep.isPriority(decision)) {
            return priority(decision);
        }
        switch (kind) {
            case "choose_target":
            case "select_object":
            case "choose_cost_target":
            case "finish_selection":
                return pick(decision, game);
            case "choose_boolean":
            case "optional_cost":
                return value(decision, Move.Kind.USE,
                        (c, v) -> Objects.equals(bool(c, "value", "pay"), ((ComputerPlayerPolicyOnlyLockstep.Use) v).value));
            case "choose_option":
                return value(decision, Move.Kind.USE,
                        (c, v) -> Objects.equals(Lockstep.may(c), ((ComputerPlayerPolicyOnlyLockstep.Use) v).value));
            case "choose_cost_option":
                return value(decision, Move.Kind.USE,
                        (c, v) -> Lockstep.names(((ComputerPlayerPolicyOnlyLockstep.Use) v).option, Views.text(c, "choice")));
            case "choose_spell_mode":
                return value(decision, Move.Kind.MODE, (c, v) -> Objects.equals(number(c, "mode_index"), v));
            case "choose_number":
                return value(decision, Move.Kind.NUMBER, (c, v) -> Objects.equals(number(c, "value"), v));
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

    private Integer priority(JsonObject decision) {
        while (!moves.isEmpty()) {
            Move move = moves.peek();
            if (move.kind != Move.Kind.PASS && move.kind != Move.Kind.ACTION) {
                throw new LockstepDivergence("the kernel asks MageZero priority; its next move in XMage is " + move);
            }
            List<String> diff = views.diff(Lockstep.observation(decision), move.view.json);
            if (diff.isEmpty()) {
                moves.poll();
                return move.kind == Move.Kind.PASS
                        ? candidate(decision, c -> "pass".equals(Views.text(c, "kind")), "pass")
                        : action(decision, move);
            }
            if (move.kind == Move.Kind.ACTION) {
                throw new LockstepDivergence("MageZero's " + move + " in XMage is where the kernel asks nothing: " + diff);
            }
            moves.poll(); // a pass where the kernel did not ask
            mismatch = diff.toString();
        }
        return null;
    }

    private int action(JsonObject decision, Move move) {
        ActivatedAbility ability = (ActivatedAbility) move.value;
        String source = kernelId(decision, move.view, ability.getSourceId());
        String kind = ability instanceof PlayLandAbility ? "play_land"
                : ability instanceof SpellAbility ? "cast_spell"
                : ability instanceof ManaAbility ? "activate_mana_ability" : "activate_ability";
        return candidate(decision, c -> kind.equals(Views.text(c, "kind")) && source != null
                && source.equals(Views.text(c.getAsJsonObject("source"), "object_id")), move + " as " + kind);
    }

    // ------------------------------------------------------------------ dialogs

    private Integer pick(JsonObject decision, Game game) {
        if (Lockstep.substep(decision) == 0 || selection == null) {
            selection = next(Move.Kind.TARGETS);
            if (selection == null) {
                return null;
            }
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
        if (game.getState().getZone(pick) == Zone.LIBRARY) {
            // a library card is shown by name only: any card of that name in that library is the same pick
            String name = game.getCard(pick).getName();
            return candidate(decision, c -> {
                JsonObject object = object(c);
                return object != null && "library".equals(Views.text(object, "zone"))
                        && name.equals(Views.text(object, "card_name"));
            }, what);
        }
        String id = kernelId(decision, selection.view, pick);
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
     * top, then the ones put on the bottom, each top to bottom, so each position takes the card XMage has there. The
     * kernel's library then needs no reordering, which would move cards it knows. A graveyard's order is not compared
     * (XMage keeps the order cards arrive in), so any order stands there.
     */
    private int order(JsonObject decision, Game game) {
        JsonObject first = Lockstep.semantic(decision, 0);
        JsonObject object = first.getAsJsonObject("item").getAsJsonObject("object");
        if (!"library".equals(Views.text(object, "zone"))) {
            return 0;
        }
        List<mage.cards.Card> library = game.getPlayer(views.player(Views.text(object, "owner_seat")))
                .getLibrary().getCards(game);
        int position = first.get("position").getAsInt();
        int count = first.get("count").getAsInt();
        String name = library.get(position < kept ? position : library.size() - count + position).getName();
        return candidate(decision, c -> name.equals(Views.text(
                c.getAsJsonObject("item").getAsJsonObject("object"), "card_name")), "XMage's order, " + name);
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
        }
        JsonObject first = Lockstep.semantic(decision, 0);
        UUID creature = declaration.view.objects.get(Views.find(declaration.view.json,
                Views.locate(Lockstep.observation(decision), objectId(first, attack ? "attacker" : "blocker"))));
        @SuppressWarnings("unchecked")
        List<UUID> into = ((Map<UUID, List<UUID>>) declaration.value).get(creature);
        if (into == null || into.isEmpty()) {
            return candidate(decision, c -> c.get(attack ? "defender" : "attacker").isJsonNull(), "no declaration");
        }
        String target = attack ? views.seat(into.get(0)) : kernelId(decision, declaration.view, into.get(0));
        return candidate(decision, c -> {
            JsonElement other = c.get(attack ? "defender" : "attacker");
            return !other.isJsonNull() && target.equals(attack ? Views.text(other.getAsJsonObject(), "player")
                    : Views.text(other.getAsJsonObject(), "object_id"));
        }, "declaration of " + declaration);
    }

    /**
     * MageZero's next move, which must be a {@code kind} dialog; null when it has not answered yet. Passes before it
     * were at priority windows the kernel does not open (it is asking a dialog now).
     */
    private Move next(Move.Kind kind) {
        while (!moves.isEmpty() && moves.peek().kind == Move.Kind.PASS) {
            moves.poll();
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
        for (Map.Entry<String, UUID> e : view.objects.entrySet()) {
            if (e.getValue().equals(id)) {
                Views.Locator at = Views.locate(view.json, e.getKey());
                return at == null ? null : Views.find(Lockstep.observation(decision), at);
            }
        }
        return null;
    }

    private static int candidate(JsonObject decision, Predicate<JsonObject> semantic, String what) {
        JsonArray candidates = decision.getAsJsonArray("candidates");
        for (int i = 0; i < candidates.size(); i++) {
            if (semantic.test(candidates.get(i).getAsJsonObject().getAsJsonObject("semantic"))) {
                return i;
            }
        }
        throw new LockstepDivergence("the kernel offers no candidate for MageZero's " + what);
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

    private static Object bool(JsonObject semantic, String... fields) {
        for (String field : fields) {
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
