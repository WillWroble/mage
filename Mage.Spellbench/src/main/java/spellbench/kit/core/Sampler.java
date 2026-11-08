package spellbench.kit.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

/**
 * The determinization sampler (design Section 4, with the addendum's change 7: disjoint allocations). It fills
 * every slot hidden from the viewer with a card name, honouring every pin of {@code known}, from the decklists the
 * viewer may see. It reads only permitted inputs: {@code game_start} and the current observation.
 * <p>
 * Allocation (all disjoint):
 * <ul>
 * <li>public cards: every nontoken object with a {@code card_name} in a public zone (battlefield, graveyard,
 * face-up exile, stack spells, command) and the viewer's own hand, counted for its owner;</li>
 * <li>pinned hidden cards: {@code known} entries (other-seat hand, either library);</li>
 * <li>unknown slots U: the rest of the other seat's hand and library, the rest of the viewer's library, and every
 * object whose identity is hidden from the viewer ({@code card_name} null: face-down objects, which are counted
 * here only and never as public cards).</li>
 * </ul>
 * Physical total of a seat = U + pinned + public. The pool for U is the decklist minus public cards minus pins; a
 * pool smaller than U is a deficit (filled from the name domain, flagged), a larger one a surplus (a uniform subset,
 * flagged). Both are reported per decision and expected to be zero on admitted pools.
 */
public final class Sampler {

    /** One hidden slot: the card name chosen for it and, for a looked-at or searched card, its object id. */
    public static final class Slot {
        public final String name;
        public final String objectId;
        public final boolean pinned;

        public Slot(String name, String objectId, boolean pinned) {
            this.name = name;
            this.objectId = objectId;
            this.pinned = pinned;
        }

        Map<String, Object> json() {
            return Json.map("name", name, "object_id", objectId, "pinned", pinned);
        }
    }

    /** The hidden zones of one seat. */
    public static final class SeatSample {
        public final String seat;
        /** Library top to bottom. */
        public final List<Slot> library = new ArrayList<>();
        /** The other seat's hand (empty for the viewer, whose hand is public to it). */
        public final List<Slot> hand = new ArrayList<>();
        /** Names for this seat's objects whose identity the viewer cannot see, by object id. */
        public final Map<String, String> faceDown = new LinkedHashMap<>();
        public int unknownSlots;
        public int pinned;
        public int publicCards;
        public int poolSize;
        public int deficit;
        public int surplus;

        public SeatSample(String seat) {
            this.seat = seat;
        }

        Map<String, Object> json() {
            List<Object> lib = new ArrayList<>();
            for (Slot s : library) {
                lib.add(s.json());
            }
            List<Object> hd = new ArrayList<>();
            for (Slot s : hand) {
                hd.add(s.json());
            }
            return Json.map("seat", seat, "library", lib, "hand", hd, "face_down", new LinkedHashMap<>(faceDown),
                    "unknown_slots", unknownSlots, "pinned", pinned, "public_cards", publicCards,
                    "pool_size", poolSize, "deficit", deficit, "surplus", surplus);
        }
    }

    public static final class Sample {
        public final Map<String, SeatSample> seats = new LinkedHashMap<>();
        public final List<String> flags = new ArrayList<>();

        public Map<String, Object> json() {
            Map<String, Object> s = new LinkedHashMap<>();
            for (Map.Entry<String, SeatSample> e : seats.entrySet()) {
                s.put(e.getKey(), e.getValue().json());
            }
            return Json.map("seats", s, "flags", new ArrayList<Object>(flags));
        }
    }

    private Sampler() {
    }

    static String other(String seat) {
        return "p0".equals(seat) ? "p1" : "p0";
    }

    /** Decklist rows as a name -> count map (code point order). */
    static TreeMap<String, Integer> counts(Map<String, Object> deck) {
        TreeMap<String, Integer> out = new TreeMap<>();
        if (deck == null) {
            return out;
        }
        for (Object r : Json.arr(deck, "decklist")) {
            Map<String, Object> row = Json.obj(r);
            out.merge(Json.str(row, "name"), (int) Json.num(row, "count", 0), Integer::sum);
        }
        return out;
    }

    /**
     * The decklist row a public object counts against. A multi-face card shows the name of its current face (a
     * transformed permanent shows its back face), while the list names the card by its front face or its full name;
     * {@code full_name} ({@code "A // B"}, Section 6) bridges the two. A name the list holds, or one no face of which
     * it holds, is returned unchanged.
     */
    static String listName(String name, String fullName, Map<String, Integer> list) {
        if (list.containsKey(name) || fullName == null) {
            return name;
        }
        if (list.containsKey(fullName)) {
            return fullName;
        }
        for (String face : fullName.split(" // ")) {
            if (list.containsKey(face)) {
                return face;
            }
        }
        return name;
    }

    /**
     * Samples one world. {@code gameStart} is the {@code game_start} request, {@code observation} the current
     * decision's observation; {@code rng} the world's sampler stream.
     */
    public static Sample sample(Map<String, Object> gameStart, Map<String, Object> observation, Random rng) {
        String viewer = Json.str(observation, "viewer");
        String opp = other(viewer);
        Sample out = new Sample();
        Map<String, Map<String, Object>> players = new LinkedHashMap<>();
        for (Object p : Json.arr(observation, "players")) {
            Map<String, Object> pm = Json.obj(p);
            players.put(Json.str(pm, "seat"), pm);
        }
        Map<String, Object> rules = Json.obj(gameStart, "rules");
        boolean oppHidden = gameStart.get("opponent_deck") == null
                || "hidden".equals(rules == null ? null : Json.str(rules, "opponent_decklist"));
        List<String> domain = new ArrayList<>();
        if (rules != null && rules.get("card_name_domain") != null) {
            for (Object n : Json.arr(Json.obj(rules, "card_name_domain"), "names")) {
                domain.add((String) n);
            }
        }
        Collections.sort(domain);

        // public cards per owner, and face-down (identity hidden) objects per owner
        Map<String, TreeMap<String, Integer>> publicCards = new LinkedHashMap<>();
        Map<String, List<String>> hiddenObjects = new LinkedHashMap<>();
        for (String s : new String[]{"p0", "p1"}) {
            publicCards.put(s, new TreeMap<String, Integer>());
            hiddenObjects.put(s, new ArrayList<String>());
        }
        List<Map<String, Object>> visible = new ArrayList<>();
        for (Map<String, Object> pm : players.values()) {
            // command-zone objects (emblems, dungeons) are not cards of a list: never counted as public cards
            for (String zone : new String[]{"hand", "battlefield", "graveyard", "exile"}) {
                for (Object o : Json.arr(pm, zone)) {
                    visible.add(Json.obj(o));
                }
            }
        }
        for (Object o : Json.arr(observation, "stack")) {
            Map<String, Object> e = Json.obj(o);
            if ("spell".equals(Json.str(e, "stack_kind"))) {
                visible.add(e);
            }
        }
        boolean copies = false;
        for (Map<String, Object> rec : visible) {
            if (Json.bool(rec, "token")) {
                continue;
            }
            if (Json.bool(rec, "copy")) {
                copies = true;
                continue; // a nontoken copy is not one of the list's cards (approximate, below)
            }
            String owner = Json.str(rec, "owner_seat");
            String name = Json.str(rec, "card_name");
            if (name == null) {
                hiddenObjects.get(owner).add(Json.str(rec, "object_id"));
            } else {
                Map<String, Object> deck = Json.obj(gameStart, owner.equals(viewer) ? "own_deck" : "opponent_deck");
                publicCards.get(owner).merge(listName(name, Json.str(rec, "full_name"), counts(deck)), 1,
                        Integer::sum);
            }
        }
        if (copies) {
            out.flags.add("approximate:nontoken_copy");
        }

        // pins from known
        Map<String, List<Map<String, Object>>> knownHand = new LinkedHashMap<>();
        Map<String, List<Map<String, Object>>> knownLibrary = new LinkedHashMap<>();
        for (String s : new String[]{"p0", "p1"}) {
            knownHand.put(s, new ArrayList<Map<String, Object>>());
            knownLibrary.put(s, new ArrayList<Map<String, Object>>());
        }
        for (Object o : Json.arr(observation, "known")) {
            Map<String, Object> k = Json.obj(o);
            String owner = Json.str(k, "owner_seat");
            if ("hand".equals(Json.str(k, "zone"))) {
                knownHand.get(owner).add(k);
            } else {
                knownLibrary.get(owner).add(k);
            }
        }

        for (String seat : new String[]{"p0", "p1"}) {
            Map<String, Object> pm = players.get(seat);
            SeatSample ss = new SeatSample(seat);
            out.seats.put(seat, ss);
            boolean isViewer = seat.equals(viewer);
            int handCount = (int) Json.num(pm, "hand_count", 0);
            int libraryCount = (int) Json.num(pm, "library_count", 0);
            List<Map<String, Object>> kh = isViewer ? new ArrayList<Map<String, Object>>() : knownHand.get(seat);
            List<Map<String, Object>> kl = knownLibrary.get(seat);
            if (kh.size() > handCount) {
                out.flags.add("approximate:known_hand_exceeds_count");
                kh = kh.subList(0, handCount);
            }
            int handSlots = isViewer ? 0 : handCount - kh.size();
            int libSlots = Math.max(0, libraryCount - kl.size());
            List<String> faceDownIds = hiddenObjects.get(seat);
            ss.unknownSlots = handSlots + libSlots + faceDownIds.size();
            ss.pinned = kh.size() + kl.size();
            int pub = 0;
            for (int c : publicCards.get(seat).values()) {
                pub += c;
            }
            ss.publicCards = pub;

            // the pool: decklist minus public cards minus pins
            boolean listHidden = !isViewer && oppHidden;
            TreeMap<String, Integer> pool = listHidden ? new TreeMap<String, Integer>()
                    : counts(Json.obj(gameStart, isViewer ? "own_deck" : "opponent_deck"));
            if (!listHidden) {
                for (Map.Entry<String, Integer> e : publicCards.get(seat).entrySet()) {
                    pool.merge(e.getKey(), -e.getValue(), Integer::sum);
                }
                for (Map<String, Object> k : kh) {
                    pool.merge(Json.str(k, "card_name"), -1, Integer::sum);
                }
                for (Map<String, Object> k : kl) {
                    pool.merge(Json.str(k, "card_name"), -1, Integer::sum);
                }
            }
            List<String> poolNames = new ArrayList<>();
            int negative = 0;
            for (Map.Entry<String, Integer> e : pool.entrySet()) {
                for (int i = 0; i < e.getValue(); i++) {
                    poolNames.add(e.getKey());
                }
                if (e.getValue() < 0) {
                    negative -= e.getValue();
                }
            }
            if (negative > 0) {
                out.flags.add("approximate:public_exceeds_list:" + seat + ":" + negative);
            }
            ss.poolSize = poolNames.size();
            int u = ss.unknownSlots;
            List<String> fill;
            if (listHidden) {
                // first release: names uniform over the domain (A1 build-out adds the DomainPrior of Section 4.2)
                fill = new ArrayList<>();
                for (int i = 0; i < u && !domain.isEmpty(); i++) {
                    fill.add(domain.get(rng.nextInt(domain.size())));
                }
                out.flags.add("approximate:hidden_list_domain_uniform:" + seat);
            } else if (poolNames.size() == u) {
                fill = new ArrayList<>(poolNames);
            } else if (poolNames.size() > u) {
                ss.surplus = poolNames.size() - u;
                Collections.shuffle(poolNames, rng);
                fill = new ArrayList<>(poolNames.subList(0, u));
                Collections.sort(fill);
                out.flags.add("approximate:pool_surplus:" + seat + ":" + ss.surplus);
            } else {
                ss.deficit = u - poolNames.size();
                fill = new ArrayList<>(poolNames);
                List<String> from = domain.isEmpty() ? new ArrayList<>(pool.keySet()) : domain;
                for (int i = 0; i < ss.deficit && !from.isEmpty(); i++) {
                    fill.add(from.get(rng.nextInt(from.size())));
                }
                out.flags.add("approximate:pool_deficit:" + seat + ":" + ss.deficit);
            }
            Collections.shuffle(fill, rng);
            int at = 0;
            // face-down objects first (their own slots), then the hand, then the library
            for (String id : faceDownIds) {
                ss.faceDown.put(id, at < fill.size() ? fill.get(at++) : null);
            }
            for (Map<String, Object> k : kh) {
                ss.hand.add(new Slot(Json.str(k, "card_name"), Json.str(k, "object_id"), true));
            }
            for (int i = 0; i < handSlots; i++) {
                ss.hand.add(new Slot(at < fill.size() ? fill.get(at++) : null, null, false));
            }
            // library: positional pins in place, searching pins and unknown cards shuffled into the rest
            Slot[] lib = new Slot[libraryCount];
            List<Slot> loose = new ArrayList<>();
            for (Map<String, Object> k : kl) {
                Slot s = new Slot(Json.str(k, "card_name"), Json.str(k, "object_id"), true);
                Object top = k.get("position_from_top");
                Object bottom = k.get("position_from_bottom");
                int pos = -1;
                if (top instanceof Number) {
                    pos = ((Number) top).intValue();
                } else if (bottom instanceof Number) {
                    pos = libraryCount - 1 - ((Number) bottom).intValue();
                }
                if (pos >= 0 && pos < libraryCount && lib[pos] == null) {
                    lib[pos] = s;
                } else {
                    loose.add(s);
                }
            }
            for (int i = 0; i < libSlots; i++) {
                loose.add(new Slot(at < fill.size() ? fill.get(at++) : null, null, false));
            }
            Collections.shuffle(loose, rng);
            int li = 0;
            for (int i = 0; i < libraryCount; i++) {
                if (lib[i] == null && li < loose.size()) {
                    lib[i] = loose.get(li++);
                }
            }
            for (Slot s : lib) {
                if (s != null) {
                    ss.library.add(s);
                }
            }
        }
        return out;
    }
}
