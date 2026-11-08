package spellbench.kit.xmage;

import mage.abilities.Ability;
import mage.abilities.effects.ContinuousEffect;
import mage.abilities.effects.common.continuous.BoostSourceEffect;
import mage.abilities.effects.common.continuous.BoostTargetEffect;
import mage.abilities.keyword.ProwessAbility;
import mage.constants.Duration;
import mage.game.permanent.Permanent;
import mage.player.cabt.CardResolver;
import mage.target.targetpointer.FixedTarget;
import spellbench.kit.core.Json;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Restore native temporary effects only from explicit public resolution witnesses. */
final class PublicReplayEffects {
    private static final String DISTRACTION = "target creature gets -1/-0 until end of turn. <br>Draw a card.";
    private PublicReplayEffects() { }

    private static void require(boolean value, String message) {
        if (!value) throw new IllegalArgumentException("public effect replay: " + message);
    }

    private static Map<String, Map<String, Object>> board(List<Object> cards) {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        for (Object item : cards) {
            Map<String, Object> card = Json.obj(item);
            String id = Json.str(card, "object_id");
            require(id != null && !id.isEmpty() && result.put(id, card) == null, "invalid battlefield identity");
        }
        return result;
    }

    private static Map<String, Map<String, Object>> board(Map<String, Object> observation) {
        List<Object> cards = new ArrayList<>();
        for (Object player : Json.arr(observation, "players")) cards.addAll(Json.arr(Json.obj(player), "battlefield"));
        return board(cards);
    }

    private static void sameIdentity(Map<String, Object> left, Map<String, Object> right) {
        require(left != null && right != null, "missing public permanent");
        for (String key : new String[]{"object_id", "card_name", "owner_seat", "controller_seat", "zone"}) {
            require(left.get(key) != null && left.get(key).equals(right.get(key)), "changed permanent " + key);
        }
        require("battlefield".equals(Json.str(left, "zone")), "target is not a permanent");
    }

    static void restore(World world, Map<String, Object> observation, Map<String, Object> history) {
        if (history == null || !history.containsKey("public_effects")) return;
        require(history.get("public_effects") instanceof List, "invalid witness list");
        Map<String, Map<String, Object>> current = board(observation);
        Set<String> occurrences = new HashSet<>();
        List<Ability> sources = new ArrayList<>();
        List<ContinuousEffect> effects = new ArrayList<>();
        Set<String> affected = new HashSet<>();
        Map<String, long[]> delta = new LinkedHashMap<>();
        world.random.scopeWorld("replay-public-effects");
        try {
            // Validate every witness before installing any effects. Merely observing
            // a non-base power or toughness never supplies a missing effect.
            for (Object item : Json.arr(history, "public_effects")) {
                Map<String, Object> witness = Json.obj(item);
                require(world.viewer.equals(Json.str(witness, "viewer"))
                        && Json.num(witness, "turn", -1) == Json.num(observation, "turn", -2), "stale viewer or turn");
                List<Object> beforeStack = Json.arr(witness, "stack_before"), afterStack = Json.arr(witness, "stack_after");
                require(!beforeStack.isEmpty() && beforeStack.subList(0, beforeStack.size() - 1).equals(afterStack),
                        "not a single top-stack resolution");
                Map<String, Object> top = Json.obj(beforeStack.get(beforeStack.size() - 1));
                String occurrence = Json.str(top, "object_id");
                require(occurrence != null && !occurrence.isEmpty() && occurrences.add(occurrence), "repeated occurrence");
                require(Boolean.FALSE.equals(top.get("copy")) && Boolean.FALSE.equals(top.get("face_down")),
                        "unsupported copied or hidden source");
                Map<String, Object> before = Json.obj(witness, "before"), after = Json.obj(witness, "after");
                sameIdentity(before, after);
                for (String key : new String[]{"copy", "face_down", "token"}) {
                    require(Boolean.FALSE.equals(before.get(key)), "unsupported target " + key);
                }
                String id = Json.str(before, "object_id"), kind = Json.str(witness, "kind");
                sameIdentity(after, current.get(id));
                UUID uuid = world.idToUuid.get(id);
                Permanent target = uuid == null ? null : world.game.getPermanent(uuid);
                require(target != null && target.isCreature(world.game), "missing native target");
                Ability source = null;
                ContinuousEffect effect;
                int power, toughness;
                if ("prowess".equals(kind)) {
                    require("triggered_ability".equals(Json.str(top, "stack_kind"))
                            && new ProwessAbility().getRule().equals(Json.str(top, "text"))
                            && Json.arr(top, "targets").isEmpty(), "unrecognized prowess source");
                    sameIdentity(before, Json.obj(top, "source"));
                    require(before.get("controller_seat").equals(top.get("controller_seat"))
                            && Json.arr(Json.obj(before, "characteristics"), "keywords").contains("prowess"),
                            "unrecognized prowess controller or keyword");
                    for (Ability ability : target.getAbilities(world.game)) {
                        if (ability instanceof ProwessAbility) { source = ability.copy(); break; }
                    }
                    require(source != null && source.getEffects().get(0) instanceof BoostSourceEffect,
                            "native prowess ability is unavailable");
                    effect = (ContinuousEffect) source.getEffects().get(0).copy();
                    power = 1; toughness = 1;
                } else {
                    require("fleeting_distraction".equals(kind) && "spell".equals(Json.str(top, "stack_kind"))
                            && "Fleeting Distraction".equals(Json.str(top, "card_name"))
                            && DISTRACTION.equals(Json.str(top, "text")) && Json.arr(top, "targets").size() == 1,
                            "unrecognized temporary spell effect");
                    sameIdentity(before, Json.obj(Json.obj(Json.arr(top, "targets").get(0)), "object"));
                    UUID controller = world.player(Json.str(top, "controller_seat"));
                    require(controller != null, "unknown spell controller");
                    source = new CardResolver().resolve("Fleeting Distraction").createCard(controller).getSpellAbility();
                    require(source.getEffects().get(0) instanceof BoostTargetEffect, "native distraction effect changed");
                    effect = (ContinuousEffect) source.getEffects().get(0).copy();
                    effect.setTargetPointer(new FixedTarget(uuid, world.game));
                    power = -1; toughness = 0;
                }
                require(effect.getDuration() == Duration.EndOfTurn, "native effect duration changed");
                Map<String, Object> expected = Json.obj(Json.copy(before));
                Map<String, Object> characteristics = Json.obj(expected, "characteristics");
                require(characteristics.get("power") instanceof Long && characteristics.get("toughness") instanceof Long,
                        "missing integer characteristics");
                characteristics.put("power", Json.num(characteristics, "power", 0) + power);
                characteristics.put("toughness", Json.num(characteristics, "toughness", 0) + toughness);
                require(expected.equals(after), "observed change differs from the native effect");
                Map<String, Map<String, Object>> beforeBoard = board(Json.arr(witness, "battlefield_before"));
                Map<String, Map<String, Object>> afterBoard = board(Json.arr(witness, "battlefield_after"));
                require(before.equals(beforeBoard.get(id)) && after.equals(afterBoard.get(id)), "witness target differs from board");
                beforeBoard.put(id, expected);
                require(beforeBoard.equals(afterBoard), "another permanent or attachment changed during resolution");
                sources.add(source); effects.add(effect); affected.add(id);
                delta.merge(id, new long[]{power, toughness}, (a, b) -> new long[]{a[0] + b[0], a[1] + b[1]});
            }
            // Both witnessed effects are additive boosts. Install them only when they explain the current public
            // power and toughness exactly; another unwitnessed effect leaves the world unrestored, and
            // checkCharacteristics() marks it approximate as before.
            for (Map.Entry<String, long[]> entry : delta.entrySet()) {
                Permanent target = world.game.getPermanent(world.idToUuid.get(entry.getKey()));
                Map<String, Object> observed = Json.obj(current.get(entry.getKey()), "characteristics");
                if (target.getPower().getValue() + entry.getValue()[0] != Json.num(observed, "power", Long.MIN_VALUE)
                        || target.getToughness().getValue() + entry.getValue()[1]
                        != Json.num(observed, "toughness", Long.MIN_VALUE)) {
                    return;
                }
            }
            for (int i = 0; i < effects.size(); i++) world.game.addEffect(effects.get(i), sources.get(i));
            if (!effects.isEmpty()) {
                world.game.applyEffects();
                for (String id : affected) {
                    Permanent target = world.game.getPermanent(world.idToUuid.get(id));
                    Map<String, Object> observed = Json.obj(current.get(id), "characteristics");
                    require(target.getPower().getValue() == Json.num(observed, "power", Long.MIN_VALUE)
                            && target.getToughness().getValue() == Json.num(observed, "toughness", Long.MIN_VALUE),
                            "native effects contradict the current public characteristics");
                }
                world.flags.add("replay:public_temporary_effects");
            }
        } finally {
            world.random.scopeWorld("play");
        }
    }
}
