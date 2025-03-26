package mage.player.ai;

import com.j256.ormlite.stmt.query.In;

import java.util.*;


public class Features {
    private Map<String, Map<Integer, Features>> subFeatures;
    private Map<String, Map<Integer, Integer>> features;
    private Map<String, TreeMap<Integer, Map<Integer, Integer>>> numericFeatures; //name->value->occurrences
    private Map<String, Integer> occurances;
    private Map<String, TreeMap<Integer, Integer>> numericOccurences;
    String featureName;
    Features parent;
    public Features() {
        //constructor
        subFeatures = new HashMap<>();
        features = new HashMap<>();
        occurances = new HashMap<>();
        numericFeatures = new HashMap<>();
        numericOccurences = new HashMap<>();
        parent = null;
        featureName = "root";
    }
    public Features(Features p, String name) {
        this();
        parent = p;
        featureName = name;
    }

    /**
     * gets subfeatures at class c or creates them if they dont exist
     * @param c
     * @return subfeature at class c (never returns null)
     */
    public Features getSubFeatures(String c) {
        int n = occurances.get(c);
        if(subFeatures.containsKey(c)) {//already contains feature
            if(subFeatures.get(c).containsKey(n)) {//conatins count too
                return subFeatures.get(c).get(n);
            } else {//new count
                Map<Integer, Features> map = subFeatures.get(c);
                Features newSub = new Features(this, c + "_" + Integer.toString(n));
                map.put(n, newSub);
                return newSub;
            }
        } else{//completely new
            Map<Integer, Features> newMap = new HashMap<>();
            Features newSub = new Features(this, c + "_1");
            newMap.put(1, newSub);
            subFeatures.put(c, newMap);
            return newSub;
        }
    }
    public int getIndexOfFeature(String c) {
        int count = occurances.get(c);
        return features.get(c).get(count);
    }
    public Set<Integer> getIndicesOfNumericFeature(String name, int num) {
        Set<Integer> out = new HashSet<>();
        for (int n : numericFeatures.get(name).headMap(num, true).keySet()) {
            int count = numericOccurences.get(name).get(num);
            out.add(numericFeatures.get(name).get(num).get(count));
        }
        return out;
    }
    public void addFeature(String c) {
        //always add feature to parent
        if(parent != null) parent.addFeature(c);

        if(features.containsKey(c)) {//has feature
            int count = occurances.get(c)+1;
            occurances.put(c, count);
            if(features.get(c).containsKey(count)) {//already contains feature at this count
                System.out.printf("Index %d is already reserved for feature %s at %d times in %s\n", features.get(c).get(count), c, count, featureName);
            } else {//contains feature but different count
                features.get(c).put(count, StateEmbedder.indexCount++);
                System.out.printf("Feature %s exists but has not occurred %d times, reserving index %d for the %d occurrence of this feature in %s\n",
                        c, count, StateEmbedder.indexCount-1, count, featureName);
            }
        } else {//completely new feature
            occurances.put(c, 1);
            Map<Integer, Integer> n = new HashMap<>();
            n.put(1, StateEmbedder.indexCount++);
            features.put(c, n);
            System.out.printf("New feature %s discovered in %s, reserving index %d for this feature\n", c, featureName, n.get(1));
        }
    }
    public void addNumericFeature(String name, int num) {
        addNumericFeature(name, num, true);
    }

    public void addNumericFeature(String name, int num, boolean callParent) {
        //usually add feature to parent
        if(parent != null && callParent) parent.addNumericFeature(name, num);

        if(numericFeatures.containsKey(name)) {

            //also adds copy to number right below this one which will recursively increment the occurrences of each lesser feature
            Integer nextHighest = numericFeatures.get(name).floorKey(num-1);
            if(nextHighest != null) addNumericFeature(name, nextHighest, false);

            if(numericFeatures.get(name).containsKey(num)) {
                int count = numericOccurences.get(name).get(num)+1;
                numericOccurences.get(name).put(num, count);

                if(numericFeatures.get(name).get(num).containsKey(count)) {//already contains feature at this count
                    System.out.printf("Index %d is already reserved for numeric feature %s with %d at %d times in %s\n", numericFeatures.get(name).get(num).get(count), name, num, count, featureName);
                } else {//contains feature and num but different count
                    numericFeatures.get(name).get(num).put(count, StateEmbedder.indexCount++);
                    System.out.printf("Feature %s with %d exists but has not occurred %d times, reserving index %d for the %d occurrence of this feature in %s\n",
                            name, num, count, StateEmbedder.indexCount-1, count, featureName);
                }
                //System.out.printf("Index %d is already reserved for numeric feature %s with %d in %s\n", numericFeatures.get(name).get(num), name, num, featureName);
            } else { //contains category but not this number
                Map<Integer, Map<Integer, Integer>> map = numericFeatures.get(name);
                Map<Integer, Integer> subMap = new HashMap<>();
                subMap.put(1, StateEmbedder.indexCount++);
                map.put(num, subMap);
                numericOccurences.get(name).put(num, 1);
                System.out.printf("Feature %s exists but has not occurred with %d, reserving index %d for this feature at %d in %s\n",
                        name, num, StateEmbedder.indexCount-1, num, featureName);
            }
        } else {//completely new category
            TreeMap<Integer, Map<Integer, Integer>> newMap = new TreeMap<>();
            Map<Integer, Integer> subMap = new HashMap<>();
            subMap.put(1, StateEmbedder.indexCount++);
            newMap.put(num, subMap);
            numericFeatures.put(name, newMap);
            TreeMap<Integer, Integer> newTreeMap = new TreeMap<>();
            newTreeMap.put(num, 1);
            numericOccurences.put(name, newTreeMap);
            System.out.printf("New numeric feature %s discovered with %d in %s, reserving index %d for this feature at %d\n", name,
                    num, featureName, StateEmbedder.indexCount-1, num);
        }
    }
    public void accumulateNumericalFeatures() {
        for(String name : numericFeatures.keySet()) {
            int toAdd = 0;
            for(int i : numericFeatures.get(name).descendingKeySet()) {
                for(int j = 0; j < toAdd; j++) {
                    addNumericFeature(name, i, false);
                }
                toAdd = numericOccurences.get(name).get(i);
            }
        }
    }
    public void resetOccurrences() {
        occurances.replaceAll((k, v) -> 0);
        for(String c : subFeatures.keySet()) {
            for(int i : subFeatures.get(c).keySet()) {
                subFeatures.get(c).get(i).resetOccurrences();
            }
        }
        for(String c : numericOccurences.keySet()) {
            numericOccurences.get(c).replaceAll((k, v) -> 0);
        }
    }
}
