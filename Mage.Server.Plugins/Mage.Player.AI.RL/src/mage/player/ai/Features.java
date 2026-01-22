package mage.player.ai;

import java.io.Serializable;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;

import org.apache.log4j.Logger;


/**
 * This hierarchical structure represents the mapping of every possible relevant feature encountered from a game state to
 * an index on a 2000000 dimension binary vector. The reduced form of this vector (~5000) will be used as input for both a policy and
 * value neural network. To see how game features are mapped look at StateEncoder.java this data structure only handles and stores the
 * mappings.
 *
 * @author willwroble
 */
public class Features implements Serializable {
    private static final Logger logger = Logger.getLogger(Features.class);

    private static final int  TABLE_SIZE        = 2_000_000;                // hash bins
    private static final long GLOBAL_SEED       = 0x9E3779B185EBCA87L;      // fixed reproducible seed

    private final Map<String, Features> subFeatures;
    private final Map<String, Integer> occurrences;
    private final Map<String, Features> categoryMap; //isn't reset between states, anchored at parent
    private final Set<Features> activeCategories; //resets every state represents temporary category features fall under
    public boolean passToParent = true;

    private transient StateEncoder encoder;

    private String featureName;
    private long seed; //namespace hash
    public Features parent;
    public static boolean printOldFeatures = false;
    public static boolean printNewFeatures = false;
    public static boolean useFeatureMap = false;
    //root constructor
    public Features() {
        subFeatures = new HashMap<>();
        occurrences = new HashMap<>();
        categoryMap = new HashMap<>();
        activeCategories = new HashSet<>();
        parent = null;
        featureName = "root";
        seed = GLOBAL_SEED;
    }
    //sub feature constructor
    public Features(Features p, String name) {
        this();
        parent = p;
        featureName = name;
        encoder = p.encoder;
        seed = hash64(name, p.seed);
    }
    //category constructor
    public Features(String name, StateEncoder e, long s) {
        this();
        featureName = name;
        encoder = e;
        seed = s;
    }

    public void setEncoder(StateEncoder encoder) {
        this.encoder = encoder;
        for (String name : subFeatures.keySet()) {
            subFeatures.get(name).setEncoder(encoder);
        }
        for (String name : categoryMap.keySet()) {
            categoryMap.get(name).setEncoder(encoder);
        }
    }
    private Features getCategory(String name) {
        if (categoryMap.containsKey(name)) { //already contains category
            return categoryMap.get(name);
        } else { //completely new
            Features newCat = new Features(name + "_" + featureName, encoder, hash64(name, seed));
            categoryMap.put(name, newCat);
            return newCat;
        }
    }

    /**
     * gets subfeatures at name or creates them if they dont exist
     *
     * @param name
     * @return subfeature at name (never returns null)
     */
    public Features getSubFeatures(String name) {
        return getSubFeatures(name, true);
    }

    public Features getSubFeatures(String name, boolean passToParent) {
        //added as normal binary feature
        addFeature(name);

        int n = occurrences.get(name);
        String key = (name+"#"+n);
        if (subFeatures.containsKey(key)) { //already contains feature
            return subFeatures.get(key);
        } else { //completely new
            Features newSub = new Features(this, key);
            subFeatures.put(key, newSub);
            newSub.passToParent = passToParent;
            return newSub;
        }
    }

    /**
     * similar to a subfeature a category will pool features within itself. however
     * unlike subfeatures a feature can inherit multiple categories(ie card type and color).
     * you can think of subfeatures as abstract classes and categories as interfaces
     * this function creates/finds the category with the given name and adds it as a
     * category for this feature to pass up to, similar to the parent
     * Categories should always be added before features you want to pool into them
     *
     * can not add categories to root!!
     *
     * @param name
     */
    public void addCategory(String name) {
        addFeature(name); //first add as feature since every category is also a feature
        Features categoryFeature = parent.getCategory(name);
        activeCategories.add(categoryFeature);
    }

    public void addFeature(String name) {
        addFeature(name, true);
    }

    public void addFeature(String name, boolean callParent) {
        //usually add feature to parent/categories
        if (parent != null && callParent && passToParent) {
            parent.addFeature(name);
            for (Features c : activeCategories) {
                c.addFeature(name);
            }
        }
        int n;
        n = occurrences.getOrDefault(name, 0);
        n++;
        occurrences.put(name, n);
        String key = (name+"#"+n);
        long hash = hash64(key, seed);
        addIndex(hash, key);
    }

    public void addNumericFeature(String name, int num) {
        addNumericFeature(name, num, true);
    }

    /**
     * thermometer encodes each less value, while also maintaining occurrence counts per value
     * @param name
     * @param num
     * @param callParent
     */
    public void addNumericFeature(String name, int num, boolean callParent) {
        for(int n = 0; n < num; n++) {
            String key = (name+"@"+n);
            addFeature(key, callParent);
        }
    }

    public void stateRefresh() {
        activeCategories.clear();
        occurrences.replaceAll((k, v) -> 0);
        for (String n : subFeatures.keySet()) {
            subFeatures.get(n).stateRefresh();
        }
        for (String n : categoryMap.keySet()) {
            categoryMap.get(n).stateRefresh();
        }
    }
    private void addIndex(long h, String key) {
        int idx = indexFor(h);
        encoder.featureVector.add(idx);
        if(useFeatureMap) {
            int nameSpace;
            if(parent != null) {
                nameSpace = indexFor(hash64(featureName, parent.seed));
            } else {
                nameSpace = -1;
                if(!featureName.equals("root")) {
                    key = (featureName + "::" + key);
                }
            }
            encoder.featureMap.addFeature(key, nameSpace, idx);
        }
        if (encoder.seenIndices != null) {
            if (!encoder.seenIndices.contains((int) h)) {
                encoder.seenIndices.add((int) h);
                if (Features.printNewFeatures) logger.info("new feature, " + key + " in " + featureName + ", at index: " + idx);
            } else {
                if (Features.printOldFeatures) logger.info("seen feature, " + key  + " in " + featureName + ", at index: " + idx);
            }
        }
    }
    private static int indexFor(long h) {
        if (h < 0) h = -h;
        return (int) (h % TABLE_SIZE);
    }
    private static long hash64(String s, long seed) {
        byte[] data = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        long h = mix64(seed ^ (data.length * 0x9E3779B185EBCA87L));
        ByteBuffer bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        while (bb.remaining() >= 8) {
            long k = bb.getLong();
            h ^= mix64(k);
            h = Long.rotateLeft(h, 27) * 0x9E3779B185EBCA87L + 0x165667B19E3779F9L;
        }
        long k = 0;
        int rem = bb.remaining();
        for (int i = 0; i < rem; i++) {
            k ^= ((long) bb.get() & 0xFFL) << (8 * i);
        }
        h ^= mix64(k);
        h ^= h >>> 33; h *= 0xff51afd7ed558ccdL;
        h ^= h >>> 33; h *= 0xc4ceb9fe1a85ec53L;
        h ^= h >>> 33;
        return h;
    }
    private static long mix64(long z) {
        z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
        z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
        return z ^ (z >>> 31);
    }
}
