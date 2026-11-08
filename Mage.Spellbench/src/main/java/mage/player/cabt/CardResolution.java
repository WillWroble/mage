package mage.player.cabt;

import mage.cards.Card;

import java.util.UUID;

/**
 * The outcome of resolving one requested card name against XMage's card
 * identity, with the diagnostics a caller needs to understand <em>why</em> a
 * name did or did not resolve: the name as requested, its normalized form,
 * whether it resolved, the {@link Strategy} that matched, the canonical XMage
 * card name and printing, and — on failure — a stable reason.
 * <p>
 * A resolution never silently substitutes: an unresolved name carries
 * {@code resolved == false} and no card factory, so callers must fail closed
 * rather than fall through to a wrong card.
 */
public final class CardResolution {

    /** How a requested name was matched to an XMage card. */
    public enum Strategy {
        /** The raw requested name matched a repository card directly. */
        EXACT,
        /** The name matched only after punctuation/whitespace normalization. */
        NORMALIZED,
        /**
         * The repository had no match; the legacy class-name heuristic
         * constructed the card by reflection. Kept as a fallback and for
         * test fixtures that run without a scanned card database.
         */
        CLASS_HEURISTIC
    }

    /** Stable failure code for an unresolved name. */
    public static final String UNKNOWN_CARD = "UNKNOWN_CARD";

    private final String requestedName;
    private final String normalizedName;
    private final boolean resolved;
    private final Strategy strategy;
    private final String canonicalName;
    private final String setCode;
    private final String cardNumber;
    private final String failureCode;
    private final String failureReason;
    // How to mint a fresh Card instance for this resolution. Never serialized;
    // null when the name did not resolve.
    private final transient CardFactory factory;

    /** Builds a fresh XMage {@link Card} for a resolved name. */
    interface CardFactory {
        Card create();
    }

    private CardResolution(String requestedName, String normalizedName, boolean resolved,
                           Strategy strategy, String canonicalName, String setCode,
                           String cardNumber, String failureCode, String failureReason,
                           CardFactory factory) {
        this.requestedName = requestedName;
        this.normalizedName = normalizedName;
        this.resolved = resolved;
        this.strategy = strategy;
        this.canonicalName = canonicalName;
        this.setCode = setCode;
        this.cardNumber = cardNumber;
        this.failureCode = failureCode;
        this.failureReason = failureReason;
        this.factory = factory;
    }

    static CardResolution resolved(String requestedName, String normalizedName,
                                   Strategy strategy, String canonicalName, String setCode,
                                   String cardNumber, CardFactory factory) {
        return new CardResolution(requestedName, normalizedName, true, strategy,
                canonicalName, setCode, cardNumber, null, null, factory);
    }

    static CardResolution unresolved(String requestedName, String normalizedName,
                                     String failureReason) {
        return new CardResolution(requestedName, normalizedName, false, null, null, null,
                null, UNKNOWN_CARD, failureReason, null);
    }

    /**
     * Mints a fresh card instance for this resolution, owned by
     * {@code ownerId}. Fails closed with {@link CabtDeckFactory.UnknownCardException}
     * for an unresolved name so a deck can never be silently shortened.
     */
    public Card createCard(UUID ownerId) {
        if (!resolved || factory == null) {
            throw new CabtDeckFactory.UnknownCardException(requestedName, null);
        }
        Card card = factory.create();
        card.setOwnerId(ownerId);
        return card;
    }

    public String requestedName() {
        return requestedName;
    }

    public String normalizedName() {
        return normalizedName;
    }

    public boolean isResolved() {
        return resolved;
    }

    public Strategy strategy() {
        return strategy;
    }

    public String canonicalName() {
        return canonicalName;
    }

    public String setCode() {
        return setCode;
    }

    public String cardNumber() {
        return cardNumber;
    }

    public String failureCode() {
        return failureCode;
    }

    public String failureReason() {
        return failureReason;
    }
}
