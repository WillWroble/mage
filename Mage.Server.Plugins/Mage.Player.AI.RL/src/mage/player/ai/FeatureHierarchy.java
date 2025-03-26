package mage.player.ai;

import java.util.HashSet;
import java.util.Set;
import java.util.Vector;

/**
 * responsible for storing the vector hierarchy that will be processed by deepsets
 */
public class FeatureHierarchy {
    Vector<Integer> features;
    Set<FeatureHierarchy> subFeatures;

    public FeatureHierarchy() {
        features = new Vector<>();
        subFeatures = new HashSet<>();
    }
}
