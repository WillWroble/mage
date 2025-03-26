package mage.player.ai;

import com.j256.ormlite.stmt.query.In;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class Features2 {
    private Map<Class<?>, Map<Class<?>, Map<Integer, Integer>>> globalFeatureMapping;
    private Map<Class<?>, Integer> vectorLengths;
    private Map<Class<?>, Integer> occurances;
    public Features2() {
        //constructor
        vectorLengths = new HashMap<>();
        globalFeatureMapping = new HashMap<>();
        occurances = new HashMap<>();
    }
    public int getIndexOfFeature(Class<?> c, Class<?> parent) {
        int count = occurances.get(c);
        return globalFeatureMapping.get(parent).get(c).get(count);
    }
    public void addFeatureMapping(Class<?> c, Class<?> parent) {
        if(globalFeatureMapping.containsKey(parent)) {//has feature
            Map<Class<?>, Map<Integer, Integer>> features = globalFeatureMapping.get(parent);
            if(features.containsKey(c)) {//has feature
                int count = occurances.get(c)+1;
                occurances.put(c, count);
                if(features.get(c).containsKey(count)) {//already contains feature
                    return;
                } else {//contains feature but different count
                    Integer i = vectorLengths.get(parent);
                    i++;
                    features.get(c).put(count, i);
                }
            } else {//completely new feature
                occurances.put(c, 1);
                Map<Integer, Integer> n = new HashMap<>();
                Integer i = vectorLengths.get(parent);
                i++;
                n.put(1, i);
                features.put(c, n);
            }
        } else {//completely new feature category
            Map<Class<?>, Map<Integer, Integer>> newMapping = new HashMap<>();
            Map<Integer, Integer> subMapping = new HashMap<>();
            subMapping.put(1, 0);
            vectorLengths.put(parent, 0);
            newMapping.put(c, subMapping);
            globalFeatureMapping.put(parent, newMapping);
        }
    }

    public void resetOccurances() {
        occurances.replaceAll((c, v) -> 0);

    }
}
