package spellbench.kit.xmage;

import mage.abilities.Ability;
import mage.constants.Outcome;
import mage.game.Game;
import mage.game.stack.StackObject;
import spellbench.kit.core.Json;
import spellbench.kit.core.ObsIndex;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The current-dialog path (design Section 5.1): the world is the current snapshot with nothing resolved, and the bot
 * answers a dialog synthesized from the decision (Section 6.3). It never re-executes effects. Returns the picks of
 * the whole logical dialog (a fixed group is planned at its first substep), or null when the kind has no synthesized
 * dialog in this release (the front then answers by fallback, tagged wrapper).
 */
public final class Dialogs {

    private Dialogs() {
    }

    static Outcome outcome(String purpose) {
        if (purpose == null) {
            return Outcome.Neutral;
        }
        switch (purpose) {
            case "discard":
            case "mulligan_bottom":
                return Outcome.Discard;
            case "sacrifice":
                return Outcome.Sacrifice;
            case "destroy":
                return Outcome.DestroyPermanent;
            case "exile":
                return Outcome.Exile;
            case "return_to_hand":
                return Outcome.ReturnToHand;
            case "search":
            case "put_into_hand":
            case "put_onto_battlefield":
                return Outcome.Benefit;
            default:
                return Outcome.Neutral;
        }
    }

    public static List<Object> answer(World w, KitPolicy bot, Map<String, Object> decision, ObsIndex idx) {
        Game game = w.game;
        List<Object> cands = Json.arr(decision, "candidates");
        Map<String, Object> first = null;
        for (Object c : cands) {
            Map<String, Object> sem = Json.obj(Json.obj(c), "semantic");
            String kind = Json.str(sem, "kind");
            if (!kind.startsWith("finish_")) {
                first = sem;
                break;
            }
        }
        if (first == null) {
            return null;
        }
        String kind = Json.str(first, "kind");
        Map<String, Object> group = Json.obj(decision, "group");
        long count = group == null ? 1 : Json.num(group, "substep_count", 1);
        long sub = group == null ? 0 : Json.num(group, "substep_index", 0);
        Map<String, Object> context = Json.obj(decision, "context");
        Ability source = sourceAbility(w, context);
        switch (kind) {
            case "select_object":
            case "choose_target":
            case "choose_cost_target":
            case "order_pick": {
                if ("order_pick".equals(kind) && !"mulligan_bottom".equals(Json.str(first, "purpose"))) {
                    return null;
                }
                Set<UUID> possible = new LinkedHashSet<>();
                for (Object c : cands) {
                    Map<String, Object> sem = Json.obj(Json.obj(c), "semantic");
                    UUID u = uuidOf(w, sem);
                    if (u != null) {
                        possible.add(u);
                    }
                }
                int min;
                int max;
                if ("order_pick".equals(kind)) {
                    min = max = (int) Json.num(first, "count", 1);
                } else if (count > 1) {
                    min = max = (int) (count - sub);
                } else {
                    long selected = Json.num(first, "selected_count", 0);
                    min = (int) Math.max(0, Json.num(first, "minimum", 0) - selected);
                    max = (int) Math.max(1, Json.num(first, "maximum", 1) - selected);
                }
                String purpose = "order_pick".equals(kind) ? "mulligan_bottom" : Json.str(first, "purpose");
                Outcome outcome = outcome(purpose);
                if ("choose_target".equals(kind) && source != null && !source.getEffects().isEmpty()) {
                    outcome = source.getEffects().get(0).getOutcome();
                }
                boolean notTarget = !"choose_target".equals(kind);
                KitTarget t = new KitTarget(possible, Math.min(min, possible.size()), Math.min(max, possible.size()), notTarget);
                String name = realTargetName(source, kind);
                if (name != null) {
                    t.withTargetName(name);
                }
                if ("choose_target".equals(kind)) {
                    bot.chooseTarget(outcome, t, source, game);
                } else {
                    bot.choose(outcome, t, source, game);
                }
                List<Object> picks = new ArrayList<>();
                for (UUID id : t.getTargets()) {
                    picks.add(Mapping.targetRef(w, id));
                }
                return picks;
            }
            case "choose_spell_mode": {
                if (source == null || source.getModes().size() < 2) {
                    return null;
                }
                mage.abilities.Modes modes = source.getModes();
                List<mage.abilities.Mode> all = new ArrayList<>(modes.values());
                int n = count > 1 ? (int) (count - sub) : 1;
                List<Object> picks = new ArrayList<>();
                for (int i = 0; i < n; i++) {
                    mage.abilities.Mode m = bot.chooseMode(modes, source, game);
                    if (m == null) {
                        break;
                    }
                    picks.add((long) all.indexOf(m));
                    if (!modes.getSelectedModes().contains(m.getId())) {
                        modes.addSelectedMode(m.getId());
                    }
                }
                return picks;
            }
            case "arrange_card": {
                // scry, surveil, look at the top: the bot's own choice of the cards that leave the top
                // (PlayerImpl.scry and doSurveil ask chooseTarget among the looked-at cards)
                List<UUID> cards = new ArrayList<>();
                List<String> ids = new ArrayList<>();
                // an arrangement decision shows one card at a time: the looked-at cards are the viewer's library
                // known entries above card_count, top first, plus the candidates' cards
                long lookCount = Json.num(first, "card_count", 0);
                Map<String, Object> obs = Json.obj(decision, "observation");
                java.util.TreeMap<Long, String> byPosition = new java.util.TreeMap<>();
                for (Object o : Json.arr(obs, "known")) {
                    Map<String, Object> k = Json.obj(o);
                    Object pos = k.get("position_from_top");
                    if ("library".equals(Json.str(k, "zone")) && Json.str(obs, "viewer").equals(Json.str(k, "owner_seat"))
                            && k.get("object_id") != null && pos instanceof Number && ((Number) pos).longValue() < lookCount) {
                        byPosition.put(((Number) pos).longValue(), Json.str(k, "object_id"));
                    }
                }
                for (String oid : byPosition.values()) {
                    UUID u = w.idToUuid.get(oid);
                    if (u != null && !cards.contains(u)) {
                        cards.add(u);
                        ids.add(oid);
                    }
                }
                for (Object c : cands) {
                    Map<String, Object> sem = Json.obj(Json.obj(c), "semantic");
                    String oid = Json.str(Json.obj(sem, "card"), "object_id");
                    UUID u = w.idToUuid.get(oid);
                    if (u != null && !cards.contains(u)) {
                        cards.add(u);
                        ids.add(oid);
                    }
                }
                String purpose = Json.str(first, "purpose");
                KitTarget t = new KitTarget(new LinkedHashSet<>(cards), 0, cards.size(), true);
                bot.chooseTarget(Outcome.Benefit, t, source, game);
                Map<String, Object> dest = new java.util.LinkedHashMap<>();
                List<Object> order = new ArrayList<>();
                String away = "surveil".equals(purpose) ? "graveyard" : "bottom";
                for (int i = 0; i < cards.size(); i++) {
                    dest.put(ids.get(i), t.getTargets().contains(cards.get(i)) ? away : "top");
                    order.add(ids.get(i));
                }
                List<Object> picks = new ArrayList<>();
                picks.add(Json.map("arrangement", true, "dest", dest, "order", order));
                return picks;
            }
            case "choose_boolean": {
                List<Object> picks = new ArrayList<>();
                picks.add(bot.chooseUse(Outcome.Benefit, "kit dialog", source, game));
                return picks;
            }
            case "choose_color": {
                // the bot's color heuristic among the offered colors (a Gate's as-enters color, Prismatic Strands)
                mage.choices.ChoiceColor choice = new mage.choices.ChoiceColor(true);
                Set<String> offered = new LinkedHashSet<>();
                for (Object c : cands) {
                    String color = Json.str(Json.obj(Json.obj(c), "semantic"), "color");
                    if (color != null && !color.isEmpty()) {
                        offered.add(color.substring(0, 1).toUpperCase(java.util.Locale.ROOT) + color.substring(1));
                    }
                }
                choice.setChoices(offered);
                bot.choose(outcome(Json.str(first, "purpose")), choice, game);
                if (choice.getChoice() == null) {
                    return null;
                }
                List<Object> picks = new ArrayList<>();
                picks.add(choice.getChoice().toLowerCase(java.util.Locale.ROOT));
                return picks;
            }
            case "choose_number": {
                int min = (int) Json.num(first, "minimum", 0);
                int max = (int) Json.num(first, "maximum", 0);
                List<Object> picks = new ArrayList<>();
                picks.add((long) bot.getAmount(min, max, "kit dialog", source, game));
                return picks;
            }
            default:
                return null;
        }
    }

    /** The object or player a choice candidate names, as a world UUID. */
    /**
     * MageZero fork: the name XMage's own target for this dialog shows (a spell's target, or the target an effect
     * holds, such as a library search's), else null. MageZero encodes it in the decision text, so a synthesized dialog
     * that names it "offered object" is a text the model never trained on.
     */
    static String realTargetName(Ability source, String kind) {
        if (source == null) {
            return null;
        }
        if ("choose_target".equals(kind) && !source.getTargets().isEmpty()) {
            return source.getTargets().get(0).getTargetName();
        }
        for (mage.abilities.effects.Effect e : source.getEffects()) {
            for (Class<?> c = e.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                    if (mage.target.Target.class.isAssignableFrom(f.getType())) {
                        try {
                            f.setAccessible(true);
                            Object v = f.get(e);
                            if (v != null) {
                                return ((mage.target.Target) v).getTargetName();
                            }
                        } catch (ReflectiveOperationException | RuntimeException ignored) {
                            // an inaccessible field: keep looking
                        }
                    }
                }
            }
        }
        return null;
    }

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

    static Ability sourceAbility(World w, Map<String, Object> context) {
        if (context == null || context.get("source") == null) {
            return null;
        }
        UUID u = w.idToUuid.get(Json.str(Json.obj(context, "source"), "object_id"));
        if (u == null) {
            return null;
        }
        StackObject so = w.game.getStack().getStackObject(u);
        return so == null ? null : so.getStackAbility();
    }
}
