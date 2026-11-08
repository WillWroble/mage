package mage.player.cabt;

import mage.cards.Card;
import mage.cards.CardSetInfo;
import mage.constants.Rarity;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Builds real XMage {@link Card} instances from card names, for protocol
 * deck input. Resolution is by card class, the same way the smoke tests
 * construct cards: {@code "Grizzly Bears"} maps to
 * {@code mage.cards.g.GrizzlyBears}, basic lands to
 * {@code mage.cards.basiclands.*}. This needs the mage-sets card classes on
 * the runtime classpath and fails closed with {@link UnknownCardException}
 * for names that resolve to no class — a deck must never be silently
 * shortened.
 * <p>
 * <b>Role:</b> this class-name heuristic is now the <em>fallback</em> resolver
 * behind {@link CardResolver}, which resolves names against XMage's
 * {@link mage.cards.repository.CardRepository} first. The heuristic
 * ({@link #classSimpleName(String)}) works for simple card names (Forest,
 * Grizzly Bears, Llanowar Elves) but misses a non-trivial portion of real
 * Magic card names — split cards, alternate class names, special punctuation,
 * variant suffixes, and renamed/rebalanced variants whose XMage class name
 * differs from the naive transform. The resolver only reaches it when the
 * repository has no match (e.g. a test JVM with no scanned card database), and
 * it still fails closed with {@link UnknownCardException} rather than
 * substituting a card. Kept for that fallback path and for building card
 * instances in unit-test fixtures.
 */
public final class CabtDeckFactory {

    /** A deck entry: {@code count} copies of the card named {@code name}. */
    public static final class Entry {
        private final String name;
        private final int count;

        public Entry(String name, int count) {
            if (name == null || name.trim().isEmpty()) {
                throw new IllegalArgumentException("deck entry needs a card name");
            }
            if (count < 1) {
                throw new IllegalArgumentException("deck entry count must be >= 1: " + name);
            }
            this.name = name.trim();
            this.count = count;
        }

        public String name() {
            return name;
        }

        public int count() {
            return count;
        }
    }

    /** Thrown when a card name resolves to no card class. */
    public static final class UnknownCardException extends RuntimeException {
        UnknownCardException(String cardName, Throwable cause) {
            super("UNKNOWN_CARD: no card class found for \"" + cardName + '"', cause);
        }
    }

    private static final Map<String, String> BASIC_LANDS = new HashMap<String, String>();

    static {
        for (String land : new String[]{"Plains", "Island", "Swamp", "Mountain", "Forest", "Wastes"}) {
            BASIC_LANDS.put(land, "mage.cards.basiclands." + land);
        }
    }

    // each constructed card gets a distinct collector number so set info
    // never collides between copies
    private int nextCollectorNumber = 1;

    /**
     * Builds one card per copy for every entry, owned by {@code ownerId}.
     */
    public List<Card> buildDeck(UUID ownerId, List<Entry> entries) {
        List<Card> cards = new ArrayList<Card>();
        for (Entry entry : entries) {
            for (int i = 0; i < entry.count(); i++) {
                Card card = createCard(entry.name());
                card.setOwnerId(ownerId);
                cards.add(card);
            }
        }
        return cards;
    }

    /**
     * Constructs a fresh card instance for the given card name.
     */
    public Card createCard(String cardName) {
        String className = BASIC_LANDS.get(cardName);
        if (className == null) {
            String simpleName = classSimpleName(cardName);
            className = "mage.cards." + Character.toLowerCase(simpleName.charAt(0)) + '.' + simpleName;
        }
        boolean basicLand = BASIC_LANDS.containsKey(cardName);
        try {
            Class<?> cardClass = Class.forName(className);
            Constructor<?> constructor = cardClass.getConstructor(UUID.class, CardSetInfo.class);
            CardSetInfo setInfo = new CardSetInfo(cardName, "CABT",
                    String.valueOf(nextCollectorNumber++),
                    basicLand ? Rarity.LAND : Rarity.COMMON);
            return (Card) constructor.newInstance((UUID) null, setInfo);
        } catch (ReflectiveOperationException e) {
            throw new UnknownCardException(cardName, e);
        } catch (RuntimeException e) {
            throw new UnknownCardException(cardName, e);
        }
    }

    /**
     * XMage card-class naming: alphanumeric tokens of the card name,
     * first-letter capitalized, concatenated — "Llanowar Elves" is
     * LlanowarElves, "Will-o'-the-Wisp" is WillOTheWisp.
     */
    static String classSimpleName(String cardName) {
        StringBuilder builder = new StringBuilder();
        boolean startOfToken = true;
        for (int i = 0; i < cardName.length(); i++) {
            char c = cardName.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                builder.append(startOfToken ? Character.toUpperCase(c) : c);
                startOfToken = false;
            } else {
                startOfToken = true;
            }
        }
        if (builder.length() == 0) {
            throw new IllegalArgumentException("card name has no letters or digits: " + cardName);
        }
        return builder.toString();
    }
}
