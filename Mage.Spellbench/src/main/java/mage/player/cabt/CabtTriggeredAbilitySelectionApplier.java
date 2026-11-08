package mage.player.cabt;

import mage.abilities.TriggeredAbility;

import java.util.List;

/**
 * CABT bridge: resolves a validated TRIGGER_ORDER selection back to the
 * TriggeredAbility object XMage expects — option index i is abilities.get(i).
 */
public final class CabtTriggeredAbilitySelectionApplier {

    public TriggeredAbility apply(List<TriggeredAbility> abilities,
                                  Selection selection, PendingDecision decision) {
        int index = selection.indices().get(0);
        TriggeredAbility selected = abilities.get(index);
        Object abilityId = decision.options().get(index).payload().get("abilityId");
        if (abilityId != null && !abilityId.equals(selected.getId().toString())) {
            throw new IllegalStateException(
                    "trigger option " + index + " no longer matches the ability list");
        }
        return selected;
    }
}
