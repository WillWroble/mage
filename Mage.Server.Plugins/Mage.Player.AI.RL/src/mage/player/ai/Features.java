package mage.player.ai;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

/**
 *this hierarchical structure represents the mapping of every possible relevant feature encountered from a game state to
 * an index on a 200000 dimension binary vector. the reduced form of this vector (~6000) will be used as input for both a policy and
 * value neural network. To see how game features are mapped look at StateEncoder.java this data structure only handles and stores the
 * mappings
 * @author willwroble
 */
public class Features  implements Serializable {
    private static final long serialVersionUID = 1L;
    public int globalIndexCount;
    Set<Integer> ignoreList;
    Map<Integer, Integer> rawToReduced;
    int version;

    private final Map<String, Map<Integer, Features>> subFeatures;
    private final Map<String, Map<Integer, Integer>> features;
    private final Map<String, TreeMap<Integer, Map<Integer, Integer>>> numericFeatures; //name->value->occurrences
    private final Map<String, Integer> occurrences;
    private final Map<String, TreeMap<Integer, Integer>> numericOccurrences;
    private final Map<String, Features> categoriesForChildren; //isn't reset between states, represents all possible categories for children
    private final Set<Features> categories; //resets every state represents temporary category features fall under
    public boolean passToParent = true;

    private StateEncoder encoder;

    private String featureName;
    private Features parent;
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
        parent = null;
        featureName = "root";
    }
    public Features(Features p, String name) {
        this();
        parent = p;
        featureName = name;
        encoder = p.encoder;
    }

    public void setEncoder(StateEncoder encoder) {
        this.encoder = encoder;
    }

    public Features getCategory(String name) {

        if(categoriesForChildren.containsKey(name)) {//already contains category
            return categoriesForChildren.get(name);

        } else{//completely new
            Features parentCategory = null;
            if(parent != null) parentCategory = parent.getCategory(name); //categories can have a parent
            Features newCat = new Features(parentCategory, name + "_" + featureName);
            categoriesForChildren.put(name, newCat);
            return newCat;
        }

    }

    /**
     * gets subfeatures at name or creates them if they dont exist
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
        if(subFeatures.containsKey(name)) {//already contains feature
            if(subFeatures.get(name).containsKey(n)) {//contains count too
                return subFeatures.get(name).get(n);
            } else {//new count
                Map<Integer, Features> map = subFeatures.get(name);
                Features newSub = new Features(this, name + "_" + Integer.toString(n));
                map.put(n, newSub);
                return newSub;
            }
        } else{//completely new
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
     * @param name
     */
    public void addCategory(String name) {
        addFeature(name); //first add as feature since every category is also a feature
        Features categoryFeature = parent.getCategory(name);
        categories.add(categoryFeature);
    }
    public void addFeature(String name) {
        addFeature(name, true);
    }
    public void addFeature(String name, boolean callParent) {
        //usually add feature to parent/categories
        if(parent != null && callParent && passToParent) {
            parent.addFeature(name);
            for(Features c : categories) {
                c.addFeature(name);
            }
        }

        if(features.containsKey(name)) {//has feature
            int count = occurrences.get(name)+1;
            occurrences.put(name, count);
            if(features.get(name).containsKey(count)) {//already contains feature at this count
                if(printOldFeatures) System.out.printf("Index %d is already reserved for feature %s at %d times in %s\n", features.get(name).get(count), name, count, featureName);
            } else {//contains feature but different count
                features.get(name).put(count, StateEncoder.indexCount++);
                if(printNewFeatures) System.out.printf("Feature %s exists but has not occurred %d times, reserving index %d for the %d occurrence of this feature in %s\n",
                        name, count, StateEncoder.indexCount-1, count, featureName);
            }
        } else {//completely new feature
            occurrences.put(name, 1);
            Map<Integer, Integer> n = new HashMap<>();
            n.put(1, StateEncoder.indexCount++);
            features.put(name, n);
            if(printNewFeatures) System.out.printf("New feature %s discovered in %s, reserving index %d for this feature\n", name, featureName, n.get(1));
        }
        StateEncoder.featureVector.add(features.get(name).get(occurrences.get(name)));
    }
    public void addNumericFeature(String name, int num) {
        addNumericFeature(name, num, true);
    }
    public void addNumericFeature(String name, int num, boolean callParent) {
        //usually add feature to parent/categories
        if(parent != null && callParent && passToParent) {
            parent.addNumericFeature(name, num);
        }

        //also adds copy to number right below this one which will recursively increment the occurrences of each lesser feature
        //Integer nextHighest = numericFeatures.get(name).floorKey(num-1);
        if(num > 0) addNumericFeature(name, num-1, false);

        if(numericFeatures.containsKey(name)) {

            if(numericFeatures.get(name).containsKey(num)) {
                int count = numericOccurrences.get(name).get(num)+1;
                numericOccurrences.get(name).put(num, count);

                if(numericFeatures.get(name).get(num).containsKey(count)) {//already contains feature at this count
                    if(printOldFeatures) System.out.printf("Index %d is already reserved for numeric feature %s with %d at %d times in %s\n", numericFeatures.get(name).get(num).get(count), name, num, count, featureName);
                } else {//contains feature and num but different count
                    numericFeatures.get(name).get(num).put(count, StateEncoder.indexCount++);
                    if(printNewFeatures) System.out.printf("Numeric feature %s with %d exists but has not occurred %d times, reserving index %d for the %d occurrence of this feature in %s\n",
                            name, num, count, StateEncoder.indexCount-1, count, featureName);
                }
            } else { //contains category but not this number
                Map<Integer, Map<Integer, Integer>> map = numericFeatures.get(name);
                Map<Integer, Integer> subMap = new HashMap<>();
                subMap.put(1, StateEncoder.indexCount++);
                map.put(num, subMap);
                numericOccurrences.get(name).put(num, 1);
                if(printNewFeatures) System.out.printf("Numeric feature %s exists but has not occurred with %d, reserving index %d for this feature at %d in %s\n",
                        name, num, StateEncoder.indexCount-1, num, featureName);
            }
        } else {//completely new feature category
            TreeMap<Integer, Map<Integer, Integer>> newMap = new TreeMap<>();
            Map<Integer, Integer> subMap = new HashMap<>();
            subMap.put(1, StateEncoder.indexCount++);
            newMap.put(num, subMap);
            numericFeatures.put(name, newMap);
            TreeMap<Integer, Integer> newTreeMap = new TreeMap<>();
            newTreeMap.put(num, 1);
            numericOccurrences.put(name, newTreeMap);
            if(printNewFeatures) System.out.printf("New numeric feature %s discovered with %d in %s, reserving index %d for this feature at %d\n", name,
                    num, featureName, StateEncoder.indexCount-1, num);
        }
        StateEncoder.featureVector.add(numericFeatures.get(name).get(num).get(numericOccurrences.get(name).get(num)));
    }
    public void stateRefresh() {
        categories.clear();
        occurrences.replaceAll((k, v) -> 0);
        for(String c : numericOccurrences.keySet()) {
            numericOccurrences.get(c).replaceAll((k, v) -> 0);
        }
        for(String n : subFeatures.keySet()) {
            for(int i : subFeatures.get(n).keySet()) {
                subFeatures.get(n).get(i).stateRefresh();
            }
        }
        for (String n : categoriesForChildren.keySet()) {
            categoriesForChildren.get(n).stateRefresh();
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
