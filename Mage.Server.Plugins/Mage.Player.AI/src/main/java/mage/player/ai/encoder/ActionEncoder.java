package mage.player.ai.encoder;

import mage.abilities.Ability;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static java.lang.Math.abs;

public class ActionEncoder {
    public enum ActionType {
        PRIORITY, CHOOSE_NUM, BLANK, CHOOSE_TARGET, MAKE_CHOICE, CHOOSE_USE
    }
    public  Map<String, Integer> playerActionMap = new HashMap<>();
    public  Map<String, Integer> opponentActionMap = new HashMap<>();
    public  Map<String, Integer> targetMap = new HashMap<>();

    public ActionEncoder() {
        List<Map<String, Integer>> maps = new ArrayList<>(); maps.add(playerActionMap); maps.add(opponentActionMap);
        for(Map<String, Integer> map : maps) {
            map.put("Pass", 0);
            map.put("{T}: Add {B}.", 1);
            map.put("{T}: Add {G}.", 2);
            map.put("{T}: Add {R}.", 3);
            map.put("{T}: Add {U}.", 4);
            map.put("{T}: Add {W}.", 5);
            map.put("{T}: Add {C}.", 6);
        }

        targetMap.put("Stop Choosing", 0);
        targetMap.put("PlayerA", 1);
        targetMap.put("PlayerB", 2);

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
            return (abs(name.hashCode()) % 127)+1; //pass is fully reserved
        }
    }
    public int getTargetIndex(String name) {
        if(targetMap.containsKey(name)) {
            return targetMap.get(name);
        } else {
            return (abs(name.hashCode()) % 127)+1; //stop choosing fully reserved
        }
    }
}
