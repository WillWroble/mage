package mage.player.ai;

import java.util.*;


public class Features {
    private Map<String, Map<Integer, Features>> subFeatures;
    private Map<String, Map<Integer, Integer>> features;
    private Map<String, TreeMap<Integer, Map<Integer, Integer>>> numericFeatures; //name->value->occurrences
    private Map<String, Integer> occurances;
    private Map<String, TreeMap<Integer, Integer>> numericOccurences;
    private Map<String, Features> categoriesForChildren; //isnt reset between states represent all possible categories for children
    public Set<Features> categories; //resets every state represents temporary category features fall under

    public String featureName;
    public Features parent;
    public static boolean printOldFeatures = true;
    public static boolean printNewFeatures = true;
    public Features() {
        //constructor
        subFeatures = new HashMap<>();
        features = new HashMap<>();
        occurances = new HashMap<>();
        numericFeatures = new HashMap<>();
        numericOccurences = new HashMap<>();
        categoriesForChildren = new HashMap<>();
        categories = new HashSet<>();
        parent = null;
        featureName = "root";
    }
    public Features(Features p, String name) {
        this();
        parent = p;
        featureName = name;
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
        //first add as a normal binary feature
        addFeature(name);

        int n = occurances.get(name);
        if(subFeatures.containsKey(name)) {//already contains feature
            if(subFeatures.get(name).containsKey(n)) {//conatins count too
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
        if(parent != null && callParent) {
            parent.addFeature(name);
            for(Features c : categories) {
                c.addFeature(name);
            }
        }

        if(features.containsKey(name)) {//has feature
            int count = occurances.get(name)+1;
            occurances.put(name, count);
            if(features.get(name).containsKey(count)) {//already contains feature at this count
                if(printOldFeatures) System.out.printf("Index %d is already reserved for feature %s at %d times in %s\n", features.get(name).get(count), name, count, featureName);
            } else {//contains feature but different count
                features.get(name).put(count, StateEncoder.indexCount++);
                if(printNewFeatures) System.out.printf("Feature %s exists but has not occurred %d times, reserving index %d for the %d occurrence of this feature in %s\n",
                        name, count, StateEncoder.indexCount-1, count, featureName);
            }
        } else {//completely new feature
            occurances.put(name, 1);
            Map<Integer, Integer> n = new HashMap<>();
            n.put(1, StateEncoder.indexCount++);
            features.put(name, n);
            if(printNewFeatures) System.out.printf("New feature %s discovered in %s, reserving index %d for this feature\n", name, featureName, n.get(1));
        }
        StateEncoder.featureVector[features.get(name).get(occurances.get(name))] = true;
    }
    public void addNumericFeature(String name, int num) {
        addNumericFeature(name, num, true);
    }
    public void addNumericFeature(String name, int num, boolean callParent) {
        //usually add feature to parent/categories
        if(parent != null && callParent) {
            parent.addNumericFeature(name, num);
            //keep track of numerical sum for parents
            for(int i = 0; i < num; i++) {
                parent.addFeature(name + "_SUM", false);
            }
            for(Features c : categories) {
                c.addFeature(name);
                //keep track of numerical sum for categories
                for(int i = 0; i < num; i++) {
                    c.addFeature(name + "_SUM", false);
                }
            }
        }

        //also adds copy to number right below this one which will recursively increment the occurrences of each lesser feature
        //Integer nextHighest = numericFeatures.get(name).floorKey(num-1);
        if(num > 0) addNumericFeature(name, num-1, false);

        if(numericFeatures.containsKey(name)) {

            if(numericFeatures.get(name).containsKey(num)) {
                int count = numericOccurences.get(name).get(num)+1;
                numericOccurences.get(name).put(num, count);

                if(numericFeatures.get(name).get(num).containsKey(count)) {//already contains feature at this count
                    if(printOldFeatures) System.out.printf("Index %d is already reserved for numeric feature %s with %d at %d times in %s\n", numericFeatures.get(name).get(num).get(count), name, num, count, featureName);
                } else {//contains feature and num but different count
                    numericFeatures.get(name).get(num).put(count, StateEncoder.indexCount++);
                    if(printNewFeatures) System.out.printf("Numeric feature %s with %d exists but has not occurred %d times, reserving index %d for the %d occurrence of this feature in %s\n",
                            name, num, count, StateEncoder.indexCount-1, count, featureName);
                }
                //System.out.printf("Index %d is already reserved for numeric feature %s with %d in %s\n", numericFeatures.get(name).get(num), name, num, featureName);
            } else { //contains category but not this number
                Map<Integer, Map<Integer, Integer>> map = numericFeatures.get(name);
                Map<Integer, Integer> subMap = new HashMap<>();
                subMap.put(1, StateEncoder.indexCount++);
                map.put(num, subMap);
                numericOccurences.get(name).put(num, 1);
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
            numericOccurences.put(name, newTreeMap);
            if(printNewFeatures) System.out.printf("New numeric feature %s discovered with %d in %s, reserving index %d for this feature at %d\n", name,
                    num, featureName, StateEncoder.indexCount-1, num);
        }
        StateEncoder.featureVector[numericFeatures.get(name).get(num).get(numericOccurences.get(name).get(num))] = true;
    }
    public void stateRefresh() {
        categories.clear();
        occurances.replaceAll((k, v) -> 0);
        for(String c : numericOccurences.keySet()) {
            numericOccurences.get(c).replaceAll((k, v) -> 0);
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
}
