package spellbench.kit.xmage;

import mage.cards.repository.CardScanner;

/** Builds the card database template ({@code ./db}) that each agent copies; run once per machine. */
public final class CardDb {

    private CardDb() {
    }

    public static void main(String[] args) {
        CardScanner.scan();
        System.exit(0);
    }
}
