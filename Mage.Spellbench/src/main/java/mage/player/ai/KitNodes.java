package mage.player.ai;

/** Kit code (not vendored): reads MAD's static node counter (one world at a time per runner, design 5.4 item 1). */
public final class KitNodes {

    private KitNodes() {
    }

    public static int count() {
        return SimulationNode2.nodeCount;
    }
}
