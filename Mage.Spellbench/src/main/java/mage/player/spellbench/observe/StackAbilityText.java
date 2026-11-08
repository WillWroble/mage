package mage.player.spellbench.observe;

import mage.abilities.Ability;

/** Public rules text of an ability, without resolving names, targets or any game state. */
public final class StackAbilityText {
    private StackAbilityText() { }

    public static String of(Ability ability) {
        return ability == null ? null : ObservationBuilder.nfc(ability.getRule());
    }
}
