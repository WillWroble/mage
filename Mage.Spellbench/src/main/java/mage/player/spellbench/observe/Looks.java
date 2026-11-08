package mage.player.spellbench.observe;

import mage.cards.Card;
import mage.cards.Cards;
import mage.constants.Zone;
import mage.game.Game;
import mage.player.cabt.MagicOption;
import mage.player.cabt.MagicSelectType;
import mage.player.cabt.PendingDecision;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The default looks of a CABT prompt: every card in a zone hidden from the acting seat that one of the prompt's
 * options offers (a target, a card to choose, a pile card; at priority, a card the seat may play). The source of a
 * prompt (its {@code sourceId} outside priority) is not offered and is never shown this way. With
 * {@code known_cards} false, {@code known} lists only cards shown by the current decision (Section 6.7), and every
 * object a candidate references must be in the observation (Section 5.1), so these are the least a decision needs.
 * <p>
 * The defaults under-inform, which Section 6.7 allows: a library card is {@code searching} with no position (its
 * position may have changed since the viewer saw it), and an other-seat hand card is {@code revealed} when XMage
 * revealed it to both players, else {@code looked_at}. The decision mapper (X4) passes exact looks with positions
 * where it knows them (scry, surveil, look at the top cards).
 */
public final class Looks {

    /** Payload keys of CABT options that hold object ids. */
    private static final List<String> ID_KEYS = Arrays.asList(
            "targetId", "attackerId", "defenderId", "blockerId", "objectId", "sourceId");

    private Looks() {
    }

    public static List<Look> fromPrompt(Game game, UUID viewerPlayerId, PendingDecision decision) {
        Set<UUID> named = new LinkedHashSet<>();
        boolean priority = decision.selectType() == MagicSelectType.PRIORITY;
        for (MagicOption option : decision.options()) {
            collect(option.payload(), named, priority);
        }
        List<Look> looks = new ArrayList<>();
        for (UUID id : named) {
            Card card = game.getCard(id);
            Zone zone = game.getState().getZone(id);
            if (card == null || zone == null) {
                continue;
            }
            if (zone == Zone.LIBRARY) {
                looks.add(Look.searching(id));
            } else if (zone == Zone.HAND && !card.getOwnerId().equals(viewerPlayerId)) {
                looks.add(new Look(id, revealed(game, id) ? "revealed" : "looked_at", null, null));
            }
        }
        return looks;
    }

    private static boolean revealed(Game game, UUID id) {
        for (Cards cards : game.getState().getRevealed().values()) {
            if (cards.contains(id)) {
                return true;
            }
        }
        return false;
    }

    private static void collect(Object value, Set<UUID> out, boolean withSource) {
        if (value instanceof Map) {
            for (Map.Entry<?, ?> e : ((Map<?, ?>) value).entrySet()) {
                String key = String.valueOf(e.getKey());
                if (ID_KEYS.contains(key) && e.getValue() instanceof String) {
                    UUID id = parse((String) e.getValue());
                    if (id != null && (withSource || !key.equals("sourceId"))) {
                        out.add(id);
                    }
                } else {
                    collect(e.getValue(), out, withSource);
                }
            }
        } else if (value instanceof Iterable) {
            for (Object o : (Iterable<?>) value) {
                collect(o, out, withSource);
            }
        }
    }

    /** The object ids a CABT option names, in payload order (for the mapper's candidate references). */
    public static List<UUID> optionIds(MagicOption option) {
        Set<UUID> out = new LinkedHashSet<>();
        collect(option.payload(), out, true);
        return new ArrayList<>(out);
    }

    private static UUID parse(String s) {
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
