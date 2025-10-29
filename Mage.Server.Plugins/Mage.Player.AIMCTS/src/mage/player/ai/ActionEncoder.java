package mage.player.ai;

import mage.abilities.Ability;

import java.util.*;

import static mage.player.ai.ComputerPlayerMCTS.logger;

public class ActionEncoder {
    public static Map<String, Integer> playerActionMap = new HashMap<>();
    public static Map<String, Integer> opponentActionMap = new HashMap<>();
    public static Map<String, Integer> targetMap = new HashMap<>();

    public static synchronized int getActionIndex(Ability sa, boolean isPlayer) {
        String name = sa.toString();
        Map<String, Integer> map;
        if(isPlayer) {
            map = playerActionMap;
        } else {
            map = opponentActionMap;
        }
        if(map.containsKey(name)) {
            return map.get(name);
        } else {
            logger.warn("unrecognized action: " + name);
            return 0;
        }
    }
    public static synchronized int getTargetIndex(String name) {
        if(targetMap.containsKey(name)) {
            return targetMap.get(name);
        } else {
            logger.warn("unrecognized target: " + name);
            return 0;
        }
    }
}
