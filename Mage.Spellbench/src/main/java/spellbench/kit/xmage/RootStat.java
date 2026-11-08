package spellbench.kit.xmage;

import mage.abilities.Ability;

/**
 * One root alternative of a MAD search (design Section 5.5.1), captured inside the root {@code simulatePriority}
 * before the alpha comparison, in generation order. An option that was never evaluated has a reason instead of a
 * score.
 */
public final class RootStat {

    public final int index;
    public final Ability ability;
    /** finalScore as returned; null when not evaluated. */
    public Integer raw;
    /** after the passivity penalty of a root pass. */
    public Integer adjusted;
    public Integer alphaBefore;
    public Integer beta;
    /** "exact" or "at_most"; null when not evaluated. */
    public String bound;
    public boolean tie;
    /** Why it was not evaluated: static, activation_failed, repeated, cut_win, cut_lose, cut_alpha_beta, cut_nodes,
     * cut_interrupt, options_capped, budget_stop. Null when evaluated. */
    public String reason;
    public boolean best;
    /** Horizon encounters (design 5.6) inside this alternative's subtree (A1 result review, change 7). */
    public long horizonHits;

    public RootStat(int index, Ability ability) {
        this.index = index;
        this.ability = ability;
    }
}
