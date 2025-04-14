package mage.player.ai;

import mage.abilities.Ability;
import mage.abilities.SpellAbility;
import mage.interfaces.Action;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

public class ActionEncoder {
    public static int indexCount = 0;
    //public static Boolean[] actionVector = new Boolean[1000];
    public static Map<String, Integer> actionMap = new HashMap<>();
    public static boolean[] addAction(Ability sa) {
        String name = sa.toString();
        boolean[] actionVector = new boolean[1000]; //inits with false's
        if(actionMap.containsKey(name)) {//already contains action
            System.out.printf("Action: %s already maps to index %d\n", name, actionMap.get(name));
        } else {
            actionMap.put(name, indexCount++);
            System.out.printf("New action: %s discovered, reserving index %d for this action\n", name, actionMap.get(name));
        }
        actionVector[actionMap.get(name)] = true;
        //System.out.println(Arrays.toString(actionVector));
        return actionVector;
    }
}
