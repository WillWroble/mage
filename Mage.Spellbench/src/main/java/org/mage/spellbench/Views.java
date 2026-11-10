package org.mage.spellbench;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import mage.cards.Card;
import mage.game.ExileZone;
import mage.game.Game;
import mage.game.permanent.Permanent;
import mage.game.stack.StackObject;
import mage.player.spellbench.ids.ObjectIds;
import mage.player.spellbench.observe.Observation;
import mage.player.spellbench.observe.ObservationBuilder;
import mage.players.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * The XMage game written as the kernel writes a seat's observation (Jack's projection, with the kernel's declared
 * observation flags), the comparison of two such observations, and the matching of an object across them.
 * Object ids differ between engines (each is opaque and per viewer), so an object is located by what both engines
 * show: its zone, its owner, its card name and its place among the objects of that name there.
 */
final class Views {

    /** One seat's projection of the XMage game, with the XMage object behind each projected id. */
    static final class View {
        final JsonObject json;
        final Map<String, UUID> objects;

        View(JsonObject json, Map<String, UUID> objects) {
            this.json = json;
            this.objects = objects;
        }
    }

    /** Where an object is, as both engines show it. */
    static final class Locator {
        final String zone;
        final String owner;
        final String name;
        final int ordinal;

        Locator(String zone, String owner, String name, int ordinal) {
            this.zone = zone;
            this.owner = owner;
            this.name = name;
            this.ordinal = ordinal;
        }

        @Override
        public String toString() {
            return name + " (" + owner + " " + zone + " #" + ordinal + ")";
        }
    }

    /** Keeps null fields: the protocol writes every field, null or not. */
    private static final Gson JSON = new GsonBuilder().serializeNulls().create();

    private static final List<String> STEPS = List.of("untap", "upkeep", "draw", "precombat_main",
            "beginning_of_combat", "declare_attackers", "declare_blockers", "combat_damage", "end_of_combat",
            "postcombat_main", "end_step", "cleanup");

    private final Map<String, Object> flags;
    private final UUID p0;
    private final UUID p1;
    private final String startingSeat;

    Views(Map<String, Object> flags, UUID p0, UUID p1, String startingSeat) {
        this.flags = flags;
        this.p0 = p0;
        this.p1 = p1;
        this.startingSeat = startingSeat;
    }

    /**
     * The game turn of a kernel observation. The spec counts both players' turns; mtg-kernel counts each player's
     * own, so the second player's first turn is its turn 1, the game's turn 2.
     */
    int gameTurn(JsonObject kernel) {
        int turn = kernel.get("turn").getAsInt();
        return startingSeat.equals(text(kernel, "active_seat")) ? 2 * turn - 1 : 2 * turn;
    }

    /** The kernel observation's point in the game is earlier than the XMage one's. */
    boolean before(JsonObject kernel, JsonObject xmage) {
        int turn = gameTurn(kernel);
        int other = xmage.get("turn").getAsInt();
        return turn != other ? turn < other
                : STEPS.indexOf(text(kernel, "phase_step")) < STEPS.indexOf(text(xmage, "phase_step"));
    }

    String seat(UUID playerId) {
        return playerId.equals(p0) ? "p0" : "p1";
    }

    UUID player(String seat) {
        return "p0".equals(seat) ? p0 : p1;
    }

    /** The game as {@code seat} sees it now. */
    View view(Game game, String seat) {
        try {
            UUID priority = game.getState().getPriorityPlayerId();
            Observation observation = new ObservationBuilder(game, new ObjectIds(new byte[32]), flags, p0, p1)
                    .build(seat, priority == null ? null : seat(priority), Collections.emptyList());
            Map<String, UUID> objects = new HashMap<>();
            for (UUID id : objectIds(game)) {
                Map<String, Object> reference = observation.reference(id);
                if (reference != null && reference.get("object_id") != null) {
                    objects.put((String) reference.get("object_id"), id);
                }
            }
            return new View(JSON.toJsonTree(observation.json()).getAsJsonObject(), objects);
        } catch (Exception e) {
            throw new IllegalStateException("projection failed", e);
        }
    }

    private static List<UUID> objectIds(Game game) {
        List<UUID> ids = new ArrayList<>();
        for (Permanent permanent : game.getBattlefield().getAllPermanents()) {
            ids.add(permanent.getId());
        }
        for (StackObject object : game.getStack()) {
            ids.add(object.getId());
        }
        for (Player player : game.getPlayers().values()) {
            ids.addAll(player.getHand());
            ids.addAll(player.getGraveyard());
            ids.addAll(player.getLibrary().getCardList());
        }
        for (ExileZone zone : game.getExile().getExileZones()) {
            ids.addAll(zone);
        }
        return ids;
    }

    // ------------------------------------------------------------------ comparison

    /**
     * The differences between a kernel observation and an XMage one as game states (the kernel's turn counted as the
     * game's). Left out: combat fields outside combat (mtg-kernel keeps them past it), summoning sickness of a
     * non-creature, object ids (opaque per engine and
     * viewer), {@code known} and {@code passed_seats} (bookkeeping), each engine's rendering of card text
     * ({@code full_name}, {@code characteristics}), a spell's name on the stack (mtg-kernel names the card, XMage the
     * cast face). A non-modal object without X is {@code modes [0]}, {@code x_value 0} on mtg-kernel and null in the
     * projection. XMage keeps no chosen order on the battlefield, in exile or in graveyards (the battlefield and
     * exile zones are maps, a graveyard keeps the order cards arrive in, never asking anyone), so those compare as
     * sets of cards.
     */
    List<String> diff(JsonObject kernel, JsonObject xmage) {
        JsonObject game = kernel.deepCopy();
        game.addProperty("turn", gameTurn(kernel));
        boolean combat = COMBAT.contains(text(xmage, "phase_step")) && COMBAT.contains(text(kernel, "phase_step"));
        List<String> out = new ArrayList<>();
        walk("", state(game, combat), state(xmage, combat), out);
        return out;
    }

    /** The steps in which creatures are in combat (CR 506.4: they leave it as the combat phase ends). */
    private static final Set<String> COMBAT = Set.of("beginning_of_combat", "declare_attackers", "declare_blockers",
            "combat_damage", "end_of_combat");
    private static final Set<String> COMBAT_FIELDS = Set.of("attacking", "attack_target", "blocking",
            "blocked_attackers");
    private static final Set<String> UNORDERED = Set.of("battlefield", "exile", "graveyard");

    private static JsonElement state(JsonElement e, boolean combat) {
        if (e.isJsonObject()) {
            JsonObject in = e.getAsJsonObject();
            if (in.has("permanent") && in.get("permanent").isJsonObject() && !creature(in)) {
                // summoning sickness matters only to creatures: a land's is no state (the engines tap different
                // identical lands to pay)
                in = in.deepCopy();
                in.getAsJsonObject("permanent").remove("summoning_sick");
            }
            JsonObject out = new JsonObject(); // keys in one order, as the engines write theirs in different ones
            for (String k : new TreeSet<>(in.keySet())) {
                JsonElement v = in.get(k);
                if (k.equals("object_id") || k.equals("known") || k.equals("passed_seats") || k.equals("full_name")
                        || k.equals("characteristics") || !combat && COMBAT_FIELDS.contains(k)
                        || k.equals("card_name") && in.has("stack_kind") && "spell".equals(text(in, "stack_kind"))) {
                    continue;
                }
                if (k.equals("modes") && v.isJsonNull()) {
                    JsonArray zero = new JsonArray();
                    zero.add(0);
                    v = zero;
                } else if (k.equals("x_value") && v.isJsonNull()) {
                    v = new JsonPrimitive(0);
                }
                v = state(v, combat);
                if (UNORDERED.contains(k) && v.isJsonArray()) {
                    List<JsonElement> cards = new ArrayList<>();
                    v.getAsJsonArray().forEach(cards::add);
                    cards.sort((x, y) -> x.toString().compareTo(y.toString()));
                    JsonArray sorted = new JsonArray();
                    cards.forEach(sorted::add);
                    v = sorted;
                }
                out.add(k, v);
            }
            return out;
        }
        if (e.isJsonArray()) {
            JsonArray out = new JsonArray();
            e.getAsJsonArray().forEach(x -> out.add(state(x, combat)));
            return out;
        }
        return e;
    }

    private static boolean creature(JsonObject object) {
        JsonElement characteristics = object.get("characteristics");
        return characteristics != null && characteristics.isJsonObject()
                && characteristics.getAsJsonObject().getAsJsonArray("types").contains(new JsonPrimitive("creature"));
    }

    private static void walk(String path, JsonElement a, JsonElement b, List<String> out) {
        if (a.isJsonObject() && b.isJsonObject()) {
            JsonObject x = a.getAsJsonObject();
            JsonObject y = b.getAsJsonObject();
            for (String k : x.keySet()) {
                if (!y.has(k)) {
                    out.add(path + "/" + k + " (only in the kernel's)");
                } else {
                    walk(path + "/" + k, x.get(k), y.get(k), out);
                }
            }
            for (String k : y.keySet()) {
                if (!x.has(k)) {
                    out.add(path + "/" + k + " (only in XMage's)");
                }
            }
        } else if (a.isJsonArray() && b.isJsonArray() && a.getAsJsonArray().size() == b.getAsJsonArray().size()) {
            for (int i = 0; i < a.getAsJsonArray().size(); i++) {
                walk(path + "/" + i, a.getAsJsonArray().get(i), b.getAsJsonArray().get(i), out);
            }
        } else if (!a.equals(b)) {
            out.add(path + " (" + abbreviate(a) + " vs " + abbreviate(b) + ")");
        }
    }

    private static String abbreviate(JsonElement e) {
        String s = e.toString();
        return s.length() > 80 ? s.substring(0, 77) + "..." : s;
    }

    // ------------------------------------------------------------------ objects across engines

    /** Where the object with {@code objectId} is in {@code observation}, or null if it is not shown there. */
    static Locator locate(JsonObject observation, String objectId) {
        for (Map.Entry<String, List<JsonObject>> zone : zones(observation).entrySet()) {
            Map<String, Integer> seen = new HashMap<>();
            for (JsonObject object : zone.getValue()) {
                String name = text(object, "card_name");
                int ordinal = seen.merge(name, 1, Integer::sum) - 1;
                if (objectId.equals(text(object, "object_id"))) {
                    String[] key = zone.getKey().split(" ");
                    return new Locator(key[1], key[0], name, ordinal);
                }
            }
        }
        return null;
    }

    /** The id of the object at {@code at} in {@code observation}, or null. */
    static String find(JsonObject observation, Locator at) {
        Map<String, Integer> seen = new HashMap<>();
        for (JsonObject object : zones(observation).getOrDefault(at.owner + " " + at.zone, Collections.emptyList())) {
            String name = text(object, "card_name");
            if (Objects.equals(name, at.name) && seen.merge(name, 1, Integer::sum) - 1 == at.ordinal) {
                return text(object, "object_id");
            }
        }
        return null;
    }

    /**
     * Each zone's objects keyed "seat zone" (the stack: "all stack"): an ordered zone as the observation lists it, an
     * unordered one by its cards' state, the order both engines share when their states are equal.
     */
    private static Map<String, List<JsonObject>> zones(JsonObject observation) {
        Map<String, List<JsonObject>> out = new HashMap<>();
        for (JsonElement p : observation.getAsJsonArray("players")) {
            JsonObject player = p.getAsJsonObject();
            for (String zone : new String[]{"hand", "battlefield", "graveyard", "exile"}) {
                JsonElement objects = player.get(zone);
                if (objects != null && objects.isJsonArray()) {
                    List<JsonObject> list = new ArrayList<>();
                    objects.getAsJsonArray().forEach(o -> list.add(o.getAsJsonObject()));
                    if (UNORDERED.contains(zone)) {
                        list.sort((x, y) -> state(x, true).toString().compareTo(state(y, true).toString()));
                    }
                    out.put(text(player, "seat") + " " + zone, list);
                }
            }
        }
        List<JsonObject> stack = new ArrayList<>();
        observation.getAsJsonArray("stack").forEach(o -> stack.add(o.getAsJsonObject()));
        out.put("all stack", stack);
        for (JsonElement k : observation.getAsJsonArray("known")) {
            JsonObject known = k.getAsJsonObject();
            if ("library".equals(text(known, "zone")) && text(known, "object_id") != null) {
                out.computeIfAbsent(text(known, "owner_seat") + " library", z -> new ArrayList<>()).add(known);
            }
        }
        return out;
    }

    static String text(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? null : e.getAsString();
    }
}
