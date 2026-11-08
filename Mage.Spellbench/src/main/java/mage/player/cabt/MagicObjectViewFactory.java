package mage.player.cabt;

import mage.MageInt;
import mage.MageObject;
import mage.abilities.Ability;
import mage.constants.CardType;
import mage.constants.SubType;
import mage.constants.Zone;
import mage.counters.Counter;
import mage.counters.Counters;
import mage.game.Controllable;
import mage.game.Game;
import mage.game.Ownerable;
import mage.game.permanent.Permanent;
import mage.game.stack.StackObject;
import mage.target.Target;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Builds observation views from XMage game objects. Read-only and null-safe:
 * missing state serializes as null/empty, never as engine access that could
 * advance the game.
 * <p>
 * The zone recorded in a reference prefers the engine's own answer
 * (game.getState().getZone(id), the same resolution GameImpl.getObject uses)
 * and falls back to the caller's contextual zone.
 */
public final class MagicObjectViewFactory {

    private MagicObjectViewFactory() {
    }

    public static MagicObjectReference reference(Game game, MageObject object,
                                                 Zone contextZone, UUID sourceId) {
        String ownerId = object instanceof Ownerable
                ? nullableToString(((Ownerable) object).getOwnerId())
                : null;
        String controllerId = object instanceof Controllable
                ? nullableToString(((Controllable) object).getControllerId())
                : null;
        return new MagicObjectReference(
                nullableToString(object.getId()),
                nullableToString(sourceId),
                zoneName(game, object.getId(), contextZone),
                object.getName(),
                object.getClass().getSimpleName(),
                ownerId,
                controllerId);
    }

    public static MagicObjectView objectView(Game game, MageObject object, Zone contextZone) {
        return new MagicObjectView(
                reference(game, object, contextZone, null),
                cardTypeNames(game, object),
                subTypeNames(game, object));
    }

    public static MagicPermanentView permanentView(Game game, Permanent permanent) {
        return new MagicPermanentView(
                reference(game, permanent, Zone.BATTLEFIELD, null),
                nullableToString(permanent.getControllerId()),
                nullableToString(permanent.getOwnerId()),
                permanent.isTapped(),
                permanent.isFaceDown(game),
                mageIntValue(permanent.getPower()),
                mageIntValue(permanent.getToughness()),
                counterCounts(permanent.getCounters(game)),
                cardTypeNames(game, permanent),
                subTypeNames(game, permanent));
    }

    public static MagicStackObjectView stackObjectView(Game game, StackObject stackObject) {
        return new MagicStackObjectView(
                reference(game, stackObject, Zone.STACK, stackObject.getSourceId()),
                nullableToString(stackObject.getControllerId()),
                nullableToString(stackObject.getSourceId()),
                stackObject.getName(),
                stackObject.getClass().getSimpleName(),
                targetIds(stackObject));
    }

    public static MagicZoneView zoneView(Zone zone, List<MagicObjectView> objects) {
        return new MagicZoneView(zone == null ? null : zone.name(), objects);
    }

    private static List<String> targetIds(StackObject stackObject) {
        List<String> ids = new ArrayList<String>();
        Ability stackAbility = stackObject.getStackAbility();
        if (stackAbility != null && stackAbility.getTargets() != null) {
            for (Target target : stackAbility.getTargets()) {
                for (UUID targetId : target.getTargets()) {
                    ids.add(targetId.toString());
                }
            }
        }
        return ids;
    }

    private static List<String> cardTypeNames(Game game, MageObject object) {
        List<String> names = new ArrayList<String>();
        List<CardType> cardTypes = object.getCardType(game);
        if (cardTypes != null) {
            for (CardType cardType : cardTypes) {
                names.add(cardType.name());
            }
        }
        return names;
    }

    private static List<String> subTypeNames(Game game, MageObject object) {
        List<String> names = new ArrayList<String>();
        List<SubType> subTypes = object.getSubtype(game);
        if (subTypes != null) {
            for (SubType subType : subTypes) {
                names.add(subType.name());
            }
        }
        return names;
    }

    private static Map<String, Integer> counterCounts(Counters counters) {
        Map<String, Integer> counts = new LinkedHashMap<String, Integer>();
        if (counters != null) {
            for (Counter counter : counters.values()) {
                counts.put(counter.getName(), counter.getCount());
            }
        }
        return counts;
    }

    private static Integer mageIntValue(MageInt value) {
        return value == null ? null : Integer.valueOf(value.getValue());
    }

    private static String zoneName(Game game, UUID objectId, Zone contextZone) {
        if (game != null && game.getState() != null && objectId != null) {
            Zone zone = game.getState().getZone(objectId);
            if (zone != null) {
                return zone.name();
            }
        }
        return contextZone == null ? null : contextZone.name();
    }

    private static String nullableToString(Object value) {
        return value == null ? null : value.toString();
    }
}
