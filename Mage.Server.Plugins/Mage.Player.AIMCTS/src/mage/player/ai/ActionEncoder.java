package mage.player.ai;

import mage.abilities.Ability;

import java.util.*;

import static java.lang.Math.abs;
import static mage.player.ai.ComputerPlayerMCTS.logger;

public class ActionEncoder {
    public  Map<String, Integer> playerActionMap = new HashMap<>();
    public  Map<String, Integer> opponentActionMap = new HashMap<>();
    public  Map<String, Integer> targetMap = new HashMap<>();

    public ActionEncoder() {
        playerActionMap = new HashMap<>();
        opponentActionMap = new HashMap<>();
        targetMap = new HashMap<>();
    }

    public int getActionIndex(Ability sa, boolean isPlayer) {
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
            int offset = abs(name.hashCode()) % (128-map.size());
            return map.size()+offset;
        }
    }
    public int getTargetIndex(String name) {
        if(targetMap.containsKey(name)) {
            return targetMap.get(name);
        } else {
            logger.warn("unrecognized target: " + name);
            int offset = abs(name.hashCode()) % (128-targetMap.size());
            return targetMap.size()+offset;
        }
    }
}
