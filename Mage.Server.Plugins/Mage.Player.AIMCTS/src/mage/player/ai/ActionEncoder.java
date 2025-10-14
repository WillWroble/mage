package mage.player.ai;

import mage.abilities.Ability;

import java.util.*;

import static mage.player.ai.ComputerPlayerMCTS.logger;

public class ActionEncoder {
    public static int indexCount = 0;
    public static int microIndexCount = 0;
    public static boolean printActions = false;
    public static Map<String, Integer> actionMap = new HashMap<>();
    public static Map<String, Integer> microActionMap = new HashMap<>();

    public static synchronized int getAction(Ability sa) {
        String name = sa.toString();
        if(actionMap.containsKey(name)) {//already contains action
            if(printActions) logger.info(String.format("Action: %s already maps to index %d\n", name, actionMap.get(name)));
        } else {
            actionMap.put(name, indexCount++);
            logger.warn(String.format("New action: %s discovered, reserving index %d for this action\n", name, actionMap.get(name)));
        }
        return actionMap.get(name);
    }
    public static synchronized int getMicroAction(String name) {
        if(microActionMap.containsKey(name)) {//already contains action
            if(printActions) logger.info(String.format("Micro Action: %s already maps to index %d\n", name, microActionMap.get(name)));
        } else {
            microActionMap.put(name, microIndexCount++);
            logger.warn(String.format("New micro action: %s discovered, reserving index %d for this action\n", name, microActionMap.get(name)));
        }
        return microActionMap.get(name);
    }
}
