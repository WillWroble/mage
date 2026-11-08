package mage.player.cabt;

/**
 * Stable reference to an XMage game object inside an observation. Object ids
 * are the engine's own UUIDs (as strings), so later prompts can reference the
 * same objects the observation describes.
 */
public final class MagicObjectReference {

    private final String objectId;
    private final String sourceId;
    private final String zone;
    private final String name;
    private final String objectClass;
    private final String ownerId;
    private final String controllerId;

    public MagicObjectReference(String objectId, String sourceId, String zone,
                                String name, String objectClass,
                                String ownerId, String controllerId) {
        this.objectId = objectId;
        this.sourceId = sourceId;
        this.zone = zone;
        this.name = name;
        this.objectClass = objectClass;
        this.ownerId = ownerId;
        this.controllerId = controllerId;
    }

    public String getObjectId() {
        return objectId;
    }

    public String getSourceId() {
        return sourceId;
    }

    public String getZone() {
        return zone;
    }

    public String getName() {
        return name;
    }

    public String getObjectClass() {
        return objectClass;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public String getControllerId() {
        return controllerId;
    }
}
