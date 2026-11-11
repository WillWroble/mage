package org.mage.spellbench;

import mage.Mana;
import mage.abilities.Ability;
import mage.constants.Zone;
import mage.game.permanent.Permanent;
import mage.abilities.mana.ActivatedManaAbilityImpl;
import mage.abilities.costs.mana.ManaCost;
import mage.abilities.mana.ManaAbility;
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
import java.util.function.Consumer;
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
        enum Kind { ACTION, PASS, TARGETS, USE, MODE, NUMBER, CHOICE, COST, ATTACKERS, BLOCKERS, TRIGGER }

        final Kind kind;
        final Views.View view;
        final Object value;
        /** An action's: the mana XMage paid it with, each a permanent and its color (W, U, B, R, G, C; null: any). */
        final List<Paid> paid = new ArrayList<>();

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

    /** A permanent's mana paid toward MageZero's action. */
    static final class Paid {
        final UUID source;
        final String color;

        Paid(UUID source, String color) {
            this.source = source;
            this.color = color;
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
    /** MageZero's action XMage is carrying out (its mana is recorded with it). */
    private transient Move acting;
    /** The mana left to pay while XMage pays for MageZero. */
    private transient ManaCost paying;
    /** Told when XMage shuffles this player's library (the lockstep meets the kernel's shuffle). */
    transient Consumer<Game> shuffled;
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
        if (!live(game) || dialogDepth > 0 || payManaMode || isPayingMana) {
            return decision.decide(); // inside another dialog, or while paying mana (the kernel pays itself)
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
        try {
            return super.priority(game);
        } catch (IllegalStateException e) {
            if (!live(game)) {
                throw e;
            }
            // an action XMage's AI could not complete (its payer could not make mana it counted on): XMage rolled it
            // back and its move is dropped; MageZero passes instead of ending the game, as the generator would
            pass(game);
            return false;
        }
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
        // recorded before it runs: the dialogs it asks (targets, modes, X, costs) are recorded after it, in order;
        // mana made while paying a cost (an unless-payment) is the payment's, which the kernel makes itself
        boolean action = live(game) && !(ability instanceof PassAbility) && actionDepth == 0 && dialogDepth == 0
                && !payManaMode && !isPayingMana;
        if (paying != null && ability instanceof ManaAbility && !ability.getManaCosts().isEmpty() && !filterNeeded(game)) {
            return false;
        }
        int before = moves.size();
        Move outer = acting;
        if (action) {
            acting = new Move(Move.Kind.ACTION, view.apply(game), ability);
            moves.add(acting);
        } else if (acting != null && ability instanceof ManaAbility) {
            // the mana its action is paid with, by color when the ability makes one (a Bridge has one per color)
            List<Mana> made = ((ManaAbility) ability).getNetMana(game);
            Mana mana = made.size() == 1 ? made.get(0) : new Mana();
            String color = mana.getWhite() > 0 ? "W" : mana.getBlue() > 0 ? "U" : mana.getBlack() > 0 ? "B"
                    : mana.getRed() > 0 ? "R" : mana.getGreen() > 0 ? "G" : mana.getColorless() > 0 ? "C" : null;
            acting.paid.add(new Paid(ability.getSourceId(), color));
        }
        actionDepth++;
        try {
            boolean done = super.activateAbility(ability, game);
            while (action && !done && moves.size() > before) {
                moves.pollLast(); // a failed action (XMage's AI could not pay) is rolled back, its dialogs with it
            }
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
            acting = outer;
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
        // a key choice while casting is the cost (an alternative cost keyed from 1): XMage asks it before the targets,
        // the kernel after them, or not at all
        Move.Kind kind = actionDepth > 0 && choice.isKeyChoice() ? Move.Kind.COST : Move.Kind.CHOICE;
        return dialog(game, kind, () -> super.choose(outcome, choice, game),
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
                messages, totalMax) : null;
        return amounts != null ? amounts : super.getMultiAmountWithIndividualConstraints(outcome, messages, totalMin,
                totalMax, type, game);
    }

    /**
     * An unless-payment MageZero agreed to but cannot complete taps nothing (Spell Pierce's {2} with one Island): XMage's
     * AI taps what it has and fails; the kernel offers a payment only when it can be made. Not MageZero's own actions,
     * which XMage found playable (its estimate of the mana available misses filters and chosen colors).
     */
    @Override
    public boolean playMana(Ability ability, ManaCost unpaid, String promptText, Game game) {
        if (live(game) && actionDepth == 0 && !getManaAvailable(game).enough(unpaid.getMana())) {
            return false;
        }
        paying = unpaid;
        try {
            return super.playMana(ability, unpaid, promptText, game);
        } finally {
            paying = null;
        }
    }

    /**
     * While paying, a mana ability that costs mana itself (Heap Gate's filter) only for a color the free sources cannot
     * make: XMage's AI pays generic mana with it, spends a second Gate on its cost and comes up short.
     */
    private boolean filterNeeded(Game game) {
        Mana need = paying.getMana();
        Mana free = new Mana();
        for (Permanent permanent : game.getBattlefield().getAllActivePermanents(playerId)) {
            for (ActivatedManaAbilityImpl a : permanent.getAbilities().getAvailableActivatedManaAbilities(Zone.BATTLEFIELD, playerId, game)) {
                if (a.getManaCosts().isEmpty()) {
                    a.getNetMana(game).forEach(free::add);
                }
            }
        }
        return need.getWhite() > 0 && free.getWhite() == 0 || need.getBlue() > 0 && free.getBlue() == 0
                || need.getBlack() > 0 && free.getBlack() == 0 || need.getRed() > 0 && free.getRed() == 0
                || need.getGreen() > 0 && free.getGreen() == 0;
    }

    @Override
    public void shuffleLibrary(Ability source, Game game) {
        super.shuffleLibrary(source, game);
        if (shuffled != null) {
            shuffled.accept(game);
        }
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
