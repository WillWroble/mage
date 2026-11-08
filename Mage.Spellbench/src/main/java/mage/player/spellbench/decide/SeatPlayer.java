package mage.player.spellbench.decide;

import mage.MageObject;
import mage.abilities.Ability;
import mage.abilities.ActivatedAbility;
import mage.abilities.Mode;
import mage.abilities.Modes;
import mage.abilities.SpellAbility;
import mage.abilities.TriggeredAbility;
import mage.abilities.costs.mana.ManaCost;
import mage.abilities.mana.ManaOptions;
import mage.cards.Card;
import mage.cards.Cards;
import mage.cards.CardsImpl;
import mage.cards.ModalDoubleFacedCard;
import mage.choices.Choice;
import mage.choices.ChoiceBasicLandType;
import mage.choices.ChoiceCardType;
import mage.choices.ChoiceColor;
import mage.choices.ChoiceCreatureType;
import mage.choices.ChoiceLandType;
import mage.constants.AbilityType;
import mage.constants.MultiAmountType;
import mage.constants.Outcome;
import mage.constants.RangeOfInfluence;
import mage.constants.Zone;
import mage.filter.common.FilterCreatureForCombat;
import mage.filter.common.FilterCreatureForCombatBlock;
import mage.filter.predicate.permanent.ControllerIdPredicate;
import mage.game.Game;
import mage.game.combat.CombatGroup;
import mage.game.events.GameEvent;
import mage.game.mulligan.LondonMulligan;
import mage.game.mulligan.Mulligan;
import mage.game.permanent.Permanent;
import mage.game.stack.StackObject;
import mage.player.spellbench.observe.Look;
import mage.player.spellbench.observe.Observation;
import mage.players.PlayerImpl;
import mage.target.Target;
import mage.target.TargetAmount;
import mage.target.TargetCard;
import mage.target.common.TargetCardInHand;
import mage.target.common.TargetCardInLibrary;
import mage.util.MultiAmountMessage;

import java.io.Serializable;
import java.lang.reflect.Field;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * An XMage player whose every decision is posed to its seat as protocol v2 decisions (Sections 7 and 8; task X4,
 * stage 1). It replaces CABT's {@code CabtBridgePlayer} (final, and shaped around CABT's multi-select prompts)
 * with the same discipline: no callback silently falls back to {@code ComputerPlayer}, except mana payment, which
 * the engine answers itself under the declared {@code engine_autopay} (Section 7.6; {@link AutoPayPlayer}).
 * <p>
 * Every override runs on the game thread. Decisions are posed through the {@link Exchange}; any failure of this
 * mapper ends the game halted (Section 9.5) instead of reaching XMage's error recovery, which would roll the game
 * back silently. Mutable state lives in the shared {@link SeatState}, never in player fields (XMage copies players).
 */
final class SeatPlayer extends AutoPayPlayer {

    private static final long serialVersionUID = 1L;
    private static final Field LONDON_OPENING = field(LondonMulligan.class, "openingHandSizes");

    private final transient SeatState st;

    SeatPlayer(String name, SeatState state) {
        super(name, RangeOfInfluence.ALL);
        this.st = state;
    }

    private SeatPlayer(final SeatPlayer player) {
        super(player);
        this.st = player.st; // shared on purpose: see SeatState
    }

    @Override
    public SeatPlayer copy() {
        return new SeatPlayer(this);
    }

    /** No auto-choice of forced targets: every decision is posed (Section 8 "Posing"; TargetImpl.tryToAutoChoose). */
    @Override
    public boolean getStrictChooseMode() {
        return true;
    }

    // =============================================================================================
    // plumbing

    private Exchange ex() {
        return st.exchange;
    }

    private String seat() {
        return st.seat;
    }

    /** Decisions are answered only for the live game (Annex C "No simulations"); a closed game unwinds. */
    private void live(Game game, String method) {
        if (st.exchange == null || st.exchange.isClosed()) {
            throw new Exchange.Closed();
        }
        if (game != null && (game.isSimulation() || game.inCheckPlayableState())) {
            throw new IllegalStateException(method + " asked in a simulation game; only live decisions are posed");
        }
    }

    private Exchange.Closed fail(RuntimeException e) {
        System.err.println("xmage-spellbench: mapper error");
        e.printStackTrace(System.err);
        return ex().halt("mapper_error");
    }

    private int ask(Pose p) {
        return ex().ask(p);
    }

    static Map<String, Object> sem(String kind) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", kind);
        return m;
    }

    private static Map<String, Object> ref(Observation o, UUID id) throws Unrepresentable {
        Map<String, Object> r = id == null ? null : o.reference(id);
        if (r == null) {
            throw new Unrepresentable("missing_reference", String.valueOf(id));
        }
        return r;
    }

    private static Map<String, Object> target(Observation o, UUID id) throws Unrepresentable {
        Map<String, Object> t = id == null ? null : o.target(id);
        if (t == null) {
            throw new Unrepresentable("missing_reference", String.valueOf(id));
        }
        return t;
    }

    /** The id of {@code source}'s own stack entry (a spell, or an ability being activated or resolving), or null. */
    private static UUID stackId(Ability source, Game game) {
        if (source == null || source.getId() == null) {
            return null;
        }
        for (StackObject so : game.getStack()) {
            if (so.getId().equals(source.getId())) {
                return so.getId();
            }
        }
        return null;
    }

    /**
     * An {@code R|null} source (Section 7.3): the stack entry when there is one, else the current visible
     * incarnation of the source object, else null.
     */
    private static Map<String, Object> looseSource(Observation o, Ability source, UUID stackId) {
        if (stackId != null) {
            return o.reference(stackId);
        }
        if (source == null || source.getSourceId() == null) {
            return null;
        }
        return o.reference(source.getSourceId());
    }

    // =============================================================================================
    // priority (Section 7.2)

    @Override
    public boolean priority(Game game) {
        live(game, "priority");
        try {
            passed = false;
            checkCombatAsDeclared(game);
            List<ActivatedAbility> playables = getPlayable(game, true, Zone.ALL, false);
            Pose pose = new Pose(seat(), true, "priority");
            if (st.rewindNext) {
                st.rewindNext = false;
                pose.rewind = ex().canRewind(seat());
            }
            pose.add(o -> sem("pass")).order(0, null, 0);
            List<ActivatedAbility> offered = new ArrayList<>();
            List<String> keys = new ArrayList<>();
            offered.add(null);
            keys.add(null);
            for (ActivatedAbility a : playables) {
                AbilityType type = a.getAbilityType();
                if ((type == AbilityType.ACTIVATED_MANA && !ex().priorityMana)
                        || type == AbilityType.SPECIAL_MANA_PAYMENT) {
                    continue; // cost payment stays engine-owned; ordinary mana activation is opt-in
                }
                String key = actionKey(a);
                if (st.excluded.contains(key)) {
                    continue; // Section 8: re-posed without the failing candidate
                }
                if (!completable(a, game)) {
                    ex().stats.add("action_not_offered:incompletable");
                    continue; // Section 7.1 no dead ends, where XMage's playable list is optimistic
                }
                Pose.Cand c = priorityCandidate(pose, a, game);
                if (c == null) {
                    continue;
                }
                offered.add(a);
                keys.add(key);
            }
            int k = ask(pose);
            if (k == 0) {
                st.excluded.clear();
                pass(game);
                return false;
            }
            ActivatedAbility chosen = offered.get(k);
            st.autopayFailed = false;
            boolean done = activateAbility(chosen, game);
            if (!done) {
                ex().stats.add("action_rejected:" + chosen.getAbilityType()
                        + (st.autopayFailed ? ":autopay" : ":other"));
                MageObject src = game.getObject(chosen.getSourceId());
                ex().stats.add("action_rejected_card:" + (src == null ? "?" : src.getName())
                        + (st.autopayFailed ? ":autopay" : ":other"));
                st.excluded.add(keys.get(k));
                st.rewindNext = true;
            } else {
                st.excluded.clear();
            }
            return true;
        } catch (Exchange.Closed e) {
            throw e;
        } catch (RuntimeException e) {
            throw fail(e);
        }
    }

    /**
     * Cheap completability checks XMage's playable list skips: the non-mana costs (a sacrifice with nothing to
     * sacrifice), and for a spell whose modes carry their own mana cost (spree), at least one mode the seat's mana
     * covers. What remains incompletable is rewound (Section 8).
     */
    private boolean completable(ActivatedAbility a, Game game) {
        AbilityType type = a.getAbilityType();
        if (type != AbilityType.SPELL && type != AbilityType.ACTIVATED_NONMANA
                && type != AbilityType.ACTIVATED_MANA) {
            return true;
        }
        try {
            if (!a.getCosts().canPay(a, a, getId(), game)) {
                return false;
            }
            if (a.getModes().size() <= 1 && !a.getTargets().isEmpty() && !a.getTargets().canChoose(getId(), a, game)) {
                return false;
            }
            if (type == AbilityType.SPELL && a.getModes().size() > 1) {
                boolean costed = false;
                mage.abilities.mana.ManaOptions mana = null;
                for (Mode m : a.getModes().values()) {
                    if (!(m.getCost() instanceof ManaCost)) {
                        continue;
                    }
                    costed = true;
                    if (!m.getTargets().canChoose(getId(), a, game)) {
                        continue;
                    }
                    if (mana == null) {
                        mana = getManaAvailable(game);
                    }
                    mage.Mana needed = a.getManaCostsToPay().getMana().copy();
                    needed.add(((ManaCost) m.getCost()).getMana());
                    if (mana.enough(needed)) {
                        return true;
                    }
                }
                return !costed;
            }
            if ((type == AbilityType.ACTIVATED_NONMANA || type == AbilityType.ACTIVATED_MANA)
                    && !a.getManaCostsToPay().isEmpty()) {
                // a source that taps as part of the cost cannot also make mana for it (XMage counts it)
                boolean tapsSource = false;
                for (mage.abilities.costs.Cost c : a.getCosts()) {
                    if (c instanceof mage.abilities.costs.common.TapSourceCost) {
                        tapsSource = true;
                    }
                }
                Permanent source = game.getPermanent(a.getSourceId());
                if (tapsSource && source != null) {
                    Game sim = game.createSimulationForPlayableCalc();
                    Permanent simSource = sim.getPermanent(a.getSourceId());
                    if (simSource != null) {
                        simSource.setTapped(true);
                        return getManaAvailable(sim).enough(a.getManaCostsToPay().getMana());
                    }
                }
            }
            return true;
        } catch (RuntimeException e) {
            return true; // a check XMage cannot answer here leaves the action to the rewind path
        }
    }

    /**
     * Counts declarations XMage changed after they were posed: for a computer player, its combat checks remove
     * attackers and blockers that break a restriction and add required blocks instead of asking again. The
     * completability oracle makes every posed declaration one the checks accept unchanged, so these stay 0.
     * Creatures an effect adds to combat later are not alterations; a declared pair that is gone is.
     */
    private void checkCombatAsDeclared(Game game) {
        if (ex().declaredAttack != null) {
            Map<UUID, UUID> now = CombatOracle.attackers(game.getCombat());
            for (Map.Entry<UUID, UUID> e : ex().declaredAttack.entrySet()) {
                if (!e.getValue().equals(now.get(e.getKey()))) {
                    ex().stats.add("attack_altered_by_engine");
                    break;
                }
            }
            ex().declaredAttack = null;
        }
        if (ex().declaredBlock != null) {
            if (!CombatOracle.blocks(game.getCombat()).containsAll(ex().declaredBlock)) {
                ex().stats.add("block_altered_by_engine");
            }
            ex().declaredBlock = null;
        }
    }

    private static String actionKey(ActivatedAbility a) {
        return a.getAbilityType() + ":" + a.getSourceId() + ":" + a.getOriginalId();
    }

    private Pose.Cand priorityCandidate(Pose pose, ActivatedAbility a, Game game) {
        UUID src = a.getSourceId();
        if (src == null) {
            return null;
        }
        switch (a.getAbilityType()) {
            case PLAY_LAND: {
                long face = isBackFace(game, src) ? 1 : 0;
                return pose.add(o -> {
                    Map<String, Object> s = sem("play_land");
                    s.put("source", ref(o, src));
                    s.put("face", face);
                    return s;
                }, src).order(1, src, face);
            }
            case SPELL: {
                String method = castMethod((SpellAbility) a);
                return pose.add(o -> {
                    Map<String, Object> s = sem("cast_spell");
                    s.put("source", ref(o, src));
                    s.put("method", method);
                    return s;
                }, src).order(2, src, 0);
            }
            case ACTIVATED_MANA: {
                long index = abilityIndex(game, a);
                if (index < 0) {
                    throw ex().halt("unmatched_priority_mana_ability");
                }
                return pose.add(o -> {
                    Map<String, Object> s = sem("activate_mana_ability");
                    s.put("source", ref(o, src));
                    s.put("ability_index", index);
                    s.put("mana_choice", null);
                    s.put("cost_target", null);
                    return s;
                }, src).order(5, src, index);
            }
            case ACTIVATED_NONMANA: {
                long index = abilityIndex(game, a);
                if (index < 0) {
                    ex().stats.add("ability_index_unmatched");
                    index = 1000 + (a.getRule() == null ? 0 : 0);
                }
                long idx = index;
                return pose.add(o -> {
                    Map<String, Object> s = sem("activate_ability");
                    s.put("source", ref(o, src));
                    s.put("ability_index", idx);
                    return s;
                }, src).order(3, src, idx);
            }
            case SPECIAL_ACTION: {
                String action = specialAction(a);
                return pose.add(o -> {
                    Map<String, Object> s = sem("special_action");
                    s.put("source", ref(o, src));
                    s.put("action", action);
                    return s;
                }, src).order(4, src, 0);
            }
            default:
                ex().stats.add("unmapped_playable:" + a.getAbilityType());
                return null;
        }
    }

    private static boolean isBackFace(Game game, UUID sourceId) {
        Card card = game.getCard(sourceId);
        if (card == null) {
            return false;
        }
        Card main = card.getMainCard();
        return main instanceof ModalDoubleFacedCard && !main.getId().equals(sourceId)
                && ((ModalDoubleFacedCard) main).getRightHalfCard().getId().equals(sourceId);
    }

    /** Section 7.4 {@code method} of a cast. */
    static String castMethod(SpellAbility sa) {
        String cls = sa.getClass().getSimpleName();
        String[][] byClass = {
                {"Flashback", "flashback"}, {"Escape", "escape"}, {"Foretell", "foretell"}, {"Disguise", "disguise"},
                {"Morph", "morph"}, {"Overload", "overload"}, {"Prototype", "prototype"}, {"Plot", "plot"},
                {"Disturb", "disturb"}, {"Evoke", "evoke"}, {"Madness", "madness"}, {"Miracle", "miracle"},
                {"Suspend", "suspend"}};
        for (String[] m : byClass) {
            if (cls.contains(m[0])) {
                return m[1];
            }
        }
        switch (sa.getSpellAbilityType()) {
            case BASE:
            case MODAL_LEFT:
                return "normal";
            case BASE_ALTERNATE:
                return "alternative";
            case ADVENTURE_SPELL:
                return "adventure";
            case SPLIT_LEFT:
                return "split_left";
            case SPLIT_RIGHT:
                return "split_right";
            case SPLIT_FUSED:
                return "fuse";
            case MODAL_RIGHT:
                return "mdfc_back";
            default:
                return "other";
        }
    }

    private static String specialAction(ActivatedAbility a) {
        String cls = a.getClass().getSimpleName();
        if (cls.contains("TurnFaceUp")) {
            return "turn_face_up";
        }
        if (cls.contains("Plot")) {
            return "plot";
        }
        if (cls.contains("Foretell")) {
            return "foretell";
        }
        if (cls.contains("Suspend")) {
            return "suspend";
        }
        if (cls.contains("Unlock") || cls.contains("Door")) {
            return "unlock_door";
        }
        return "other";
    }

    /**
     * Section 7.2 {@code ability_index}: the ability's 0-based position among its object's activated abilities of
     * the same class (mana or non-mana), in {@link #orderedAbilities} order. -1 when the object does not list it.
     */
    static long abilityIndex(Game game, Ability a) {
        boolean mana = a.getAbilityType() == AbilityType.ACTIVATED_MANA;
        long index = 0;
        for (Ability ab : orderedAbilities(game, a.getSourceId())) {
            AbilityType t = ab.getAbilityType();
            if (t != AbilityType.ACTIVATED_NONMANA && t != AbilityType.ACTIVATED_MANA) {
                continue;
            }
            if ((t == AbilityType.ACTIVATED_MANA) != mana) {
                continue;
            }
            if (ab.getId().equals(a.getId()) || ab.getOriginalId().equals(a.getOriginalId())) {
                return index;
            }
            index++;
        }
        return -1;
    }

    /**
     * An object's abilities in the order every {@code ability_index} counts them (Sections 7.2, 7.3): the abilities
     * it has of its own (its card's or token's, or, for a copy, those of the object it copies) in XMage's order,
     * which is Oracle order, then every ability other effects grant it, ordered by public keys: rule text in code
     * point order, then the original ability's id. The id only orders abilities whose text is identical, so it
     * tells the seat nothing. XMage itself lists granted abilities in the order its effects added them, and
     * several effects iterate a hash set of abilities (Agatha's Soul Cauldron, Hazel's Brewmaster, Necrotic Ooze,
     * Mirran Safehouse, ...), so that order followed identity hash codes and changed from run to run (X5). XMage
     * does not record which effect granted an ability, so the granting card's name cannot be a key.
     */
    static List<Ability> orderedAbilities(Game game, UUID sourceId) {
        MageObject object = game.getPermanent(sourceId);
        Iterable<Ability> abilities;
        Iterable<Ability> own;
        if (object != null) {
            Permanent perm = (Permanent) object;
            abilities = perm.getAbilities(game);
            if (perm.isCopy() && perm.getCopyFrom() != null) {
                own = perm.getCopyFrom().getAbilities();
            } else if (perm instanceof mage.game.permanent.PermanentCard) {
                // both faces: a transformed permanent has its back face's abilities of its own
                Card card = ((mage.game.permanent.PermanentCard) perm).getCard();
                List<Ability> faces = new ArrayList<>(card.getAbilities());
                Card back = card.getSecondCardFace();
                if (back != null) {
                    faces.addAll(back.getAbilities());
                }
                own = faces;
            } else if (perm instanceof mage.game.permanent.PermanentToken) {
                own = ((mage.game.permanent.PermanentToken) perm).getToken().getAbilities();
            } else {
                own = abilities;
            }
        } else {
            Card card = game.getCard(sourceId);
            if (card != null) {
                abilities = card.getAbilities(game);
                List<Ability> faces = new ArrayList<>(card.getAbilities());
                Card back = card.getSecondCardFace();
                if (back != null) {
                    faces.addAll(back.getAbilities());
                }
                own = faces;
            } else {
                object = game.getObject(sourceId);
                if (object == null) {
                    return Collections.emptyList();
                }
                abilities = object.getAbilities();
                own = abilities;
            }
        }
        Set<UUID> ownIds = new java.util.HashSet<>();
        for (Ability ab : own) {
            ownIds.add(ab.getOriginalId());
        }
        List<Ability> out = new ArrayList<>();
        List<Ability> granted = new ArrayList<>();
        for (Ability ab : abilities) {
            (ownIds.contains(ab.getOriginalId()) ? out : granted).add(ab);
        }
        granted.sort((x, y) -> {
            int c = HiddenOrder.codePoints(rule(x), rule(y));
            return c != 0 ? c : x.getOriginalId().toString().compareTo(y.getOriginalId().toString());
        });
        out.addAll(granted);
        return out;
    }

    private static String rule(Ability a) {
        String r = a.getRule();
        return r == null ? "" : r;
    }

    @Override
    public SpellAbility chooseAbilityForCast(Card card, Game game, boolean noMana) {
        live(game, "chooseAbilityForCast");
        try {
            Map<UUID, SpellAbility> usable = PlayerImpl.getCastableSpellAbilities(game, getId(), card,
                    game.getState().getZone(card.getId()), noMana);
            List<SpellAbility> options = new ArrayList<>();
            for (SpellAbility a : usable.values()) {
                if (a.getTargets().canChoose(getId(), a, game)) {
                    options.add(a);
                }
            }
            if (options.isEmpty()) {
                return null;
            }
            if (options.size() == 1) {
                return options.get(0);
            }
            UUID cardId = card.getId();
            Pose pose = new Pose(seat(), false, "choose_cast_method");
            for (int i = 0; i < options.size(); i++) {
                String method = castMethod(options.get(i));
                pose.add(o -> {
                    Map<String, Object> s = sem("choose_cast_method");
                    s.put("source", ref(o, cardId));
                    s.put("method", method);
                    return s;
                }, cardId).order(0, null, i);
            }
            return options.get(ask(pose));
        } catch (Exchange.Closed e) {
            throw e;
        } catch (RuntimeException e) {
            throw fail(e);
        }
    }

    /**
     * "You may play that card" for free (CardUtil's free plays): XMage's PlayerImpl answers with the card's spell
     * ability and never asks. Posed when the card can be played more than one way: as {@code choose_option}, one
     * option per way ("play_land", or "cast:" and the Section 7.4 method), since v2.0 has no kind for a land or a
     * spell (a question for P).
     */
    @Override
    public ActivatedAbility chooseLandOrSpellAbility(Card card, Game game, boolean noMana) {
        live(game, "chooseLandOrSpellAbility");
        try {
            MageObject object = game.getObject(card.getId());
            if (object == null) {
                return card.getSpellAbility();
            }
            List<ActivatedAbility> options = new ArrayList<>();
            for (SpellAbility a : PlayerImpl.getCastableSpellAbilities(game, getId(), object,
                    game.getState().getZone(object.getId()), noMana).values()) {
                if (a.getTargets().canChoose(getId(), a, game)) {
                    options.add(a);
                }
            }
            if (canPlayLand() && game.isActivePlayer(getId())) {
                for (Ability a : card.getAbilities(game)) {
                    if (a instanceof mage.abilities.PlayLandAbility) {
                        options.add((ActivatedAbility) a);
                    }
                }
            }
            if (options.isEmpty()) {
                return card.getSpellAbility();
            }
            if (options.size() == 1) {
                return options.get(0);
            }
            UUID cardId = card.getId();
            Pose pose = new Pose(seat(), false, "choose_option:land_or_spell");
            pose.sort = false;
            long count = options.size();
            for (int i = 0; i < options.size(); i++) {
                ActivatedAbility a = options.get(i);
                String label = a instanceof SpellAbility ? "cast:" + castMethod((SpellAbility) a) : "play_land";
                long idx = i;
                pose.add(o -> {
                    Map<String, Object> s = sem("choose_option");
                    s.put("source", o.reference(cardId));
                    s.put("purpose", "effect_option");
                    s.put("option_index", idx);
                    s.put("option_count", count);
                    s.put("option_label", label);
                    return s;
                }, cardId);
            }
            ex().stats.add("land_or_spell_posed");
            return options.get(ask(pose));
        } catch (Exchange.Closed e) {
            throw e;
        } catch (RuntimeException e) {
            throw fail(e);
        }
    }

    // =============================================================================================
    // targets and selections (Sections 7.3 and 7.5)

    @Override
    public boolean chooseTarget(Outcome outcome, Target target, Ability source, Game game) {
        if (isInPayManaMode()) {
            return payChoose("chooseTarget", outcome, target, source, game, null);
        }
        live(game, "chooseTarget");
        return selectGuarded(true, outcome, target, source, game, null);
    }

    @Override
    public boolean choose(Outcome outcome, Target target, Ability source, Game game) {
        if (isInPayManaMode()) {
            return payChoose("choose", outcome, target, source, game, null);
        }
        live(game, "choose");
        return selectGuarded(false, outcome, target, source, game, null);
    }

    @Override
    public boolean choose(Outcome outcome, Target target, Ability source, Game game,
                          Map<String, Serializable> options) {
        if (isInPayManaMode()) {
            return payChoose("choose", outcome, target, source, game, null);
        }
        live(game, "choose");
        return selectGuarded(false, outcome, target, source, game, null);
    }

    @Override
    public boolean chooseTarget(Outcome outcome, Cards cards, TargetCard target, Ability source, Game game) {
        if (isInPayManaMode()) {
            return payChoose("chooseTarget:cards", outcome, target, source, game, cards);
        }
        live(game, "chooseTarget");
        if (cards == null || cards.isEmpty()) {
            return false;
        }
        return selectGuarded(true, outcome, target, source, game, cards);
    }

    @Override
    public boolean choose(Outcome outcome, Cards cards, TargetCard target, Ability source, Game game) {
        if (isInPayManaMode()) {
            return payChoose("choose:cards", outcome, target, source, game, cards);
        }
        live(game, "choose");
        if (cards == null || cards.isEmpty()) {
            return false;
        }
        return selectGuarded(false, outcome, target, source, game, cards);
    }

    /**
     * Divided damage and distributed counters (CR 601.2d; Annex C {@code chooseTargetAmount}, fail-closed in CABT):
     * the targets one per decision, each its own group with a finish once the minimum is met, at most one target
     * per point to divide; then a {@code distribute} group with one decision per target in the order chosen, every
     * target getting at least 1 (Section 7.5 Distribution).
     */
    @Override
    public boolean chooseTargetAmount(Outcome outcome, TargetAmount target, Ability source, Game game) {
        if (isInPayManaMode()) {
            ex().stats.add("autopay_default:chooseTargetAmount");
            return super.chooseTargetAmount(outcome, target, source, game);
        }
        live(game, "chooseTargetAmount");
        try {
            target.prepareAmount(source, game);
            if (source == null || target.getAmountRemaining() <= 0
                    || (target.getMaxNumberOfTargets() == 0 && target.getMinNumberOfTargets() == 0)) {
                return false;
            }
            int total = target.getAmountTotal(game, source);
            if (total <= 0) {
                return false;
            }
            UUID controller = target.getAffectedAbilityControllerId(getId());
            UUID stack = stackId(source, game);
            String family = stack != null ? "choose_target" : "select_object";
            long slot = stack != null ? slot(source, target) : 0;
            int min = target.getMinNumberOfTargets();
            int max = target.getMaxNumberOfTargets() <= 0 ? Integer.MAX_VALUE : target.getMaxNumberOfTargets();
            while (true) {
                int sel = target.getTargets().size();
                Set<UUID> now = possible(target, controller, source, game, null);
                int cap = (int) Math.min(Math.min((long) max, total), (long) sel + now.size());
                if (sel >= cap || now.isEmpty()) {
                    break;
                }
                Pose pose = selectPose(family, "other", null, source, stack, slot, now, sel, Math.min(min, cap), cap,
                        sel >= min);
                UUID chosen = pick(pose, now);
                if (chosen == null) {
                    break;
                }
                target.addTarget(chosen, source, game);
                if (!target.getTargets().contains(chosen)) {
                    throw ex().halt("target_not_added");
                }
            }
            List<UUID> targets = new ArrayList<>(target.getTargets());
            if (targets.isEmpty()) {
                return false;
            }
            String purpose = source.getRule() != null && source.getRule().contains("damage") ? "damage"
                    : source.getRule() != null && source.getRule().contains("counter") ? "counters" : "other";
            int n = targets.size();
            int assigned = 0;
            for (int i = 0; i < n; i++) {
                long remaining = total - assigned;
                long most = remaining - (n - 1 - i);
                long least = i == n - 1 ? remaining : 1;
                List<Long> legal = new ArrayList<>();
                for (long a = least; a <= most; a++) {
                    legal.add(a);
                }
                if (legal.isEmpty()) {
                    throw ex().halt("dead_end:distribute");
                }
                Pose pose = distributePose(targets.get(i), legal, remaining, purpose, stack, source).substep(i, n);
                int a = (int) (long) legal.get(ask(pose));
                target.setTargetAmount(targets.get(i), a, source, game);
                assigned += a;
            }
            return true;
        } catch (Exchange.Closed e) {
            throw e;
        } catch (RuntimeException e) {
            throw fail(e);
        }
    }

    /** An object choice inside a payment: engine autopay's, made by {@link PayChoice} (Section 7.6). */
    private boolean payChoose(String method, Outcome outcome, Target target, Ability source, Game game,
                              Cards cards) {
        ex().stats.add("autopay_default:" + method);
        return PayChoice.choose(this, outcome, target, source, game, cards);
    }

    /** Gives a choice whose options came in a hash collection a code point order before XMage reads it (X4h). */
    private void visibleChoiceOrder(Choice choice) {
        if (choice.isKeyChoice()) {
            Map<String, String> m = choice.getKeyChoices();
            if (m instanceof java.util.HashMap && !(m instanceof LinkedHashMap)) {
                List<String> keys = new ArrayList<>(m.keySet());
                List<String> values = new ArrayList<>();
                for (String k : keys) {
                    values.add(m.get(k));
                }
                sortByValue(keys, values);
                Map<String, String> sorted = new LinkedHashMap<>();
                for (int i = 0; i < keys.size(); i++) {
                    sorted.put(keys.get(i), values.get(i));
                }
                choice.setKeyChoices(sorted);
            }
        } else {
            Set<String> c = choice.getChoices();
            if (c instanceof java.util.HashSet && !(c instanceof LinkedHashSet)) {
                List<String> values = new ArrayList<>(c);
                values.sort(HiddenOrder::codePoints);
                choice.setChoices(new LinkedHashSet<>(values));
            }
        }
    }

    private boolean selectGuarded(boolean targeted, Outcome outcome, Target target, Ability source, Game game,
                                  Cards cards) {
        try {
            return select(targeted, outcome, target, source, game, cards);
        } catch (Exchange.Closed e) {
            throw e;
        } catch (RuntimeException e) {
            throw fail(e);
        }
    }

    private Set<UUID> possible(Target target, UUID controller, Ability source, Game game, Cards cards) {
        Set<UUID> p = cards == null ? target.possibleTargets(controller, source, game)
                : target.possibleTargets(controller, source, game, cards);
        Set<UUID> out = new LinkedHashSet<>(p);
        out.removeAll(target.getTargets());
        return out;
    }

    private boolean select(boolean targeted, Outcome outcome, Target target, Ability source, Game game,
                           Cards cards) {
        UUID controller = target.getAffectedAbilityControllerId(getId());
        // answers a posed group already decided (an order loop, the London bottom loop)
        if (!st.scriptedCards.isEmpty()) {
            UUID id = st.scriptedCards.poll();
            if (!possible(target, controller, source, game, cards).contains(id)) {
                throw ex().halt("script_mismatch");
            }
            if (targeted) {
                target.addTarget(id, source, game);
            } else {
                target.add(id, game);
            }
            return true;
        }
        if (st.finishedTarget == target && st.finishedSize == target.getTargets().size()) {
            st.finishedTarget = null;
            return false; // the seat already ended this selection with a finish candidate
        }
        st.finishedTarget = null;
        if (target.isChoiceCompleted(controller, source, game, cards)) {
            return false;
        }
        if (isMulliganBottom(game, target, source)) {
            return mulliganBottom(game, target);
        }
        CallSite site = CallSite.here();
        String costClass = site.cost();
        UUID stack = stackId(source, game);
        boolean search = target instanceof TargetCardInLibrary
                || (target.getZone() == Zone.LIBRARY && cards == null);
        int min = target.getMinNumberOfTargets();
        int max = target.getMaxNumberOfTargets();
        boolean required = targeted || costClass != null || !target.isRequiredExplicitlySet() || target.isRequired();
        int effMin = search ? 0 : (required ? min : 0);
        int sel = target.getTargets().size();
        Set<UUID> poss = possible(target, controller, source, game, cards);
        int cap = (max <= 0 || max == Integer.MAX_VALUE || max > sel + poss.size()) ? sel + poss.size() : max;
        if (cap <= sel) {
            return target.isChosen(game) && sel > 0;
        }
        String family;
        if (targeted && !target.isNotTarget() && stack != null) {
            family = "choose_target";
        } else if (costClass != null && stack != null && effMin == cap) {
            family = "choose_cost_target";
        } else {
            family = "select_object";
        }
        String purpose = search ? "search" : selectPurpose(outcome, site, costClass);
        long slot = "choose_target".equals(family) ? slot(source, target) : 0;
        String costKind = costClass == null ? null : CallSite.costKind(costClass);
        boolean fixed = effMin == cap && !search;
        if (fixed) {
            int n = cap - sel;
            for (int i = 0; i < n; i++) {
                Set<UUID> now = completable(possible(target, controller, source, game, cards), target, controller,
                        source, game, cards, n - i - 1, family);
                if (now.isEmpty()) {
                    if (i == 0 && "choose_target".equals(family)) {
                        // no complete set of targets exists (Run Away Together with every creature on one side):
                        // the choice fails before any decision, XMage rolls the cast back, and the seat's priority
                        // decision is re-posed with rewind (Section 8), as for any failed activation
                        ex().stats.add("targets_incompletable");
                        return false;
                    }
                    throw ex().halt("dead_end:" + family);
                }
                Pose pose = selectPose(family, purpose, costKind, source, stack, slot, now,
                        target.getTargets().size(), Math.min(effMin, cap), cap, false).substep(i, n);
                showCards(pose, cards);
                UUID chosen = pick(pose, now);
                if (targeted) {
                    target.addTarget(chosen, source, game);
                } else {
                    target.add(chosen, game);
                }
                if (!target.getTargets().contains(chosen)) {
                    throw ex().halt("target_not_added");
                }
            }
            return true;
        }
        // variable count: one group per decision, finish once the minimum is met (Section 7.5)
        while (true) {
            sel = target.getTargets().size();
            Set<UUID> now = possible(target, controller, source, game, cards);
            if (sel < effMin) {
                now = completable(now, target, controller, source, game, cards, effMin - sel - 1, family);
            }
            if (sel >= cap || now.isEmpty()) {
                if (sel < effMin) {
                    ex().stats.add("selection_short:" + family);
                }
                return target.isChosen(game) && sel > 0;
            }
            boolean finish = sel >= effMin;
            Pose pose = selectPose(family, purpose, costKind, source, stack, slot, now, sel,
                    Math.min(effMin, cap), cap, finish);
            showCards(pose, cards);
            UUID chosen = pick(pose, now);
            if (chosen == null) {
                st.finishedTarget = target;
                st.finishedSize = sel;
                return target.isChosen(game) && sel > 0;
            }
            if (targeted) {
                target.addTarget(chosen, source, game);
            } else {
                target.add(chosen, game);
            }
            if (!target.getTargets().contains(chosen)) {
                throw ex().halt("target_not_added");
            }
            if (target.isChoiceCompleted(controller, source, game, cards)) {
                return true;
            }
        }
    }

    /** Complete selections tried per question before {@link #completable} gives up and keeps every candidate. */
    private static final int COMPLETION_BUDGET = 3000;

    /**
     * The candidates of {@code now} after which {@code need} more objects can still be chosen (Section 7.1: no dead
     * ends). Targets whose legality depends on the others already chosen (Run Away Together's "controlled by
     * different players") otherwise offer a first pick with no legal second one. Each pick is tried on a copy of the
     * target with {@code Target.add}, which fires no event; past the budget every remaining candidate is kept.
     */
    private Set<UUID> completable(Set<UUID> now, Target target, UUID controller, Ability source, Game game,
                                  Cards cards, int need, String family) {
        if (need <= 0 || now.isEmpty()) {
            return now;
        }
        int[] budget = {COMPLETION_BUDGET};
        Set<UUID> out = new LinkedHashSet<>();
        for (UUID id : now) {
            Target copy = target.copy();
            copy.add(id, game);
            if (budget[0] <= 0 || fillable(copy, controller, source, game, cards, need, budget)) {
                out.add(id);
            }
        }
        if (budget[0] <= 0) {
            ex().stats.add("completion_budget:" + family);
        }
        if (out.size() < now.size()) {
            ex().stats.add("incompletable_removed:" + family);
        }
        return out;
    }

    private boolean fillable(Target target, UUID controller, Ability source, Game game, Cards cards, int need,
                             int[] budget) {
        if (need <= 0) {
            return true;
        }
        for (UUID id : possible(target, controller, source, game, cards)) {
            if (--budget[0] < 0) {
                return true;
            }
            Target copy = target.copy();
            copy.add(id, game);
            if (fillable(copy, controller, source, game, cards, need - 1, budget)) {
                return true;
            }
        }
        return false;
    }

    /**
     * A card choice shows the seat every card the effect looks at, also those it may not choose (the other seat's
     * lands, say): each one in a hidden zone becomes a {@code known} entry (Section 6.7).
     */
    private static void showCards(Pose pose, Cards cards) {
        if (cards != null) {
            pose.shown.addAll(cards);
        }
    }

    /** Players have no observation position: they sort p0 then p1 (a visible key, never a UUID). */
    private long seatMinor(UUID id) {
        String seat = ex().seatOf(id);
        return seat == null ? 0 : Exchange.SEATS.indexOf(seat);
    }

    /** Asks a selection pose; the chosen id, or null for the finish candidate (always added last). */
    private UUID pick(Pose pose, Set<UUID> ids) {
        List<UUID> order = new ArrayList<>(ids);
        int k = ask(pose);
        return k < order.size() ? order.get(k) : null;
    }

    private Pose selectPose(String family, String purpose, String costKind, Ability source, UUID stack, long slot,
                            Set<UUID> ids, int selected, int minimum, int maximum, boolean finish) {
        Pose pose = new Pose(seat(), false, family + ":" + purpose);
        long sel = selected;
        long min = minimum;
        long max = maximum;
        for (UUID id : ids) {
            pose.add(o -> {
                Map<String, Object> s = sem(family);
                switch (family) {
                    case "choose_target":
                        s.put("source", ref(o, stack));
                        s.put("slot", slot);
                        s.put("target", target(o, id));
                        break;
                    case "choose_cost_target":
                        s.put("source", ref(o, stack));
                        s.put("cost_kind", costKind);
                        s.put("candidate", ref(o, id));
                        break;
                    default:
                        s.put("source", looseSource(o, source, stack));
                        s.put("purpose", purpose);
                        s.put("choice", target(o, id));
                        break;
                }
                s.put("selected_count", sel);
                s.put("minimum", min);
                s.put("maximum", max);
                return s;
            }, id).order(0, id, seatMinor(id));
        }
        if (finish) {
            pose.add(o -> {
                Map<String, Object> s;
                if ("choose_target".equals(family)) {
                    s = sem("finish_target_selection");
                    s.put("source", ref(o, stack));
                    s.put("slot", slot);
                } else {
                    s = sem("finish_selection");
                    s.put("source", looseSource(o, source, stack));
                    s.put("purpose", purpose);
                }
                s.put("selected_count", sel);
                return s;
            }).order(1, null, 0);
        }
        return pose;
    }

    /** The target requirement's 0-based index among the ability's targets in mode order (Section 7.3 slot). */
    private static long slot(Ability source, Target target) {
        if (source == null) {
            return 0;
        }
        long index = 0;
        Modes modes = source.getModes();
        for (UUID modeId : modes.getSelectedModes()) {
            Mode mode = modes.get(modeId);
            if (mode == null) {
                continue;
            }
            for (Target t : mode.getTargets()) {
                if (t == target) {
                    return index;
                }
                index++;
            }
        }
        index = 0;
        for (Target t : source.getTargets()) {
            if (t == target) {
                return index;
            }
            index++;
        }
        return 0;
    }

    private static String selectPurpose(Outcome outcome, CallSite site, String costClass) {
        if (costClass != null) {
            String kind = CallSite.costKind(costClass);
            if (!"other".equals(kind) && !"remove_counter".equals(kind)) {
                return kind;
            }
        }
        if (site.find("LegendRule", "Legendary") != null) {
            return "legend_rule";
        }
        if (outcome == null) {
            return "other";
        }
        switch (outcome) {
            case Discard:
                return "discard";
            case Sacrifice:
                return "sacrifice";
            case Exile:
                return "exile";
            case DestroyPermanent:
                return "destroy";
            case ReturnToHand:
                return "return_to_hand";
            case PutCreatureInPlay:
            case PutCardInPlay:
            case PutLandInPlay:
                return "put_onto_battlefield";
            case Tap:
                return "tap";
            case Untap:
                return "untap";
            default:
                return "other";
        }
    }

    // ---------------------------------------------------------------------------------------------
    // London mulligan bottom (Section 7.5): XMage asks one card per loop; the seat answers all k picks up front

    private static boolean isMulliganBottom(Game game, Target target, Ability source) {
        return source == null && game.getTurnStepType() == null && target instanceof TargetCardInHand
                && game.getMulligan() instanceof LondonMulligan;
    }

    private boolean mulliganBottom(Game game, Target target) {
        int k = getHand().size() - openingHandSize(game);
        if (k <= 0) {
            throw ex().halt("mulligan_bottom_count");
        }
        List<UUID> picked = new ArrayList<>();
        for (int p = 0; p < k; p++) {
            List<UUID> remaining = new ArrayList<>();
            for (UUID id : getHand()) {
                if (!picked.contains(id)) {
                    remaining.add(id);
                }
            }
            Pose pose = new Pose(seat(), false, "order_pick:mulligan_bottom").substep(p, k);
            long position = p;
            long count = k;
            for (UUID id : remaining) {
                pose.add(o -> {
                    Map<String, Object> s = sem("order_pick");
                    s.put("source", null);
                    s.put("purpose", "mulligan_bottom");
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("object", ref(o, id));
                    s.put("item", item);
                    s.put("position", position);
                    s.put("count", count);
                    return s;
                }, id).order(0, id, 0);
            }
            picked.add(remaining.get(ask(pose)));
        }
        // XMage puts each loop's card on the bottom in turn, so the first pick ends closest to the top
        for (int i = 1; i < picked.size(); i++) {
            st.scriptedCards.add(picked.get(i));
        }
        target.add(picked.get(0), game);
        return true;
    }

    private int openingHandSize(Game game) {
        Mulligan m = game.getMulligan();
        try {
            Object v = ((Map<?, ?>) LONDON_OPENING.get(m)).get(getId());
            if (v instanceof Integer) {
                return (Integer) v;
            }
        } catch (IllegalAccessException | RuntimeException e) {
            // fall through
        }
        throw ex().halt("mulligan_state_unreadable");
    }

    // =============================================================================================
    // library orders (Section 7.5 Ordering) and arrangements (scry, surveil)

    @Override
    public boolean putCardsOnTopOfLibrary(Cards cardsToLibrary, Game game, Ability source, boolean anyOrder) {
        if (anyOrder && cardsToLibrary != null && cardsToLibrary.size() > 1 && st.scriptedCards.isEmpty()
                && !isInPayManaMode() && !game.isSimulation()) {
            live(game, "putCardsOnTopOfLibrary");
            try {
                List<UUID> order = orderBlock(game, cardsToLibrary, source, "library_top");
                scriptTop(order);
            } catch (Exchange.Closed e) {
                throw e;
            } catch (RuntimeException e) {
                throw fail(e);
            }
        }
        return super.putCardsOnTopOfLibrary(cardsToLibrary, game, source, anyOrder);
    }

    @Override
    public boolean putCardsOnBottomOfLibrary(Cards cardsToLibrary, Game game, Ability source, boolean anyOrder) {
        if (anyOrder && cardsToLibrary != null && cardsToLibrary.size() > 1 && st.scriptedCards.isEmpty()
                && !isInPayManaMode() && !game.isSimulation()) {
            live(game, "putCardsOnBottomOfLibrary");
            try {
                List<UUID> order = orderBlock(game, cardsToLibrary, source, "library_bottom");
                // XMage puts each chosen card on the bottom in turn, so the first one ends closest to the top
                st.scriptedCards.clear();
                for (int i = 0; i < order.size() - 1; i++) {
                    st.scriptedCards.add(order.get(i));
                }
            } catch (Exchange.Closed e) {
                throw e;
            } catch (RuntimeException e) {
                throw fail(e);
            }
        }
        return super.putCardsOnBottomOfLibrary(cardsToLibrary, game, source, anyOrder);
    }

    /** An order_pick block placing every card (the last position implied): position 0 is closest to the top. */
    private List<UUID> orderBlock(Game game, Cards cards, Ability source, String purpose) {
        List<UUID> remaining = new ArrayList<>(cards);
        List<UUID> order = new ArrayList<>();
        int n = remaining.size();
        UUID stack = stackId(source, game);
        for (int p = 0; p < n - 1; p++) {
            Pose pose = new Pose(seat(), false, "order_pick:" + purpose).substep(p, n - 1);
            long position = p;
            long count = n;
            for (UUID id : remaining) {
                pose.add(o -> {
                    Map<String, Object> s = sem("order_pick");
                    s.put("source", looseSource(o, source, stack));
                    s.put("purpose", purpose);
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("object", ref(o, id));
                    s.put("item", item);
                    s.put("position", position);
                    s.put("count", count);
                    return s;
                }, id).order(0, id, 0);
            }
            UUID chosen = remaining.get(ask(pose));
            order.add(chosen);
            remaining.remove(chosen);
        }
        order.addAll(remaining);
        return order;
    }

    @Override
    public boolean scry(int value, Ability source, Game game) {
        live(game, "scry");
        try {
            GameEvent event = new GameEvent(GameEvent.EventType.SCRY, getId(), source, getId(), value, true);
            if (game.replaceEvent(event)) {
                return false;
            }
            game.informPlayers(getLogName() + " scries " + event.getAmount());
            List<UUID> cards = new ArrayList<>();
            for (Card c : getLibrary().getTopCards(game, event.getAmount())) {
                cards.add(c.getId());
            }
            if (!cards.isEmpty()) {
                Map<String, List<UUID>> placed = arrange(game, source, "scry", cards,
                        new String[]{"top", "bottom"});
                List<UUID> bottom = placed.get("bottom");
                List<UUID> top = placed.get("top");
                if (!bottom.isEmpty()) {
                    scriptBottom(bottom);
                    super.putCardsOnBottomOfLibrary(new CardsImpl(bottom), game, source, true);
                    game.fireEvent(GameEvent.getEvent(GameEvent.EventType.SCRY_TO_BOTTOM, getId(), source, getId(),
                            bottom.size()));
                }
                if (!top.isEmpty()) {
                    scriptTop(top);
                    super.putCardsOnTopOfLibrary(new CardsImpl(top), game, source, true);
                }
            }
            game.fireEvent(new GameEvent(GameEvent.EventType.SCRIED, getId(), source, getId(), event.getAmount(),
                    true));
            return true;
        } catch (Exchange.Closed e) {
            throw e;
        } catch (RuntimeException e) {
            throw fail(e);
        }
    }

    @Override
    public mage.players.Player.SurveilResult doSurveil(int value, Ability source, Game game) {
        live(game, "surveil");
        try {
            GameEvent event = new GameEvent(GameEvent.EventType.SURVEIL, getId(), source, getId(), value, true);
            if (game.replaceEvent(event) || event.getAmount() < 1) {
                return mage.players.Player.SurveilResult.noSurveil();
            }
            game.informPlayers(getLogName() + " surveils " + event.getAmount());
            List<UUID> cards = new ArrayList<>();
            for (Card c : getLibrary().getTopCards(game, event.getAmount())) {
                cards.add(c.getId());
            }
            int total = cards.size();
            int kept = 0;
            if (!cards.isEmpty()) {
                Map<String, List<UUID>> placed = arrange(game, source, "surveil", cards,
                        new String[]{"top", "graveyard"});
                List<UUID> grave = placed.get("graveyard");
                List<UUID> top = placed.get("top");
                if (!grave.isEmpty()) {
                    moveCards(new CardsImpl(grave), Zone.GRAVEYARD, source, game);
                }
                if (!top.isEmpty()) {
                    scriptTop(top);
                    super.putCardsOnTopOfLibrary(new CardsImpl(top), game, source, true);
                }
                kept = top.size();
            }
            game.fireEvent(new GameEvent(GameEvent.EventType.SURVEILED, getId(), source, getId(), event.getAmount(),
                    true));
            return mage.players.Player.SurveilResult.surveil(total - kept, kept);
        } catch (Exchange.Closed e) {
            throw e;
        } catch (RuntimeException e) {
            throw fail(e);
        }
    }

    /** XMage's top loop puts each answer on top and the leftover last: answers from the bottom of the block up. */
    private void scriptTop(List<UUID> top) {
        st.scriptedCards.clear();
        for (int i = top.size() - 1; i >= 1; i--) {
            st.scriptedCards.add(top.get(i));
        }
    }

    /** XMage's bottom loop puts each answer on the bottom and the leftover last: answers from the top down. */
    private void scriptBottom(List<UUID> bottom) {
        st.scriptedCards.clear();
        for (int i = 0; i < bottom.size() - 1; i++) {
            st.scriptedCards.add(bottom.get(i));
        }
    }

    /**
     * A fixed arrangement group of 2n - 1 decisions (Section 7.5): n {@code arrange_card} partitions, top card
     * first, then n - 1 {@code order_pick} placements destination by destination. Returns each destination's cards
     * in placed order (position 0 first).
     */
    private Map<String, List<UUID>> arrange(Game game, Ability source, String purpose, List<UUID> cards,
                                            String[] destinations) {
        int n = cards.size();
        int count = 2 * n - 1;
        UUID stack = stackId(source, game);
        List<Look> looks = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            looks.add(new Look(cards.get(i), "looked_at", i, null));
        }
        Map<String, List<UUID>> into = new LinkedHashMap<>();
        for (String d : destinations) {
            into.put(d, new ArrayList<>());
        }
        int substep = 0;
        for (int i = 0; i < n; i++) {
            UUID card = cards.get(i);
            long index = i;
            long cardCount = n;
            Pose pose = new Pose(seat(), false, "arrange_card:" + purpose).substep(substep++, count);
            pose.looks.addAll(looks);
            pose.sort = false;
            for (String d : destinations) {
                pose.add(o -> {
                    Map<String, Object> s = sem("arrange_card");
                    s.put("source", looseSource(o, source, stack));
                    s.put("purpose", purpose);
                    s.put("card", ref(o, card));
                    s.put("card_index", index);
                    s.put("card_count", cardCount);
                    s.put("destination", d);
                    return s;
                }, card);
            }
            into.get(destinations[ask(pose)]).add(card);
        }
        Map<String, List<UUID>> placed = new LinkedHashMap<>();
        int position = 0;
        for (String d : destinations) {
            List<UUID> left = new ArrayList<>(into.get(d));
            List<UUID> order = new ArrayList<>();
            while (!left.isEmpty()) {
                if (position == n - 1) {
                    order.add(left.remove(0)); // only the final pick of the arrangement is implied
                    position++;
                    break;
                }
                long pos = position;
                long cardCount = n;
                Pose pose = new Pose(seat(), false, "order_pick:arrangement").substep(substep++, count);
                pose.looks.addAll(looks);
                pose.sort = false;
                for (UUID id : left) {
                    pose.add(o -> {
                        Map<String, Object> s = sem("order_pick");
                        s.put("source", looseSource(o, source, stack));
                        s.put("purpose", "arrangement");
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("object", ref(o, id));
                        s.put("item", item);
                        s.put("position", pos);
                        s.put("count", cardCount);
                        return s;
                    }, id);
                }
                order.add(left.remove(ask(pose)));
                position++;
            }
            placed.put(d, order);
        }
        return placed;
    }

    // =============================================================================================
    // yes/no (Section 7.3 choose_boolean, optional_cost, choose_cast_method)

    @Override
    public boolean chooseUse(Outcome outcome, String message, Ability source, Game game) {
        return chooseUse(outcome, message, null, null, null, source, game);
    }

    @Override
    public boolean chooseUse(Outcome outcome, String message, String secondMessage, String trueText,
                             String falseText, Ability source, Game game) {
        if (isInPayManaMode()) {
            ex().stats.add("autopay_default:chooseUse");
            return super.chooseUse(outcome, message, secondMessage, trueText, falseText, source, game);
        }
        live(game, "chooseUse");
        try {
            CallSite site = CallSite.here();
            UUID stack = stackId(source, game);
            String cost = optionalCost(site);
            Pose pose;
            if (cost != null && stack != null) {
                pose = new Pose(seat(), false, "optional_cost:" + cost);
                // no dead ends (Section 7.1): pay is offered only when the seat's mana can cover it
                boolean payable = optionalCostPayable(game, cost, message, source);
                if (!payable) {
                    ex().stats.add("optional_cost_unpayable:" + cost);
                }
                for (boolean pay : payable ? new boolean[]{false, true} : new boolean[]{false}) {
                    pose.add(o -> {
                        Map<String, Object> s = sem("optional_cost");
                        s.put("source", ref(o, stack));
                        s.put("cost", cost);
                        s.put("pay", pay);
                        return s;
                    }).order(0, null, pay ? 1 : 0);
                }
            } else if (site.find("AlternativeCost", "Evoke") != null && stack != null) {
                String alt = site.find("Evoke") != null ? "evoke" : "alternative";
                pose = new Pose(seat(), false, "choose_cast_method");
                for (String method : new String[]{"normal", alt}) {
                    pose.add(o -> {
                        Map<String, Object> s = sem("choose_cast_method");
                        s.put("source", ref(o, stack));
                        s.put("method", method);
                        return s;
                    }).order(0, null, "normal".equals(method) ? 0 : 1);
                }
                return ask(pose) == 1;
            } else {
                String purpose = booleanPurpose(site, source, stack);
                if (cost != null) {
                    ex().stats.add("optional_cost_without_stack:" + cost);
                }
                pose = new Pose(seat(), false, "choose_boolean:" + purpose);
                for (boolean value : new boolean[]{false, true}) {
                    pose.add(o -> {
                        Map<String, Object> s = sem("choose_boolean");
                        s.put("source", looseSource(o, source, stack));
                        s.put("purpose", purpose);
                        s.put("value", value);
                        return s;
                    }).order(0, null, value ? 1 : 0);
                }
                ex().stats.add("choose_use_site:" + purpose + ":" + site.innermost());
            }
            return ask(pose) == 1;
        } catch (Exchange.Closed e) {
            throw e;
        } catch (RuntimeException e) {
            throw fail(e);
        }
    }

    /**
     * Whether the seat's available mana covers an optional cost XMage asks about: the mana symbols in its prompt,
     * plus, for a cost paid while casting, the spell's own mana cost. A cost without mana symbols is not gated.
     */
    private boolean optionalCostPayable(Game game, String cost, String message, Ability source) {
        mage.Mana extra = manaIn(message);
        if (extra == null) {
            return true;
        }
        mage.Mana needed = extra.copy();
        if (!"unless_payment".equals(cost) && !"other".equals(cost) && source != null
                && source.getAbilityType() == AbilityType.SPELL) {
            needed.add(source.getManaCostsToPay().getMana());
        }
        return getManaAvailable(game).enough(needed);
    }

    private static final java.util.regex.Pattern MANA_RUN = java.util.regex.Pattern.compile("(\\{[^}]+\\})+");

    /**
     * The mana of the first run of mana symbols in an XMage prompt text, or null when it has none. Parsed here,
     * never with {@code ManaCostsImpl(String)}: that constructor caches parsed costs process-wide and mints object
     * ids only on a cache miss, so a parse in a game would draw ids depending on the games the process played
     * before (found by the X4 cross-process rerun). Hybrid and Phyrexian symbols count as one generic mana.
     */
    static mage.Mana manaIn(String text) {
        if (text == null) {
            return null;
        }
        java.util.regex.Matcher m = MANA_RUN.matcher(text);
        if (!m.find()) {
            return null;
        }
        mage.Mana mana = new mage.Mana();
        java.util.regex.Matcher symbol = java.util.regex.Pattern.compile("\\{([^}]+)\\}").matcher(m.group());
        boolean any = false;
        while (symbol.find()) {
            String sym = symbol.group(1);
            any = true;
            mage.constants.ManaType type;
            int amount = 1;
            if (sym.matches("[0-9]+")) {
                type = mage.constants.ManaType.GENERIC;
                amount = Integer.parseInt(sym);
            } else if (sym.equals("W")) {
                type = mage.constants.ManaType.WHITE;
            } else if (sym.equals("U")) {
                type = mage.constants.ManaType.BLUE;
            } else if (sym.equals("B")) {
                type = mage.constants.ManaType.BLACK;
            } else if (sym.equals("R")) {
                type = mage.constants.ManaType.RED;
            } else if (sym.equals("G")) {
                type = mage.constants.ManaType.GREEN;
            } else if (sym.equals("C")) {
                type = mage.constants.ManaType.COLORLESS;
            } else if (sym.equals("X")) {
                continue;
            } else {
                type = mage.constants.ManaType.GENERIC;
            }
            mana.add(new mage.Mana(type, amount));
        }
        return any ? mana : null;
    }

    /** Section 7.4 {@code optional_cost.cost} of the calling cost class, or null for a plain yes/no. */
    private static String optionalCost(CallSite site) {
        String[][] costs = {
                {"Kicker", "kicker"}, {"Multikicker", "kicker"}, {"Buyback", "buyback"}, {"Entwine", "entwine"},
                {"Conspire", "conspire"}, {"Casualty", "casualty"}, {"Bargain", "bargain"}, {"Gift", "gift"},
                {"Offspring", "offspring"}, {"Replicate", "copy"}, {"UnlessPays", "unless_payment"},
                {"UnlessAnyPlayerPays", "unless_payment"}, {"UnlessPay", "unless_payment"},
                {"OptionalAdditionalCost", "additional"}, {"DoIfCostPaid", "other"}};
        for (String[] c : costs) {
            if (site.find(c[0]) != null) {
                return c[1];
            }
        }
        return null;
    }

    private static String booleanPurpose(CallSite site, Ability source, UUID stack) {
        if (site.find("Replacement") != null) {
            return "optional_replacement";
        }
        if (site.find("TriggeredAbility") != null && source instanceof TriggeredAbility) {
            return "optional_trigger";
        }
        if (site.find("Reveal") != null) {
            return "reveal";
        }
        if (site.find("Cast", "PlayCard") != null && site.find("Effect") != null) {
            return "may_cast";
        }
        if (stack != null) {
            return "may_ability";
        }
        return "other";
    }

    // =============================================================================================
    // choices: colors, names, options (Section 7.3; Annex C CHOOSE_CHOICE)

    @Override
    public boolean choose(Outcome outcome, Choice choice, Game game) {
        if (isInPayManaMode() || outcome == Outcome.PutManaInPool) {
            ex().stats.add("autopay_choice");
            visibleChoiceOrder(choice);
            return super.choose(outcome, choice, game);
        }
        live(game, "choose");
        try {
            UUID stack = null;
            List<String> keys = new ArrayList<>();
            List<String> values = new ArrayList<>();
            if (choice.isKeyChoice()) {
                for (Map.Entry<String, String> e : choice.getKeyChoices().entrySet()) {
                    keys.add(e.getKey());
                    values.add(e.getValue());
                }
            } else {
                for (String c : choice.getChoices()) {
                    keys.add(c);
                    values.add(c);
                }
            }
            if (keys.isEmpty()) {
                return false;
            }
            Object source = choice.isKeyChoice() ? choice.getKeyChoices() : choice.getChoices();
            if (source instanceof java.util.HashMap && !(source instanceof LinkedHashMap)
                    || source instanceof java.util.HashSet && !(source instanceof LinkedHashSet)) {
                // the card's options came in a hash collection: a visible order instead of the hash order (X4h)
                sortByValue(keys, values);
                ex().stats.add("choice_hash_order_sorted");
            }
            Pose pose;
            List<Integer> index = new ArrayList<>();
            Pose castMethod = castMethodChoice(choice, values, game, index);
            if (castMethod != null) {
                pose = castMethod;
            } else if (choice instanceof ChoiceColor) {
                pose = new Pose(seat(), false, "choose_color");
                for (int i = 0; i < keys.size(); i++) {
                    String color = values.get(i).toLowerCase(Locale.ROOT);
                    if (colorRank(color) < 0) {
                        continue;
                    }
                    pose.add(o -> {
                        Map<String, Object> s = sem("choose_color");
                        s.put("source", null);
                        s.put("purpose", "effect");
                        s.put("color", color);
                        return s;
                    }).order(0, null, colorRank(color));
                    index.add(i);
                }
            } else {
                String namePurpose = namePurpose(choice, values);
                if (namePurpose != null) {
                    pose = new Pose(seat(), false, "choose_name:" + namePurpose);
                    // names in code point order of their wire value, whatever order XMage's set holds (X4h)
                    List<Integer> byName = new ArrayList<>();
                    List<String> wire = new ArrayList<>();
                    for (int i = 0; i < keys.size(); i++) {
                        byName.add(i);
                        wire.add(nameValue(namePurpose, values.get(i)));
                    }
                    byName.sort((x, y) -> {
                        String a = wire.get(x);
                        String b = wire.get(y);
                        if (a == null || b == null) {
                            return a == null ? (b == null ? Integer.compare(x, y) : 1) : -1;
                        }
                        int c = HiddenOrder.codePoints(a, b);
                        return c != 0 ? c : Integer.compare(x, y);
                    });
                    for (int i : byName) {
                        String raw = values.get(i);
                        String value = nameValue(namePurpose, raw);
                        if (value == null) {
                            continue;
                        }
                        pose.add(o -> {
                            Map<String, Object> s = sem("choose_name");
                            s.put("source", null);
                            s.put("purpose", namePurpose);
                            s.put("value", value);
                            return s;
                        }).order(0, null, 0);
                        index.add(i);
                    }
                    pose.sort = false;
                } else {
                    pose = new Pose(seat(), false, "choose_option");
                    long count = keys.size();
                    for (int i = 0; i < keys.size(); i++) {
                        long idx = i;
                        String label = values.get(i);
                        pose.add(o -> {
                            Map<String, Object> s = sem("choose_option");
                            s.put("source", null);
                            s.put("purpose", "effect_option");
                            s.put("option_index", idx);
                            s.put("option_count", count);
                            s.put("option_label", label);
                            return s;
                        }).order(0, null, i);
                        index.add(i);
                    }
                    ex().stats.add("choose_option_site:" + CallSite.here().innermost());
                }
            }
            if (pose.cands.isEmpty()) {
                throw ex().halt("empty_choice");
            }
            int k = index.get(ask(pose));
            if (choice.isKeyChoice()) {
                choice.setChoiceByKey(keys.get(k));
            } else {
                choice.setChoice(keys.get(k));
            }
            return true;
        } catch (Exchange.Closed e) {
            throw e;
        } catch (RuntimeException e) {
            throw fail(e);
        }
    }

    /**
     * XMage's alternative-cost menu while casting ({@code AbilityImpl}: "Choose an alternative cost") is the cast
     * method (Section 7.3 {@code choose_cast_method}), sourced by the spell being cast, on top of the stack. Null
     * when the menu is something else or two of its entries map to the same method.
     */
    private Pose castMethodChoice(Choice choice, List<String> labels, Game game, List<Integer> index) {
        String message = choice.getMessage();
        if (message == null || !message.toLowerCase(Locale.ROOT).contains("alternative cost")
                || game.getStack().isEmpty()) {
            return null;
        }
        UUID spell = game.getStack().getFirst().getId();
        Pose pose = new Pose(seat(), false, "choose_cast_method");
        pose.sort = false;
        Set<String> methods = new LinkedHashSet<>();
        List<Integer> local = new ArrayList<>();
        for (int i = 0; i < labels.size(); i++) {
            String method = methodOfLabel(labels.get(i));
            if (!methods.add(method)) {
                return null;
            }
            pose.add(o -> {
                Map<String, Object> s = sem("choose_cast_method");
                s.put("source", ref(o, spell));
                s.put("method", method);
                return s;
            });
            local.add(i);
        }
        index.addAll(local);
        return pose;
    }

    private static String methodOfLabel(String label) {
        String l = label.toLowerCase(Locale.ROOT);
        if (l.contains("no alternative cost")) {
            return "normal";
        }
        String[] methods = {"disguise", "morph", "evoke", "foretell", "plot", "madness", "miracle", "flashback",
                "escape", "overload", "prototype", "suspend", "disturb"};
        for (String m : methods) {
            if (l.contains(m)) {
                return m;
            }
        }
        return "alternative";
    }

    /** Sorts parallel key and value lists by value, then key, in code point order. */
    private static void sortByValue(List<String> keys, List<String> values) {
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < keys.size(); i++) {
            order.add(i);
        }
        java.util.Comparator<String> cp = HiddenOrder::codePoints;
        order.sort((x, y) -> {
            int c = cp.compare(values.get(x), values.get(y));
            return c != 0 ? c : cp.compare(keys.get(x), keys.get(y));
        });
        List<String> k = new ArrayList<>();
        List<String> v = new ArrayList<>();
        for (int i : order) {
            k.add(keys.get(i));
            v.add(values.get(i));
        }
        keys.clear();
        keys.addAll(k);
        values.clear();
        values.addAll(v);
    }

    private static long colorRank(String color) {
        return java.util.Arrays.asList("white", "blue", "black", "red", "green").indexOf(color);
    }

    /** The choose_name purpose of a name choice, or null for a plain option choice. */
    private String namePurpose(Choice choice, List<String> values) {
        if (choice instanceof ChoiceCreatureType) {
            return "creature_type";
        }
        if (choice instanceof ChoiceBasicLandType) {
            return "basic_land_type";
        }
        if (choice instanceof ChoiceLandType) {
            return "land_type";
        }
        if (choice instanceof ChoiceCardType) {
            return "card_type";
        }
        // a card-name choice (XMage offers its whole database): restricted to rules.card_name_domain (Section 7.5)
        if (values.size() > 64 && CallSite.here().find("Name") != null) {
            return "card_name";
        }
        return null;
    }

    private String nameValue(String purpose, String raw) {
        if ("card_name".equals(purpose)) {
            String nfc = Normalizer.normalize(raw, Normalizer.Form.NFC);
            return ex().cardNameDomain.contains(nfc) ? nfc : null;
        }
        String s = Normalizer.normalize(raw, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        s = s.toLowerCase(Locale.ROOT).replace("'", "").replace("\u2019", "").replace(' ', '_').replace('-', '_');
        return s.matches("[a-z][a-z0-9_]*") ? s : null;
    }

    // =============================================================================================
    // piles, modes, numbers

    @Override
    public boolean choosePile(Outcome outcome, String message, List<? extends Card> pile1,
                              List<? extends Card> pile2, Game game) {
        live(game, "choosePile");
        try {
            List<UUID> a = new ArrayList<>();
            List<UUID> b = new ArrayList<>();
            for (Card c : pile1) {
                a.add(c.getId());
            }
            for (Card c : pile2) {
                b.add(c.getId());
            }
            Pose pose = new Pose(seat(), false, "choose_pile");
            List<UUID> all = new ArrayList<>(a);
            all.addAll(b);
            for (long p = 0; p < 2; p++) {
                long index = p;
                pose.add(o -> {
                    Map<String, Object> s = sem("choose_pile");
                    s.put("source", null);
                    s.put("purpose", "effect");
                    s.put("pile_index", index);
                    List<Object> piles = new ArrayList<>();
                    for (List<UUID> pile : java.util.Arrays.asList(a, b)) {
                        List<Object> refs = new ArrayList<>();
                        for (UUID id : pile) {
                            refs.add(ref(o, id));
                        }
                        piles.add(refs);
                    }
                    s.put("piles", piles);
                    return s;
                }, all.toArray(new UUID[0])).order(0, null, p);
            }
            return ask(pose) == 0; // true picks pile1, as HumanPlayer.choosePile
        } catch (Exchange.Closed e) {
            throw e;
        } catch (RuntimeException e) {
            throw fail(e);
        }
    }

    @Override
    public Mode chooseMode(Modes modes, Ability source, Game game) {
        if (modes.size() == 0) {
            return null;
        }
        if (modes.size() == 1) {
            return modes.getMode();
        }
        live(game, "chooseMode");
        try {
            if (!st.scriptedModes.isEmpty()) {
                UUID id = st.scriptedModes.poll();
                Mode m = modes.get(id);
                if (m == null) {
                    throw ex().halt("script_mismatch");
                }
                return m;
            }
            List<Mode> all = new ArrayList<>(modes.values());
            int max = modes.getMaxModes(game, source);
            int min = modes.getMinModes();
            boolean repeat = modes.isMayChooseSameModeMoreThanOnce();
            boolean pawPrints = modes.getMaxPawPrints() > 0;
            int selected = modes.getSelectedModes().size();
            UUID stack = stackId(source, game);
            if (!pawPrints && min == max && !repeat && selected == 0 && max > 1) {
                // a fixed group of max picks (Section 7.5 Modes)
                List<UUID> picks = new ArrayList<>();
                for (int i = 0; i < max; i++) {
                    List<Mode> avail = modeOptions(modes, source, game, all, picks, false);
                    if (avail.isEmpty()) {
                        throw ex().halt("dead_end:choose_spell_mode");
                    }
                    Pose pose = modePose(all, avail, stack, i, min, max, false).substep(i, max);
                    picks.add(avail.get(ask(pose)).getId());
                }
                for (int i = 1; i < picks.size(); i++) {
                    st.scriptedModes.add(picks.get(i));
                }
                return modes.get(picks.get(0));
            }
            List<Mode> avail = modeOptions(modes, source, game, all, Collections.emptyList(), repeat);
            boolean canStop = pawPrints ? selected > 0 : selected >= min || (modes.isMayChooseNone() && selected == 0);
            if (avail.isEmpty()) {
                return null;
            }
            int cap = pawPrints ? Math.max(selected + 1, all.size()) : max;
            Pose pose = modePose(all, avail, stack, selected, Math.min(min, cap), cap, canStop);
            int k = ask(pose);
            return k < avail.size() ? avail.get(k) : null;
        } catch (Exchange.Closed e) {
            throw e;
        } catch (RuntimeException e) {
            throw fail(e);
        }
    }

    private List<Mode> modeOptions(Modes modes, Ability source, Game game, List<Mode> all,
                                   List<UUID> picked, boolean repeat) {
        List<Mode> out = new ArrayList<>();
        List<Mode> available = modes.getAvailableModes(source, game);
        // modes with their own mana cost (spree): only those the seat's mana still covers (Section 7.1)
        mage.Mana committed = null;
        mage.abilities.mana.ManaOptions mana = null;
        for (Mode m : all) {
            if (m.getCost() instanceof ManaCost) {
                committed = source.getManaCostsToPay().getMana().copy();
                for (UUID id : modes.getSelectedModes()) {
                    Mode chosen = modes.get(id);
                    if (chosen != null && chosen.getCost() instanceof ManaCost) {
                        committed.add(((ManaCost) chosen.getCost()).getMana());
                    }
                }
                for (UUID id : picked) {
                    Mode chosen = modes.get(id);
                    if (chosen != null && chosen.getCost() instanceof ManaCost) {
                        committed.add(((ManaCost) chosen.getCost()).getMana());
                    }
                }
                mana = getManaAvailable(game);
                break;
            }
        }
        for (Mode m : all) {
            if (!available.contains(m)) {
                continue;
            }
            if (!repeat && (modes.getSelectedModes().contains(m.getId()) || picked.contains(m.getId()))) {
                continue;
            }
            if (!m.getTargets().canChoose(source.getControllerId(), source, game)) {
                continue;
            }
            if (mana != null && m.getCost() instanceof ManaCost) {
                mage.Mana needed = committed.copy();
                needed.add(((ManaCost) m.getCost()).getMana());
                if (!mana.enough(needed)) {
                    ex().stats.add("mode_unpayable");
                    continue;
                }
            }
            out.add(m);
        }
        return out;
    }

    private Pose modePose(List<Mode> all, List<Mode> avail, UUID stack, int selected, int min, int max,
                          boolean finish) {
        Pose pose = new Pose(seat(), false, "choose_spell_mode");
        pose.sort = false;
        long count = all.size();
        long sel = selected;
        long lo = min;
        long hi = Math.min(max, all.size());
        for (Mode m : avail) {
            long index = all.indexOf(m);
            pose.add(o -> {
                Map<String, Object> s = sem("choose_spell_mode");
                s.put("source", ref(o, stack));
                s.put("mode_index", index);
                s.put("mode_count", count);
                s.put("selected_count", sel);
                s.put("minimum", lo);
                s.put("maximum", hi);
                return s;
            });
        }
        if (finish) {
            pose.add(o -> {
                Map<String, Object> s = sem("finish_selection");
                s.put("source", o.reference(stack));
                s.put("purpose", "modes");
                s.put("selected_count", sel);
                return s;
            });
        }
        return pose;
    }

    @Override
    public int announceX(int min, int max, String message, Game game, Ability source, boolean isManaPay) {
        live(game, "announceX");
        int bound = max;
        if (isManaPay || max > 4096) {
            int available = 0;
            ManaOptions options = getManaAvailable(game);
            for (mage.Mana m : options) {
                available = Math.max(available, m.count());
            }
            bound = Math.max(min, Math.min(max, available));
        }
        return number("x_value", min, bound, source, game);
    }

    @Override
    public int getAmount(int min, int max, String message, Ability source, Game game) {
        if (isInPayManaMode()) {
            ex().stats.add("autopay_default:getAmount");
            return super.getAmount(min, max, message, source, game);
        }
        live(game, "getAmount");
        CallSite site = CallSite.here();
        String purpose = site.find("PayLife", "PayVariableLife") != null ? "life_payment"
                : site.find("Multikicker", "Replicate", "Repetition") != null ? "cost_repetitions" : "amount";
        return number(purpose, min, max, source, game);
    }

    private int number(String purpose, int min, int max, Ability source, Game game) {
        try {
            if (max < min) {
                max = min;
            }
            if ((long) max - min + 1 > 4096) {
                throw ex().halt("candidate_limit");
            }
            UUID stack = stackId(source, game);
            Pose pose = new Pose(seat(), false, "choose_number:" + purpose);
            pose.sort = false;
            long lo = min;
            long hi = max;
            for (int v = min; v <= max; v++) {
                long value = v;
                pose.add(o -> {
                    Map<String, Object> s = sem("choose_number");
                    s.put("source", looseSource(o, source, stack));
                    s.put("purpose", purpose);
                    s.put("value", value);
                    s.put("minimum", lo);
                    s.put("maximum", hi);
                    return s;
                });
            }
            return min + ask(pose);
        } catch (Exchange.Closed e) {
            throw e;
        } catch (RuntimeException e) {
            throw fail(e);
        }
    }

    // =============================================================================================
    // distributions (Section 7.5): combat damage among blockers or attackers, one decision per recipient

    @Override
    public List<Integer> getMultiAmountWithIndividualConstraints(Outcome outcome, List<MultiAmountMessage> messages,
                                                                 int totalMin, int totalMax, MultiAmountType type,
                                                                 Game game) {
        if (isInPayManaMode()) {
            ex().stats.add("autopay_default:getMultiAmount");
            return super.getMultiAmountWithIndividualConstraints(outcome, messages, totalMin, totalMax, type, game);
        }
        live(game, "getMultiAmount");
        try {
            if (messages == null || messages.isEmpty()) {
                return new ArrayList<>();
            }
            if (type == MultiAmountType.MANA) {
                return manaCombination(messages, totalMin, totalMax);
            }
            CallSite site = CallSite.here();
            if (site.find("CombatGroup") == null) {
                return amountGroup(messages, totalMin, totalMax);
            }
            List<UUID> recipients = combatRecipients(game, messages);
            if (recipients == null) {
                throw ex().halt("unsupported:combat_damage_recipients");
            }
            int n = messages.size();
            boolean trample = totalMin < totalMax;
            UUID defender = trample ? trampleDefender(game, recipients) : null;
            if (trample && defender == null) {
                throw ex().halt("unsupported:trample_defender");
            }
            int total = totalMax;
            int groupSize = n + (trample ? 1 : 0);
            List<Integer> amounts = new ArrayList<>();
            int assigned = 0;
            for (int i = 0; i < n; i++) {
                int lowerRest = 0;
                int upperRest = 0;
                for (int j = i + 1; j < n; j++) {
                    lowerRest += messages.get(j).min;
                    upperRest += messages.get(j).max;
                }
                List<Long> legal = new ArrayList<>();
                for (int a = messages.get(i).min; a <= messages.get(i).max; a++) {
                    if (assigned + a + lowerRest <= totalMax && assigned + a + upperRest >= totalMin) {
                        legal.add((long) a);
                    }
                }
                if (legal.isEmpty()) {
                    throw ex().halt("dead_end:distribute");
                }
                UUID recipient = recipients.get(i);
                long remaining = total - assigned;
                Pose pose = distributePose(recipient, legal, remaining).substep(i, groupSize);
                int a = (int) (long) legal.get(ask(pose));
                amounts.add(a);
                assigned += a;
            }
            if (trample) {
                long rest = total - assigned;
                Pose pose = distributePose(defender, Collections.singletonList(rest), rest).substep(n, groupSize);
                ask(pose);
            }
            return amounts;
        } catch (Exchange.Closed e) {
            throw e;
        } catch (RuntimeException e) {
            throw fail(e);
        }
    }

    /**
     * "Add N mana in any combination of colors" (Chandra, Hope's Beacon's +2; XMage asks one amount per color): one
     * group of N {@code choose_color} decisions with purpose {@code mana}, one per unit of mana. Colors are offered
     * in WUBRG order and never below the previous pick, so each combination has one spelling; a color is offered
     * only while its own bounds and the rest of the group can still be met (Section 7.1).
     */
    private List<Integer> manaCombination(List<MultiAmountMessage> messages, int totalMin, int totalMax) {
        int n = messages.size();
        long[] rank = new long[n];
        String[] color = new String[n];
        for (int i = 0; i < n; i++) {
            String symbol = messages.get(i).message == null ? "" : messages.get(i).message.replace("{", "")
                    .replace("}", "").trim();
            int k = "WUBRG".indexOf(symbol);
            if (symbol.length() != 1 || k < 0) {
                throw ex().halt("unsupported:multi_amount_mana:" + symbol);
            }
            color[i] = java.util.Arrays.asList("white", "blue", "black", "red", "green").get(k);
            rank[i] = k;
        }
        if (totalMin != totalMax) {
            throw ex().halt("unsupported:multi_amount_mana_range");
        }
        int total = totalMax;
        int[] count = new int[n];
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            order.add(i);
        }
        order.sort((a, b) -> Long.compare(rank[a], rank[b]));
        long last = -1;
        for (int step = 0; step < total; step++) {
            Pose pose = new Pose(seat(), false, "choose_color:mana");
            pose.sort = false;
            List<Integer> offered = new ArrayList<>();
            for (int i : order) {
                if (rank[i] < last || count[i] >= messages.get(i).max) {
                    continue;
                }
                if (!manaRestFits(messages, count, rank, i, total - step - 1)) {
                    continue;
                }
                String c = color[i];
                pose.add(o -> {
                    Map<String, Object> m = sem("choose_color");
                    m.put("source", null);
                    m.put("purpose", "mana");
                    m.put("color", c);
                    return m;
                });
                offered.add(i);
            }
            if (offered.isEmpty()) {
                throw ex().halt("dead_end:choose_color");
            }
            int i = offered.get(ask(pose.substep(step, total)));
            count[i]++;
            last = rank[i];
        }
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(count[i]);
        }
        return out;
    }

    /**
     * Any other multi-amount question outside combat (Glissa Sunslayer's "remove up to three counters", one line per
     * counter kind on the target): one group of {@code choose_number} decisions with purpose {@code amount}, one per
     * line, in code point order of the lines (XMage lists counter kinds in hash order), each bounded so the rest of
     * the group can still meet the totals (Section 7.1). v2.0 has no label for which line a number answers; the
     * order is the documented rule (a question for P).
     */
    private List<Integer> amountGroup(List<MultiAmountMessage> messages, int totalMin, int totalMax) {
        int n = messages.size();
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            order.add(i);
        }
        order.sort((a, b) -> {
            String x = messages.get(a).message == null ? "" : messages.get(a).message;
            String y = messages.get(b).message == null ? "" : messages.get(b).message;
            int c = HiddenOrder.codePoints(x, y);
            return c != 0 ? c : Integer.compare(a, b);
        });
        ex().stats.add("multi_amount_numbers");
        Integer[] amounts = new Integer[n];
        int assigned = 0;
        for (int k = 0; k < n; k++) {
            int i = order.get(k);
            int lowerRest = 0;
            int upperRest = 0;
            for (int j = k + 1; j < n; j++) {
                lowerRest += messages.get(order.get(j)).min;
                upperRest += messages.get(order.get(j)).max;
            }
            int lo = Integer.MAX_VALUE;
            int hi = Integer.MIN_VALUE;
            for (int a = messages.get(i).min; a <= messages.get(i).max; a++) {
                if (assigned + a + lowerRest <= totalMax && assigned + a + upperRest >= totalMin) {
                    lo = Math.min(lo, a);
                    hi = Math.max(hi, a);
                }
            }
            if (lo > hi) {
                throw ex().halt("dead_end:choose_number");
            }
            if ((long) hi - lo + 1 > 4096) {
                throw ex().halt("candidate_limit");
            }
            Pose pose = new Pose(seat(), false, "choose_number:amount");
            pose.sort = false;
            long min = lo;
            long max = hi;
            for (int v = lo; v <= hi; v++) {
                long value = v;
                pose.add(o -> {
                    Map<String, Object> s = sem("choose_number");
                    s.put("source", null);
                    s.put("purpose", "amount");
                    s.put("value", value);
                    s.put("minimum", min);
                    s.put("maximum", max);
                    return s;
                });
            }
            int a = lo + ask(pose.substep(k, n));
            amounts[i] = a;
            assigned += a;
        }
        return new ArrayList<>(java.util.Arrays.asList(amounts));
    }

    /** Whether, after one more unit of color {@code pick}, {@code rest} units can still meet every bound. */
    private static boolean manaRestFits(List<MultiAmountMessage> messages, int[] count, long[] rank, int pick,
                                        int rest) {
        int capacity = 0;
        int needed = 0;
        for (int j = 0; j < messages.size(); j++) {
            int have = count[j] + (j == pick ? 1 : 0);
            if (rank[j] < rank[pick]) {
                if (have < messages.get(j).min) {
                    return false; // colors before the pick get no more units
                }
                continue;
            }
            capacity += Math.max(0, messages.get(j).max - have);
            needed += Math.max(0, messages.get(j).min - have);
        }
        return needed <= rest && rest <= capacity;
    }

    private Pose distributePose(UUID recipient, List<Long> amounts, long remaining) {
        return distributePose(recipient, amounts, remaining, "combat_damage", null, null);
    }

    private Pose distributePose(UUID recipient, List<Long> amounts, long remaining, String purpose, UUID stack,
                                Ability source) {
        Pose pose = new Pose(seat(), false, "distribute:" + purpose);
        pose.sort = false;
        for (Long amount : amounts) {
            pose.add(o -> {
                Map<String, Object> s = sem("distribute");
                s.put("source", source == null ? null : looseSource(o, source, stack));
                s.put("purpose", purpose);
                s.put("recipient", target(o, recipient));
                s.put("amount", amount);
                s.put("remaining", remaining);
                return s;
            });
        }
        return pose;
    }

    /**
     * The permanents XMage's damage dialog lists, in its order: a combat group's blockers (an attacker assigning
     * among them) or attackers (a blocker assigning among them) whose dialog lines ("<log name>, P/T: p/t") equal
     * the messages; else each message's first unused match on the battlefield.
     */
    private static List<UUID> combatRecipients(Game game, List<MultiAmountMessage> messages) {
        for (CombatGroup group : game.getCombat().getGroups()) {
            for (List<UUID> ids : java.util.Arrays.asList(group.getBlockers(), group.getAttackers())) {
                if (ids.size() == messages.size() && dialogLines(game, ids, messages)) {
                    return new ArrayList<>(ids);
                }
            }
        }
        List<UUID> out = new ArrayList<>();
        for (MultiAmountMessage m : messages) {
            UUID found = null;
            for (Permanent p : game.getBattlefield().getAllActivePermanents()) {
                if (!out.contains(p.getId()) && dialogLine(p).equals(m.message)) {
                    found = p.getId();
                    break;
                }
            }
            if (found == null) {
                return null;
            }
            out.add(found);
        }
        return out;
    }

    private static boolean dialogLines(Game game, List<UUID> ids, List<MultiAmountMessage> messages) {
        for (int i = 0; i < ids.size(); i++) {
            Permanent p = game.getPermanent(ids.get(i));
            if (p == null || !dialogLine(p).equals(messages.get(i).message)) {
                return false;
            }
        }
        return true;
    }

    private static String dialogLine(Permanent p) {
        return String.format("%s, P/T: %d/%d", p.getLogName(), p.getPower().getValue(), p.getToughness().getValue());
    }

    /** The player or permanent a trampling attacker blocked by these creatures attacks. */
    private static UUID trampleDefender(Game game, List<UUID> blockers) {
        for (CombatGroup group : game.getCombat().getGroups()) {
            if (group.getBlockers().containsAll(blockers)) {
                return group.getDefenderId();
            }
        }
        return null;
    }

    // =============================================================================================
    // combat declarations (Section 7.5): one decision per creature (per block), one group, every candidate checked
    // by the completability oracle (Section 7.1; CombatOracle)

    @Override
    public void selectAttackers(Game game, UUID attackingPlayerId) {
        live(game, "selectAttackers");
        try {
            if (ex().declaredAttack != null && st.attackTurn == game.getTurnNum()) {
                // XMage asks again only after rejecting a declaration: the oracle let a bad one through
                ex().stats.add("attack_rejected");
                if (++st.attackRejections >= 3) {
                    throw ex().halt("dead_end:declare_attack");
                }
            } else {
                st.attackRejections = 0;
            }
            st.attackTurn = game.getTurnNum();
            ex().declaredAttack = null;
            // start from no attackers: XMage pre-declares creatures that must attack one defender
            // (Combat.checkAttackRequirements); the seat declares them, the oracle keeps the requirement
            for (UUID id : new ArrayList<>(game.getCombat().getAttackers())) {
                game.getCombat().removeAttacker(id, game);
                ex().stats.add("attack_predeclared_by_engine");
            }
            FilterCreatureForCombat filter = new FilterCreatureForCombat();
            filter.add(new ControllerIdPredicate(attackingPlayerId));
            List<Permanent> attackers = new ArrayList<>();
            for (Permanent p : game.getBattlefield().getActivePermanents(filter, attackingPlayerId, game)) {
                if (p.canAttack(null, game)) {
                    attackers.add(p);
                }
            }
            if (attackers.isEmpty()) {
                return;
            }
            // defenders in a visible order (Combat keeps them in a HashSet): players by seat, then permanents in
            // battlefield order
            List<UUID> defenders = new ArrayList<>();
            for (String s : Exchange.SEATS) {
                UUID p = ex().playerId(s);
                if (game.getCombat().getDefenders().contains(p)) {
                    defenders.add(p);
                }
            }
            for (Permanent p : game.getBattlefield().getAllActivePermanents()) {
                if (game.getCombat().getDefenders().contains(p.getId())) {
                    defenders.add(p.getId());
                }
            }
            if (defenders.size() != game.getCombat().getDefenders().size()) {
                throw ex().halt("unsupported:defenders");
            }
            CombatOracle oracle = CombatOracle.attack(game, attackingPlayerId, attackers, defenders, ex().stats);
            UUID[] decl = declare(oracle, "declare_attack", (aid, d) -> o -> {
                Map<String, Object> s = sem("declare_attack");
                s.put("attacker", ref(o, aid));
                s.put("defender", d == null ? null : target(o, d));
                return s;
            });
            Map<UUID, UUID> record = new LinkedHashMap<>();
            for (int i = 0; i < decl.length; i++) {
                if (decl[i] != null) {
                    declareAttacker(oracle.creatures.get(i), decl[i], game, false);
                    record.put(oracle.creatures.get(i), decl[i]);
                }
            }
            if (!CombatOracle.attackers(game.getCombat()).equals(record)) {
                ex().stats.add("attack_not_as_declared");
            }
            ex().declaredAttack = record;
        } catch (Exchange.Closed e) {
            throw e;
        } catch (RuntimeException e) {
            throw fail(e);
        }
    }

    @Override
    public void selectBlockers(Ability source, Game game, UUID defendingPlayerId) {
        live(game, "selectBlockers");
        try {
            if (ex().declaredBlock != null && st.blockTurn == game.getTurnNum()) {
                ex().stats.add("block_rejected");
                if (++st.blockRejections >= 3) {
                    throw ex().halt("dead_end:declare_block");
                }
            } else {
                st.blockRejections = 0;
            }
            st.blockTurn = game.getTurnNum();
            ex().declaredBlock = null;
            // start from no blockers: XMage pre-declares a block for each creature that must block
            // (Combat.retrieveMustBlockAttackerRequirements); the seat declares them, XMage's checks in the
            // oracle keep the requirement
            for (UUID id : new ArrayList<>(game.getCombat().getBlockers())) {
                game.getCombat().removeBlocker(id, game);
                ex().stats.add("block_predeclared_by_engine");
            }
            FilterCreatureForCombatBlock filter = new FilterCreatureForCombatBlock();
            filter.add(new ControllerIdPredicate(defendingPlayerId));
            List<Permanent> blockers = new ArrayList<>();
            List<Integer> blocks = new ArrayList<>();
            List<UUID> attackers = new ArrayList<>();
            for (CombatGroup group : game.getCombat().getGroups()) {
                attackers.addAll(group.getAttackers()); // XMage's group order, the order attacks were declared
            }
            for (Permanent p : game.getBattlefield().getActivePermanents(filter, defendingPlayerId, game)) {
                int can = 0;
                for (UUID a : attackers) {
                    CombatGroup g = game.getCombat().findGroup(a);
                    if (g != null && g.canBlock(p, game)) {
                        can++;
                    }
                }
                if (can > 0) {
                    blockers.add(p);
                    // a creature that can block additional attackers gets one decision per block (Section 7.5)
                    blocks.add(p.getMaxBlocks() == 0 ? can : Math.min(can, p.getMaxBlocks()));
                }
            }
            ex().stats.add("blockers_called:" + attackers.size() + ":" + blockers.size());
            if (blockers.isEmpty()) {
                return;
            }
            CombatOracle oracle = CombatOracle.block(game, defendingPlayerId, blockers, blocks, attackers,
                    ex().stats);
            UUID[] decl = declare(oracle, "declare_block", (bid, a) -> o -> {
                Map<String, Object> s = sem("declare_block");
                s.put("blocker", ref(o, bid));
                s.put("attacker", a == null ? null : ref(o, a));
                return s;
            });
            for (int i = 0; i < decl.length; i++) {
                if (decl[i] != null) {
                    declareBlocker(defendingPlayerId, oracle.creatures.get(i), decl[i], game);
                }
            }
            Set<String> record = new LinkedHashSet<>();
            List<String> pairs = new ArrayList<>();
            for (int i = 0; i < decl.length; i++) {
                if (decl[i] != null) {
                    pairs.add(oracle.creatures.get(i) + ">" + decl[i]);
                }
            }
            Collections.sort(pairs);
            record.addAll(pairs);
            if (!CombatOracle.blocks(game.getCombat()).equals(record)) {
                ex().stats.add("block_not_as_declared");
            }
            ex().declaredBlock = record;
        } catch (Exchange.Closed e) {
            throw e;
        } catch (RuntimeException e) {
            throw fail(e);
        }
    }

    /** A combat candidate's semantic for a slot's creature and choice (null: does not attack or block). */
    private interface CombatSemantic {
        Pose.Semantic of(UUID creature, UUID choice);
    }

    /**
     * Poses a declaration group: one decision per oracle slot, each offering the choices the oracle completes
     * (null first, then attackers or defenders in observation order). Returns the declaration.
     */
    private UUID[] declare(CombatOracle oracle, String kind, CombatSemantic semantic) {
        int n = oracle.slots();
        UUID[] decl = new UUID[n];
        for (int i = 0; i < n; i++) {
            Map<Integer, UUID[]> ok = oracle.completable(decl, i);
            if (ok.isEmpty()) {
                throw ex().halt("dead_end:" + kind);
            }
            UUID creature = oracle.creatures.get(i);
            List<UUID> choices = oracle.options.get(i);
            Pose pose = new Pose(seat(), false, kind).substep(i, n);
            List<UUID> offered = new ArrayList<>();
            for (Integer k : ok.keySet()) {
                UUID c = choices.get(k);
                offered.add(c);
                Pose.Cand cand = c == null ? pose.add(semantic.of(creature, null), creature)
                        : pose.add(semantic.of(creature, c), creature);
                if (c == null) {
                    cand.order(0, null, 0);
                } else {
                    cand.order(1, c, seatMinor(c));
                }
            }
            if (offered.size() < choices.size()) {
                ex().stats.add("combat_candidate_not_completable:" + kind);
            }
            decl[i] = offered.get(ask(pose));
        }
        return decl;
    }

    // =============================================================================================
    // triggers, replacements, mulligan

    @Override
    public TriggeredAbility chooseTriggeredAbility(List<TriggeredAbility> abilities, Game game) {
        if (abilities == null || abilities.isEmpty()) {
            return null;
        }
        if (abilities.size() == 1) {
            st.scriptedTriggers.clear();
            return abilities.get(0); // the last position is implied (Section 7.5 Ordering)
        }
        live(game, "chooseTriggeredAbility");
        try {
            if (!st.scriptedTriggers.isEmpty()) {
                UUID id = st.scriptedTriggers.poll();
                for (TriggeredAbility t : abilities) {
                    if (t.getId().equals(id)) {
                        return t;
                    }
                }
                ex().stats.add("trigger_script_reposed");
                st.scriptedTriggers.clear();
            }
            List<TriggeredAbility> left = new ArrayList<>(abilities);
            List<TriggeredAbility> order = new ArrayList<>();
            int n = left.size();
            for (int p = 0; p < n - 1; p++) {
                Pose pose = new Pose(seat(), false, "order_pick:triggers").substep(p, n - 1);
                pose.sort = false;
                long position = p;
                long count = n;
                Map<String, Integer> instances = new LinkedHashMap<>();
                for (TriggeredAbility t : left) {
                    UUID sourceId = t.getSourceId();
                    long abilityIndex = triggerIndex(game, t);
                    String sourceName = sourceNameFor(game, t);
                    MageObject stillThere = t.getSourceObjectIfItStillExists(game);
                    String instanceKey = (stillThere == null ? "gone:" + sourceName : stillThere.getId().toString())
                            + ":" + abilityIndex;
                    long instance = instances.merge(instanceKey, 1, Integer::sum) - 1;
                    pose.add(o -> {
                        Map<String, Object> trigger = new LinkedHashMap<>();
                        Map<String, Object> src = stillThere == null ? null : o.reference(stillThere.getId());
                        trigger.put("source", src);
                        trigger.put("source_name", src != null ? src.get("card_name") : sourceName);
                        trigger.put("ability_index", abilityIndex < 0 ? null : abilityIndex);
                        trigger.put("event_objects", new ArrayList<>());
                        trigger.put("instance", instance);
                        trigger.put("label", null);
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("trigger", trigger);
                        Map<String, Object> s = sem("order_pick");
                        s.put("source", null);
                        s.put("purpose", "triggers");
                        s.put("item", item);
                        s.put("position", position);
                        s.put("count", count);
                        return s;
                    });
                }
                TriggeredAbility chosen = left.get(ask(pose));
                order.add(chosen);
                left.remove(chosen);
            }
            for (int i = 1; i < order.size(); i++) {
                st.scriptedTriggers.add(order.get(i).getId());
            }
            return order.get(0);
        } catch (Exchange.Closed e) {
            throw e;
        } catch (RuntimeException e) {
            throw fail(e);
        }
    }

    /** A trigger's index among its source's triggered abilities (Section 7.3 order_pick), or -1. */
    private static long triggerIndex(Game game, TriggeredAbility t) {
        if (game.getObject(t.getSourceId()) == null) {
            return -1;
        }
        long index = 0;
        for (Ability ab : orderedAbilities(game, t.getSourceId())) {
            if (!(ab instanceof TriggeredAbility)) {
                continue;
            }
            if (ab.getOriginalId().equals(t.getOriginalId()) || ab.getId().equals(t.getId())) {
                return index;
            }
            index++;
        }
        return -1;
    }

    /** The source's public name when its object is gone (Section 6.6); null for an object hidden from both. */
    private String sourceNameFor(Game game, TriggeredAbility t) {
        MageObject o = game.getObject(t.getSourceId());
        if (o == null) {
            o = game.getLastKnownInformation(t.getSourceId(), Zone.BATTLEFIELD);
        }
        if (o == null || o.getName() == null || o.getName().isEmpty()) {
            return null;
        }
        Zone zone = game.getState().getZone(t.getSourceId());
        if (zone == Zone.LIBRARY || (zone == Zone.HAND && !getId().equals(((Card) o).getOwnerId()))) {
            return null;
        }
        if (o instanceof Permanent && ((Permanent) o).isFaceDown(game)) {
            return null;
        }
        return Normalizer.normalize(o.getName(), Normalizer.Form.NFC);
    }

    @Override
    public int chooseReplacementEffect(Map<String, String> effectsMap, Map<String, MageObject> objectsMap,
                                       Game game) {
        if (effectsMap == null || effectsMap.size() <= 1) {
            return 0;
        }
        live(game, "chooseReplacementEffect");
        try {
            Pose pose = new Pose(seat(), false, "choose_replacement");
            pose.sort = false;
            List<String> keys = new ArrayList<>(effectsMap.keySet());
            long count = keys.size();
            String me = seat();
            for (int i = 0; i < keys.size(); i++) {
                MageObject obj = objectsMap == null ? null : objectsMap.get(keys.get(i));
                UUID objId = obj == null ? null : obj.getId();
                long index = i;
                pose.add(o -> {
                    Map<String, Object> s = sem("choose_replacement");
                    Map<String, Object> affected = new LinkedHashMap<>();
                    affected.put("player", me);
                    s.put("affected", affected);
                    s.put("event", "other");
                    s.put("replacement_source", objId == null ? null : o.reference(objId));
                    s.put("replacement_index", index);
                    s.put("replacement_count", count);
                    return s;
                });
            }
            return ask(pose);
        } catch (Exchange.Closed e) {
            throw e;
        } catch (RuntimeException e) {
            throw fail(e);
        }
    }

    @Override
    public boolean chooseMulligan(Game game) {
        live(game, "chooseMulligan");
        if (ex().mulliganNone) {
            return false; // rules.mulligan none: the engine keeps for both seats (Section 7.6)
        }
        try {
            boolean canMulligan = game.getMulligan().canTakeMulligan(game, this);
            Pose pose = new Pose(seat(), false, "mulligan");
            pose.sort = false;
            long handSize = getHand().size();
            for (boolean keep : canMulligan ? new boolean[]{true, false} : new boolean[]{true}) {
                pose.add(o -> {
                    Map<String, Object> s = sem("mulligan");
                    s.put("hand_size", handSize);
                    s.put("mulligans_taken", mulligansTaken(o));
                    s.put("keep", keep);
                    return s;
                });
            }
            return ask(pose) == 1;
        } catch (Exchange.Closed e) {
            throw e;
        } catch (RuntimeException e) {
            throw fail(e);
        }
    }

    /** The viewer's mulligans_taken as its observation states it (validator R2-25). */
    private Object mulligansTaken(Observation o) {
        for (Object p : Exchange.castList(o.json().get("players"))) {
            Map<String, Object> player = Exchange.castMap(p);
            if (seat().equals(player.get("seat"))) {
                return player.get("mulligans_taken");
            }
        }
        return 0L;
    }

    // =============================================================================================

    @Override
    public boolean playMana(Ability ability, ManaCost unpaid, String promptText, Game game) {
        ex().stats.add("autopay");
        boolean paid = super.playMana(ability, unpaid, promptText, game);
        if (!paid) {
            ex().stats.add("autopay_failed");
            st.autopayFailed = true;
        }
        return paid;
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
}
