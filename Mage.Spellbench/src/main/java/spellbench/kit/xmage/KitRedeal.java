package spellbench.kit.xmage;

import mage.cards.Card;
import mage.constants.Zone;
import mage.game.Game;
import mage.players.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * The re-deal hook of H3 (design Section 5.3 item 3): replaces upstream MCTS's {@code randomizePlayers} and
 * {@code createMCTSGame}'s re-deal. Per owner it collects the card objects in hidden slots that this branch's
 * {@link KnowledgeWatcher} marks unknown (the other seat's hand cards beyond its known name counts, library cards
 * not known at their positions) and permutes them among those same slots with the branch's stream. Known cards never
 * move; zone sizes and the multiset of physical cards are conserved by construction.
 * <p>
 * With no knowledge (an empty watcher) it permutes the other seat's hand and library together and the decider's
 * library alone: upstream's semantics (hand returned to the library, library shuffled, hand redrawn), which E7
 * checks.
 */
public final class KitRedeal {

    private KitRedeal() {
    }

    public static void redeal(Game game, UUID decider, Random stream) {
        KnowledgeWatcher w = KnowledgeWatcher.get(game);
        KitContext.count("redeal");
        for (Player p : game.getState().getPlayers().values()) {
            boolean other = !p.getId().equals(decider);
            // hand slots (the other seat only): known name counts keep the first cards of each name in hand order
            List<Card> hand = new ArrayList<>(p.getHand().getCards(game));
            List<Integer> handSlots = new ArrayList<>();
            List<Card> pool = new ArrayList<>();
            if (other) {
                Map<String, Integer> keep = new HashMap<>(w == null ? Collections.<String, Integer>emptyMap() : w.handKnown(p.getId()));
                for (int i = 0; i < hand.size(); i++) {
                    Card c = hand.get(i);
                    Integer k = keep.get(c.getName());
                    if (k != null && k > 0) {
                        keep.put(c.getName(), k - 1);
                        continue;
                    }
                    handSlots.add(i);
                    pool.add(c);
                }
            }
            List<Card> library = new ArrayList<>(p.getLibrary().getCards(game));
            List<Integer> libSlots = new ArrayList<>();
            for (int i = 0; i < library.size(); i++) {
                if (w != null && w.knownInLibrary(p.getId(), library.get(i).getId())) {
                    continue;
                }
                libSlots.add(i);
                pool.add(library.get(i));
            }
            List<String> before = names(pool);
            Collections.shuffle(pool, stream);
            int at = 0;
            Card[] newHand = hand.toArray(new Card[0]);
            for (int slot : handSlots) {
                newHand[slot] = pool.get(at++);
            }
            Card[] newLib = library.toArray(new Card[0]);
            for (int slot : libSlots) {
                newLib[slot] = pool.get(at++);
            }
            // rewrite the zones without events
            for (Card c : hand) {
                p.getHand().remove(c);
            }
            p.getLibrary().clear();
            for (Card c : newHand) {
                c.setZone(Zone.HAND, game);
                p.getHand().add(c);
            }
            for (Card c : newLib) {
                c.setZone(Zone.LIBRARY, game);
                p.getLibrary().putOnBottom(c, game);
            }
            // conservation check (S7 d): sizes and multisets
            List<String> after = new ArrayList<>();
            for (int slot : handSlots) {
                after.add(newHand[slot].getName());
            }
            for (int slot : libSlots) {
                after.add(newLib[slot].getName());
            }
            Collections.sort(after);
            if (!before.equals(after) || p.getHand().size() != hand.size() || p.getLibrary().size() != library.size()) {
                KitContext.count("redeal_conservation_violation");
            }
        }
    }

    static List<String> names(List<Card> cards) {
        List<String> out = new ArrayList<>();
        for (Card c : cards) {
            out.add(c.getName());
        }
        Collections.sort(out);
        return out;
    }
}
