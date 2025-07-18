package mage.player.ai;

import java.util.HashMap;

public class MCTSNextActionFactory {
    public static final HashMap<MCTSPlayer.NextAction, MCTSNodeNextAction> strategyMap = new HashMap<>();

    static {
        strategyMap.put(MCTSPlayer.NextAction.PRIORITY, new PriorityNextAction());
        strategyMap.put(MCTSPlayer.NextAction.SELECT_BLOCKERS, new SelectBlockersNextAction());
        strategyMap.put(MCTSPlayer.NextAction.SELECT_ATTACKERS, new SelectAttackersNextAction());
        strategyMap.put(MCTSPlayer.NextAction.CHOOSE_TARGET, new ChooseTargetNextAction());
    }

    public static MCTSNodeNextAction createNextAction(MCTSPlayer.NextAction nextAction) {
        MCTSNodeNextAction strategy = strategyMap.get(nextAction);
        if (strategy == null) {
            throw new IllegalArgumentException("Unsupported action: " + nextAction);
        }
        return strategy;
    }
}
