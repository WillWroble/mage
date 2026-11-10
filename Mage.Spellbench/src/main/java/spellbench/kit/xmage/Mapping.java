package spellbench.kit.xmage;

import mage.abilities.Ability;
import mage.abilities.ActivatedAbility;
import mage.abilities.Mode;
import mage.abilities.Modes;
import mage.abilities.SpellAbility;
import mage.abilities.common.PassAbility;
import mage.abilities.costs.Cost;
import mage.cards.Card;
import mage.cards.ModalDoubleFacedCard;
import mage.game.Game;
import mage.game.stack.Spell;
import mage.game.stack.StackAbility;
import mage.game.stack.StackObject;
import mage.player.spellbench.decide.KitBridge;
import mage.target.Target;
import mage.target.TargetAmount;
import spellbench.kit.core.Json;
import spellbench.kit.core.ObsIndex;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * From world actions back to v2 (design Section 6): the priority semantic of an XMage action computed on the world
 * with the engine's own functions ({@code KitBridge}: {@code ability_index}, cast {@code method}), its key (Section
 * 5.5.2: the canonical JSON of the full semantic), and the executed payload of an activation (Section 5.5.3).
 */
public final class Mapping {

    private Mapping() {
    }

    /** {"player": seat}, {"object_id": id} or null for a world UUID. */
    public static Map<String, Object> targetRef(World w, UUID id) {
        if (id == null) {
            return null;
        }
        String seat = w.seatOf(id);
        if (seat != null) {
            return Json.map("player", seat);
        }
        String oid = w.uuidToId.get(id);
        return oid == null ? null : Json.map("object_id", oid);
    }

    /**
     * The v2 priority semantic of {@code a} on the world (Section 7.2), with references taken from the observation,
     * or null when the action has no v2 form (an unmapped source).
     */
    public static Map<String, Object> prioritySemantic(World w, Game game, Ability a, ObsIndex obs) {
        if (a == null || a instanceof PassAbility) {
            return Json.map("kind", "pass");
        }
        UUID src = a.getSourceId();
        String oid = src == null ? null : w.uuidToId.get(src);
        Map<String, Object> ref = obs.ref(oid);
        if (ref == null) {
            return null;
        }
        switch (a.getAbilityType()) {
            case PLAY_LAND: {
                long face = isBackFace(game, src) ? 1 : 0;
                return Json.map("kind", "play_land", "source", ref, "face", face);
            }
            case SPELL:
                return Json.map("kind", "cast_spell", "source", ref, "method", KitBridge.castMethod((SpellAbility) a));
            case ACTIVATED_NONMANA: {
                long index = KitBridge.abilityIndex(game, a);
                if (index < 0) {
                    index = 1000;
                }
                return Json.map("kind", "activate_ability", "source", ref, "ability_index", index);
            }
            case SPECIAL_ACTION:
                return Json.map("kind", "special_action", "source", ref, "action", specialAction(a));
            default:
                return null;
        }
    }

    public static String key(Map<String, Object> semantic) {
        return semantic == null ? null : Json.canonical(semantic);
    }

    /** Ordinary priority mana activation for an explicitly offered opt-in candidate. */
    public static Map<String, Object> priorityManaSemantic(World w, Game game, Ability a, ObsIndex obs) {
        if (a == null || a.getAbilityType() != mage.constants.AbilityType.ACTIVATED_MANA) {
            return null;
        }
        Map<String, Object> ref = obs.ref(w.uuidToId.get(a.getSourceId()));
        if (ref == null) {
            return null;
        }
        long index = KitBridge.abilityIndex(game, a);
        if (index < 0) {
            throw new IllegalArgumentException("unmatched priority mana ability index");
        }
        return Json.map("kind", "activate_mana_ability", "source", ref, "ability_index", index,
                "mana_choice", null, "cost_target", null);
    }

    static boolean isBackFace(Game game, UUID sourceId) {
        Card card = game.getCard(sourceId);
        if (card == null) {
            return false;
        }
        Card main = card.getMainCard();
        return main instanceof ModalDoubleFacedCard && !main.getId().equals(sourceId)
                && ((ModalDoubleFacedCard) main).getRightHalfCard().getId().equals(sourceId);
    }

    /** Copied from the engine's {@code SeatPlayer.specialAction} (private there). */
    static String specialAction(Ability a) {
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
     * The choices an ability carries (targets per requirement across the selected modes, modes, X, divided amounts,
     * cost targets), read from {@code executed}: the spell or stack ability the activation put on the stack, or an
     * option of the search (which carries its preselected targets).
     */
    public static Map<String, Object> payload(World w, Ability executed, Game game) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (executed == null) {
            return out;
        }
        List<Object> slots = new ArrayList<>();
        List<Object> divided = new ArrayList<>();
        Modes modes = executed.getModes();
        for (UUID modeId : modes.getSelectedModes()) {
            Mode mode = modes.get(modeId);
            if (mode == null) {
                continue;
            }
            for (Target t : mode.getTargets()) {
                List<Object> slot = new ArrayList<>();
                for (UUID id : t.getTargets()) {
                    slot.add(targetRef(w, id));
                    if (t instanceof TargetAmount) {
                        divided.add((long) t.getTargetAmount(id));
                    }
                }
                slots.add(slot);
            }
        }
        out.put("targets", slots);
        if (!divided.isEmpty()) {
            out.put("divided", divided);
        }
        if (modes.size() > 1) {
            List<Object> chosen = new ArrayList<>();
            int index = 0;
            for (Mode mode : modes.values()) {
                for (int n = modes.getSelectedStats(mode.getId()); n > 0; n--) {
                    chosen.add((long) index);
                }
                index++;
            }
            out.put("modes", chosen);
        }
        Map<String, Object> tags = executed.getCostsTagMap();
        Object x = tags == null ? null : tags.get("X");
        if (x instanceof Integer) {
            out.put("x", ((Integer) x).longValue());
        }
        List<Object> costTargets = new ArrayList<>();
        for (Cost c : executed.getCosts()) {
            for (Target t : c.getTargets()) {
                for (UUID id : t.getTargets()) {
                    costTargets.add(targetRef(w, id));
                }
            }
        }
        if (!costTargets.isEmpty()) {
            out.put("cost_targets", costTargets);
        }
        return out;
    }

    /** The executed object of the activation that just put {@code top} on the stack (N1: never the option object). */
    public static Ability executedAbility(StackObject top) {
        if (top instanceof Spell) {
            return ((Spell) top).getSpellAbility();
        }
        if (top instanceof StackAbility) {
            return ((StackAbility) top).getStackAbility(); // its own getCosts() is empty; the underlying ability holds them
        }
        return null;
    }

    public static boolean isActivated(Ability a) {
        return a instanceof ActivatedAbility;
    }

    /**
     * Why {@code a} has no offered v2 form on this world (A1 result review, change 5), or null when its semantic
     * maps: {@code no_source}, {@code source_unbound} (the source has no v2 id in this world: an object the world
     * created, or one the observation does not show), {@code source_not_in_observation}, {@code ability_type:T} (a
     * kind the priority candidates never carry), {@code ability_index_unresolved} (the engine's index function does not
     * list the ability on its object).
     */
    public static String failure(World w, Game game, Ability a, ObsIndex obs) {
        if (a == null || a instanceof PassAbility) {
            return null;
        }
        UUID src = a.getSourceId();
        if (src == null) {
            return "no_source";
        }
        String oid = w.uuidToId.get(src);
        if (oid == null) {
            return "source_unbound";
        }
        if (obs.ref(oid) == null) {
            return "source_not_in_observation";
        }
        switch (a.getAbilityType()) {
            case PLAY_LAND:
            case SPELL:
            case SPECIAL_ACTION:
                return null;
            case ACTIVATED_NONMANA:
                return KitBridge.abilityIndex(game, a) < 0 ? "ability_index_unresolved" : null;
            default:
                return "ability_type:" + a.getAbilityType();
        }
    }

    /** What an action is, for the mapping diagnostics: class, type, source, zone, rule text. */
    public static Map<String, Object> describe(World w, Game game, Ability a) {
        if (a == null) {
            return null;
        }
        UUID src = a.getSourceId();
        mage.MageObject so = src == null ? null : game.getObject(src);
        String rule;
        try {
            rule = a.getRule();
        } catch (RuntimeException e) {
            rule = "rule unavailable: " + e.getClass().getSimpleName();
        }
        if (rule != null && rule.length() > 240) {
            rule = rule.substring(0, 240);
        }
        long index;
        try {
            index = a.getAbilityType() == mage.constants.AbilityType.ACTIVATED_NONMANA ? KitBridge.abilityIndex(game, a) : -2;
        } catch (RuntimeException e) {
            index = -3;
        }
        return Json.map("class", a.getClass().getName(), "type", String.valueOf(a.getAbilityType()),
                "source_name", so == null ? null : so.getName(), "source_object_id", src == null ? null : w.uuidToId.get(src),
                "source_zone", src == null ? null : String.valueOf(game.getState().getZone(src)),
                "ability_index", index, "rule", rule);
    }

    /** The world's playable non-mana action whose v2 semantic is {@code semantic} (the mapping, inverted). */
    public static ActivatedAbility findPlayable(World w, mage.players.Player p, Map<String, Object> semantic, ObsIndex idx) {
        String want = Json.canonical(semantic);
        boolean mana = "activate_mana_ability".equals(semantic.get("kind"));
        if (mana && !(p instanceof mage.players.PlayerImpl)) {
            throw new IllegalArgumentException("priority mana lookup requires an XMage PlayerImpl");
        }
        // The default overload hides equal ability text, which can drop a different offered Forest.
        List<ActivatedAbility> playables = mana
                ? ((mage.players.PlayerImpl) p).getPlayable(w.game, true, mage.constants.Zone.ALL, false)
                : p.getPlayable(w.game, true);
        for (ActivatedAbility a : playables) {
            if (mana) {
                Map<String, Object> s = priorityManaSemantic(w, w.game, a, idx);
                if (s != null && want.equals(Json.canonical(s))) return a;
                continue;
            }
            if (a.getAbilityType() == mage.constants.AbilityType.ACTIVATED_MANA) {
                continue;
            }
            Map<String, Object> s = prioritySemantic(w, w.game, a, idx);
            if (s != null && (want.equals(Json.canonical(s)) || spellbench.kit.core.Offers.answers(semantic, s))) {
                return a;
            }
        }
        return null;
    }

    /** The object or player a choice candidate names, as a world UUID. */
    static UUID uuidOf(World w, Map<String, Object> sem) {
        Object t = null;
        switch (Json.str(sem, "kind")) {
            case "select_object":
                t = sem.get("choice");
                break;
            case "choose_target":
                t = sem.get("target");
                break;
            case "choose_cost_target":
                return w.idToUuid.get(Json.str(Json.obj(sem, "candidate"), "object_id"));
            case "order_pick": {
                Map<String, Object> item = Json.obj(sem, "item");
                Map<String, Object> o = item == null ? null : Json.obj(item, "object");
                return o == null ? null : w.idToUuid.get(Json.str(o, "object_id"));
            }
            default:
                return null;
        }
        if (t == null) {
            return null;
        }
        Map<String, Object> tm = Json.obj(t);
        if (tm.get("player") != null) {
            return w.player((String) tm.get("player"));
        }
        Map<String, Object> o = Json.obj(tm, "object");
        return o == null ? null : w.idToUuid.get(Json.str(o, "object_id"));
    }
}
