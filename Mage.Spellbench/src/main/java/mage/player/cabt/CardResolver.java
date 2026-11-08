package mage.player.cabt;

import mage.cards.Card;
import mage.cards.repository.CardInfo;
import mage.cards.repository.CardRepository;
import mage.cards.repository.CardScanner;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Resolves requested card names to real XMage cards, repository-first.
 * <p>
 * The primary strategy queries XMage's {@link CardRepository}, which knows the
 * canonical name of every imported card — including the ones the legacy
 * class-name heuristic gets wrong: names with punctuation
 * ({@code "Boseiju, Who Endures"}), split / adventure / modal cards
 * ({@code "Fire // Ice"}, an adventure creature's combined name), and cards
 * whose XMage class name is not the naive transform of the printed name. When
 * the raw name misses, a conservative punctuation/whitespace
 * {@linkplain CardNameNormalizer normalization} is retried. Only if the
 * repository has no match at all does resolution fall back to the
 * {@link CabtDeckFactory} class-name heuristic — kept so the bridge still works
 * in a test JVM with no scanned card database, and as a fixture builder.
 * <p>
 * Resolution <b>fails closed</b>: an unknown name yields an unresolved
 * {@link CardResolution} (and {@link #buildDeck} throws), never a substituted
 * or omitted card. Every resolution carries the diagnostics needed to explain
 * the outcome (requested name, normalized name, strategy, canonical name,
 * printing, failure reason).
 */
public final class CardResolver {

    /** Seam over the XMage card database, so unit tests can run without one. */
    interface RepositoryLookup {
        /** The preferred printing for {@code name}, or null if none exists. */
        CardInfo find(String name);
    }

    private static final RepositoryLookup DEFAULT_REPOSITORY = new RepositoryLookup() {
        @Override
        public CardInfo find(String name) {
            // The card database owns its own precondition: a fresh
            // CabtProtocolServer subprocess must resolve repository-only names
            // (Boseiju, Who Endures; Fire // Ice) without any caller having
            // scanned first. Scanning happens here, before the first lookup can
            // return — so no unresolved result is ever cached against an
            // unscanned database.
            ensureCardDatabaseScanned();
            try {
                return CardRepository.instance.findPreferredCoreExpansionCard(name);
            } catch (RuntimeException e) {
                // an unavailable card database is a miss, not a crash: let the
                // heuristic fallback take over
                return null;
            }
        }
    };

    // scanning the whole set catalogue is a one-time, idempotent cost
    // (CardScanner guards on its own static flag); we guard again so a failed
    // scan in a set-less JVM is attempted only once and degrades to the
    // heuristic fallback rather than being retried on every lookup
    private static volatile boolean scanAttempted;

    private static synchronized void ensureCardDatabaseScanned() {
        if (scanAttempted) {
            return;
        }
        scanAttempted = true;
        try {
            CardScanner.scan();
        } catch (RuntimeException e) {
            // no set classes on the classpath (bare/partial checkout): leave
            // the repository empty so resolution falls back to the heuristic
        }
    }

    private final RepositoryLookup repository;
    private final CabtDeckFactory heuristic;
    private final boolean allowHeuristicFallback;
    private final Map<String, CardResolution> cache = new HashMap<String, CardResolution>();

    /** Production resolver: real card repository, heuristic fallback enabled. */
    public CardResolver() {
        this(DEFAULT_REPOSITORY, new CabtDeckFactory(), true);
    }

    CardResolver(RepositoryLookup repository, CabtDeckFactory heuristic,
                 boolean allowHeuristicFallback) {
        this.repository = repository;
        this.heuristic = heuristic;
        this.allowHeuristicFallback = allowHeuristicFallback;
    }

    /**
     * Resolves one requested name. Results are memoized per resolver so a
     * decklist with repeated names resolves each distinct name once.
     */
    public CardResolution resolve(String requestedName) {
        if (requestedName == null) {
            throw new IllegalArgumentException("card name must not be null");
        }
        CardResolution cached = cache.get(requestedName);
        if (cached != null) {
            return cached;
        }
        CardResolution resolution = resolveUncached(requestedName);
        cache.put(requestedName, resolution);
        return resolution;
    }

    private CardResolution resolveUncached(String requestedName) {
        String trimmed = requestedName.trim();
        String normalized = CardNameNormalizer.normalize(requestedName);
        if (normalized.isEmpty()) {
            return CardResolution.unresolved(requestedName, normalized,
                    "card name is blank");
        }

        // 1) exact repository match on the raw (trimmed) name
        CardInfo info = repository.find(trimmed);
        if (info != null) {
            return repositoryResolution(requestedName, normalized,
                    CardResolution.Strategy.EXACT, info);
        }

        // 2) repository match after conservative normalization
        if (!normalized.equals(trimmed)) {
            info = repository.find(normalized);
            if (info != null) {
                return repositoryResolution(requestedName, normalized,
                        CardResolution.Strategy.NORMALIZED, info);
            }
        }

        // 3) legacy class-name heuristic fallback (test fixtures / no DB)
        if (allowHeuristicFallback) {
            CardResolution heuristicResolution =
                    resolveByHeuristic(requestedName, normalized);
            if (heuristicResolution != null) {
                return heuristicResolution;
            }
        }

        return CardResolution.unresolved(requestedName, normalized,
                "no XMage card matches name \"" + requestedName + '"');
    }

    private CardResolution repositoryResolution(String requestedName, String normalized,
                                                CardResolution.Strategy strategy,
                                                final CardInfo info) {
        CardResolution.CardFactory factory = new CardResolution.CardFactory() {
            @Override
            public Card create() {
                Card card = info.createCard();
                if (card == null) {
                    throw new CabtDeckFactory.UnknownCardException(info.getName(), null);
                }
                return card;
            }
        };
        return CardResolution.resolved(requestedName, normalized, strategy,
                info.getName(), info.getSetCode(), info.getCardNumber(), factory);
    }

    private CardResolution resolveByHeuristic(String requestedName, final String normalized) {
        Card probe;
        try {
            probe = heuristic.createCard(normalized);
        } catch (CabtDeckFactory.UnknownCardException e) {
            return null;
        }
        CardResolution.CardFactory factory = new CardResolution.CardFactory() {
            @Override
            public Card create() {
                return heuristic.createCard(normalized);
            }
        };
        return CardResolution.resolved(requestedName, normalized,
                CardResolution.Strategy.CLASS_HEURISTIC, probe.getName(), null, null, factory);
    }

    /** Resolves every entry of a decklist, in order, without building cards. */
    public DeckValidation validateDeck(List<CabtDeckFactory.Entry> entries) {
        List<DeckValidation.Entry> resolved =
                new ArrayList<DeckValidation.Entry>(entries.size());
        for (CabtDeckFactory.Entry entry : entries) {
            resolved.add(new DeckValidation.Entry(
                    entry.name(), entry.count(), resolve(entry.name())));
        }
        return new DeckValidation(resolved);
    }

    /**
     * Builds one card per copy for every entry, owned by {@code ownerId}.
     * Fails closed: if any name is unresolved this throws
     * {@link CabtDeckFactory.UnknownCardException} naming the first offender,
     * so a deck is never silently shortened or substituted.
     */
    public List<Card> buildDeck(UUID ownerId, List<CabtDeckFactory.Entry> entries) {
        DeckValidation validation = validateDeck(entries);
        if (!validation.isValid()) {
            throw new CabtDeckFactory.UnknownCardException(
                    validation.failures().get(0).requestedName(), null);
        }
        List<Card> cards = new ArrayList<Card>();
        for (DeckValidation.Entry entry : validation.entries()) {
            for (int i = 0; i < entry.count(); i++) {
                cards.add(entry.resolution().createCard(ownerId));
            }
        }
        return cards;
    }
}
