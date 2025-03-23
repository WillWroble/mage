package mage.player.ai;

import java.util.HashMap;
import java.util.Map;


public class Features {
    private Map<Class<?>, Features> subFeatures;
    private Map<Class<?>, Map<Integer, Integer>> features;
    private Map<Class<?>, Integer> occurances;
    public Features() {
        //constructor
        subFeatures = new HashMap<>();
        features = new HashMap<>();
        occurances = new HashMap<>();
    }

    /**
     * gets subfeatures at class c or creates them if they dont exist
     * @param c
     * @return subfeature at class c (never returns null)
     */
    public Features getSubFeatures(Class<?> c) {
        if(subFeatures.containsKey(c)) {//already contains feature
            return subFeatures.get(c);
        } else{
            Features newSub = new Features();
            subFeatures.put(c, newSub);
            return newSub;
        }
    }
    public int getIndexOfFeature(Class<?> c) {
        int count = occurances.get(c);
        return features.get(c).get(count);
    }
    public boolean addFeature(Class<?> c) {
        if(features.containsKey(c)) {//has feature
            int count = occurances.get(c)+1;
            occurances.put(c, count);
            if(features.get(c).containsKey(count)) {//already contains feature
                return false;
            } else {//contains feature but different count
                features.get(c).put(count, StateEmbedder.indexCount++);
            }
        } else {//completely new feature
            occurances.put(c, 1);
            Map<Integer, Integer> n = new HashMap<>();
            n.put(1, StateEmbedder.indexCount++);
            features.put(c, n);
        }
        return false;
    }
    public void resetOccurances() {
        occurances.replaceAll((c, v) -> 0);
        for(Class<?> c : subFeatures.keySet()) {
            subFeatures.get(c).resetOccurances();
        }
    }
}
