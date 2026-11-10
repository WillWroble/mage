package org.mage.spellbench;

import mage.abilities.Ability;
import mage.abilities.ActivatedAbility;
import mage.abilities.Mode;
import mage.abilities.Modes;
import mage.abilities.TriggeredAbility;
import mage.abilities.common.PassAbility;
import mage.cards.Cards;
import mage.choices.Choice;
import mage.constants.MultiAmountType;
import mage.constants.Outcome;
import mage.constants.PhaseStep;
import mage.constants.RangeOfInfluence;
import mage.game.Game;
import mage.game.combat.CombatGroup;
import mage.player.ai.ComputerPlayerPolicyOnly;
import mage.target.Target;
import mage.target.TargetAmount;
import mage.target.TargetCard;
import mage.util.MultiAmountMessage;

import java.io.Serializable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
        enum Kind { ACTION, PASS, TARGETS, USE, MODE, NUMBER, CHOICE, ATTACKERS, BLOCKERS, TRIGGER }

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

    /**
     * A yes/no answer, with the text of the option it names and of the yes option, when the dialog names its options (a
     * choice of costs, Winding Way's "Creature" or "Land").
     */
    static final class Use {
        final boolean value;
        final String option;
        final String trueText;

        Use(boolean value, String option, String trueText) {
            this.value = value;
            this.option = option;
            this.trueText = trueText;
        }

        @Override
        public String toString() {
            return value + (option == null ? "" : " (" + option + ")");
        }
    }

    /** The decisions not yet given to the kernel, oldest first. */
    final transient Deque<Move> moves = new ArrayDeque<>();
    /** The game as this seat sees it, written as the kernel's observation; set by the relay when its game starts. */
    transient Function<Game, Views.View> view;
    /** This combat's blockers in the kernel's order, which divides combat damage; set by the relay. */
    transient List<UUID> blockOrder;
    /** A dialog's own nested calls (one overload calling another) are part of that dialog. */
    private transient int dialogDepth;
    /** Activations inside another one (its mana payment) are part of it. */
    private transient int actionDepth;
    /**
     * Where this player last acted at priority: its turn and step, and what it put on the stack (else the stack's size
     * after the action). The round it acted in lasts until the step changes or something resolves.
     */
    private transient int actedTurn;
    private transient PhaseStep actedStep;
    private transient UUID actedObject;
    private transient int actedStack;
    /** Its last action used the stack, and this player has not had priority since. */
    private transient boolean actedOnStack;

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

    /** The steps in which mtg-kernel opens priority: the lockstep game's only priority windows. */
    private static final Set<PhaseStep> WINDOWS = EnumSet.of(PhaseStep.PRECOMBAT_MAIN, PhaseStep.DECLARE_ATTACKERS,
            PhaseStep.DECLARE_BLOCKERS, PhaseStep.POSTCOMBAT_MAIN);

    /**
     * The lockstep game's rules, the kernel's priority surface (mtg-kernel {@code surface.rs}, after the reference
     * harness), where this player passes without deciding (taking the last-priority snapshot as every player does):
     * no priority outside the kernel's windows; in declare attackers and declare blockers, one action per round (until
     * the step changes or something resolves); and no priority right after its own action that uses the stack.
     */
    @Override
    public boolean priority(Game game) {
        if (live(game) && (!WINDOWS.contains(game.getTurnStepType()) || passes(game))) {
            game.setLastPriority(playerId);
            pass(game);
            return false;
        }
        return super.priority(game);
    }

    private boolean passes(Game game) {
        PhaseStep step = game.getTurnStepType();
        boolean round = actedStep == step && actedTurn == game.getTurnNum() && (actedObject != null
                ? game.getStack().getStackObject(actedObject) != null : game.getStack().size() >= actedStack);
        boolean onStack = actedOnStack;
        actedOnStack = false;
        return round && (step == PhaseStep.DECLARE_ATTACKERS || step == PhaseStep.DECLARE_BLOCKERS || onStack);
    }

    @Override
    public boolean activateAbility(ActivatedAbility ability, Game game) {
        // recorded before it runs: the dialogs it asks (targets, modes, X, costs) are recorded after it, in order
        boolean action = live(game) && !(ability instanceof PassAbility) && actionDepth == 0 && dialogDepth == 0;
        if (action) {
            moves.add(new Move(Move.Kind.ACTION, view.apply(game), ability));
        }
        actionDepth++;
        try {
            boolean done = super.activateAbility(ability, game);
            if (action && done) {
                actedTurn = game.getTurnNum();
                actedStep = game.getTurnStepType();
                actedStack = game.getStack().size();
                actedOnStack = ability.isUsesStack();
                actedObject = actedOnStack && !game.getStack().isEmpty() ? game.getStack().getFirst().getId() : null;
            }
            return done;
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
        return selection(game, target, source, null, () -> super.chooseTarget(outcome, target, source, game));
    }

    @Override
    public boolean chooseTarget(Outcome outcome, Cards cards, TargetCard target, Ability source, Game game) {
        return selection(game, target, source, cards, () -> super.chooseTarget(outcome, cards, target, source, game));
    }

    @Override
    public boolean choose(Outcome outcome, Target target, Ability source, Game game, Map<String, Serializable> options) {
        return selection(game, target, source, null, () -> super.choose(outcome, target, source, game, options));
    }

    @Override
    public boolean choose(Outcome outcome, Cards cards, TargetCard target, Ability source, Game game) {
        return selection(game, target, source, cards, () -> super.choose(outcome, cards, target, source, game));
    }

    @Override
    public boolean chooseTargetAmount(Outcome outcome, TargetAmount target, Ability source, Game game) {
        return selection(game, target, source, null, () -> super.chooseTargetAmount(outcome, target, source, game));
    }

    /** A selection; a forced one (a single possible pick and no stopping) is no decision and is not recorded. */
    private boolean selection(Game game, Target target, Ability source, Cards cards, Decision<Boolean> decision) {
        UUID controller = target.getAffectedAbilityControllerId(getId());
        long possible = target.possibleTargets(controller, source, game, cards).stream()
                .filter(id -> !target.contains(id)).count();
        if (possible + (target.isChosen(game) ? 1 : 0) <= 1) {
            dialogDepth++;
            try {
                return decision.decide();
            } finally {
                dialogDepth--;
            }
        }
        return dialog(game, Move.Kind.TARGETS, decision, r -> new ArrayList<>(target.getTargets()));
    }

    @Override
    public boolean chooseUse(Outcome outcome, String message, String secondMessage, String trueText, String falseText,
                             Ability source, Game game) {
        return dialog(game, Move.Kind.USE,
                () -> super.chooseUse(outcome, message, secondMessage, trueText, falseText, source, game),
                r -> new Use(r, r ? trueText : falseText, trueText));
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

    @Override
    public TriggeredAbility chooseTriggeredAbility(List<TriggeredAbility> abilities, Game game) {
        return dialog(game, Move.Kind.TRIGGER, () -> super.chooseTriggeredAbility(abilities, game), a -> a);
    }

    // ------------------------------------------------------------------ combat

    /** The lockstep game's rules: combat damage among blockers is divided as the kernel divides it (no decision). */
    @Override
    public List<Integer> getMultiAmountWithIndividualConstraints(Outcome outcome, List<MultiAmountMessage> messages,
                                                                 int totalMin, int totalMax, MultiAmountType type,
                                                                 Game game) {
        List<Integer> amounts = live(game) ? ComputerPlayerSpellBench.engineOrder(game, playerId, blockOrder,
                messages.size(), totalMax) : null;
        return amounts != null ? amounts : super.getMultiAmountWithIndividualConstraints(outcome, messages, totalMin,
                totalMax, type, game);
    }

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
