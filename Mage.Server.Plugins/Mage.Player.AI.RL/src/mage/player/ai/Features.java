package mage.player.ai;

import java.io.Serializable;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.log4j.Logger;


/**
 * Ephemeral, hashing-based Features tree (drop-in replacement).
 * Key properties:
 *  - Per-state, on-demand namespace nodes; GC after state.
 *  - Occurrence-aware subfeatures (getSubFeatures) AND terminal features (addFeature),
 *    each namespace tracks its own local counts.
 *  - Categories are real child namespaces (cached in categoriesForChildren),
 *    so occurrences within categories are tracked naturally.
 *  - pooling upward honors callParent (per call) and passToParent (persistent per node).
 *  - Numeric features are thermometer encoded (binary thresholds).
 *  - Indices computed by hashing; no global feature dictionary / merge.
 *
 * Optional:
 *  - If the encoder exposes a persistent "seen indices" set/bitmap, emitHere() will add to it.
 *  - If StateEncoder.globalIgnore (e.g., ImmutableRoaringBitmap) is present, you can use it for logging/filtering.
 */
public class Features implements Serializable {
    private static final long serialVersionUID = 4L;

    // ---- Tuning knobs ----
    private static final int  TABLE_SIZE        = 2_000_000;                // hash bins
    private static final long GLOBAL_SEED       = 0x9E3779B185EBCA87L;      // fixed reproducible seed
    private static final long OCC_CONST         = 0x165667B19E3779F9L;      // mix-in for occurrence separation
    private static final Logger logger = Logger.getLogger(Features.class);

    // Compatibility flags (kept; you can wire them to logs if desired)
    public static boolean printOldFeatures = false;
    public static boolean printNewFeatures = true;

    // ---- Public fields preserved for compatibility ----
    public boolean passToParent          = true;                 // persistent per-namespace guard for pooling
    public Features parent;                                      // upward link
    public String featureName = "root";                          // debug/compat

    // ---- Internal per-node state ----
    private transient StateEncoder encoder;                      // set once per state
    private final long nsSeed;                                   // namespace seed for this node

    // Occurrence counters:
    private final Map<String,Integer> occSub   = new HashMap<>(); // for getSubFeatures(token)   → K
    private final Map<String,Integer> occTerm  = new HashMap<>(); // for addFeature(name)        → K

    // Category handling:
    //  - categories active at THIS node (applies when emitting at this node).
    //  - each category is a real Features node anchored to this node's parent.
    private final Map<String, Features> categoriesForChildren = new HashMap<>();
    private final List<Features> activeCategories;               // inherited list of active category nodes

    // ---- Constructors ----

    /** Root constructor (legacy-compatible) */
    public Features() {
        this.featureName = "root";
        this.parent = null;
        this.encoder = null;
        this.nsSeed = GLOBAL_SEED;
        this.activeCategories = new ArrayList<>();
    }
    /** Legacy root/name/encoder/atomic ctor (compat) */
    public Features(String name, StateEncoder e, AtomicInteger i) {
        this.featureName = name;
        this.parent = null;
        this.encoder = e;
        this.nsSeed = GLOBAL_SEED;
        this.activeCategories = new ArrayList<>();
    }

    /** Internal child builder used by getSubFeatures(name, passToParent). */
    private Features(Features parent, String childToken, boolean childPassToParent, boolean emitNamespaceBit) {
        this.parent = parent;
        this.encoder = parent.encoder;
        this.featureName = childToken;

        int k = parent.bumpSubOccurrence(childToken);
        if (emitNamespaceBit) {
            parent.emitHereOcc(childToken); // "namespace is a feature" with terminal-occurrence semantics
        }
        this.nsSeed = deriveChildSeed(parent.nsSeed, childToken, k);
        this.activeCategories = new ArrayList<>(parent.activeCategories); // inherit active categories
        this.passToParent = childPassToParent;
    }

    /** Internal constructor to materialize a category node with an explicit seed (no subclass). */
    private Features(Features anchorParent, String catName, long explicitSeed, boolean isCategory) {
        this.parent = anchorParent;            // anchor at the parent of the scope that activated it
        this.encoder = (anchorParent != null ? anchorParent.encoder : null);
        this.featureName = (isCategory ? "CAT:" + catName : catName);
        this.nsSeed = explicitSeed;
        this.activeCategories = (anchorParent != null ? new ArrayList<>(anchorParent.activeCategories) : new ArrayList<>());
    }

    // ---- Public API (drop-in) ----

    public void setEncoder(StateEncoder encoder) { this.encoder = encoder; }

    /** Category accessor (compat). Usually you call addCategory(name) then emit under this scope/children. */
    public Features getCategory(String name) {
        if (name == null || name.isEmpty()) return null;
        // Follow old semantics: category namespace anchored at THIS node's parent.
        Features anchor = (this.parent != null ? this.parent : this);
        Features cat = anchor.categoriesForChildren.get(name);
        if (cat == null) {
            long catSeed = mix64(anchor.nsSeed ^ hash64("CAT:" + name, anchor.nsSeed));
            cat = new Features(anchor, name, catSeed, /*isCategory=*/true);
            anchor.categoriesForChildren.put(name, cat);
        }
        return cat;
    }

    /** Subfeature accessor (occurrence-aware); emits the subfeature token as a feature at the parent (old behavior). */
    public Features getSubFeatures(String name) { return getSubFeatures(name, true); }

    public Features getSubFeatures(String name, boolean passToParentForChild) {
        if (name == null || name.isEmpty()) return null;
        return new Features(this, name, passToParentForChild, /*emitNamespaceBit*/true);
    }

    /** Add a category: categories are features too, and activate pooling for this subtree. */
    public void addCategory(String name) {
        if (name == null || name.isEmpty()) return;
        // Emit the category as a terminal feature here (with occurrence counting)
        emitHereOcc(name);

        // Activate or reuse the category node anchored at this node's parent
        Features cat = getCategory(name);
        // Add to THIS node's active list if not present
        boolean present = false;
        for (Features c : activeCategories) {
            if (c == cat) { present = true; break; }
        }
        if (!present) activeCategories.add(cat);
    }

    public void addFeature(String name) { addFeature(name, true); }

    /**
     * Binary feature; terminal occurrences are tracked per node:
     *   1st/2nd/3rd call to the same 'name' under the same namespace produce distinct buckets.
     * pools to ancestors if (callParent && passToParent at each ancestor).
     */
    public void addFeature(String name, boolean callParent) {
        if (name == null || name.isEmpty()) return;

        emitHereOcc(name); // emit at THIS, with terminal-occurrence separation

        if (callParent && this.passToParent) {
            Features anc = this.parent;
            while (anc != null) {
                anc.emitHereOcc(name);          // ancestor tracks its own terminal occurrences
                if (!anc.passToParent) break;   // stop pooling if ancestor blocks
                anc = anc.parent;
            }
        }
    }

    public void addNumericFeature(String name, int num) { addNumericFeature(name, num, true); }

    /** Numeric feature with per-threshold occurrence counting (thermometer).
     *  Example: value=3 emits keys name:1, name:2, name:3
     *  Each threshold key tracks its own terminal occurrence (so multiple entities
     *  contributing to the same threshold produce distinct buckets).
     */
    public void addNumericFeature(String name, int k, boolean callParent) {
        if (name == null || name.isEmpty()) return;
        k = Math.max(0, k);
        for (int i = 0; i <= k; i++) {
            final String key = name + "<" + i + ">";

            // emit here with per-threshold terminal occurrence separation
            this.emitHereOcc(key);

            // pool to ancestors if requested, honoring passToParent at each hop
            if (callParent && this.passToParent) {
                Features anc = this.parent;
                while (anc != null) {
                    anc.emitHereOcc(key);          // ancestor tracks occurrences for this threshold separately
                    if (!anc.passToParent) break;
                    anc = anc.parent;
                }
            }
        }
    }

    /** Clears per-state runtime data on this node (call on root at start of processState). */
    public void stateRefresh() {
        this.occSub.clear();
        this.occTerm.clear();
        this.activeCategories.clear();
        this.categoriesForChildren.clear();
        // encoder/seed/passToParent unchanged; children from previous state are unreferenced and GC'd.
    }

    // ---- Internals ----

    /** Emit at THIS node, with terminal-occurrence separation and category mirroring. */
    private void emitHereOcc(String key) {
        if (encoder == null || encoder.featureVector == null) return;

        // Terminal occurrence number under THIS namespace:
        int k = bumpTermOccurrence(key);
        long seed = (k == 1 ? nsSeed : mix64(nsSeed ^ (OCC_CONST * (long) k)));

        // Emit main
        int idx = indexFor(seed, key);
        addIndex(idx, key, k);
        // Mirror into active category nodes (each with its own terminal occurrence counters)
        if (!activeCategories.isEmpty()) {
            for (int i = 0; i < activeCategories.size(); i++) {
                Features cat = activeCategories.get(i);
                cat.emitHereOcc(key); // note: cat handles its own occurrence separation
            }
        }
    }

    /** Bump occurrence for subfeature token used in getSubFeatures(token). */
    private int bumpSubOccurrence(String token) {
        Integer next = occSub.get(token);
        int k = (next == null ? 1 : next + 1);
        occSub.put(token, k);
        return k;
    }

    /** Bump occurrence for terminal features emitted at THIS node. */
    private int bumpTermOccurrence(String key) {
        Integer next = occTerm.get(key);
        int k = (next == null ? 1 : next + 1);
        occTerm.put(key, k);
        return k;
    }

    /** Append idx to encoder vector and update optional persistent seen sets/bitmaps. */
        private void addIndex(int idx, String key, int k) {
            encoder.featureVector.add(idx);

            if (encoder.seenFeatures != null) {
                if(!encoder.seenFeatures.contains(idx)) {
                    encoder.seenFeatures.add(idx); // RoaringBitmap/IntSet-up to you
                    if(Features.printNewFeatures) logger.info("new feature, " + key + "#" + k + " in " + featureName + ", at index: " + idx);
                } else {
                    if(Features.printOldFeatures) logger.info("seen feature, " + key + "#" + k + " in " + featureName + ", at index: " + idx);
                }
            }

        }

    private static int indexFor(long nsSeed, String key) {
        long h = hash64(key, nsSeed);
        if (h < 0) h = -h;
        return (int)(h % TABLE_SIZE);
    }

    private static long deriveChildSeed(long parentSeed, String token, int occurrenceK) {
        long s = mix64(parentSeed ^ hash64(token, parentSeed));
        s = mix64(s ^ (OCC_CONST * (long) occurrenceK)); // separate CARD#1 vs CARD#2
        return s;
    }

    // Hashing utilities
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
