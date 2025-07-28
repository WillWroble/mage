package mage.player.ai;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * This hierarchical structure represents the mapping of every possible relevant feature encountered from a game state to
 * an index on a 200000 dimension binary vector. The reduced form of this vector (~5000) will be used as input for both a policy and
 * value neural network. To see how game features are mapped look at StateEncoder.java this data structure only handles and stores the
 * mappings.
 *
 * @author willwroble
 */
public class Features implements Serializable {
    private static final long serialVersionUID = 2L; // Version updated for the structural change
    public AtomicInteger localIndexCount; // a mutable, thread-safe counter
    public int previousLocalIndexCount = 0;
    public Set<Integer> ignoreList;
    public int version = 0;

    private final Map<String, Map<Integer, Features>> subFeatures;
    private final Map<String, Map<Integer, Integer>> features;
    private final Map<String, TreeMap<Integer, Map<Integer, Integer>>> numericFeatures; //name->value->occurrences
    private final Map<String, Integer> occurrences;
    private final Map<String, TreeMap<Integer, Integer>> numericOccurrences;
    private final Map<String, Features> categoriesForChildren; //isn't reset between states, represents all possible categories for children
    private final Set<Features> categories; //resets every state represents temporary category features fall under
    public boolean passToParent = true;

    private transient StateEncoder encoder;

    private String featureName;
    public Features parent;
    public static boolean printOldFeatures = true;
    public static boolean printNewFeatures = true;

    public Features() {
        //constructor
        subFeatures = new HashMap<>();
        features = new HashMap<>();
        occurrences = new HashMap<>();
        numericFeatures = new HashMap<>();
        numericOccurrences = new HashMap<>();
        categoriesForChildren = new HashMap<>();
        categories = new HashSet<>();
        ignoreList = new HashSet<>();
        localIndexCount = new AtomicInteger(0);
        parent = null;
        featureName = "root";
    }

    public Features(Features p, String name) {
        this();
        // Manually set fields instead of calling this(), to avoid creating a new AtomicInteger
        parent = p;
        featureName = name;
        encoder = p.encoder;
        localIndexCount = p.localIndexCount;
    }

    public Features(String name, StateEncoder e, AtomicInteger i) {
        this();
        featureName = name;
        encoder = e;
        localIndexCount = i;
    }

    public void setEncoder(StateEncoder encoder) {
        this.encoder = encoder;
        for (String n : subFeatures.keySet()) {
            for (Integer i : subFeatures.get(n).keySet()) {
                subFeatures.get(n).get(i).setEncoder(encoder);
            }
        }
        for (String n : categoriesForChildren.keySet()) {
            categoriesForChildren.get(n).setEncoder(encoder);
        }
    }
    public Features getCategory(String name) {
        if(name.isEmpty()) return null;
        if (categoriesForChildren.containsKey(name)) { //already contains category
            return categoriesForChildren.get(name);
        } else { //completely new
            Features parentCategory = null;
            if (parent != null) parentCategory = parent.getCategory(name); //categories can have a parent
            Features newCat;
            if (parentCategory != null) {
                newCat = new Features(parentCategory, name + "_" + featureName);
            } else {
                newCat = new Features(name + "_" + featureName, encoder, localIndexCount);
            }
            categoriesForChildren.put(name, newCat);
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
        if(name.isEmpty()) return null;
        //added as normal binary feature
        addFeature(name);

        int n = occurrences.get(name);
        if (subFeatures.containsKey(name)) { //already contains feature
            if (subFeatures.get(name).containsKey(n)) { //contains count too
                return subFeatures.get(name).get(n);
            } else { //new count
                Map<Integer, Features> map = subFeatures.get(name);
                Features newSub = new Features(this, name + "_" + Integer.toString(n));
                map.put(n, newSub);
                return newSub;
            }
        } else { //completely new
            Map<Integer, Features> newMap = new HashMap<>();
            Features newSub = new Features(this, name + "_1");
            newMap.put(1, newSub);
            subFeatures.put(name, newMap);
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
     * Categories should always be added before features
     *
     * @param name
     */
    public void addCategory(String name) {
        if(name.isEmpty()) return;
        addFeature(name); //first add as feature since every category is also a feature
        Features categoryFeature = parent.getCategory(name);
        categories.add(categoryFeature);
    }

    public void addFeature(String name) {
        addFeature(name, true);
    }

    public void addFeature(String name, boolean callParent) {
        if(name.isEmpty()) return;
        //usually add feature to parent/categories
        if (parent != null && callParent && passToParent) {
            parent.addFeature(name);
            for (Features c : categories) {
                c.addFeature(name);
            }
        }

        if (features.containsKey(name)) { //has feature
            int count = occurrences.get(name) + 1;
            occurrences.put(name, count);
            if (features.get(name).containsKey(count)) { //already contains feature at this count
                if (printOldFeatures)
                    System.out.printf("Index %d is already reserved for feature %s at %d times in %s\n", features.get(name).get(count), name, count, featureName);
            } else { //contains feature but different count
                features.get(name).put(count, localIndexCount.getAndIncrement()); //  FIXED: Use atomic increment
                if (printNewFeatures)
                    System.out.printf("Feature %s exists but has not occurred %d times, reserving index %d for the %d occurrence of this feature in %s\n",
                            name, count, localIndexCount.get() - 1, count, featureName);
            }
        } else { //completely new feature
            occurrences.put(name, 1);
            Map<Integer, Integer> n = new HashMap<>();
            n.put(1, localIndexCount.getAndIncrement());
            features.put(name, n);
            if (printNewFeatures)
                System.out.printf("New feature %s discovered in %s, reserving index %d for this feature\n", name, featureName, n.get(1));
        }
        encoder.featureVector.add(features.get(name).get(occurrences.get(name)));
    }

    public void addNumericFeature(String name, int num) {
        addNumericFeature(name, num, true);
    }

    public void addNumericFeature(String name, int num, boolean callParent) {
        if(name.isEmpty()) return;
        //usually add feature to parent/categories
        if (parent != null && callParent && passToParent) {
            parent.addNumericFeature(name, num);
            for(int i = 0; i < num; i++) {
                //parent.addFeature(name + "_SUM", false);
            }
            for(Features c : categories) {
                c.addFeature(name);
                //keep track of numerical sum for categories
                for(int i = 0; i < num; i++) {
                    //c.addFeature(name + "_SUM", false);
                }
            }
        }

        //also adds copy to number right below this one which will recursively increment the occurrences of each lesser feature
        //Integer nextHighest = numericFeatures.get(name).floorKey(num-1);
        if (num > 0) addNumericFeature(name, num - 1, false);

        if (numericFeatures.containsKey(name)) {
            if (numericFeatures.get(name).containsKey(num)) {
                int count = numericOccurrences.get(name).get(num) + 1;
                numericOccurrences.get(name).put(num, count);

                if (numericFeatures.get(name).get(num).containsKey(count)) { //already contains feature at this count
                    if (printOldFeatures)
                        System.out.printf("Index %d is already reserved for numeric feature %s with %d at %d times in %s\n", numericFeatures.get(name).get(num).get(count), name, num, count, featureName);
                } else { //contains feature and num but different count
                    numericFeatures.get(name).get(num).put(count, localIndexCount.getAndIncrement());
                    if (printNewFeatures)
                        System.out.printf("Numeric feature %s with %d exists but has not occurred %d times, reserving index %d for the %d occurrence of this feature in %s\n",
                                name, num, count, localIndexCount.get() - 1, count, featureName);
                }
            } else { //contains category but not this number
                Map<Integer, Map<Integer, Integer>> map = numericFeatures.get(name);
                Map<Integer, Integer> subMap = new HashMap<>();
                subMap.put(1, localIndexCount.getAndIncrement());
                map.put(num, subMap);
                numericOccurrences.get(name).put(num, 1);
                if (printNewFeatures)
                    System.out.printf("Numeric feature %s exists but has not occurred with %d, reserving index %d for this feature at %d in %s\n",
                            name, num, localIndexCount.get() - 1, num, featureName);
            }
        } else { //completely new feature category
            TreeMap<Integer, Map<Integer, Integer>> newMap = new TreeMap<>();
            Map<Integer, Integer> subMap = new HashMap<>();
            subMap.put(1, localIndexCount.getAndIncrement());
            newMap.put(num, subMap);
            numericFeatures.put(name, newMap);
            TreeMap<Integer, Integer> newTreeMap = new TreeMap<>();
            newTreeMap.put(num, 1);
            numericOccurrences.put(name, newTreeMap);
            if (printNewFeatures)
                System.out.printf("New numeric feature %s discovered with %d in %s, reserving index %d for this feature at %d\n", name,
                        num, featureName, localIndexCount.get() - 1, num);
        }
        encoder.featureVector.add(numericFeatures.get(name).get(num).get(numericOccurrences.get(name).get(num)));
    }

    public void stateRefresh() {
        categories.clear();
        occurrences.replaceAll((k, v) -> 0);
        for (String c : numericOccurrences.keySet()) {
            numericOccurrences.get(c).replaceAll((k, v) -> 0);
        }
        for (String n : subFeatures.keySet()) {
            for (int i : subFeatures.get(n).keySet()) {
                subFeatures.get(n).get(i).stateRefresh();
            }
        }
        for (String n : categoriesForChildren.keySet()) {
            categoriesForChildren.get(n).stateRefresh();
        }
    }

    /**
     * copies the index of a feature to a new index between lists of state vectors
     * @param copyTo
     * @param copyFrom
     * @param indexTo
     * @param indexFrom
     */
    private void copyIndex(List<Set<Integer>> copyTo, List<Set<Integer>> copyFrom, int indexTo, int indexFrom) {
        for(int i = 0; i < copyFrom.size(); i++) {
            if(copyFrom.get(i).contains(indexFrom)) {
                copyTo.get(i).add(indexTo);
            }
        }
    }
    /**
     * always discard f after merging
     *
     * @param f object to merge with
     */
    public synchronized void merge(Features f, List<Set<Integer>> newStateVectors) {
        if (this == f) return;
        List<Set<Integer>> oldStateVectors = f.encoder.macroStateVectors;
        // Normal features
        for (String n : f.features.keySet()) {
            Map<Integer, Integer> thisOccurrenceMap = this.features.computeIfAbsent(n, k -> new HashMap<>());
            this.occurrences.putIfAbsent(n, 0);
            for (int i : f.features.get(n).keySet()) {
                if (!thisOccurrenceMap.containsKey(i)) {
                    thisOccurrenceMap.put(i, this.localIndexCount.getAndIncrement());
                }
                copyIndex(newStateVectors, oldStateVectors, thisOccurrenceMap.get(i), f.features.get(n).get(i));
            }
        }

        // Numeric features
        for (String n : f.numericFeatures.keySet()) {
            TreeMap<Integer, Map<Integer, Integer>> thisNumericMap = this.numericFeatures.computeIfAbsent(n, k -> new TreeMap<>());
            this.numericOccurrences.putIfAbsent(n, new TreeMap<>());
            for (int num : f.numericFeatures.get(n).keySet()) {
                Map<Integer, Integer> thisOccurrenceMap = thisNumericMap.computeIfAbsent(num, k -> new HashMap<>());
                this.numericOccurrences.get(n).putIfAbsent(num, 0);
                for (int i  : f.numericFeatures.get(n).get(num).keySet()) {
                    if (!thisOccurrenceMap.containsKey(i)) {
                        thisOccurrenceMap.put(i, this.localIndexCount.getAndIncrement());
                    }
                    copyIndex(newStateVectors, oldStateVectors, thisOccurrenceMap.get(i), f.numericFeatures.get(n).get(num).get(i));
                }
            }
        }
        //subfeatures
        for (String n : f.subFeatures.keySet()) {
            Map<Integer, Features> thisSubMap = this.subFeatures.computeIfAbsent(n, k -> new HashMap<>());
            for (int i : f.subFeatures.get(n).keySet()) {
                Features thisSubFeature = thisSubMap.computeIfAbsent(i, k -> new Features(this, n + "_" + i));
                thisSubFeature.merge(f.subFeatures.get(n).get(i), newStateVectors);
            }
        }
        //category labels
        for (String n : f.categoriesForChildren.keySet()) {
            if (!this.categoriesForChildren.containsKey(n)) {
                this.categoriesForChildren.put(n, this.getCategory(n));
            }
            this.categoriesForChildren.get(n).merge(f.categoriesForChildren.get(n), newStateVectors);
        }
    }
    /**
     * Creates a synchronized, deep copy of this Features object.
     * By being synchronized, it ensures we get a clean snapshot and never
     * copy the object while another thread is in the middle of merging.
     * @return A new, completely independent deep copy of this object.
     */
    public synchronized Features createDeepCopy() {
        try {
            ByteArrayOutputStream byteOutput = new ByteArrayOutputStream();
            ObjectOutputStream objectOutput = new ObjectOutputStream(byteOutput);
            objectOutput.writeObject(this);
            objectOutput.close();

            ByteArrayInputStream byteInput = new ByteArrayInputStream(byteOutput.toByteArray());
            ObjectInputStream objectInput = new ObjectInputStream(byteInput);
            Features copy = (Features) objectInput.readObject();
            objectInput.close();

            return copy;
        } catch (IOException | ClassNotFoundException e) {
            throw new RuntimeException("Failed to create a deep copy of the Features object.", e);
        }
    }
    /**
     * Prints the entire feature tree, hiding features on the ignore list by default.
     */
    public void printFeatureTree() {
        // Default behavior: do not print ignored features.
        printFeatureTree(false);
    }

    /**
     * Prints the entire feature tree in a hierarchical format.
     *
     * @param showIgnored If true, all features will be printed. If false, features
     * whose indices are in the ignoreList will not be printed.
     */
    public void printFeatureTree(boolean showIgnored) {
        // Start the recursion, passing the root's ignoreList down the tree.
        printTreeRecursive(this.featureName, showIgnored, this.ignoreList);
    }

    /**
     * Helper function to recursively traverse and print the feature tree.
     *
     * @param prefix        The current hierarchical path of the feature.
     * @param showIgnored   If false, features on the ignore list are skipped.
     * @param masterIgnoreList The single ignoreList from the root object to check against.
     */
    private void printTreeRecursive(String prefix, boolean showIgnored, Set<Integer> masterIgnoreList) {
        // The conditional check for printing.
        final boolean shouldPrintAll = showIgnored;

        // Print the direct "leaf" features of the current node
        if (this.features != null) {
            for (Map.Entry<String, Map<Integer, Integer>> featureEntry : this.features.entrySet()) {
                String featureName = featureEntry.getKey();
                Map<Integer, Integer> occurrenceMap = featureEntry.getValue();
                for (Map.Entry<Integer, Integer> occurrenceEntry : occurrenceMap.entrySet()) {
                    Integer occurrence = occurrenceEntry.getKey();
                    Integer index = occurrenceEntry.getValue();
                    if (shouldPrintAll || !masterIgnoreList.contains(index)) {
                        System.out.println(prefix + "/" + featureName + "/" + occurrence + "=>" + index);
                    }
                }
            }
        }

        // Print the direct numeric "leaf" features of the current node
        if (this.numericFeatures != null) {
            for (Map.Entry<String, TreeMap<Integer, Map<Integer, Integer>>> numericEntry : this.numericFeatures.entrySet()) {
                String featureName = numericEntry.getKey();
                for (Map.Entry<Integer, Map<Integer, Integer>> valueEntry : numericEntry.getValue().entrySet()) {
                    int numValue = valueEntry.getKey();
                    for (Map.Entry<Integer, Integer> occurrenceEntry : valueEntry.getValue().entrySet()) {
                        Integer occurrence = occurrenceEntry.getKey();
                        Integer index = occurrenceEntry.getValue();
                        if (shouldPrintAll || !masterIgnoreList.contains(index)) {
                            System.out.println(prefix + "/" + featureName + "_val" + numValue + "/" + occurrence + "=>" + index);
                        }
                    }
                }
            }
        }

        // Recurse into sub-features, passing the master list along
        if (this.subFeatures != null) {
            for (Map.Entry<String, Map<Integer, Features>> subEntry : this.subFeatures.entrySet()) {
                String subFeatureName = subEntry.getKey();
                Map<Integer, Features> occurrenceMap = subEntry.getValue();
                for (Map.Entry<Integer, Features> occurrenceEntry : occurrenceMap.entrySet()) {
                    Integer occurrence = occurrenceEntry.getKey();
                    Features subFeatureInstance = occurrenceEntry.getValue();
                    String newPrefix = prefix + "/" + subFeatureName + "/" + occurrence;
                    subFeatureInstance.printTreeRecursive(newPrefix, showIgnored, masterIgnoreList);
                }
            }
        }

        // Recurse into categories, passing the master list along
        if (this.categoriesForChildren != null) {
            for (Map.Entry<String, Features> categoryEntry : this.categoriesForChildren.entrySet()) {
                String categoryName = categoryEntry.getKey();
                Features categoryFeature = categoryEntry.getValue();
                String newPrefix = prefix + "/" + categoryName;
                categoryFeature.printTreeRecursive(newPrefix, showIgnored, masterIgnoreList);
            }
        }
    }
    // Helper method to persist the Features mapping to a file
    public void saveMapping(String filename) throws IOException {
        try (ObjectOutputStream oos = new ObjectOutputStream(Files.newOutputStream(Paths.get(filename)))) {
            oos.writeObject(this);
        }
    }

    // Helper method to load a Features mapping from a file
    public static Features loadMapping(String filename) throws IOException, ClassNotFoundException {
        try (ObjectInputStream ois = new ObjectInputStream(Files.newInputStream(Paths.get(filename)))) {
            return (Features) ois.readObject();
        }
    }
}