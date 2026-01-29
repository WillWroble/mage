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
        List<Map<String, Integer>> maps = new ArrayList<>(); maps.add(playerActionMap); maps.add(opponentActionMap);
        for(Map<String, Integer> map : maps) {
            /*map.put("Pass", 0);
            map.put("{T}: Add {B}.", 1);
            map.put("{T}: Add {G}.", 2);
            map.put("{T}: Add {R}.", 3);
            map.put("{T}: Add {U}.", 4);
            map.put("{T}: Add {W}.", 5);
            map.put("{T}: Add {C}.", 6);*/
        }

        targetMap = new HashMap<>();
        //targetMap.put("Stop Choosing", 0);

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
            //logger.warn("unrecognized action: " + name);
            int offset = abs(name.hashCode()) % (128-map.size());
            return map.size()+offset;
        }
    }
    public int getTargetIndex(String name) {
        if(targetMap.containsKey(name)) {
            return targetMap.get(name);
        } else {
            //logger.warn("unrecognized target: " + name);
            int offset = abs(name.hashCode()) % (128-targetMap.size());
            return targetMap.size()+offset;
        }
    }
}
