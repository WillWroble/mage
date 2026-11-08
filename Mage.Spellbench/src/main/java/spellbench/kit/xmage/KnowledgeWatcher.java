package spellbench.kit.xmage;

import mage.cards.Card;
import mage.constants.WatcherScope;
import mage.constants.Zone;
import mage.game.Game;
import mage.game.events.GameEvent;
import mage.game.events.ZoneChangeEvent;
import mage.players.Player;
import mage.watchers.Watcher;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Branch-local knowledge for H3 (design Section 5.3, round 2 item 2-2, and the addendum's change 1). The world
 * builder installs it in the world's {@code GameState}; {@code GameState}'s copy constructor copies watchers, so every
 * simulated branch carries its own copy, updated by that branch's own events under the protocol's update table
 * (spec Section 6.7), from the decider's viewpoint:
 * <ul>
 * <li>the other seat's hand: name-level counts (a duplicate name is two counts, not two objects);</li>
 * <li>libraries: the card objects known at their positions (pinned, looked at, revealed); a shuffle clears that
 * library, a rearrangement the other seat chose hidden clears it too.</li>
 * </ul>
 * Looks and reveals fire no {@code GameEvent}, so the vendored simulated players notify it ({@link #looked}).
 * {@link KitRedeal} reads it before every re-deal. It never resets with the turn.
 */
public final class KnowledgeWatcher extends Watcher {

    private static final long serialVersionUID = 1L;

    private UUID decider;
    /** other seat's hand: owner -> name -> known count */
    private LinkedHashMap<UUID, LinkedHashMap<String, Integer>> handKnown = new LinkedHashMap<>();
    /** library owner -> card objects known at their positions */
    private LinkedHashMap<UUID, LinkedHashSet<UUID>> libKnown = new LinkedHashMap<>();

    public KnowledgeWatcher() {
        super(WatcherScope.GAME);
    }

    public static KnowledgeWatcher install(Game game, UUID decider) {
        KnowledgeWatcher w = new KnowledgeWatcher();
        w.decider = decider;
        game.getState().addWatcher(w);
        return w;
    }

    public static KnowledgeWatcher get(Game game) {
        return game.getState().getWatcher(KnowledgeWatcher.class);
    }

    @Override
    public void reset() {
        // knowledge does not end with the turn
    }

    public UUID decider() {
        return decider;
    }

    // ------------------------------------------------------------------ queries

    public int knownHandCount(UUID owner, String name) {
        Map<String, Integer> m = handKnown.get(owner);
        Integer c = m == null ? null : m.get(name);
        return c == null ? 0 : c;
    }

    public boolean knownInLibrary(UUID owner, UUID card) {
        LinkedHashSet<UUID> s = libKnown.get(owner);
        return s != null && s.contains(card);
    }

    public Map<String, Integer> handKnown(UUID owner) {
        Map<String, Integer> m = handKnown.get(owner);
        return m == null ? Collections.<String, Integer>emptyMap() : Collections.unmodifiableMap(m);
    }

    public List<UUID> libKnown(UUID owner) {
        LinkedHashSet<UUID> s = libKnown.get(owner);
        return s == null ? new ArrayList<UUID>() : new ArrayList<>(s);
    }

    // ------------------------------------------------------------------ updates

    public void pinHand(UUID owner, String name) {
        handKnown.computeIfAbsent(owner, k -> new LinkedHashMap<>()).merge(name, 1, Integer::sum);
    }

    public void pinLibrary(UUID owner, UUID card) {
        libKnown.computeIfAbsent(owner, k -> new LinkedHashSet<>()).add(card);
    }

    private void handLeftPublic(UUID owner, String name) {
        Map<String, Integer> m = handKnown.get(owner);
        if (m != null && m.containsKey(name)) {
            int c = m.get(name) - 1;
            if (c <= 0) {
                m.remove(name);
            } else {
                m.put(name, c);
            }
        }
    }

    private void handLeftHidden(UUID owner) {
        Map<String, Integer> m = handKnown.get(owner);
        if (m == null) {
            return;
        }
        for (String name : new ArrayList<>(m.keySet())) {
            int c = m.get(name) - 1; // spec 6.7: every name's count c becomes max(0, c - 1)
            if (c <= 0) {
                m.remove(name);
            } else {
                m.put(name, c);
            }
        }
    }

    /**
     * Notification for looks and reveals, which fire no event: {@code viewer} looked at {@code cards}
     * ({@code toAll}: revealed to every player). Only what the decider sees is knowledge.
     */
    public static void looked(Game game, UUID viewer, Iterable<UUID> cards, boolean toAll) {
        KnowledgeWatcher w = get(game);
        if (w == null || w.decider == null || !(toAll || w.decider.equals(viewer))) {
            return;
        }
        for (UUID id : cards) {
            Card c = game.getCard(id);
            if (c == null) {
                continue;
            }
            Zone z = game.getState().getZone(id);
            if (z == Zone.LIBRARY) {
                w.pinLibrary(c.getOwnerId(), id);
            } else if (z == Zone.HAND && !c.getOwnerId().equals(w.decider)) {
                Player owner = game.getPlayer(c.getOwnerId());
                int inHand = 0;
                for (Card h : owner.getHand().getCards(game)) {
                    if (h.getName().equals(c.getName())) {
                        inHand++;
                    }
                }
                if (w.knownHandCount(c.getOwnerId(), c.getName()) < inHand) {
                    w.pinHand(c.getOwnerId(), c.getName());
                }
            }
        }
        KitContext.count("knowledge:looked");
    }

    /** Notification: a seat other than the decider rearranged cards of a library without showing them. */
    public static void hiddenRearrangement(Game game, UUID chooser, UUID libraryOwner) {
        KnowledgeWatcher w = get(game);
        if (w == null || w.decider == null || w.decider.equals(chooser)) {
            return;
        }
        LinkedHashSet<UUID> s = w.libKnown.get(libraryOwner);
        if (s != null) {
            s.clear();
        }
    }

    @Override
    public void watch(GameEvent event, Game game) {
        if (decider == null) {
            return;
        }
        switch (event.getType()) {
            case LIBRARY_SHUFFLED: {
                LinkedHashSet<UUID> s = libKnown.get(event.getPlayerId());
                if (s != null) {
                    s.clear();
                }
                KitContext.count("knowledge:shuffle_cleared");
                return;
            }
            case SCRIED:
            case SURVEILED:
                hiddenRearrangement(game, event.getPlayerId(), event.getPlayerId());
                return;
            case ZONE_CHANGE:
                zoneChange((ZoneChangeEvent) event, game);
                return;
            default:
        }
    }

    private void zoneChange(ZoneChangeEvent e, Game game) {
        UUID id = e.getTargetId();
        Card c = game.getCard(id);
        if (c == null) {
            return;
        }
        UUID owner = c.getOwnerId();
        String name = c.getName();
        Zone from = e.getFromZone();
        Zone to = e.getToZone();
        boolean otherHand = owner != null && !owner.equals(decider);
        if (from == Zone.LIBRARY) {
            boolean known = knownInLibrary(owner, id);
            LinkedHashSet<UUID> s = libKnown.get(owner);
            if (s != null) {
                s.remove(id);
            }
            if (to == Zone.HAND && otherHand && known) {
                pinHand(owner, name); // tracked
                KitContext.count("knowledge:tracked_to_hand");
            }
        }
        if (from == Zone.HAND && otherHand) {
            if (to == Zone.LIBRARY || (to == Zone.EXILED && c.isFaceDown(game))) {
                handLeftHidden(owner);
            } else {
                handLeftPublic(owner, name);
            }
        }
        if (to == Zone.HAND && otherHand && from != Zone.LIBRARY && from != Zone.HAND) {
            pinHand(owner, name); // from a public zone
        }
        if (to == Zone.LIBRARY && from != Zone.HAND && from != Zone.LIBRARY) {
            pinLibrary(owner, id); // a public card put into a library: the decider saw it go there
        }
    }
}
