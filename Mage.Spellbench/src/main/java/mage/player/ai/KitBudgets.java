package mage.player.ai;

import mage.abilities.Ability;
import mage.abilities.Mode;
import mage.target.Target;
import mage.game.Game;

import java.util.UUID;

/**
 * Kit code (not vendored): the option-generation estimate behind the budget of design Section 5.4 item 2b and the
 * addendum's change 2. {@code PlayerImpl.getPlayableOptions} enumerates every mode subset, every target combination
 * and every cost target before the search polls anything, so an ability is checked before it is enumerated: the
 * estimate multiplies, per target requirement, the number of target combinations its possible targets allow, over
 * the mode subsets of a modal ability, and over the cost targets. Saturating arithmetic; an ability whose estimate
 * exceeds the budget is skipped and recorded {@code options_capped}.
 */
public final class KitBudgets {

    private static final long SATURATE = Long.MAX_VALUE / 4;

    private KitBudgets() {
    }

    public static long estimateOptions(Ability ability, UUID playerId, Game game) {
        try {
            long est = 1;
            if (ability.isModal()) {
                long modesTotal = 0;
                for (Mode mode : ability.getModes().values()) {
                    long perMode = 1;
                    for (Target t : mode.getTargets()) {
                        perMode = mul(perMode, combos(t, playerId, ability, game));
                    }
                    modesTotal = add(modesTotal, perMode);
                }
                int n = ability.getModes().size();
                int max = Math.max(1, Math.min(ability.getModes().getMaxModes(game, ability), n));
                // subsets of up to max modes, each with its own targets: bounded by (sum of per-mode options)^max
                long subsets = 1;
                for (int i = 0; i < max; i++) {
                    subsets = mul(subsets, Math.max(1, modesTotal));
                }
                est = mul(est, subsets);
            } else {
                for (Target t : ability.getTargets()) {
                    est = mul(est, combos(t, playerId, ability, game));
                }
            }
            for (Target t : ability.getCosts().getTargets()) {
                est = mul(est, Math.max(1, t.possibleTargets(playerId, ability, game).size()));
            }
            if (!ability.getManaCosts().getVariableCosts().isEmpty()) {
                est = mul(est, 1 + game.getBattlefield().getAllActivePermanents(playerId).size());
            }
            return est;
        } catch (RuntimeException e) {
            return 1; // an estimate XMage cannot compute here leaves the ability to the enumeration-size check
        }
    }

    /** Ways to fill one target requirement: sum over k = min..max of C(n, k). */
    static long combos(Target t, UUID playerId, Ability ability, Game game) {
        int n = t.possibleTargets(playerId, ability, game).size();
        int min = Math.max(0, t.getMinNumberOfTargets());
        int max = Math.min(n, Math.max(min, t.getMaxNumberOfTargets()));
        long total = 0;
        for (int k = min; k <= max; k++) {
            total = add(total, choose(n, k));
        }
        return Math.max(1, total);
    }

    static long choose(int n, int k) {
        long r = 1;
        for (int i = 1; i <= k; i++) {
            r = mul(r, n - k + i) / i;
            if (r >= SATURATE) {
                return SATURATE;
            }
        }
        return r;
    }

    static long mul(long a, long b) {
        if (a == 0 || b == 0) {
            return 0;
        }
        if (a >= SATURATE / b) {
            return SATURATE;
        }
        return a * b;
    }

    static long add(long a, long b) {
        long r = a + b;
        return r >= SATURATE || r < 0 ? SATURATE : r;
    }
}
