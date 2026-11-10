package mage.player.spellbench.observe;

import mage.Mana;
import mage.MageObject;
import mage.ObjectColor;
import mage.abilities.Ability;
import mage.abilities.Mode;
import mage.abilities.Modes;
import mage.abilities.TriggeredAbility;
import mage.cards.Card;
import mage.cards.CardWithSpellOption;
import mage.cards.ModalDoubleFacedCard;
import mage.cards.SplitCard;
import mage.constants.AbilityType;
import mage.constants.PhaseStep;
import mage.constants.SubType;
import mage.constants.Zone;
import mage.counters.Counter;
import mage.counters.CounterType;
import mage.designations.DesignationType;
import mage.game.ExileZone;
import mage.game.Game;
import mage.game.combat.CombatGroup;
import mage.game.command.CommandObject;
import mage.game.command.Dungeon;
import mage.game.command.Emblem;
import mage.game.mulligan.LondonMulligan;
import mage.game.mulligan.Mulligan;
import mage.game.permanent.Permanent;
import mage.game.permanent.PermanentToken;
import mage.game.stack.Spell;
import mage.game.stack.StackAbility;
import mage.game.stack.StackObject;
import mage.player.spellbench.ids.ObjectIds;
import mage.players.ManaPoolItem;
import mage.players.Player;
import mage.target.Target;
import mage.target.TargetAmount;
import mage.target.TargetImpl;
import mage.util.CardUtil;
import mage.watchers.common.TemptedByTheRingWatcher;

import java.lang.reflect.Field;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * The Section 6 observation of one XMage game for either seat (task X3; design D4).
 * <p>
 * Built on XMage's own client-view visibility, never CABT's serializer: a face-down object shows its name only to
 * its controller (battlefield, stack) or owner (exile), as {@code CardView} and {@code CardUtil.canShowAsControlled}
 * decide; the other seat's hand and both libraries appear only as counts, plus the cards the current decision
 * shows (Section 6.7 with {@code known_cards} false); XMage's sideboard is never loaded.
 * <p>
 * Object ids are per viewer (Section 5.3, {@link ViewerIds}): the builder owns the look counters and incarnations,
 * so one builder serves one game, for both seats. Read the game only while its thread is parked at a prompt.
 * Every optional field follows the declared flags (Section 6.9); a flag this builder does not implement must be
 * false.
 */
public final class ObservationBuilder {

    public static final List<String> SEATS = Collections.unmodifiableList(Arrays.asList("p0", "p1"));

    static final List<String> FLAGS = Collections.unmodifiableList(Arrays.asList(
            "poison", "player_counters", "designations", "player_progress", "day_night", "passed_seats",
            "pending_triggers", "keywords", "full_name", "exiled_by", "stack_text", "permanent_details",
            "known_cards"));

    /** Flags whose fields this builder can fill; the others are declared false and always null. */
    static final List<String> IMPLEMENTED = Collections.unmodifiableList(Arrays.asList(
            "poison", "designations", "exiled_by", "permanent_details", "player_progress", "day_night", "passed_seats", "pending_triggers", "keywords", "full_name", "stack_text"));

    private static final Field TARGET_ZCC = field(TargetImpl.class, "zoneChangeCounters");
    private static final Field LONDON_STARTING = field(LondonMulligan.class, "startingHandSizes");
    private static final Field LONDON_OPENING = field(LondonMulligan.class, "openingHandSizes");
    private static final Field FREE_USED = field(Mulligan.class, "usedFreeMulligans");

    private final Game game;
    private final Map<String, Boolean> flags = new HashMap<>();
    private final List<UUID> players;
    private final Map<UUID, String> seatOf = new HashMap<>();
    private final Map<String, ViewerIds> viewers = new HashMap<>();
    // exile arrival order by internal key, per viewer: ordered by that viewer's own observations only
    private final Map<String, Map<String, Long>> arrival = new HashMap<>();

    /**
     * @param flags the declared {@code hello_ok.observation} flags (all thirteen of Section 6.9)
     * @param p0    XMage's player id for seat p0
     * @param p1    XMage's player id for seat p1
     */
    public ObservationBuilder(Game game, ObjectIds ids, Map<String, ?> flags, UUID p0, UUID p1) {
        this.game = game;
        for (String f : FLAGS) {
            Object v = flags.get(f);
            if (!(v instanceof Boolean)) {
                throw new IllegalArgumentException("observation flag " + f + " is not declared");
            }
            if ((Boolean) v && !IMPLEMENTED.contains(f)) {
                throw new IllegalArgumentException("observation flag " + f + " is true but not implemented");
            }
            this.flags.put(f, (Boolean) v);
        }
        if (flags.size() != FLAGS.size()) {
            throw new IllegalArgumentException("observation flags must be exactly the thirteen of Section 6.9");
        }
        this.players = Arrays.asList(p0, p1);
        seatOf.put(p0, "p0");
        seatOf.put(p1, "p1");
        viewers.put("p0", new ViewerIds(ids, "p0"));
        viewers.put("p1", new ViewerIds(ids, "p1"));
        arrival.put("p0", new HashMap<>());
        arrival.put("p1", new HashMap<>());
    }

    /**
     * The builder for a game whose seats are XMage players named {@code p0} and {@code p1}, as
     * {@code GameSession} and the CABT session name them.
     */
    public static ObservationBuilder forSession(Game game, byte[] gameSecret, Map<String, ?> flags) {
        UUID[] seats = new UUID[2];
        for (UUID id : game.getPlayers().keySet()) {
            int k = SEATS.indexOf(game.getPlayer(id).getName());
            if (k < 0 || seats[k] != null) {
                throw new IllegalArgumentException("players must be named p0 and p1");
            }
            seats[k] = id;
        }
        if (seats[0] == null || seats[1] == null) {
            throw new IllegalArgumentException("players must be named p0 and p1");
        }
        return new ObservationBuilder(game, new ObjectIds(gameSecret), flags, seats[0], seats[1]);
    }

    /**
     * Builds {@code viewer}'s observation. Each call is one observation of that viewer's stream: ids that leave it
     * are never reissued, and a look lasts while consecutive observations show the card.
     *
     * @param prioritySeat the seat holding priority, or null (see {@link PriorityHolder}); null in pregame
     * @param looks        cards in zones hidden from the viewer that the current decision shows (see {@link Looks})
     */
    public Observation build(String viewer, String prioritySeat, List<Look> looks) throws ObservationException {
        if (!SEATS.contains(viewer)) {
            throw new IllegalArgumentException("viewer must be p0 or p1");
        }
        ViewerIds v = viewers.get(viewer);
        v.begin();
        Pass pass = new Pass(viewer, players.get(SEATS.indexOf(viewer)), v);
        Map<String, Object> obs;
        try {
            obs = pass.observation(prioritySeat, looks);
        } catch (RuntimeException e) {
            throw new ObservationException("engine_error", e.toString()); // fail closed: no partial observation
        }
        v.commit();
        return new Observation(obs, pass.held, seatOf, pass.audit);
    }

    /** One build: the viewer, its ids, and the objects held so far. */
    private final class Pass {
        final String viewer;
        final UUID viewerId;
        final ViewerIds ids;
        final Map<UUID, Map<String, Object>> held = new HashMap<>();
        final List<Map<String, Object>> audit = new ArrayList<>();
        final List<Object[]> permanents = new ArrayList<>(); // {Permanent, its permanent map}
        final List<Object[]> exiledCards = new ArrayList<>(); // {Card, its record}
        final List<Object[]> stackEntries = new ArrayList<>(); // {StackObject, its entry}

        Pass(String viewer, UUID viewerId, ViewerIds ids) {
            this.viewer = viewer;
            this.viewerId = viewerId;
            this.ids = ids;
        }

        Map<String, Object> observation(String prioritySeat, List<Look> looks) throws ObservationException {
            PhaseStep step = game.getTurnStepType();
            boolean pregame = step == null;
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("viewer", viewer);
            o.put("turn", pregame ? 0 : game.getTurnNum());
            o.put("phase_step", pregame ? "pregame" : Vocabulary.phaseStep(step));
            o.put("active_seat", seat(pregame ? game.getStartingPlayerId() : game.getActivePlayerId()));
            o.put("priority_seat", pregame ? null : prioritySeat);
            o.put("passed_seats", flag("passed_seats") ? passedSeats() : null);
            o.put("day_night", flag("day_night") ? dayNight() : null);
            List<Object> playerList = new ArrayList<>();
            for (int k = 0; k < 2; k++) {
                playerList.add(player(k));
            }
            o.put("players", playerList);
            List<Object> stack = stack();
            o.put("stack", stack);
            List<Object> known = known(looks);
            // references inside the observation, once every held object exists (Section 5.1)
            for (Object[] p : permanents) {
                fillPermanent((Permanent) p[0], castMap(p[1]));
            }
            for (Object[] e : exiledCards) {
                castMap(e[1]).put("exiled_by", exiledBy((Card) e[0]));
            }
            for (Object[] s : stackEntries) {
                fillStackEntry((StackObject) s[0], castMap(s[1]));
            }
            o.put("pending_triggers", flag("pending_triggers") ? pendingTriggers() : null);
            o.put("known", known);
            return o;
        }

        // ---------------------------------------------------------------------------------------------
        // players and zones (Section 6.3)

        Map<String, Object> player(int k) throws ObservationException {
            UUID id = players.get(k);
            String seat = SEATS.get(k);
            Player p = game.getPlayer(id);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("seat", seat);
            m.put("life", p.getLife());
            m.put("poison", flag("poison") ? p.getCountersCount(CounterType.POISON) : null);
            m.put("counters", null);
            m.put("mana_pool", manaPool(p));
            m.put("lands_played_this_turn", Math.max(0, p.getLandsPlayed()));
            m.put("mulligans_taken", mulligansTaken(id));
            m.put("designations", flag("designations") ? designations(p) : null);
            m.put("progress", flag("player_progress") ? progress(p) : null);
            m.put("hand_count", p.getHand().size());
            m.put("library_count", p.getLibrary().size());
            if (id.equals(viewerId)) {
                List<Object> hand = new ArrayList<>();
                for (Card c : p.getHand().getCards(game)) {
                    hand.add(cardRecord(c, "hand", seat));
                }
                m.put("hand", hand);
            } else {
                m.put("hand", null);
            }
            List<Object> battlefield = new ArrayList<>();
            for (Permanent perm : game.getBattlefield().getAllPermanents()) {
                if (id.equals(perm.getControllerId())) {
                    battlefield.add(permanentRecord(perm));
                }
            }
            m.put("battlefield", battlefield);
            List<Object> graveyard = new ArrayList<>();
            for (Card c : p.getGraveyard().getCards(game)) {
                graveyard.add(cardRecord(c, "graveyard", seat));
            }
            m.put("graveyard", graveyard);
            List<Object> exile = new ArrayList<>();
            for (Card c : exiled(id)) {
                exile.add(cardRecord(c, "exile", seat));
            }
            m.put("exile", exile);
            List<Object> command = new ArrayList<>();
            for (CommandObject c : game.getState().getCommand()) {
                if (id.equals(c.getControllerId())) {
                    command.add(commandRecord(c, seat));
                }
            }
            m.put("command", command);
            return m;
        }

        Map<String, Object> manaPool(Player p) {
            int[] wubrgc = new int[6];
            for (ManaPoolItem item : p.getManaPool().getManaItems()) {
                Mana mana = item.isConditional() ? item.getConditionalMana() : item.getMana();
                wubrgc[0] += mana.getWhite();
                wubrgc[1] += mana.getBlue();
                wubrgc[2] += mana.getBlack();
                wubrgc[3] += mana.getRed();
                wubrgc[4] += mana.getGreen();
                wubrgc[5] += mana.getColorless();
            }
            Map<String, Object> m = new LinkedHashMap<>();
            String[] symbols = {"W", "U", "B", "R", "G", "C"};
            for (int i = 0; i < 6; i++) {
                m.put(symbols[i], Math.max(0, wubrgc[i]));
            }
            return m;
        }

        /** The designations this player has (Section 6.10), in a fixed order. */
        List<Object> designations(Player p) {
            List<Object> out = new ArrayList<>();
            if (p.getId().equals(game.getMonarchId())) {
                out.add("monarch");
            }
            if (p.getId().equals(game.getInitiativeId())) {
                out.add("initiative");
            }
            if (p.hasDesignation(DesignationType.CITYS_BLESSING)) {
                out.add("city_blessing");
            }
            return out;
        }

        Map<String, Object> progress(Player p) {
            Map<String, Object> m = new LinkedHashMap<>();
            Dungeon dungeon = game.getPlayerDungeon(p.getId());
            m.put("dungeon", dungeon == null ? null : nfc(dungeon.getName()));
            m.put("dungeon_room", dungeon == null || dungeon.getCurrentRoom() == null ? null
                    : dungeon.getCurrentRoom().getName());
            int tempted;
            try {
                tempted = TemptedByTheRingWatcher.getCount(p.getId(), game);
            } catch (RuntimeException e) {
                tempted = 0; // no watcher: nobody was tempted
            }
            m.put("ring_tempted", Math.max(0, tempted));
            m.put("speed", p.getSpeed() > 0 ? p.getSpeed() : null); // 0 is XMage's "no speed" (CR 702.179)
            return m;
        }

        List<Object> passedSeats() {
            List<Object> out = new ArrayList<>();
            for (int k = 0; k < 2; k++) {
                Player p = game.getPlayer(players.get(k));
                if (p != null && p.isPassed()) {
                    out.add(SEATS.get(k));
                }
            }
            return out;
        }

        String dayNight() {
            if (!game.hasDayNight()) {
                return "none";
            }
            return game.checkDayNight(true) ? "day" : "night";
        }

        /**
         * A seat's exiled cards, oldest first. XMage's exile zones are a hash map, so arrival is the order in which
         * this viewer's observations first showed each card; cards first shown together arrive in (visible name, a
         * per-viewer keyed hash of the card) order, never in XMage's hash map order (X4h).
         */
        List<Card> exiled(UUID ownerId) {
            Map<String, Long> arrival = ObservationBuilder.this.arrival.get(viewer);
            List<Card> cards = new ArrayList<>();
            List<Object[]> fresh = new ArrayList<>(); // {key, name, tie}
            for (ExileZone zone : game.getExile().getExileZones()) {
                for (Card c : zone.getCards(game)) {
                    String key = key(c.getId(), c.getZoneChangeCounter(game));
                    if (!arrival.containsKey(key)) {
                        String name = visibleCardName(c, "exile");
                        fresh.add(new Object[]{key, name == null ? "" : name, ids.tieKey(key)});
                        arrival.put(key, -1L);
                    }
                    if (ownerId.equals(c.getOwnerId())) {
                        cards.add(c);
                    }
                }
            }
            fresh.sort((a, b) -> {
                int k = Vocabulary.CODE_POINT.compare((String) a[1], (String) b[1]);
                return k != 0 ? k : ((String) a[2]).compareTo((String) b[2]);
            });
            long next = 0;
            for (Long v : arrival.values()) {
                next = Math.max(next, v + 1);
            }
            for (Object[] f : fresh) {
                arrival.put((String) f[0], next++);
            }
            cards.sort(Comparator.comparingLong(c -> arrival.get(key(c.getId(), c.getZoneChangeCounter(game)))));
            return cards;
        }

        // ---------------------------------------------------------------------------------------------
        // object records (Section 6.4)

        String visibleCardName(Card c, String zone) {
            boolean faceDown = zone.equals("exile") && c.isFaceDown(game);
            return !faceDown || CardUtil.canShowAsControlled(c, viewerId)
                    ? nfc(faceDown ? frontName(c.getMainCard()) : c.getName()) : null;
        }

        Map<String, Object> cardRecord(Card c, String zone, String ownerSeat) throws ObservationException {
            boolean faceDown = zone.equals("exile") && c.isFaceDown(game);
            boolean named = !faceDown || CardUtil.canShowAsControlled(c, viewerId); // D4: its owner, in exile
            String key = key(c.getId(), c.getZoneChangeCounter(game));
            String name = visibleCardName(c, zone);
            Map<String, Object> r = reference(key, zone, name, ownerSeat, ownerSeat, c.getId());
            for (UUID part : partIds(c)) {
                held.put(part, held.get(c.getId())); // XMage names a face or half by its own id (adventure spells)
            }
            r.put("full_name", flag("full_name") && named ? fullName(c.getMainCard(), name) : null);
            r.put("face_down", faceDown);
            r.put("token", false);
            r.put("copy", c.isCopy());
            r.put("characteristics", named ? characteristics(c, c.getAbilities(game)) : null);
            r.put("permanent", null);
            r.put("exiled_by", null);
            if (zone.equals("exile") && flag("exiled_by")) {
                exiledCards.add(new Object[]{c, r}); // filled once every object is held
            }
            return r;
        }

        /**
         * The permanent whose linked exile zone holds this card (it may return it), else null. XMage keeps a source's
         * zone id in the game state under its {@code SourceExileZone} key; it is read, never created.
         */
        Map<String, Object> exiledBy(Card c) {
            ExileZone zone = null;
            for (ExileZone z : game.getExile().getExileZones()) {
                if (z.contains(c.getId())) {
                    zone = z;
                }
            }
            if (zone == null) {
                return null;
            }
            for (Permanent p : game.getBattlefield().getAllPermanents()) {
                Object linked = game.getState().getValue(CardUtil.getObjectZoneString(CardUtil.SOURCE_EXILE_ZONE_TEXT,
                        p.getId(), game, p.getZoneChangeCounter(game), false));
                Map<String, Object> ref = held.get(p.getId());
                if (zone.getId().equals(linked) && ref != null) {
                    return new LinkedHashMap<>(ref);
                }
            }
            return null;
        }

        /** The permanent's public statuses (Section 6.10) that XMage keeps, in the spec's order. */
        List<Object> statuses(Permanent perm) {
            List<Object> out = new ArrayList<>();
            if (!perm.getGoadingPlayers().isEmpty()) {
                out.add("goaded");
            }
            if (perm.isSuspected()) {
                out.add("suspected");
            }
            if (perm.isMonstrous()) {
                out.add("monstrous");
            }
            if (perm.isRenowned()) {
                out.add("renowned");
            }
            return out;
        }

        /** The values chosen for this permanent under XMage's common keys (ChooseColorEffect, ChooseCreatureTypeEffect). */
        List<Object> chosen(Permanent perm) throws ObservationException {
            List<Object> out = new ArrayList<>();
            Object color = game.getState().getValue(perm.getId() + "_color");
            if (color instanceof ObjectColor) {
                for (Object c : Vocabulary.colors((ObjectColor) color)) {
                    out.add(chosenValue("color", c));
                }
            }
            Object type = game.getState().getValue(perm.getId() + "_type");
            if (type instanceof SubType) {
                out.add(chosenValue("creature_type", Vocabulary.normalizeName(((SubType) type).getDescription(), "subtype")));
            }
            return out;
        }

        Map<String, Object> chosenValue(String kind, Object value) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("kind", kind);
            m.put("value", value);
            return m;
        }

        Map<String, Object> permanentRecord(Permanent perm) throws ObservationException {
            boolean faceDown = perm.isFaceDown(game);
            boolean named = !faceDown || CardUtil.canShowAsControlled(perm, viewerId); // its controller, CR 708.5
            String name = null;
            if (named) {
                name = nfc(faceDown ? realName(perm) : perm.getName());
            }
            String key = key(perm.getId(), perm.getZoneChangeCounter(game));
            Map<String, Object> r = reference(key, "battlefield", name, seat(perm.getOwnerId()),
                    seat(perm.getControllerId()), perm.getId());
            boolean token = perm instanceof PermanentToken;
            r.put("full_name", flag("full_name") && named && !token ? fullName(perm.getMainCard(), name) : null);
            r.put("face_down", faceDown);
            r.put("token", token);
            r.put("copy", perm.isCopy());
            r.put("characteristics", faceDown ? faceDownCharacteristics(perm, perm.getAbilities(game), named)
                    : characteristics(perm, perm.getAbilities(game)));
            Map<String, Object> pm = new LinkedHashMap<>();
            pm.put("tapped", perm.isTapped());
            pm.put("summoning_sick", !perm.wasControlledFromStartOfControllerTurn());
            pm.put("damage", Math.max(0, perm.getDamage()));
            pm.put("counters", counters(perm.getCounters(game).values()));
            pm.put("attached_to", null);       // filled once every object is held
            pm.put("attacking", perm.isAttacking());
            pm.put("attack_target", null);
            pm.put("blocking", perm.getBlocking() > 0);
            pm.put("blocked_attackers", new ArrayList<Object>());
            pm.put("phased_out", !perm.isPhasedIn());
            boolean details = flag("permanent_details");
            pm.put("statuses", details ? statuses(perm) : null);
            pm.put("class_level", details && perm.hasSubtype(SubType.CLASS, game) ? perm.getClassLevel() : null);
            pm.put("chosen", details ? chosen(perm) : null);
            r.put("permanent", pm);
            r.put("exiled_by", null);
            permanents.add(new Object[]{perm, pm});
            return r;
        }

        void fillPermanent(Permanent perm, Map<String, Object> pm) {
            if (perm.getAttachedTo() != null) {
                pm.put("attached_to", target(perm.getAttachedTo(), null));
            }
            List<Object> blocked = new ArrayList<>();
            if (game.getCombat() != null) {
                for (CombatGroup group : game.getCombat().getGroups()) {
                    if (perm.isAttacking() && group.getAttackers().contains(perm.getId())) {
                        pm.put("attack_target", group.getDefenderId() == null ? null
                                : target(group.getDefenderId(), null));
                    }
                    if (group.getBlockers().contains(perm.getId())) {
                        for (UUID attacker : group.getAttackers()) {
                            Map<String, Object> ref = held.get(attacker);
                            if (ref != null && "battlefield".equals(ref.get("zone"))) {
                                blocked.add(new LinkedHashMap<>(ref));
                            }
                        }
                    }
                }
            }
            if (perm.getBlocking() > 0) {
                pm.put("blocked_attackers", blocked);
            }
        }

        Map<String, Object> commandRecord(CommandObject c, String seat) throws ObservationException {
            String name = c.getName();
            if (c instanceof Emblem && ((Emblem) c).getSourceObject() != null) {
                name = ((Emblem) c).getSourceObject().getName() + " Emblem"; // the Scryfall emblem name
            }
            Map<String, Object> r = reference(key(c.getId(), 0), "command", nfc(name), seat, seat, c.getId());
            r.put("full_name", null);
            r.put("face_down", false);
            r.put("token", false);
            r.put("copy", false);
            r.put("characteristics", characteristics(c, c.getAbilities()));
            r.put("permanent", null);
            r.put("exiled_by", null);
            return r;
        }

        Map<String, Object> characteristics(MageObject o, Iterable<Ability> abilities) throws ObservationException {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("supertypes", Vocabulary.supertypes(o.getSuperType(game)));
            List<Object> types = Vocabulary.cardTypes(o.getCardType(game));
            m.put("types", types);
            m.put("subtypes", Vocabulary.subtypes(o.getSubtype(game)));
            m.put("colors", Vocabulary.colors(o.getColor(game)));
            m.put("mana_value", Math.max(0, o.getManaValue()));
            boolean creature = types.contains("creature");
            m.put("power", creature ? o.getPower().getValue() : null);
            m.put("toughness", creature ? o.getToughness().getValue() : null);
            m.put("keywords", flag("keywords") ? Vocabulary.keywords(abilities) : null);
            return m;
        }

        /**
         * CR 708.2a: a nameless, colorless 2/2 creature with no mana cost; power and toughness current. A seat that
         * may not look at it sees at most ward among its keywords, the one keyword the rules give a face-down object
         * (validator V6): a keyword a public effect grants it is withheld from that seat (an open question for P).
         */
        Map<String, Object> faceDownCharacteristics(MageObject o, Iterable<Ability> abilities, boolean named)
                throws ObservationException {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("supertypes", new ArrayList<Object>());
            m.put("types", new ArrayList<Object>(Collections.singletonList("creature")));
            m.put("subtypes", new ArrayList<Object>());
            m.put("colors", new ArrayList<Object>());
            m.put("mana_value", 0);
            m.put("power", o.getPower().getValue());
            m.put("toughness", o.getToughness().getValue());
            List<Object> keywords = flag("keywords") ? Vocabulary.keywords(abilities) : null;
            if (keywords != null && !named) {
                keywords.retainAll(Collections.singletonList("ward"));
            }
            m.put("keywords", keywords);
            return m;
        }

        Map<String, Object> counters(Iterable<Counter> counters) throws ObservationException {
            TreeMap<String, Object> m = new TreeMap<>(Vocabulary.CODE_POINT);
            for (Counter c : counters) {
                if (c.getCount() > 0) {
                    String name = Vocabulary.counter(c.getName());
                    Object before = m.get(name);
                    m.put(name, (before == null ? 0 : (Integer) before) + c.getCount());
                }
            }
            return new LinkedHashMap<>(m);
        }

        // ---------------------------------------------------------------------------------------------
        // the stack (Section 6.5)

        List<Object> stack() throws ObservationException {
            List<StackObject> topFirst = new ArrayList<>(game.getStack());
            Collections.reverse(topFirst); // index 0 is the bottom
            List<Object> out = new ArrayList<>();
            for (StackObject so : topFirst) {
                out.add(so instanceof Spell ? spellEntry((Spell) so) : abilityEntry((StackAbility) so));
            }
            return out;
        }

        Map<String, Object> spellEntry(Spell spell) throws ObservationException {
            boolean faceDown = spell.isFaceDown(game);
            boolean named = !faceDown || CardUtil.canShowAsControlled(spell, viewerId); // its controller, CR 708.5
            String name = named ? nfc(faceDown ? frontName(spell.getCard().getMainCard()) : spell.getName()) : null;
            String key = key(spell.getId(), spell.getZoneChangeCounter(game));
            Map<String, Object> e = reference(key, "stack", name, seat(spell.getOwnerId()),
                    seat(spell.getControllerId()), spell.getId());
            // a candidate naming the spell's card (its source id) means the spell
            if (spell.getSourceId() != null && !held.containsKey(spell.getSourceId())) {
                held.put(spell.getSourceId(), held.get(spell.getId()));
            }
            e.put("stack_kind", "spell");
            e.put("source", null);
            e.put("face_down", faceDown);
            e.put("copy", spell.isCopy());
            Card chars = spell.getSpellAbility() == null ? null : spell.getSpellAbility().getCharacteristics(game);
            MageObject o = chars == null ? spell : chars;
            Iterable<Ability> abilities = chars == null ? spell.getAbilities(game) : chars.getAbilities(game);
            e.put("characteristics", faceDown ? faceDownCharacteristics(o, abilities, named)
                    : characteristics(o, abilities));
            e.put("targets", new ArrayList<Object>());
            e.put("divided", null);
            e.put("modes", null);
            e.put("x_value", null);
            e.put("text", null);
            stackEntries.add(new Object[]{spell, e});
            return e;
        }

        Map<String, Object> abilityEntry(StackAbility sa) throws ObservationException {
            String key = key(sa.getId(), 0);
            String controller = seat(sa.getControllerId());
            Map<String, Object> e = reference(key, "stack", sourceName(sa.getSourceId()), controller, controller,
                    sa.getId());
            AbilityType type = sa.getStackAbility().getAbilityType();
            boolean triggered = type == AbilityType.TRIGGERED_NONMANA || type == AbilityType.TRIGGERED_MANA
                    || sa.getStackAbility() instanceof TriggeredAbility;
            e.put("stack_kind", triggered ? "triggered_ability" : "activated_ability");
            e.put("source", null);
            e.put("face_down", false);
            e.put("copy", sa.isCopy());
            e.put("characteristics", null);
            e.put("targets", new ArrayList<Object>());
            e.put("divided", null);
            e.put("modes", null);
            e.put("x_value", null);
            e.put("text", null);
            stackEntries.add(new Object[]{sa, e});
            return e;
        }

        void fillStackEntry(StackObject so, Map<String, Object> e) {
            boolean hidden = Boolean.TRUE.equals(e.get("face_down")) && e.get("card_name") == null;
            List<Ability> abilities = new ArrayList<>();
            if (so instanceof Spell) {
                abilities.addAll(((Spell) so).getSpellAbilities());
            } else {
                StackAbility sa = (StackAbility) so;
                abilities.add(sa.getStackAbility());
                MageObject source = sa.getSourceObjectIfItStillExists(game);
                if (source != null) {
                    Map<String, Object> ref = held.get(source.getId());
                    e.put("source", ref == null ? null : new LinkedHashMap<>(ref));
                }
            }
            if (hidden || abilities.isEmpty()) {
                return; // a face-down spell another seat controls shows no choices (Section 6.8)
            }
            List<Object> targets = new ArrayList<>();
            List<Object> divided = new ArrayList<>();
            for (Ability ability : abilities) {
                Modes modes = ability.getModes();
                for (UUID modeId : modes.getSelectedModes()) {
                    Mode mode = modes.get(modeId);
                    if (mode == null) {
                        continue;
                    }
                    for (Target t : mode.getTargets()) {
                        for (UUID id : t.getTargets()) {
                            targets.add(target(id, t));
                            if (t instanceof TargetAmount) {
                                divided.add(Math.max(0, t.getTargetAmount(id)));
                            }
                        }
                    }
                }
            }
            e.put("targets", targets);
            e.put("divided", divided.isEmpty() ? null : divided);
            Ability main = abilities.get(0);
            if (flag("stack_text")) {
                // Only an ability's printed rule identifies a reconstructed
                // trigger. Face-down spells hidden from the viewer returned
                // above; no game state is consulted by this renderer.
                e.put("text", StackAbilityText.of(main));
            }
            Modes modes = main.getModes();
            if (modes.size() > 1) {
                List<Object> chosen = new ArrayList<>();
                int index = 0;
                for (Mode mode : modes.values()) {
                    for (int n = modes.getSelectedStats(mode.getId()); n > 0; n--) {
                        chosen.add(index);
                    }
                    index++;
                }
                e.put("modes", chosen);
            }
            Map<String, Object> tags = main.getCostsTagMap();
            Object x = tags == null ? null : tags.get("X");
            if (x instanceof Integer) {
                e.put("x_value", Math.max(0, (Integer) x));
            }
        }

        // ---------------------------------------------------------------------------------------------
        // pending triggers (Section 6.6) and knowledge (Section 6.7)

        List<Object> pendingTriggers() {
            List<Object> out = new ArrayList<>();
            UUID active = game.getActivePlayerId();
            List<UUID> order = new ArrayList<>(players);
            if (active != null && order.indexOf(active) == 1) {
                Collections.reverse(order); // APNAP
            }
            for (UUID controller : order) {
                for (TriggeredAbility t : game.getState().getTriggered(controller)) {
                    UUID sourceId = t.getSourceId();
                    Map<String, Object> source = null;
                    String sourceName = null;
                    if (sourceId != null) {
                        if (hiddenFromViewer(sourceId)) {
                            continue; // its source is in a zone hidden from the viewer and not shown
                        }
                        MageObject still = t.getSourceObjectIfItStillExists(game);
                        Map<String, Object> ref = still == null ? null : held.get(still.getId());
                        source = ref == null ? null : new LinkedHashMap<>(ref);
                        sourceName = source != null ? (String) source.get("card_name") : sourceName(sourceId);
                    }
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("source", source);
                    m.put("source_name", sourceName);
                    m.put("controller_seat", seat(controller));
                    m.put("label", null);
                    m.put("optional", t.isOptional());
                    out.add(m);
                }
            }
            return out;
        }

        List<Object> known(List<Look> looks) throws ObservationException {
            List<Map<String, Object>> entries = new ArrayList<>();
            for (Look look : looks) {
                Card c = game.getCard(look.cardId);
                if (c == null || c.getMainCard() == null || held.containsKey(look.cardId)
                        || held.containsKey(c.getMainCard().getId())) {
                    continue;
                }
                c = c.getMainCard(); // a look names the card, whichever face or half the prompt named
                Zone zone = game.getState().getZone(c.getId());
                String zoneName;
                if (zone == Zone.LIBRARY) {
                    zoneName = "library";
                    if (look.fromTop == null && look.fromBottom == null && !look.how.equals("searching")) {
                        throw new ObservationException("malformed_look", "a library look needs a position");
                    }
                } else if (zone == Zone.HAND && !viewerId.equals(c.getOwnerId())) {
                    zoneName = "hand";
                    if (look.fromTop != null || look.fromBottom != null) {
                        throw new ObservationException("malformed_look", "a hand look has no position");
                    }
                } else {
                    continue; // not hidden from the viewer: its record is in a zone array
                }
                String owner = seat(c.getOwnerId());
                String key = key(c.getId(), c.getZoneChangeCounter(game));
                String id = ids.look(key);
                String name = nfc(c.getName());
                Map<String, Object> ref = new LinkedHashMap<>();
                ref.put("object_id", id);
                ref.put("card_name", name);
                ref.put("owner_seat", owner);
                ref.put("controller_seat", owner);
                ref.put("zone", zoneName);
                held.put(c.getId(), ref);
                held.put(look.cardId, ref);
                for (UUID part : partIds(c)) {
                    held.put(part, ref);
                }
                auditEntry(id, key, zoneName, true);
                Map<String, Object> k = new LinkedHashMap<>();
                k.put("owner_seat", owner);
                k.put("zone", zoneName);
                k.put("card_name", name);
                k.put("object_id", id);
                k.put("position_from_top", look.fromTop);
                k.put("position_from_bottom", look.fromBottom);
                k.put("how", look.how);
                entries.add(k);
            }
            entries.sort(ObservationBuilder::compareKnown);
            return new ArrayList<Object>(entries);
        }

        // ---------------------------------------------------------------------------------------------
        // references (Section 5)

        /** Mints the id and holds the reference for an object in a zone the viewer sees. */
        Map<String, Object> reference(String key, String zone, String name, String owner, String controller,
                                      UUID xmageId) throws ObservationException {
            String id = ids.visible(key, zone);
            Map<String, Object> ref = new LinkedHashMap<>();
            ref.put("object_id", id);
            ref.put("card_name", name);
            ref.put("owner_seat", owner);
            ref.put("controller_seat", controller);
            ref.put("zone", zone);
            held.put(xmageId, new LinkedHashMap<>(ref));
            auditEntry(id, key, zone, false);
            return ref;
        }

        void auditEntry(String id, String key, String zone, boolean look) {
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("object_id", id);
            a.put("key", key);
            a.put("zone", zone);
            a.put("look", look);
            audit.add(a);
        }

        /**
         * A target reference (Section 5.2). An object target that changed zones since it was chosen, or that the
         * observation does not hold, is null (Section 5.1).
         */
        Map<String, Object> target(UUID id, Target chosenBy) {
            Map<String, Object> t = new LinkedHashMap<>();
            String seat = seatOf.get(id);
            if (seat != null) {
                t.put("player", seat);
                return t;
            }
            if (chosenBy instanceof TargetImpl && TARGET_ZCC != null) {
                Integer remembered = rememberedZcc((TargetImpl) chosenBy, id);
                Card card = game.getCard(id);
                if (remembered != null && card != null && remembered != card.getZoneChangeCounter(game)) {
                    return null;
                }
            }
            Map<String, Object> ref = held.get(id);
            if (ref == null) {
                return null;
            }
            t.put("object", new LinkedHashMap<>(ref));
            return t;
        }

        /** True for an object in a library, or in the other seat's hand, that the observation does not show. */
        boolean hiddenFromViewer(UUID id) {
            if (held.containsKey(id)) {
                return false;
            }
            Zone zone = game.getState().getZone(id);
            if (zone == Zone.LIBRARY) {
                return true;
            }
            if (zone == Zone.HAND) {
                Card c = game.getCard(id);
                return c == null || !viewerId.equals(c.getOwnerId());
            }
            return false;
        }

        /**
         * The name an ability takes from its source (Section 5.1): the source as it is, or as it last was on the
         * battlefield; null when the source is face down and hidden from the viewer, or unknown.
         */
        String sourceName(UUID sourceId) {
            if (sourceId == null) {
                return null;
            }
            Map<String, Object> ref = held.get(sourceId);
            if (ref != null) {
                return (String) ref.get("card_name");
            }
            MageObject o = game.getPermanent(sourceId);
            if (o == null) {
                o = game.getObject(sourceId);
            }
            if (o == null) {
                o = game.getLastKnownInformation(sourceId, Zone.BATTLEFIELD);
            }
            if (o == null) {
                return null;
            }
            if (o instanceof Permanent) {
                Permanent p = (Permanent) o;
                if (p.isFaceDown(game)) {
                    return CardUtil.canShowAsControlled(p, viewerId) ? nfc(realName(p)) : null;
                }
                return nfc(p.getName());
            }
            if (o instanceof Spell) {
                Spell s = (Spell) o;
                if (s.isFaceDown(game)) {
                    return CardUtil.canShowAsControlled(s, viewerId) ? nfc(frontName(s.getCard().getMainCard())) : null;
                }
                return nfc(s.getName());
            }
            if (o instanceof Card) {
                Card c = (Card) o;
                if (game.getState().getZone(sourceId) == Zone.EXILED && c.isFaceDown(game)
                        && !CardUtil.canShowAsControlled(c, viewerId)) {
                    return null;
                }
                return nfc(c.getName());
            }
            if (o instanceof Emblem && ((Emblem) o).getSourceObject() != null) {
                return nfc(((Emblem) o).getSourceObject().getName() + " Emblem");
            }
            return o.getName() == null || o.getName().isEmpty() ? null : nfc(o.getName());
        }

        String seat(UUID playerId) {
            return playerId == null ? null : seatOf.get(playerId);
        }

        boolean flag(String name) {
            return flags.get(name);
        }
    }

    // -------------------------------------------------------------------------------------------------
    // names (Section 4.4)

    static String key(UUID id, int zoneChangeCounter) {
        return ObjectIds.internalKey(id, zoneChangeCounter);
    }

    /**
     * A name in NFC; an empty name is null. A face-up object can have no name: a copy of a face-down permanent
     * copies its nameless face-down values (CR 707.2, 708.2).
     */
    static String nfc(String name) {
        return name == null || name.isEmpty() ? null : Normalizer.normalize(name, Normalizer.Form.NFC);
    }

    /** The name a face-down card shows its controller or owner: its front face (CardView shows the main card). */
    static String frontName(Card main) {
        if (main instanceof ModalDoubleFacedCard) {
            return ((ModalDoubleFacedCard) main).getLeftHalfCard().getName();
        }
        return main.getName();
    }

    /** The ids of a multi-part card's faces or halves, which share its zone outside the battlefield and the stack. */
    static List<UUID> partIds(Card c) {
        List<UUID> out = new ArrayList<>();
        if (c instanceof SplitCard) {
            out.add(((SplitCard) c).getLeftHalfCard().getId());
            out.add(((SplitCard) c).getRightHalfCard().getId());
        } else if (c instanceof ModalDoubleFacedCard) {
            out.add(((ModalDoubleFacedCard) c).getLeftHalfCard().getId());
            out.add(((ModalDoubleFacedCard) c).getRightHalfCard().getId());
        } else if (c instanceof CardWithSpellOption) {
            out.add(((CardWithSpellOption) c).getSpellCard().getId());
        }
        out.remove(c.getId());
        return out;
    }

    /** A face-down permanent's own name, for its controller. */
    static String realName(Permanent perm) {
        if (perm instanceof PermanentToken) {
            return ((PermanentToken) perm).getToken().getName();
        }
        return frontName(perm.getMainCard());
    }

    /**
     * The Oracle full name {@code "A // B"} of a multi-face card, when {@code name} is one of its faces (a copy of
     * another card shows that card's name, not its own); null for a single-faced card.
     */
    static String fullName(Card main, String name) {
        if (main == null || name == null) {
            return null;
        }
        String full;
        List<String> faces = new ArrayList<>();
        if (main instanceof SplitCard) {
            full = main.getName();
            faces.addAll(Arrays.asList(full.split(" // ")));
        } else if (main instanceof ModalDoubleFacedCard) {
            ModalDoubleFacedCard d = (ModalDoubleFacedCard) main;
            faces.add(d.getLeftHalfCard().getName());
            faces.add(d.getRightHalfCard().getName());
            full = faces.get(0) + " // " + faces.get(1);
        } else if (main instanceof CardWithSpellOption) {
            faces.add(main.getName());
            faces.add(((CardWithSpellOption) main).getSpellCard().getName());
            full = faces.get(0) + " // " + faces.get(1);
        } else if (main.isTransformable() && main.getSecondCardFace() != null) {
            faces.add(main.getName());
            faces.add(main.getSecondCardFace().getName());
            full = faces.get(0) + " // " + faces.get(1);
        } else if (main.isFlipCard()) {
            faces.add(main.getName());
            faces.add(main.getFlipCardName());
            full = faces.get(0) + " // " + faces.get(1);
        } else {
            return null;
        }
        full = nfc(full);
        for (String face : faces) {
            if (name.equals(nfc(face))) {
                return full;
            }
        }
        return name.equals(full) ? full : null;
    }

    // -------------------------------------------------------------------------------------------------
    // helpers

    private int mulligansTaken(UUID playerId) throws ObservationException {
        Mulligan m = game.getMulligan();
        if (!(m instanceof LondonMulligan) || LONDON_STARTING == null || LONDON_OPENING == null || FREE_USED == null) {
            throw new ObservationException("unsupported_mulligan", String.valueOf(m));
        }
        try {
            Integer start = castInt(((Map<?, ?>) LONDON_STARTING.get(m)).get(playerId));
            Integer open = castInt(((Map<?, ?>) LONDON_OPENING.get(m)).get(playerId));
            Integer free = castInt(((Map<?, ?>) FREE_USED.get(m)).get(playerId));
            int taken = (start == null || open == null ? 0 : start - open) + (free == null ? 0 : free);
            return Math.max(0, taken);
        } catch (IllegalAccessException e) {
            throw new ObservationException("unsupported_mulligan", e.toString());
        }
    }

    private static Integer castInt(Object o) {
        return o instanceof Integer ? (Integer) o : null;
    }

    private static Integer rememberedZcc(TargetImpl target, UUID id) {
        try {
            return castInt(((Map<?, ?>) TARGET_ZCC.get(target)).get(id));
        } catch (IllegalAccessException e) {
            return null;
        }
    }

    private static Field field(Class<?> owner, String name) {
        try {
            Field f = owner.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (NoSuchFieldException | RuntimeException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object o) {
        return (Map<String, Object>) o;
    }

    /** Section 6.7 order: owner, zone, name (code points), top, bottom, how, id; nulls first. */
    static int compareKnown(Map<String, Object> a, Map<String, Object> b) {
        int c = Vocabulary.CODE_POINT.compare((String) a.get("owner_seat"), (String) b.get("owner_seat"));
        if (c == 0) {
            c = Vocabulary.CODE_POINT.compare((String) a.get("zone"), (String) b.get("zone"));
        }
        if (c == 0) {
            c = Vocabulary.CODE_POINT.compare((String) a.get("card_name"), (String) b.get("card_name"));
        }
        if (c == 0) {
            c = nullsFirst((Integer) a.get("position_from_top"), (Integer) b.get("position_from_top"));
        }
        if (c == 0) {
            c = nullsFirst((Integer) a.get("position_from_bottom"), (Integer) b.get("position_from_bottom"));
        }
        if (c == 0) {
            c = Vocabulary.CODE_POINT.compare((String) a.get("how"), (String) b.get("how"));
        }
        if (c == 0) {
            String x = (String) a.get("object_id");
            String y = (String) b.get("object_id");
            c = x == null ? (y == null ? 0 : -1) : (y == null ? 1 : Vocabulary.CODE_POINT.compare(x, y));
        }
        return c;
    }

    private static int nullsFirst(Integer x, Integer y) {
        if (x == null) {
            return y == null ? 0 : -1;
        }
        return y == null ? 1 : Integer.compare(x, y);
    }
}
