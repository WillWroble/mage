package spellbench.kit.xmage;

import mage.MageObject;
import mage.Mana;
import mage.abilities.Ability;
import mage.abilities.ActivatedAbility;
import mage.abilities.ActivatedAbilityImpl;
import mage.abilities.Modes;
import mage.abilities.SpellAbility;
import mage.abilities.TriggeredAbility;
import mage.abilities.common.SimpleStaticAbility;
import mage.abilities.common.delayed.ReflexiveTriggeredAbility;
import mage.abilities.effects.ContinuousEffect;
import mage.abilities.effects.common.InfoEffect;
import mage.abilities.effects.common.TapTargetEffect;
import mage.abilities.keyword.FlashbackAbility;
import mage.abilities.keyword.KickerAbility;
import mage.cards.Card;
import mage.cards.CardWithSpellOption;
import mage.cards.MeldCard;
import mage.cards.decks.Deck;
import mage.cards.repository.TokenInfo;
import mage.cards.repository.TokenRepository;
import mage.cards.repository.TokenType;
import mage.constants.CardType;
import mage.constants.AbilityType;
import mage.constants.PhaseStep;
import mage.constants.TurnPhase;
import mage.constants.Zone;
import mage.counters.Counter;
import mage.counters.CounterType;
import mage.game.Game;
import mage.game.GameOptions;
import mage.game.GameState;
import mage.game.combat.Combat;
import mage.game.events.ZoneChangeEvent;
import mage.game.mulligan.LondonMulligan;
import mage.game.permanent.Permanent;
import mage.game.permanent.PermanentCard;
import mage.game.permanent.PermanentImpl;
import mage.game.permanent.PermanentMeld;
import mage.game.permanent.PermanentToken;
import mage.game.permanent.token.Token;
import mage.game.stack.Spell;
import mage.game.stack.StackAbility;
import mage.game.stack.StackObject;
import mage.game.turn.Phase;
import mage.game.turn.Step;
import mage.game.turn.Turn;
import mage.game.turn.TurnMod;
import mage.player.cabt.CardResolver;
import mage.player.spellbench.observe.KitBridge;
import mage.players.Player;
import mage.players.PlayerImpl;
import mage.target.Target;
import mage.target.TargetAmount;
import mage.target.common.TargetOpponentsCreaturePermanent;
import spellbench.kit.core.Json;
import spellbench.kit.core.Sampler;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Builds a world from the viewer's permitted inputs (design Section 3): the observation of the current decision, the
 * decklists of {@code game_start}, and one sample of the hidden zones. Ports mzbridge's event-free injection
 * (DraftZero, MIT: {@code StateInjector}) to v2 input: init() only, zones filled without events, the turn position
 * set by reflection, triggers, queued events and watcher history cleared. Visible objects take their UUIDs from the
 * id scope of their object id ({@link KitRandom#scopeObject}).
 */
public final class WorldBuilder {

    /** Creates the XMage player of one seat (the decider for the viewer, a puppet for the other seat). */
    public interface PlayerFactory {
        Player create(String seat);
    }

    /** What the world is built for (design Section 5.1). */
    public enum Mode {
        /** the observed window with the viewer holding priority */
        PRIORITY,
        /** the declare attackers step, before attackers are declared */
        ATTACK,
        /** the declare blockers step, attackers declared, before blocks */
        BLOCK,
        /** the observed snapshot, nothing resolved (current dialog) */
        SNAPSHOT,
        /** the observed step, before its turn-based actions (the cleanup discard) */
        STEP_START,
        /** pregame (mulligan decisions) */
        PREGAME
    }

    public static final class Spec {
        public Map<String, Object> gameStart;
        public Map<String, Object> observation;
        public Sampler.Sample sample;
        public KitRandom random;
        public Mode mode = Mode.PRIORITY;
        public PlayerFactory viewerFactory;
        public PlayerFactory otherFactory;
        public int index;
        /** "first" or "regular" for a combat_damage observation; null: decide from the board */
        public String combatDamageStep;
        /** The front's own history ({@code x_history}): confirmed own loyalty activations this turn. */
        public Map<String, Object> history;
    }

    static final List<String> SEATS = Arrays.asList("p0", "p1");
    private static final CardResolver RESOLVER = new CardResolver();
    private static volatile List<TokenInfo> tokens;
    private static final Map<String, CounterType> COUNTERS = new HashMap<>();

    private final Spec spec;
    private final World world;
    private final Game game;
    private final Map<String, Object> obs;
    private final Map<String, Map<String, Object>> playersObs = new LinkedHashMap<>();
    private final Ability fake = new SimpleStaticAbility(Zone.OUTSIDE, new InfoEffect("spellbench kit world injection"));
    /** object record -> created card */
    private final Map<Map<String, Object>, Card> cardOf = new HashMap<>();
    private final Map<String, List<Card>> handCards = new HashMap<>();
    private final Map<String, List<Card>> libraryCards = new HashMap<>();

    private WorldBuilder(Spec spec) {
        this.spec = spec;
        this.obs = spec.observation;
        this.game = new KitDuel();
        this.world = new World(game, Json.str(obs, "viewer"), spec.index, spec.random);
        for (Object p : Json.arr(obs, "players")) {
            Map<String, Object> pm = Json.obj(p);
            playersObs.put(Json.str(pm, "seat"), pm);
        }
    }

    public static World build(Spec spec) {
        WorldBuilder b = new WorldBuilder(spec);
        b.run();
        return b.world;
    }

    /** Resolve changed names from this seat's own history before sampling or rebuilding. */
    public static List<String> restoreVisibleNames(Map<String, Object> observation, Map<String, Object> history) {
        Map<String, Object> repairs = spellbench.kit.core.VisibleNames.restore(observation, history,
                name -> RESOLVER.resolve(name).isResolved());
        return repairs.isEmpty() ? new ArrayList<>()
                : new ArrayList<>(Collections.singletonList("approximate:renamed_object"));
    }

    private void flag(String f) {
        if (!world.flags.contains(f)) {
            world.flags.add(f);
        }
    }

    private Player player(String seat) {
        return game.getPlayer(world.player(seat));
    }

    // =============================================================================================

    private void run() {
        KitRandom random = spec.random;
        world.sample = spec.sample.json();
        world.flags.addAll(spec.sample.flags);

        // ---- 1. players (UUIDs stable across worlds: one id scope per seat) and cards
        KitDuel.KitMatch match;
        random.scopeWorld("game");
        match = new KitDuel.KitMatch();
        for (String seat : SEATS) {
            random.scopeObject("player:" + seat);
            Player p = seat.equals(world.viewer) ? spec.viewerFactory.create(seat) : spec.otherFactory.create(seat);
            created.put(seat, p);
            world.seatPlayer.put(seat, p.getId());
            random.assignSeat(p.getId(), seat);
        }
        Map<String, List<Card>> decks = new LinkedHashMap<>();
        for (String seat : SEATS) {
            decks.put(seat, createCards(seat));
        }
        for (String seat : SEATS) {
            Player p = findCreated(seat);
            Deck deck = new Deck();
            deck.getCards().addAll(decks.get(seat));
            game.loadCards(new LinkedHashSet<>(decks.get(seat)), p.getId());
            game.addPlayer(p, deck);
            match.add(p, deck);
        }
        random.scopeWorld("init");
        GameOptions options = new GameOptions();
        options.testMode = true;
        options.skipInitShuffling = true;
        options.stopOnTurn = 1;
        options.stopAtStep = PhaseStep.UNTAP;
        game.setGameOptions(options);
        String startingSeat = startingSeat();
        game.setStartingPlayerId(world.player(startingSeat));
        game.start(world.player(startingSeat));
        if (game.checkIfGameIsOver()) {
            throw new IllegalStateException("world ended during init()");
        }
        for (String seat : SEATS) {
            Player p = player(seat);
            for (Card c : new ArrayList<>(p.getHand().getCards(game))) {
                p.getHand().remove(c);
            }
            p.getLibrary().clear();
        }

        // ---- 2. zones without events
        random.scopeWorld("inject");
        for (String seat : SEATS) {
            injectZones(seat);
        }
        for (String seat : SEATS) {
            injectBattlefield(seat);
        }
        for (String seat : SEATS) {
            attach(seat);
        }
        for (String seat : SEATS) {
            command(seat);
        }
        ownHistory();

        // ---- 3. players' public state and the turn position
        String active = Json.str(obs, "active_seat");
        if (active == null) {
            active = startingSeat;
        }
        long turn = Json.num(obs, "turn", 0);
        String phaseStep = Json.str(obs, "phase_step");
        boolean pregame = "pregame".equals(phaseStep) || spec.mode == Mode.PREGAME;
        for (String seat : SEATS) {
            playerState(seat);
        }
        if (!pregame) {
            positionTurn(active, (int) turn, phaseStep, startingSeat);
            combat(active, phaseStep);
            stack();
        }

        // ---- 4. hygiene
        game.getState().getTurnMods().clear();
        if (!pregame && turn == 1 && active.equals(startingSeat) && beforeDraw(phaseStep)) {
            game.getState().getTurnMods().add(new TurnMod(world.player(startingSeat)).withSkipStep(PhaseStep.DRAW));
        }
        if (!Json.arr(obs, "pending_triggers").isEmpty()) {
            flag("approximate:pending_triggers_dropped");
            flag("horizon:pending_triggers"); // the register (review change 3): such a world is not searched
        }
        game.getState().clearTriggeredAbilities();
        ((List<?>) Reflect.get(GameState.class, game.getState(), "simultaneousEvents")).clear();
        game.getState().resetWatchers();
        flag("approximate:watchers_reset");
        game.applyEffects();
        PublicReplayEffects.restore(world, obs, spec.history);
        checkCharacteristics();
        game.getOptions().stopOnTurn = null;
        random.scopeWorld("play");
    }

    /**
     * Design 3.2: a difference between the world's power, toughness or keywords and the observation (an until-end-of-
     * turn effect, a condition) marks the world approximate:unexplained_characteristics. Only explicitly witnessed
     * public native effects are restored above; an unexplained numerical gap never supplies an effect.
     */
    private void checkCharacteristics() {
        for (Map<String, Object> pm : playersObs.values()) {
            for (Object o : Json.arr(pm, "battlefield")) {
                Map<String, Object> rec = Json.obj(o);
                Map<String, Object> ch = Json.obj(rec, "characteristics");
                UUID u = world.idToUuid.get(Json.str(rec, "object_id"));
                Permanent p = u == null ? null : game.getPermanent(u);
                if (ch == null || p == null || Json.bool(rec, "face_down")) {
                    continue;
                }
                boolean differs = false;
                if (ch.get("power") instanceof Number && p.isCreature(game)) {
                    differs |= ((Number) ch.get("power")).intValue() != p.getPower().getValue()
                            || ((Number) ch.get("toughness")).intValue() != p.getToughness().getValue();
                }
                List<Object> kw = KitBridge.keywords(p.getAbilities(game));
                if (ch.get("keywords") instanceof List && kw != null) {
                    differs |= !new java.util.HashSet<>(Json.arr(ch, "keywords")).equals(new java.util.HashSet<>(kw));
                }
                if (differs) {
                    flag("approximate:unexplained_characteristics");
                }
            }
        }
    }

    private final Map<String, Player> created = new HashMap<>();

    private Player findCreated(String seat) {
        Player p = created.get(seat);
        if (p == null) {
            throw new IllegalStateException("player " + seat + " not created");
        }
        return p;
    }

    private String startingSeat() {
        Map<String, Object> rules = Json.obj(spec.gameStart, "rules");
        String s = rules == null ? null : Json.str(rules, "starting_seat");
        return s == null ? "p0" : s;
    }

    private static boolean beforeDraw(String phaseStep) {
        return "untap".equals(phaseStep) || "upkeep".equals(phaseStep);
    }

    // =============================================================================================
    // cards

    private Card newCard(String name, UUID ownerId) {
        try {
            return RESOLVER.resolve(name).createCard(ownerId);
        } catch (RuntimeException e) {
            throw new IllegalStateException("cannot create card '" + name + "': " + e, e);
        }
    }

    /** Every card a seat owns in this world, each created under its id scope. */
    private List<Card> createCards(String seat) {
        KitRandom random = spec.random;
        UUID owner = world.player(seat);
        List<Card> out = new ArrayList<>();
        Map<String, Object> pm = playersObs.get(seat);
        Sampler.SeatSample ss = spec.sample.seats.get(seat);
        List<Map<String, Object>> records = new ArrayList<>();
        for (Map<String, Object> pmAny : playersObs.values()) {
            for (Object o : Json.arr(pmAny, "battlefield")) {
                records.add(Json.obj(o));
            }
        }
        for (String zone : new String[]{"hand", "graveyard", "exile"}) {
            for (Object o : Json.arr(pm, zone)) {
                records.add(Json.obj(o));
            }
        }
        for (Object o : Json.arr(obs, "stack")) {
            Map<String, Object> e = Json.obj(o);
            if ("spell".equals(Json.str(e, "stack_kind"))) {
                records.add(e);
            }
        }
        for (Map<String, Object> rec : records) {
            if (!seat.equals(Json.str(rec, "owner_seat")) || Json.bool(rec, "token")) {
                continue;
            }
            if (Json.bool(rec, "copy")) {
                flag("approximate:nontoken_copy");
            }
            String name = Json.str(rec, "card_name");
            String id = Json.str(rec, "object_id");
            if (name == null) {
                name = ss.faceDown.get(id);
                flag("approximate:face_down_identity");
                if (name == null) {
                    flag("unsupported:face_down_unsampled");
                    continue;
                }
            }
            random.scopeObject(id);
            Card c = newCard(name, owner);
            cardOf.put(rec, c);
            out.add(c);
        }
        // hidden: the other seat's hand, then the library, top first
        List<Card> hand = new ArrayList<>();
        int hidden = 0;
        for (Sampler.Slot slot : ss.hand) {
            if (slot.name == null) {
                flag("unsupported:hidden_slot_without_name");
                continue;
            }
            if (slot.objectId != null) {
                random.scopeObject(slot.objectId);
            } else {
                random.scopeWorld("hidden:" + seat + ":" + hidden++);
            }
            Card c = newCard(slot.name, owner);
            if (slot.objectId != null) {
                bindCard(slot.objectId, c);
            }
            if (slot.pinned) {
                world.knownHandNames.add(slot.name);
            }
            hand.add(c);
            out.add(c);
        }
        handCards.put(seat, hand);
        List<Card> library = new ArrayList<>();
        for (Sampler.Slot slot : ss.library) {
            if (slot.name == null) {
                flag("unsupported:hidden_slot_without_name");
                continue;
            }
            if (slot.objectId != null) {
                random.scopeObject(slot.objectId);
            } else {
                random.scopeWorld("hidden:" + seat + ":" + hidden++);
            }
            Card c = newCard(slot.name, owner);
            if (slot.objectId != null) {
                bindCard(slot.objectId, c);
            }
            if (slot.pinned) {
                world.pinnedLibrary.add(c.getId());
            }
            library.add(c);
            out.add(c);
        }
        libraryCards.put(seat, library);
        return out;
    }

    private void bindCard(String objectId, Card c) {
        world.bind(objectId, c.getId());
        for (UUID part : partIds(c)) {
            world.alias(part, objectId);
        }
    }

    static List<UUID> partIds(Card c) {
        List<UUID> out = new ArrayList<>();
        if (c instanceof mage.cards.SplitCard) {
            out.add(((mage.cards.SplitCard) c).getLeftHalfCard().getId());
            out.add(((mage.cards.SplitCard) c).getRightHalfCard().getId());
        } else if (c instanceof mage.cards.ModalDoubleFacedCard) {
            out.add(((mage.cards.ModalDoubleFacedCard) c).getLeftHalfCard().getId());
            out.add(((mage.cards.ModalDoubleFacedCard) c).getRightHalfCard().getId());
        } else if (c instanceof mage.cards.CardWithSpellOption) {
            out.add(((mage.cards.CardWithSpellOption) c).getSpellCard().getId());
        }
        out.remove(c.getId());
        return out;
    }

    // =============================================================================================
    // zones

    private void injectZones(String seat) {
        Player p = player(seat);
        Map<String, Object> pm = playersObs.get(seat);
        if (seat.equals(world.viewer)) {
            for (Object o : Json.arr(pm, "hand")) {
                Card c = cardOf.get(Json.obj(o));
                if (c == null) {
                    continue;
                }
                c.setZone(Zone.HAND, game);
                p.getHand().add(c);
                bindCard(Json.str(Json.obj(o), "object_id"), c);
            }
        } else {
            for (Card c : handCards.get(seat)) {
                c.setZone(Zone.HAND, game);
                p.getHand().add(c);
            }
        }
        for (Object o : Json.arr(pm, "graveyard")) {
            Card c = cardOf.get(Json.obj(o));
            if (c == null) {
                continue;
            }
            c.setZone(Zone.GRAVEYARD, game);
            p.getGraveyard().add(c);
            bindCard(Json.str(Json.obj(o), "object_id"), c);
        }
        for (Object o : Json.arr(pm, "exile")) {
            Map<String, Object> rec = Json.obj(o);
            Card c = cardOf.get(rec);
            if (c == null) {
                continue;
            }
            c.setZone(Zone.EXILED, game);
            game.getExile().add(c);
            if (Json.bool(rec, "face_down")) {
                c.setFaceDown(true, game);
            }
            bindCard(Json.str(rec, "object_id"), c);
        }
        for (Card c : libraryCards.get(seat)) {
            c.setZone(Zone.LIBRARY, game);
            p.getLibrary().putOnBottom(c, game);
        }
    }

    private void injectBattlefield(String seat) {
        Player controller = player(seat);
        for (Object o : Json.arr(playersObs.get(seat), "battlefield")) {
            Map<String, Object> rec = Json.obj(o);
            Map<String, Object> pm = Json.obj(rec, "permanent");
            String id = Json.str(rec, "object_id");
            Permanent perm;
            if (Json.bool(rec, "token")) {
                perm = putToken(controller, rec, id);
                if (perm == null) {
                    flag("unsupported:token:" + Json.str(rec, "card_name"));
                    continue;
                }
            } else {
                Card c = cardOf.get(rec);
                if (c == null) {
                    continue;
                }
                spec.random.scopeObject(id + ":permanent");
                perm = putCardOntoBattlefield(c, Json.str(rec, "card_name"), controller,
                        pm != null && Json.bool(pm, "tapped"));
                if (perm == null) {
                    flag("unsupported:did_not_enter:" + Json.str(rec, "card_name"));
                    continue;
                }
            }
            world.bind(id, perm.getId());
            if (Json.bool(rec, "face_down")) {
                perm.setFaceDown(true, game);
                flag("approximate:face_down_permanent");
            }
            decorate(perm, pm);
        }
    }

    /**
     * mzbridge's {@code putCardOntoBattlefield}: CardUtil.putCardOntoBattlefieldWithEffects without changing the
     * card's owner. No ENTERS_THE_BATTLEFIELD event; "enters with counters / tapped" replacements still apply (the
     * observed counters and tapped state are set afterwards). A double-faced card enters on the face it shows: its
     * front face (XMage's default side, whose id is the face's, not the card's), or its back face, transformed, when
     * the observed name is the back face's (as ZonesHandler enters a card transformed). Returns the permanent, null
     * when none entered.
     */
    private Permanent putCardOntoBattlefield(Card card, String shownName, Player controller, boolean tapped) {
        Ability source = fake.copy();
        source.setControllerId(controller.getId());
        source.setSourceId(card.getId());
        Card permCard = mage.util.CardUtil.getDefaultCardSideForBattlefield(game, card);
        if (card instanceof mage.cards.ModalDoubleFacedCard) {
            Card back = ((mage.cards.ModalDoubleFacedCard) card).getRightHalfCard();
            if (back.getName().equals(shownName) && !back.getName().equals(permCard.getName())) {
                permCard = back;
            }
        }
        boolean transformed = card.isTransformable() && card.getSecondCardFace() != null
                && card.getSecondCardFace().getName().equals(shownName) && !card.getName().equals(shownName);
        permCard.setZone(Zone.BATTLEFIELD, game);
        PermanentCard permanent = permCard instanceof MeldCard ? new PermanentMeld(permCard, controller.getId(), game)
                : new PermanentCard(permCard, controller.getId(), game);
        game.getContinuousEffects().setController(permanent.getId(), controller.getId());
        game.getPermanentsEntering().put(permanent.getId(), permanent);
        permCard.applyEnterWithCounters(permanent, source, game);
        permanent.entersBattlefield(source, game, Zone.OUTSIDE, false);
        game.addPermanent(permanent, game.getState().getNextPermanentOrderNumber());
        game.getPermanentsEntering().remove(permanent.getId());
        permanent.setTapped(tapped);
        if (transformed) {
            mage.abilities.keyword.TransformAbility.transformPermanent(permanent, game, null);
        }
        for (ContinuousEffect effect : game.getState().getContinuousEffects().getLayeredEffects(game)) {
            Optional<Ability> ability = game.getState().getContinuousEffects().getLayeredEffectAbilities(effect)
                    .stream().findFirst();
            if (ability.isPresent() && permanent.getId().equals(ability.get().getSourceId())) {
                effect.init(ability.get(), game, controller.getId());
            }
        }
        return game.getPermanent(permanent.getId());
    }

    /** A token of a decklist-creatable class, matched on name and characteristics; null when none matches. */
    private Permanent putToken(Player controller, Map<String, Object> rec, String objectId) {
        String name = Json.str(rec, "card_name");
        Map<String, Object> ch = Json.obj(rec, "characteristics");
        spec.random.scopeWorld("token-probe"); // probe instances are cached across worlds: never in an object scope
        Token token = resolveToken(name, ch);
        if (token == null) {
            token = copyToken(name, controller.getId());
        }
        if (token == null) {
            return null;
        }
        spec.random.scopeObject(objectId);
        Ability src = fake.copy();
        src.setControllerId(controller.getId());
        src.setSourceId(token.getId());
        PermanentToken pm = new PermanentToken(token, controller.getId(), game);
        game.getState().addCard(pm);
        game.getPermanentsEntering().put(pm.getId(), pm);
        pm.updateZoneChangeCounter(game, new ZoneChangeEvent(pm, null, controller.getId(), Zone.OUTSIDE, Zone.BATTLEFIELD));
        game.setScopeRelevant(true);
        pm.entersBattlefield(src, game, Zone.OUTSIDE, false);
        game.setScopeRelevant(false);
        game.addPermanent(pm, game.getState().getNextPermanentOrderNumber());
        pm.setZone(Zone.BATTLEFIELD, game);
        game.getPermanentsEntering().remove(pm.getId());
        return pm;
    }

    /**
     * A token that copies a decklist card, which the token repository does not hold: an embalmed card (mtg-kernel
     * names it {@code "<card> Embalmed Token"}; XMage's EmbalmEffect makes a white Zombie copy without a mana cost),
     * or a plain copy that carries the card's own name. Null when the name is neither.
     */
    private Token copyToken(String name, UUID owner) {
        if (name == null) {
            return null;
        }
        boolean embalmed = name.endsWith(" Embalmed Token");
        String base = embalmed ? name.substring(0, name.length() - " Embalmed Token".length()) : name;
        if (!decklistNames().contains(base)) {
            return null;
        }
        Token token = mage.util.functions.CopyTokenFunction.createTokenCopy(newCard(base, owner), game);
        if (embalmed) {
            token.setColor(mage.ObjectColor.WHITE);
            token.addSubType(mage.constants.SubType.ZOMBIE);
            token.clearManaCost();
        }
        return token;
    }

    private java.util.Set<String> decklistNames() {
        java.util.Set<String> out = new java.util.HashSet<>();
        for (String side : new String[]{"own_deck", "opponent_deck"}) {
            Map<String, Object> deck = Json.obj(spec.gameStart, side);
            if (deck != null) {
                for (Object r : Json.arr(deck, "decklist")) {
                    out.add(Json.str(Json.obj(r), "name"));
                }
            }
        }
        return out;
    }

    private static final Map<String, List<Token>> TOKEN_CACHE = new HashMap<>();

    /** The token repository's classes whose instance has this name (no-argument constructors), cached per name. */
    static List<Token> tokenCandidates(String name) {
        List<Token> candidates;
        synchronized (TOKEN_CACHE) {
            candidates = TOKEN_CACHE.get(name);
        }
        if (candidates == null) {
            candidates = new ArrayList<>();
            if (tokens == null) {
                tokens = TokenRepository.instance.getByType(TokenType.TOKEN);
            }
            List<String> classes = new ArrayList<>();
            for (TokenInfo t : tokens) {
                String cls = t.getFullClassFileName();
                if (!classes.contains(cls)) {
                    classes.add(cls);
                }
            }
            Collections.sort(classes);
            for (String cls : classes) {
                try {
                    Token probe = (Token) Class.forName(cls).getConstructor().newInstance();
                    if (name.equals(probe.getName())) {
                        candidates.add(probe);
                    }
                } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                    // a token class without a no-argument constructor cannot be a decklist token here
                }
            }
            synchronized (TOKEN_CACHE) {
                TOKEN_CACHE.put(name, candidates);
            }
        }
        return candidates;
    }

    /**
     * The token class for an observed token. When the register's classes (the tokens the pool's cards create) hold
     * the name, only they are candidates. An exact match needs the observed power, toughness, keywords, types and
     * colors. An anthem or a temporary effect changes the observed power, toughness or keywords, so a register class
     * that alone has the observed name, types and colors is that token; checkCharacteristics() still flags a
     * difference no restored effect explains.
     */
    private Token resolveToken(String name, Map<String, Object> ch) {
        if (name == null) {
            return null;
        }
        List<Token> candidates = new ArrayList<>(tokenCandidates(name));
        if (ch == null) {
            return candidates.isEmpty() ? null : candidates.get(0).copy();
        }
        java.util.Set<String> pool = Register.tokenClasses();
        List<Token> fromPool = new ArrayList<>();
        for (Token t : candidates) {
            if (pool.contains(t.getClass().getSimpleName())) {
                fromPool.add(t);
            }
        }
        if (!fromPool.isEmpty()) {
            candidates = fromPool; // another set's same-named class cannot be in a pool game
        }
        Object pw = ch.get("power");
        Object th = ch.get("toughness");
        for (Token t : candidates) {
            List<Object> kw = KitBridge.keywords(t.getAbilities());
            boolean ok = (pw == null || ((Number) pw).intValue() == t.getPower().getValue())
                    && (th == null || ((Number) th).intValue() == t.getToughness().getValue())
                    && (!(ch.get("keywords") instanceof List) || kw != null
                        && new java.util.HashSet<>(Json.arr(ch, "keywords")).equals(new java.util.HashSet<>(kw)))
                    && sameTypesAndColors(t, ch);
            if (ok) {
                return t.copy();
            }
        }
        Token named = null;
        int matches = 0;
        for (Token t : fromPool) {
            if (sameTypesAndColors(t, ch)) {
                named = t;
                matches++;
            }
        }
        if (matches == 1) {
            return named.copy();
        }
        if (!candidates.isEmpty()) {
            flag("approximate:token_characteristics:" + name);
            return candidates.get(0).copy();
        }
        return null;
    }

    /**
     * Several token classes share a name ("Goblin Token" is mono-red in one set and black and red in another): the
     * observed supertypes, types, subtypes and colors pick the class, as the observation would report its instance.
     */
    private boolean sameTypesAndColors(Token t, Map<String, Object> ch) {
        Map<String, Object> have = KitBridge.typesAndColors(t, game);
        if (have == null) {
            return false;
        }
        for (String field : new String[]{"supertypes", "types", "subtypes", "colors"}) {
            Object want = ch.get(field);
            if (want instanceof List && !new java.util.HashSet<>((List<?>) want).equals(new java.util.HashSet<>((List<?>) have.get(field)))) {
                return false;
            }
        }
        return true;
    }

    private void decorate(Permanent perm, Map<String, Object> pm) {
        if (pm == null) {
            return;
        }
        perm.setTapped(Json.bool(pm, "tapped"));
        // counters: exactly the observed map (design 3.2 changes mzbridge's rule: unlisted types are removed)
        Map<String, Object> want = Json.obj(pm, "counters");
        for (Counter c : new ArrayList<>(perm.getCounters(game).values())) {
            perm.getCounters(game).removeCounter(c.getName(), c.getCount());
        }
        if (want != null) {
            for (Map.Entry<String, Object> e : want.entrySet()) {
                CounterType ct = counterType(e.getKey());
                int n = ((Number) e.getValue()).intValue();
                if (ct == null) {
                    flag("unsupported:counter:" + e.getKey());
                    continue;
                }
                if (n > 0) {
                    perm.getCounters(game).addCounter(ct.createInstance(n));
                }
            }
        }
        Reflect.set(PermanentImpl.class, perm, "damage", (int) Json.num(pm, "damage", 0));
        Reflect.set(PermanentImpl.class, perm, "controlledFromStartOfControllerTurn", !Json.bool(pm, "summoning_sick"));
        if (Json.bool(pm, "phased_out")) {
            Reflect.set(PermanentImpl.class, perm, "phasedIn", false);
        }
    }

    static synchronized CounterType counterType(String v2) {
        if (COUNTERS.isEmpty()) {
            for (CounterType ct : CounterType.values()) {
                String n = KitBridge.counter(ct.getName());
                if (n != null && !COUNTERS.containsKey(n)) {
                    COUNTERS.put(n, ct);
                }
            }
        }
        return COUNTERS.get(v2);
    }

    private void attach(String seat) {
        for (Object o : Json.arr(playersObs.get(seat), "battlefield")) {
            Map<String, Object> rec = Json.obj(o);
            Map<String, Object> pm = Json.obj(rec, "permanent");
            if (pm == null || pm.get("attached_to") == null) {
                continue;
            }
            UUID self = world.idToUuid.get(Json.str(rec, "object_id"));
            UUID host = targetUuid(Json.obj(pm, "attached_to"));
            if (self == null || host == null) {
                flag("approximate:attachment_unresolved");
                continue;
            }
            Ability src = fake.copy();
            src.setControllerId(world.player(seat));
            src.setSourceId(self);
            Permanent hostPerm = game.getPermanent(host);
            boolean ok;
            if (hostPerm != null) {
                ok = hostPerm.addAttachment(self, src, game);
            } else {
                Player hp = game.getPlayer(host);
                ok = hp != null && hp.addAttachment(self, src, game);
            }
            if (!ok) {
                flag("approximate:attachment_refused");
            }
        }
    }

    /**
     * Activation usage (Section 3.3): the viewer's loyalty activations this turn are own-history (selected by the kit
     * and confirmed by the loyalty change); another seat's planeswalkers are approximate. The viewer's confirmed uses
     * of its permanents' activated abilities ({@code x_history.ability_uses}) restore XMage's activation counts, which
     * limit an "activate only once" or once-each-turn ability.
     */
    private void ownHistory() {
        if (spec.history != null) {
            for (Object o : Json.arr(spec.history, "loyalty_used")) {
                UUID u = world.idToUuid.get((String) o);
                Permanent p = u == null ? null : game.getPermanent(u);
                if (p != null) {
                    p.addLoyaltyUsed();
                    flag("own_history:loyalty_used");
                }
            }
            for (Object o : Json.arr(spec.history, "ability_uses")) {
                abilityUse(Json.obj(o));
            }
        }
        for (Permanent p : game.getBattlefield().getAllPermanents()) {
            if (p.isPlaneswalker(game) && !p.getControllerId().equals(world.player(world.viewer))) {
                flag("approximate:activation_usage_other_seat");
            }
        }
    }

    /** One own ability's activation count, as ActivatedAbilityImpl.activate stores it (keyed by its original id). */
    private void abilityUse(Map<String, Object> use) {
        UUID u = world.idToUuid.get(Json.str(use, "object_id"));
        Permanent p = u == null ? null : game.getPermanent(u);
        ActivatedAbilityImpl used = null;
        if (p != null && p.isControlledBy(world.player(world.viewer))) {
            for (Ability a : p.getAbilities(game)) {
                if (a instanceof ActivatedAbilityImpl && a.getAbilityType() == AbilityType.ACTIVATED_NONMANA
                        && mage.player.spellbench.decide.KitBridge.abilityIndex(game, a) == Json.num(use, "ability_index", -1)) {
                    used = (ActivatedAbilityImpl) a;
                }
            }
        }
        long turn = Json.num(use, "turn", -1), thisTurn = Json.num(use, "this_turn", -1), total = Json.num(use, "total", -1);
        if (used == null || turn < 0 || thisTurn < 1 || total < thisTurn) {
            flag("approximate:ability_use_unmatched");
            return;
        }
        String id = used.getOriginalId().toString();
        game.getState().setValue(mage.util.CardUtil.getCardZoneString("activationsTurn" + id, used.getSourceId(), game), (int) turn);
        game.getState().setValue(mage.util.CardUtil.getCardZoneString("activationsCount" + id, used.getSourceId(), game), (int) thisTurn);
        game.getState().setValue(mage.util.CardUtil.getCardZoneString("totalActivations" + id, used.getSourceId(), game), (int) total);
        flag("own_history:ability_uses");
    }

    /** The world UUID of a v2 target reference ({"player": seat} or {"object": ref}), or null. */
    UUID targetUuid(Map<String, Object> t) {
        if (t == null) {
            return null;
        }
        if (t.get("player") != null) {
            return world.player((String) t.get("player"));
        }
        Map<String, Object> ref = Json.obj(t, "object");
        return ref == null ? null : world.idToUuid.get(Json.str(ref, "object_id"));
    }

    // =============================================================================================
    // players and the turn

    private void playerState(String seat) {
        Player p = player(seat);
        Map<String, Object> pm = playersObs.get(seat);
        p.initLife((int) Json.num(pm, "life", 20));
        p.resetLandsPlayed();
        for (int i = 0; i < Json.num(pm, "lands_played_this_turn", 0); i++) {
            p.incrementLandsPlayed();
        }
        Object poison = pm.get("poison");
        if (poison instanceof Number && ((Number) poison).intValue() > 0) {
            p.addCounters(CounterType.POISON.createInstance(((Number) poison).intValue()), p.getId(), fake, game);
        }
        Map<String, Object> pool = Json.obj(pm, "mana_pool");
        if (pool != null) {
            Mana m = new Mana();
            m.setWhite((int) Json.num(pool, "W", 0));
            m.setBlue((int) Json.num(pool, "U", 0));
            m.setBlack((int) Json.num(pool, "B", 0));
            m.setRed((int) Json.num(pool, "R", 0));
            m.setGreen((int) Json.num(pool, "G", 0));
            m.setColorless((int) Json.num(pool, "C", 0));
            if (m.count() > 0) {
                p.getManaPool().addMana(m, game, fake, true);
            }
        }
        // London mulligan bookkeeping (mulligans_taken)
        long taken = Json.num(pm, "mulligans_taken", 0);
        if (game.getMulligan() instanceof LondonMulligan) {
            @SuppressWarnings("unchecked")
            Map<UUID, Integer> start = (Map<UUID, Integer>) Reflect.get(LondonMulligan.class, game.getMulligan(), "startingHandSizes");
            @SuppressWarnings("unchecked")
            Map<UUID, Integer> open = (Map<UUID, Integer>) Reflect.get(LondonMulligan.class, game.getMulligan(), "openingHandSizes");
            start.put(p.getId(), 7);
            open.put(p.getId(), (int) (7 - taken));
        }
    }

    static PhaseStep step(String v2, String combatDamage) {
        switch (v2) {
            case "untap":
                return PhaseStep.UNTAP;
            case "upkeep":
                return PhaseStep.UPKEEP;
            case "draw":
                return PhaseStep.DRAW;
            case "precombat_main":
                return PhaseStep.PRECOMBAT_MAIN;
            case "beginning_of_combat":
                return PhaseStep.BEGIN_COMBAT;
            case "declare_attackers":
                return PhaseStep.DECLARE_ATTACKERS;
            case "declare_blockers":
                return PhaseStep.DECLARE_BLOCKERS;
            case "combat_damage":
                return "first".equals(combatDamage) ? PhaseStep.FIRST_COMBAT_DAMAGE : PhaseStep.COMBAT_DAMAGE;
            case "end_of_combat":
                return PhaseStep.END_COMBAT;
            case "postcombat_main":
                return PhaseStep.POSTCOMBAT_MAIN;
            case "end_step":
                return PhaseStep.END_TURN;
            case "cleanup":
                return PhaseStep.CLEANUP;
            default:
                throw new IllegalArgumentException("phase_step " + v2);
        }
    }

    static TurnPhase phaseOf(PhaseStep s) {
        switch (s) {
            case UNTAP:
            case UPKEEP:
            case DRAW:
                return TurnPhase.BEGINNING;
            case PRECOMBAT_MAIN:
                return TurnPhase.PRECOMBAT_MAIN;
            case BEGIN_COMBAT:
            case DECLARE_ATTACKERS:
            case DECLARE_BLOCKERS:
            case FIRST_COMBAT_DAMAGE:
            case COMBAT_DAMAGE:
            case END_COMBAT:
                return TurnPhase.COMBAT;
            case POSTCOMBAT_MAIN:
                return TurnPhase.POSTCOMBAT_MAIN;
            default:
                return TurnPhase.END;
        }
    }

    private void positionTurn(String active, int turn, String phaseStep, String startingSeat) {
        UUID activeId = world.player(active);
        game.getState().setTurnNum(turn);
        game.getState().setActivePlayerId(activeId);
        game.getState().setPlayerByOrderId(activeId);
        game.getState().getPlayerList().setCurrent(activeId);
        // v2 has one combat_damage step, XMage two: without a first or double striker in combat it is the regular
        // step; with one, the front's own history says which (Section 3.3), else the world is approximate
        String damage = "regular";
        if ("combat_damage".equals(phaseStep) && firstStrikerInCombat()) {
            damage = spec.combatDamageStep;
            if (damage == null) {
                flag("approximate:first_strike_step_unknown");
                damage = "first";
            } else {
                flag("own_history:first_strike_step:" + damage);
            }
        }
        PhaseStep target;
        Step.StepPart part;
        switch (spec.mode) {
            case ATTACK:
                target = PhaseStep.DECLARE_ATTACKERS;
                part = Step.StepPart.PRE;
                break;
            case BLOCK:
                target = PhaseStep.DECLARE_BLOCKERS;
                part = Step.StepPart.PRE;
                break;
            case STEP_START:
                target = step(phaseStep, damage);
                part = Step.StepPart.PRE;
                break;
            default:
                target = step(phaseStep, damage);
                part = Step.StepPart.PRIORITY;
        }
        TurnPhase phaseType = phaseOf(target);
        Turn t = game.getState().getTurn();
        Phase phase = t.getPhase(phaseType);
        if (phase == null) {
            throw new IllegalStateException("no phase " + phaseType);
        }
        @SuppressWarnings("unchecked")
        List<Step> steps = (List<Step>) Reflect.get(Phase.class, phase, "steps");
        Step step = null;
        for (Step s : steps) {
            if (s.getType() == target) {
                step = s;
            }
        }
        if (step == null) {
            throw new IllegalStateException("step " + target + " not in phase " + phaseType);
        }
        t.setPhase(phase);
        phase.setStep(step);
        Reflect.set(Step.class, step, "stepPart", part);
        Reflect.set(Phase.class, phase, "activePlayerId", activeId);
        for (String seat : SEATS) {
            Player p = player(seat);
            int want = seat.equals(startingSeat) ? (turn + 1) / 2 : turn / 2;
            for (int i = p.getTurns(); i < want; i++) {
                p.becomesActivePlayer();
            }
        }
        String prio = Json.str(obs, "priority_seat");
        if (spec.mode == Mode.PRIORITY) {
            prio = world.viewer;
        }
        game.getState().setPriorityPlayerId(world.player(prio == null ? active : prio));
        // the player list's current player is the one holding priority, as in XMage's own priority loop: MAD's
        // search takes its root player from it (simulatePriority), so on the other seat's turn it must be the viewer
        // (A1 result review change 5: all nine unmapped choices were the active opponent's options)
        game.getState().getPlayerList().setCurrent(game.getState().getPriorityPlayerId());
        List<Object> passed = Json.arr(obs, "passed_seats");
        for (String seat : SEATS) {
            boolean p = passed.contains(seat) && !(spec.mode == Mode.PRIORITY && seat.equals(world.viewer));
            Reflect.set(PlayerImpl.class, player(seat), "passed", p);
        }
    }

    private boolean firstStrikerInCombat() {
        for (Map<String, Object> pm : playersObs.values()) {
            for (Object o : Json.arr(pm, "battlefield")) {
                Map<String, Object> rec = Json.obj(o);
                Map<String, Object> perm = Json.obj(rec, "permanent");
                Map<String, Object> ch = Json.obj(rec, "characteristics");
                if (perm == null || ch == null || !(Json.bool(perm, "attacking") || Json.bool(perm, "blocking"))) {
                    continue;
                }
                List<Object> kw = Json.arr(ch, "keywords");
                if (kw.contains("first_strike") || kw.contains("double_strike")) {
                    return true;
                }
            }
        }
        return false;
    }

    private static final List<String> COMBAT_STEPS = Arrays.asList("beginning_of_combat", "declare_attackers",
            "declare_blockers", "combat_damage", "end_of_combat");

    private void combat(String active, String phaseStep) {
        if (!COMBAT_STEPS.contains(phaseStep) && spec.mode != Mode.ATTACK && spec.mode != Mode.BLOCK) {
            return;
        }
        UUID activeId = world.player(active);
        Combat combat = game.getCombat();
        combat.clear();
        combat.setAttacker(activeId);
        combat.setDefenders(game);
        if (spec.mode == Mode.ATTACK) {
            return;
        }
        Map<UUID, Boolean> tapped = new HashMap<>();
        boolean any = false;
        for (Object o : Json.arr(playersObs.get(active), "battlefield")) {
            Map<String, Object> rec = Json.obj(o);
            Map<String, Object> pm = Json.obj(rec, "permanent");
            if (pm == null || !Json.bool(pm, "attacking")) {
                continue;
            }
            UUID atk = world.idToUuid.get(Json.str(rec, "object_id"));
            UUID def = targetUuid(Json.obj(pm, "attack_target"));
            if (atk == null) {
                continue;
            }
            if (def == null) {
                def = world.player(Sampler_other(active));
                flag("approximate:attack_target_left");
            }
            Permanent p = game.getPermanent(atk);
            tapped.put(atk, p.isTapped());
            p.setTapped(false);
            if (!combat.declareAttacker(atk, def, activeId, game)) {
                flag("approximate:attacker_refused");
            }
            any = true;
        }
        if (any) {
            game.getTurn().setDeclareAttackersStepStarted(true);
        }
        boolean blocks = false;
        String defender = Sampler_other(active);
        for (Object o : Json.arr(playersObs.get(defender), "battlefield")) {
            Map<String, Object> rec = Json.obj(o);
            Map<String, Object> pm = Json.obj(rec, "permanent");
            if (pm == null || !Json.bool(pm, "blocking")) {
                continue;
            }
            UUID blk = world.idToUuid.get(Json.str(rec, "object_id"));
            Player dp = player(defender);
            for (Object a : Json.arr(pm, "blocked_attackers")) {
                UUID atk = world.idToUuid.get(Json.str(Json.obj(a), "object_id"));
                if (blk != null && atk != null) {
                    dp.declareBlocker(dp.getId(), blk, atk, game);
                    blocks = true;
                }
            }
            if (Json.arr(pm, "blocked_attackers").isEmpty()) {
                flag("approximate:blocker_without_attackers");
            }
        }
        if (blocks || "combat_damage".equals(phaseStep) || "end_of_combat".equals(phaseStep)) {
            if (blocks) {
                combat.acceptBlockers(game);
            }
            if (!"declare_blockers".equals(phaseStep) || blocks) {
                flag("approximate:blocked_status_from_observation");
            }
        }
        for (Map.Entry<UUID, Boolean> e : tapped.entrySet()) {
            game.getPermanent(e.getKey()).setTapped(e.getValue());
        }
    }

    private static String Sampler_other(String seat) {
        return "p0".equals(seat) ? "p1" : "p0";
    }

    // =============================================================================================
    // the stack (bottom to top)

    private void stack() {
        List<Object> entries = Json.arr(obs, "stack");
        for (Object o : entries) {
            Map<String, Object> e = Json.obj(o);
            String kind = Json.str(e, "stack_kind");
            String id = Json.str(e, "object_id");
            UUID controller = world.player(Json.str(e, "controller_seat"));
            spec.random.scopeObject(id + ":stack");
            if ("spell".equals(kind)) {
                Card c = cardOf.get(e);
                if (c == null) {
                    flag("unsupported:stack_spell_card");
                    placeholder(e, controller);
                    continue;
                }
                Player p = game.getPlayer(controller);
                Map<String, Object> cast = stackCast(id);
                boolean flashback = cast != null && "flashback".equals(Json.str(cast, "method"));
                Card face = flashback ? c : spellFace(c, e);
                SpellAbility sa = flashback ? flashbackAbility(c, controller) : face.getSpellAbility();
                if (sa == null || c.isLand(game)) {
                    flag(flashback ? "unsupported:stack_flashback" : "unsupported:stack_spell_not_castable");
                    placeholder(e, controller);
                    continue;
                }
                Zone from = flashback ? Zone.GRAVEYARD : Zone.HAND;
                c.setZone(from, game);
                if (flashback) {
                    p.getGraveyard().add(c);
                } else {
                    p.getHand().add(c);
                }
                face.cast(game, from, sa, controller);
                Spell spell = game.getStack().getSpell(face.getId());
                if (spell == null) {
                    flag("unsupported:stack_cast_failed");
                    placeholder(e, controller);
                    continue;
                }
                world.bind(id, spell.getId());
                world.alias(c.getId(), id);
                boolean incomplete = choices(spell.getSpellAbility(), e);
                String why = null;
                if (incomplete) {
                    why = "approximate:stack_targets_incomplete";
                } else if (Register.optionalCosts(Json.str(e, "card_name")) && !castKnown(c, spell, cast)) {
                    // kicker, flashback, gift...: whether they were paid is not observed (register row) and the
                    // front's history does not say
                    why = "approximate:optional_cost_state";
                }
                if (why != null) {
                    flag(why);
                    KitContext.horizon.add(spell.getId());
                    flag("horizon:stack_object");
                }
            } else {
                Ability ability = stackAbilitySource(e);
                if (ability == null) {
                    placeholder(e, controller);
                    continue;
                }
                Ability copy = ability.copy();
                copy.newId();
                copy.setControllerId(controller);
                StackAbility sa = new StackAbility(copy, controller);
                game.getStack().push(game, sa);
                world.bind(id, sa.getId());
                if (choices(copy, e)) {
                    flag("approximate:stack_targets_incomplete");
                    horizonNext = true;
                }
                if (departedNext) {
                    // the source has left (spec 6.5 source null): the ability remembers an earlier incarnation of it
                    int zcc = game.getState().getZoneChangeCounter(copy.getSourceId());
                    copy.setSourceObjectZoneChangeCounter(zcc + 7);
                    departedNext = false;
                }
                if (horizonNext) {
                    KitContext.horizon.add(sa.getId());
                    flag("horizon:stack_object");
                    horizonNext = false;
                }
            }
        }
    }

    /**
     * The ability a stack entry names: supported only when its source (by reference, or by lineage on its name when
     * the source has left) has exactly one ability of the entry's kind (design Section 3.3, stack ability identity).
     */
    private Ability stackAbilitySource(Map<String, Object> e) {
        boolean triggered = "triggered_ability".equals(Json.str(e, "stack_kind"));
        MageObject source = null;
        Map<String, Object> ref = Json.obj(e, "source");
        if (ref != null) {
            UUID u = world.idToUuid.get(Json.str(ref, "object_id"));
            source = u == null ? null : game.getObject(u);
        }
        if (source == null) {
            departedNext = true;
            // lineage on the name: the source left (Burnished Hart sacrificed itself); a public card of that name
            String name = Json.str(e, "card_name");
            List<MageObject> found = new ArrayList<>();
            for (Map.Entry<String, UUID> m : world.idToUuid.entrySet()) {
                MageObject mo = game.getObject(m.getValue());
                if (mo != null && name != null && name.equals(mo.getName()) && !found.contains(mo)
                        && game.getState().getZone(mo.getId()) != Zone.STACK) {
                    found.add(mo);
                }
            }
            if (found.isEmpty()) {
                Card c = null;
                try {
                    c = RESOLVER.resolve(name).createCard(world.player(Json.str(e, "controller_seat")));
                } catch (RuntimeException ex) {
                    // no such card
                }
                if (c != null) {
                    found.add(c);
                    flag("approximate:stack_source_recreated");
                    horizonNext = true;
                }
            }
            if (found.size() != 1) {
                flag("approximate:stack_source_ambiguous");
                if (found.isEmpty()) {
                    return null;
                }
                horizonNext = true;
            }
            source = found.get(0);
            flag("lineage:stack_source"); // a unique public card of that name: the departed source (N3)
        }
        List<Ability> ofKind = new ArrayList<>();
        Iterable<Ability> abilities = source instanceof Permanent ? ((Permanent) source).getAbilities(game)
                : (source instanceof Card ? ((Card) source).getAbilities(game) : source.getAbilities());
        for (Ability a : abilities) {
            if (triggered ? a instanceof TriggeredAbility
                    : (a instanceof ActivatedAbility && a.getAbilityType() == AbilityType.ACTIVATED_NONMANA)) {
                ofKind.add(a);
            }
        }
        ofKind = stackAbilitiesWithText(ofKind, Json.str(e, "text"));
        if (ofKind.isEmpty() && triggered) {
            Ability reflexive = reflexiveTrigger(source.getName(), Json.str(e, "text"));
            if (reflexive != null) {
                reflexive.setSourceId(source.getId());
                flag("register:reflexive_trigger");
                return reflexive;
            }
        }
        if (ofKind.size() != 1) {
            flag("approximate:stack_ability_identity");
            if (ofKind.isEmpty()) {
                return null;
            }
            horizonNext = true; // the first ability of the kind keeps the entry's name and kind; never resolved in search
        }
        // the register (design 3.4, review change 3): a trigger is supported only when its resolution reads no event
        // object or captured value, an activation only when it reads no captured value or paid cost
        String sourceName = source.getName();
        if (triggered && !Register.triggersEventFree(sourceName)) {
            flag("approximate:trigger_event_data");
            horizonNext = true;
        }
        if (!triggered && Register.activationReadsCaptured(sourceName)) {
            flag("approximate:activation_captured_value");
            horizonNext = true;
        }
        return ofKind.get(0);
    }

    /**
     * An audited reflexive trigger ("when you do"): the source card has no such ability, its resolving effect creates
     * one, so the trigger is rebuilt as that effect builds it. Its resolution reads no event data. Else null.
     */
    static Ability reflexiveTrigger(String sourceName, String publicText) {
        if ("Faebloom Trick".equals(sourceName) && "tap target creature an opponent controls.".equals(publicText)) {
            ReflexiveTriggeredAbility ability = new ReflexiveTriggeredAbility(new TapTargetEffect(), false);
            ability.addTarget(new TargetOpponentsCreaturePermanent());
            return ability;
        }
        return null;
    }

    static List<Ability> stackAbilitiesWithText(List<Ability> candidates, String publicText) {
        if (publicText == null) return candidates;
        List<Ability> matching = new ArrayList<>();
        for (Ability candidate : candidates) {
            if (publicText.equals(mage.player.spellbench.observe.StackAbilityText.of(candidate))) {
                matching.add(candidate);
            }
        }
        // Text is a permitted discriminator, never a guess. Missing,
        // unmatched or duplicate identities retain the existing horizon.
        return matching;
    }

    private boolean horizonNext;
    private boolean departedNext;

    // =============================================================================================
    // the command zone (review change 3): emblems rebuilt from the register, anything else unsupported

    private void command(String seat) {
        for (Object o : Json.arr(playersObs.get(seat), "command")) {
            Map<String, Object> rec = Json.obj(o);
            String name = Json.str(rec, "card_name");
            String id = Json.str(rec, "object_id");
            String sourceName = name != null && name.endsWith(" Emblem") ? name.substring(0, name.length() - " Emblem".length()) : null;
            String cls = sourceName == null ? null : Register.emblemClass(sourceName);
            if (cls == null) {
                flag("unsupported:command_object:" + name);
                continue;
            }
            try {
                spec.random.scopeObject(id + ":command");
                mage.game.command.Emblem emblem = (mage.game.command.Emblem) Class.forName(cls).getConstructor().newInstance();
                Card sourceCard = newCard(sourceName, world.player(seat));
                int before = game.getState().getCommand().size();
                game.addEmblem(emblem, sourceCard, world.player(seat));
                if (game.getState().getCommand().size() != before + 1) {
                    flag("unsupported:command_object:" + name);
                    continue;
                }
                mage.game.command.CommandObject added = game.getState().getCommand().get(before);
                world.bind(id, added.getId());
                flag("rebuilt:emblem");
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
                flag("unsupported:command_object:" + name);
            }
        }
    }

    /** How the front's history says a stack spell was cast ({@code x_history.stack_casts}), else null. */
    private Map<String, Object> stackCast(String id) {
        Map<String, Object> casts = spec.history == null ? null : Json.obj(spec.history, "stack_casts");
        return casts == null ? null : Json.obj(casts, id);
    }

    /**
     * MageZero fork: the face a stack spell was cast as. An entry names the card, but its characteristics are the
     * cast face's: an adventurer whose entry has its adventure's types (Sagu Wildling as Roost Seek) is that adventure.
     */
    private Card spellFace(Card c, Map<String, Object> e) {
        if (!(c instanceof CardWithSpellOption)) {
            return c;
        }
        Card option = ((CardWithSpellOption) c).getSpellCard();
        List<Object> types = new ArrayList<>();
        for (CardType t : option.getCardType(game)) {
            types.add(t.toString().toLowerCase(Locale.ROOT));
        }
        Map<String, Object> ch = Json.obj(e, "characteristics");
        return ch != null && new HashSet<>(Json.arr(ch, "types")).equals(new HashSet<>(types)) ? option : c;
    }

    /** The card's one flashback ability, cast by the spell's controller, else null. */
    private static SpellAbility flashbackAbility(Card c, UUID controller) {
        List<FlashbackAbility> found = new ArrayList<>();
        for (Ability a : c.getAbilities()) {
            if (a instanceof FlashbackAbility) {
                found.add((FlashbackAbility) a);
            }
        }
        if (found.size() != 1) {
            return null;
        }
        SpellAbility fb = found.get(0).copy();
        fb.setControllerId(controller);
        fb.setSourceId(c.getId());
        return fb;
    }

    /**
     * Whether the history settles every optional cost the card's register row lists: the cast method for flashback,
     * the payment count of a single kicker cost (set as XMage's kicker cost tag). Any other optional cost is unknown.
     */
    private boolean castKnown(Card c, Spell spell, Map<String, Object> cast) {
        List<Object> costs = Register.optionalCostClasses(c.getName());
        if (cast == null || costs == null || !Arrays.asList("normal", "flashback").contains(Json.str(cast, "method"))
                || !Arrays.asList("FlashbackAbility", "KickerAbility").containsAll(costs)) {
            return false;
        }
        if (costs.contains("KickerAbility")) {
            List<KickerAbility> kickers = new ArrayList<>();
            for (Ability a : c.getAbilities()) {
                if (a instanceof KickerAbility) {
                    kickers.add((KickerAbility) a);
                }
            }
            Object paid = cast.get("kicker");
            if (kickers.size() != 1 || kickers.get(0).getKickerCosts().size() != 1
                    || !(paid instanceof Long || paid instanceof Integer)
                    || ((Number) paid).intValue() < 0 || ((Number) paid).intValue() > 1) {
                return false;
            }
            if (((Number) paid).intValue() == 1) {
                spell.getSpellAbility().setCostsTag(
                        "kickerActivation" + kickers.get(0).getKickerCosts().get(0).getText(true), 1);
            }
        }
        flag("history:stack_cast");
        return true;
    }

    /** A stack object the world cannot rebuild: kept for the stack's shape, flagged, never resolved in search. */
    private void placeholder(Map<String, Object> e, UUID controller) {
        Ability info = new SimpleStaticAbility(Zone.ALL, new InfoEffect("spellbench kit placeholder"));
        info.setControllerId(controller);
        info.newId();
        StackAbility sa = new StackAbility(info, controller);
        game.getStack().push(game, sa);
        world.bind(Json.str(e, "object_id"), sa.getId());
        KitContext.horizon.add(sa.getId());
        flag("approximate:stack_placeholder");
        flag("horizon:stack_object"); // the no-search policy covers placeholders too (second review, item 2)
    }

    /**
     * Modes, targets, divided amounts and X of a rebuilt stack object, as observed; true when an observed target could
     * not be placed (the object is then approximate, review change 3).
     */
    private boolean choices(Ability ability, Map<String, Object> e) {
        Modes modes = ability.getModes();
        List<Object> observedModes = Json.arr(e, "modes");
        if (e.get("modes") != null && modes.size() > 1) {
            modes.clearSelectedModes();
            List<mage.abilities.Mode> all = new ArrayList<>(modes.values());
            for (Object m : observedModes) {
                int i = ((Number) m).intValue();
                if (i < all.size()) {
                    modes.addSelectedMode(all.get(i).getId());
                }
            }
        }
        List<Object> targets = Json.arr(e, "targets");
        List<Object> divided = Json.arr(e, "divided");
        boolean incomplete = false;
        int ti = 0;
        int di = 0;
        for (UUID modeId : modes.getSelectedModes()) {
            mage.abilities.Mode mode = modes.get(modeId);
            if (mode == null) {
                continue;
            }
            for (Target t : mode.getTargets()) {
                t.clearChosen();
                int want = Math.max(1, Math.min(t.getMaxNumberOfTargets(), targets.size() - ti));
                for (int k = 0; k < want && ti < targets.size(); k++) {
                    Object to = targets.get(ti++);
                    UUID u = to == null ? null : targetUuid(Json.obj(to));
                    if (u == null) {
                        flag("approximate:stack_target_left");
                        incomplete = true;
                        continue;
                    }
                    if (t instanceof TargetAmount && di < divided.size()) {
                        t.addTarget(u, ((Number) divided.get(di++)).intValue(), ability, game, true);
                    } else {
                        t.addTarget(u, ability, game, true);
                    }
                }
            }
        }
        if (ti < targets.size()) {
            flag("approximate:stack_targets_unplaced");
            incomplete = true;
        }
        Object x = e.get("x_value");
        if (x instanceof Number) {
            ability.setCostsTag("X", ((Number) x).intValue());
        }
        return incomplete;
    }
}
