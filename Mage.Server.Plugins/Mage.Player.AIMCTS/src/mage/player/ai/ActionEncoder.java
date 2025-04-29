package mage.player.ai;

import mage.abilities.Ability;
import mage.abilities.SpellAbility;
import mage.interfaces.Action;
import sun.security.util.ArrayUtil;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

public class ActionEncoder {
    public static int indexCount = 0;
    public static boolean printActions = false;
    public static List<boolean[]> actionVectors = new ArrayList<>();
    public static Map<Integer, String> reverseActionMap = new HashMap<>();
    //public static boolean[] actionVector = new boolean[1000];
    public static Map<String, Integer> actionMap = new HashMap<>();
    public static int addAction(Ability sa) {
        String name = sa.toString();
        boolean[] actionVector = new boolean[128]; //inits with false's
        if(actionMap.containsKey(name)) {//already contains action
            if(printActions) System.out.printf("Action: %s already maps to index %d\n", name, actionMap.get(name));
        } else {
            actionMap.put(name, indexCount++);
            if(printActions) System.out.printf("New action: %s discovered, reserving index %d for this action\n", name, actionMap.get(name));
        }
        actionVector[actionMap.get(name)] = true;
        actionVectors.add(Arrays.copyOf(actionVector, 128));
        //System.out.println(Arrays.toString(actionVector));
        return actionMap.get(name);
    }
    public static void makeInverseMap() {
        Map<String ,Integer> original = actionMap;
        Map<Integer, String> inverse = new HashMap<>();
        for (Map.Entry<String, Integer> entry : original.entrySet()) {
            inverse.put(entry.getValue(), entry.getKey());
        }
        reverseActionMap = inverse;
    }
}
