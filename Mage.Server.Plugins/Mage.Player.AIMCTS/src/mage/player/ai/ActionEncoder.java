package mage.player.ai;

import mage.abilities.Ability;

import java.util.*;

public class ActionEncoder {
    public static int indexCount = 0;
    public static boolean printActions = false;
    public static List<double[]> actionVectors = new ArrayList<>();
    public static Map<String, Integer> actionMap = new HashMap<>();

    public static void addAction(double[] label) {
        actionVectors.add(label);
    }
    public static int getAction(Ability sa) {
        String name = sa.toString();
        if(actionMap.containsKey(name)) {//already contains action
            if(printActions) System.out.printf("Action: %s already maps to index %d\n", name, actionMap.get(name));
        } else {
            actionMap.put(name, indexCount++);
            if(printActions) System.out.printf("New action: %s discovered, reserving index %d for this action\n", name, actionMap.get(name));
        }
        //actionIndices.add(actionMap.get(name));
        return actionMap.get(name);
    }

}
