package org.mage.spellbench;

import mage.abilities.Ability;
import mage.abilities.ActivatedAbility;
import mage.abilities.Mode;
import mage.abilities.Modes;
import mage.abilities.common.PassAbility;
import mage.cards.Cards;
import mage.choices.Choice;
import mage.constants.Outcome;
import mage.constants.RangeOfInfluence;
import mage.game.Game;
import mage.game.combat.CombatGroup;
import mage.player.ai.ComputerPlayerPolicyOnly;
import mage.target.Target;
import mage.target.TargetAmount;
import mage.target.TargetCard;

import java.io.Serializable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * MageZero's policy player in a lockstep game, unchanged: this subclass only records each decision it finishes,
 * with the game as it saw it when deciding, for the opponent's relay ({@link ComputerPlayerSpellBench}) to give the
 * kernel. Decisions inside its own simulations are never recorded.
 */
public class ComputerPlayerPolicyOnlyLockstep extends ComputerPlayerPolicyOnly {

    private static final long serialVersionUID = 1L;

    /** One finished decision: what it was, and the game as this seat saw it then. */
    static final class Move {
        enum Kind { ACTION, PASS, TARGETS, USE, MODE, NUMBER, CHOICE, ATTACKERS, BLOCKERS }

        final Kind kind;
        final Views.View view;
        final Object value;

        Move(Kind kind, Views.View view, Object value) {
            this.kind = kind;
            this.view = view;
            this.value = value;
        }

        @Override
        public String toString() {
            return kind + " " + value;
        }
    }

    /** The decisions not yet given to the kernel, oldest first. */
    final transient Deque<Move> moves = new ArrayDeque<>();
    /** The game as this seat sees it, written as the kernel's observation; set by the relay when its game starts. */
    transient Function<Game, Views.View> view;
    /** A dialog's own nested calls (one overload calling another) are part of that dialog. */
    private transient int dialogDepth;
    /** Activations inside another one (its mana payment) are part of it. */
    private transient int actionDepth;

    public ComputerPlayerPolicyOnlyLockstep(String name, RangeOfInfluence range, int skill) {
        super(name, range, skill);
    }

    protected ComputerPlayerPolicyOnlyLockstep(final ComputerPlayerPolicyOnlyLockstep player) {
        super(player);
    }

    @Override
    public ComputerPlayerPolicyOnlyLockstep copy() {
        return new ComputerPlayerPolicyOnlyLockstep(this);
    }

    private interface Decision<T> {
        T decide();
    }

    private boolean live(Game game) {
        return view != null && !game.isSimulation();
    }

    /** A dialog, recorded once answered with the view from before it; its nested calls are not recorded again. */
    private <T> T dialog(Game game, Move.Kind kind, Decision<T> decision, Function<T, Object> value) {
        if (!live(game) || dialogDepth > 0) {
            return decision.decide();
        }
        Views.View before = view.apply(game);
        dialogDepth++;
        T result;
        try {
            result = decision.decide();
        } finally {
            dialogDepth--;
        }
        moves.add(new Move(kind, before, value.apply(result)));
        return result;
    }

    // ------------------------------------------------------------------ priority

    @Override
    public boolean activateAbility(ActivatedAbility ability, Game game) {
        // recorded before it runs: the dialogs it asks (targets, modes, X, costs) are recorded after it, in order
        if (live(game) && !(ability instanceof PassAbility) && actionDepth == 0 && dialogDepth == 0) {
            moves.add(new Move(Move.Kind.ACTION, view.apply(game), ability));
        }
        actionDepth++;
        try {
            return super.activateAbility(ability, game);
        } finally {
            actionDepth--;
        }
    }

    @Override
    public void pass(Game game) {
        if (live(game)) {
            moves.add(new Move(Move.Kind.PASS, view.apply(game), null));
        }
        super.pass(game);
    }

    // ------------------------------------------------------------------ dialogs

    @Override
    public boolean chooseTarget(Outcome outcome, Target target, Ability source, Game game) {
        return dialog(game, Move.Kind.TARGETS, () -> super.chooseTarget(outcome, target, source, game),
                r -> new ArrayList<>(target.getTargets()));
    }

    @Override
    public boolean chooseTarget(Outcome outcome, Cards cards, TargetCard target, Ability source, Game game) {
        return dialog(game, Move.Kind.TARGETS, () -> super.chooseTarget(outcome, cards, target, source, game),
                r -> new ArrayList<>(target.getTargets()));
    }

    @Override
    public boolean choose(Outcome outcome, Target target, Ability source, Game game, Map<String, Serializable> options) {
        return dialog(game, Move.Kind.TARGETS, () -> super.choose(outcome, target, source, game, options),
                r -> new ArrayList<>(target.getTargets()));
    }

    @Override
    public boolean choose(Outcome outcome, Cards cards, TargetCard target, Ability source, Game game) {
        return dialog(game, Move.Kind.TARGETS, () -> super.choose(outcome, cards, target, source, game),
                r -> new ArrayList<>(target.getTargets()));
    }

    @Override
    public boolean chooseTargetAmount(Outcome outcome, TargetAmount target, Ability source, Game game) {
        return dialog(game, Move.Kind.TARGETS, () -> super.chooseTargetAmount(outcome, target, source, game),
                r -> new ArrayList<>(target.getTargets()));
    }

    @Override
    public boolean chooseUse(Outcome outcome, String message, String secondMessage, String trueText, String falseText,
                             Ability source, Game game) {
        return dialog(game, Move.Kind.USE,
                () -> super.chooseUse(outcome, message, secondMessage, trueText, falseText, source, game), r -> r);
    }

    @Override
    public Mode chooseMode(Modes modes, Ability source, Game game) {
        return dialog(game, Move.Kind.MODE, () -> super.chooseMode(modes, source, game),
                m -> m == null ? null : new ArrayList<>(modes.keySet()).indexOf(m.getId()));
    }

    @Override
    public int announceX(int min, int max, String message, Game game, Ability source, boolean isManaPay) {
        return dialog(game, Move.Kind.NUMBER, () -> super.announceX(min, max, message, game, source, isManaPay), x -> x);
    }

    @Override
    public int getAmount(int min, int max, String message, Ability source, Game game) {
        return dialog(game, Move.Kind.NUMBER, () -> super.getAmount(min, max, message, source, game), x -> x);
    }

    @Override
    public boolean choose(Outcome outcome, Choice choice, Game game) {
        return dialog(game, Move.Kind.CHOICE, () -> super.choose(outcome, choice, game),
                r -> choice.isKeyChoice() ? choice.getChoiceKey() : choice.getChoice());
    }

    // ------------------------------------------------------------------ combat

    @Override
    public void selectAttackers(Game game, UUID attackingPlayerId) {
        dialog(game, Move.Kind.ATTACKERS, () -> {
            super.selectAttackers(game, attackingPlayerId);
            return null;
        }, r -> declared(game, true));
    }

    @Override
    public void selectBlockers(Ability source, Game game, UUID defendingPlayerId) {
        dialog(game, Move.Kind.BLOCKERS, () -> {
            super.selectBlockers(source, game, defendingPlayerId);
            return null;
        }, r -> declared(game, false));
    }

    /** Each declared attacker with its defender, or each declared blocker with the attackers it blocks. */
    private static Map<UUID, List<UUID>> declared(Game game, boolean attackers) {
        Map<UUID, List<UUID>> out = new LinkedHashMap<>();
        for (CombatGroup group : game.getCombat().getGroups()) {
            for (UUID creature : attackers ? group.getAttackers() : group.getBlockers()) {
                out.computeIfAbsent(creature, k -> new ArrayList<>())
                        .addAll(attackers ? List.of(group.getDefenderId()) : group.getAttackers());
            }
        }
        return out;
    }
}
