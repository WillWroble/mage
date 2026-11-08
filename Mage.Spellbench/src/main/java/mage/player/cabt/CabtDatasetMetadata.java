package mage.player.cabt;

/**
 * Per-run configuration metadata stamped into every dataset record: which
 * engine build and which decks produced the transitions.
 */
public final class CabtDatasetMetadata {

    private final String xmageVersion;
    private final String deck0Id;
    private final String deck1Id;

    public CabtDatasetMetadata(String xmageVersion, String deck0Id, String deck1Id) {
        if (xmageVersion == null || deck0Id == null || deck1Id == null) {
            throw new IllegalArgumentException("dataset metadata fields must not be null");
        }
        this.xmageVersion = xmageVersion;
        this.deck0Id = deck0Id;
        this.deck1Id = deck1Id;
    }

    public String getXmageVersion() {
        return xmageVersion;
    }

    public String getDeck0Id() {
        return deck0Id;
    }

    public String getDeck1Id() {
        return deck1Id;
    }
}
