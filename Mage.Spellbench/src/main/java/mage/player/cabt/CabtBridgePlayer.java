package mage.player.cabt;

import mage.MageObject;
import mage.abilities.Ability;
import mage.abilities.Mode;
import mage.abilities.Modes;
import mage.abilities.TriggeredAbility;
import mage.abilities.costs.mana.ManaCost;
import mage.abilities.mana.ActivatedManaAbilityImpl;
import mage.cards.Card;
import mage.cards.Cards;
import mage.choices.Choice;
import mage.constants.MultiAmountType;
import mage.constants.Outcome;
import mage.constants.RangeOfInfluence;
import mage.constants.Zone;
import mage.game.Game;
import mage.game.permanent.Permanent;
import mage.player.ai.ComputerPlayer;
import mage.target.Target;
import mage.target.TargetAmount;
import mage.target.TargetCard;
import mage.util.MultiAmountMessage;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * CABT bridge: XMage player whose decisions are routed to a {@link CabtBridgeController}
 * as CABT-style option-index prompts instead of being made by the built-in AI.
 * <p>
 * Surfaced so far: priority (pass plus the engine-enumerated playable
 * actions: play land, cast spell, activate ability, special action), target
 * selection, yes/no (chooseUse), generic Choice, pile choice, mode selection,
 * numeric prompts (announceX/getAmount/multi-amount), triggered-ability
 * ordering, replacement-effect choice, mana payment, combat declarations, and
 * the pregame mulligan. Every surfaced decision is traced
 * PENDING → SELECTED → APPLIED through the shared
 * {@link CabtDecisionTraceRecorder} — REJECTED when the engine declines the
 * selected action, FAILED when selecting, validating, or applying throws.
 * Audited callbacks that are decisions but
 * are not surfaced yet fail closed with
 * {@link CabtUnhandledDecisionException}; Player callbacks outside the audit
 * still fall back to ComputerPlayer (see docs/cabt-decision-surface.md for
 * the audited surface and its enforcement).
 */
public final class CabtBridgePlayer extends ComputerPlayer {

    private final CabtBridgeController bridge;
    private final CabtDecisionTraceRecorder traceRecorder;
    private final CabtPriorityPromptBuilder priorityPromptBuilder = new CabtPriorityPromptBuilder();
    private final CabtPrioritySelectionApplier prioritySelectionApplier = new CabtPrioritySelectionApplier();
    private final CabtTargetPromptBuilder targetPromptBuilder = new CabtTargetPromptBuilder();
    private final CabtTargetSelectionApplier targetSelectionApplier = new CabtTargetSelectionApplier();
    private final CabtYesNoPromptBuilder yesNoPromptBuilder = new CabtYesNoPromptBuilder();
    private final CabtChoicePromptBuilder choicePromptBuilder = new CabtChoicePromptBuilder();
    private final CabtChoiceSelectionApplier choiceSelectionApplier = new CabtChoiceSelectionApplier();
    private final CabtPilePromptBuilder pilePromptBuilder = new CabtPilePromptBuilder();
    private final CabtModePromptBuilder modePromptBuilder = new CabtModePromptBuilder();
    private final CabtModeSelectionApplier modeSelectionApplier = new CabtModeSelectionApplier();
    private final CabtNumberPromptBuilder numberPromptBuilder = new CabtNumberPromptBuilder();
    private final CabtNumberSelectionApplier numberSelectionApplier = new CabtNumberSelectionApplier();
    private final CabtMultiAmountPromptBuilder multiAmountPromptBuilder = new CabtMultiAmountPromptBuilder();
    private final CabtMultiAmountSelectionApplier multiAmountSelectionApplier = new CabtMultiAmountSelectionApplier();
    private final CabtTriggeredAbilityPromptBuilder triggeredAbilityPromptBuilder = new CabtTriggeredAbilityPromptBuilder();
    private final CabtTriggeredAbilitySelectionApplier triggeredAbilitySelectionApplier = new CabtTriggeredAbilitySelectionApplier();
    private final CabtReplacementEffectPromptBuilder replacementEffectPromptBuilder = new CabtReplacementEffectPromptBuilder();
    private final CabtReplacementEffectSelectionApplier replacementEffectSelectionApplier = new CabtReplacementEffectSelectionApplier();
    private final CabtManaPromptBuilder manaPromptBuilder = new CabtManaPromptBuilder();
    private final CabtManaSelectionApplier manaSelectionApplier = new CabtManaSelectionApplier();
    private final CabtAttackersPromptBuilder attackersPromptBuilder = new CabtAttackersPromptBuilder();
    private final CabtAttackersSelectionApplier attackersSelectionApplier = new CabtAttackersSelectionApplier();
    private final CabtBlockersPromptBuilder blockersPromptBuilder = new CabtBlockersPromptBuilder();
    private final CabtBlockersSelectionApplier blockersSelectionApplier = new CabtBlockersSelectionApplier();
    private final CabtMulliganPromptBuilder mulliganPromptBuilder = new CabtMulliganPromptBuilder();
    private final CabtMulliganSelectionApplier mulliganSelectionApplier = new CabtMulliganSelectionApplier();

    public CabtBridgePlayer(String name, RangeOfInfluence range, CabtBridgeController bridge) {
        super(name, range);
        this.bridge = bridge;
        this.traceRecorder = new CabtDecisionTraceRecorder();
    }

    private CabtBridgePlayer(final CabtBridgePlayer player) {
        super(player);
        // deliberately shared, not copied: XMage's bookmark/rollback restores
        // player COPIES into the live game (GameState.copy/restore), so a copy
        // must keep the live controller and the live trace history to survive
        // a rollback. The hazard of sharing — a simulation copy consuming live
        // agent decisions — is closed in prompt(): simulation/playable-check
        // games fail closed instead of reaching the bridge.
        this.bridge = player.bridge;
        this.traceRecorder = player.traceRecorder;
    }

    @Override
    public CabtBridgePlayer copy() {
        return new CabtBridgePlayer(this);
    }

    public CabtDecisionTraceRecorder getTraceRecorder() {
        return traceRecorder;
    }

    @Override
    public boolean priority(Game game) {
        // guard the whole priority path, not just the prompt: getPlayable
        // below must never run for a simulation/playable-check game either
        assertLiveDecisionGame("PRIORITY", game);
        passed = false;
        CabtPriorityPrompt priorityPrompt = priorityPromptBuilder.build(
                this, game, getPlayable(game, true));
        TracedSelection traced = prompt("PRIORITY", priorityPrompt.getDecision(), game);
        CabtPrioritySelectionApplier.Result result;
        try {
            result = prioritySelectionApplier.apply(this, game, traced.selection, priorityPrompt);
        } catch (RuntimeException e) {
            traceRecorder.recordFailed(traced.trace, e);
            throw e;
        }
        if (result == CabtPrioritySelectionApplier.Result.REJECTED) {
            // the engine declined/cancelled the activation: the response is
            // consumed (the engine re-offers priority, like after a cancelled
            // HumanPlayer cast), but the trace must not claim it was applied
            traceRecorder.recordRejected(traced.trace);
            return true;
        }
        traceRecorder.recordApplied(traced.trace);
        return result == CabtPrioritySelectionApplier.Result.APPLIED;
    }

    // --- target selection ---

    @Override
    public boolean chooseTarget(Outcome outcome, Target target, Ability source, Game game) {
        PendingDecision decision = targetPromptBuilder.buildTargetPrompt(this, game, outcome, target, source);
        return resolveTargetDecision("CHOOSE_TARGET", decision, target, source, game);
    }

    @Override
    public boolean choose(Outcome outcome, Target target, Ability source, Game game) {
        PendingDecision decision = targetPromptBuilder.buildTargetPrompt(this, game, outcome, target, source);
        return resolveTargetDecision("CHOOSE", decision, target, source, game);
    }

    @Override
    public boolean choose(Outcome outcome, Target target, Ability source, Game game,
                          Map<String, Serializable> options) {
        // the options map carries UI hints only (HumanPlayer passes it to the
        // client event, never into the decision space) — same TARGET prompt
        return choose(outcome, target, source, game);
    }

    @Override
    public boolean chooseTargetAmount(Outcome outcome, TargetAmount target, Ability source, Game game) {
        // FAIL_CLOSED surface: no option builder distributes amounts across
        // targets yet, and ComputerPlayer would silently AI-decide it
        throw new CabtUnhandledDecisionException(
                "chooseTargetAmount(Outcome, TargetAmount, Ability, Game) is not surfaced yet; failing closed");
    }

    @Override
    public boolean chooseTarget(Outcome outcome, Cards cards, TargetCard target, Ability source, Game game) {
        PendingDecision decision = targetPromptBuilder.buildTargetCardPrompt(this, game, outcome, cards, target, source);
        return resolveTargetCardDecision("CHOOSE_TARGET", decision, target, game);
    }

    @Override
    public boolean choose(Outcome outcome, Cards cards, TargetCard target, Ability source, Game game) {
        PendingDecision decision = targetPromptBuilder.buildTargetCardPrompt(this, game, outcome, cards, target, source);
        return resolveTargetCardDecision("CHOOSE", decision, target, game);
    }

    private boolean resolveTargetDecision(String method, PendingDecision decision,
                                          Target target, Ability source, Game game) {
        if (decision.options().isEmpty()) {
            // nothing selectable: the target stays unchosen, XMage handles it
            return false;
        }
        TracedSelection traced = prompt(method, decision, game);
        return applyTraced(traced, () -> targetSelectionApplier.applyToTarget(
                target, source, game, traced.selection, decision));
    }

    private boolean resolveTargetCardDecision(String method, PendingDecision decision,
                                              TargetCard target, Game game) {
        if (decision.options().isEmpty()) {
            return false;
        }
        TracedSelection traced = prompt(method, decision, game);
        return applyTraced(traced, () -> targetSelectionApplier.applyToTargetCard(
                target, game, traced.selection, decision));
    }

    // --- yes/no (chooseUse) ---

    @Override
    public boolean chooseUse(Outcome outcome, String message, Ability source, Game game) {
        PendingDecision decision = yesNoPromptBuilder.build(this, game, outcome, message, source);
        return resolveYesNo(decision, game);
    }

    @Override
    public boolean chooseUse(Outcome outcome, String message, String secondMessage,
                             String trueText, String falseText, Ability source, Game game) {
        PendingDecision decision = yesNoPromptBuilder.build(
                this, game, outcome, message, secondMessage, trueText, falseText, source);
        return resolveYesNo(decision, game);
    }

    private boolean resolveYesNo(PendingDecision decision, Game game) {
        TracedSelection traced = prompt("CHOOSE_USE", decision, game);
        return applyTraced(traced, () -> {
            MagicOption selected = decision.options().get(traced.selection.indices().get(0));
            return selected.type() == MagicOptionType.PROMPT_YES;
        });
    }

    // --- generic Choice ---

    @Override
    public boolean choose(Outcome outcome, Choice choice, Game game) {
        PendingDecision decision = choicePromptBuilder.build(this, choice);
        if (decision.options().isEmpty()) {
            return false;
        }
        TracedSelection traced = prompt("CHOOSE_CHOICE", decision, game);
        return applyTraced(traced, () -> choiceSelectionApplier.apply(choice, traced.selection, decision));
    }

    // --- pile choice ---

    @Override
    public boolean choosePile(Outcome outcome, String message,
                              List<? extends Card> pile1, List<? extends Card> pile2, Game game) {
        PendingDecision decision = pilePromptBuilder.build(this, message, pile1, pile2);
        TracedSelection traced = prompt("CHOOSE_PILE", decision, game);
        return applyTraced(traced, () -> {
            MagicOption selected = decision.options().get(traced.selection.indices().get(0));
            // true = pile 1, matching HumanPlayer.choosePile's boolean convention
            return Integer.valueOf(1).equals(selected.payload().get("pileIndex"));
        });
    }

    // --- mode selection ---

    @Override
    public Mode chooseMode(Modes modes, Ability source, Game game) {
        if (modes.size() == 1) {
            // same single-mode shortcut as HumanPlayer.chooseMode
            return modes.getMode();
        }
        PendingDecision decision = modePromptBuilder.build(this, game, modes, source);
        if (decision.options().isEmpty()) {
            return null;
        }
        TracedSelection traced = prompt("CHOOSE_MODE", decision, game);
        return applyTraced(traced, () -> modeSelectionApplier.apply(
                modes, source, game, traced.selection, decision));
    }

    // --- numeric prompts ---

    @Override
    public int announceX(int min, int max, String message, Game game, Ability source, boolean isManaPay) {
        return resolveNumber("ANNOUNCE_X", min, max, message, game);
    }

    @Override
    public int getAmount(int min, int max, String message, Ability source, Game game) {
        return resolveNumber("GET_AMOUNT", min, max, message, game);
    }

    private int resolveNumber(String method, int min, int max, String message, Game game) {
        PendingDecision decision = numberPromptBuilder.build(this, min, max, message);
        TracedSelection traced = prompt(method, decision, game);
        return applyTraced(traced, () -> numberSelectionApplier.apply(traced.selection, decision));
    }

    @Override
    public List<Integer> getMultiAmountWithIndividualConstraints(
            Outcome outcome, List<MultiAmountMessage> messages,
            int totalMin, int totalMax, MultiAmountType type, Game game) {
        if (messages == null || messages.isEmpty()) {
            return new ArrayList<Integer>();
        }
        PendingDecision decision = multiAmountPromptBuilder.build(this, messages, totalMin, totalMax);
        TracedSelection traced = prompt("GET_MULTI_AMOUNT", decision, game);
        return applyTraced(traced, () -> multiAmountSelectionApplier.apply(traced.selection, decision));
    }

    // --- triggered-ability ordering ---

    @Override
    public TriggeredAbility chooseTriggeredAbility(List<TriggeredAbility> abilities, Game game) {
        if (abilities == null || abilities.isEmpty()) {
            return null;
        }
        if (abilities.size() == 1) {
            // only one order possible: no decision to surface
            return abilities.get(0);
        }
        PendingDecision decision = triggeredAbilityPromptBuilder.build(this, game, abilities);
        TracedSelection traced = prompt("CHOOSE_TRIGGERED_ABILITY", decision, game);
        return applyTraced(traced, () -> triggeredAbilitySelectionApplier.apply(
                abilities, traced.selection, decision));
    }

    // --- replacement-effect choice ---

    @Override
    public int chooseReplacementEffect(Map<String, String> effectsMap,
                                       Map<String, MageObject> objectsMap, Game game) {
        if (effectsMap == null || effectsMap.size() <= 1) {
            // same single-effect shortcut as HumanPlayer.chooseReplacementEffect
            return 0;
        }
        PendingDecision decision = replacementEffectPromptBuilder.build(this, effectsMap, objectsMap);
        TracedSelection traced = prompt("CHOOSE_REPLACEMENT_EFFECT", decision, game);
        return applyTraced(traced, () -> replacementEffectSelectionApplier.apply(
                traced.selection, decision));
    }

    // --- mana payment ---

    @Override
    public boolean playMana(Ability abilityToCast, ManaCost unpaid, String promptText, Game game) {
        PendingDecision decision = manaPromptBuilder.build(this, game, abilityToCast, unpaid, promptText);
        TracedSelection traced = prompt("PLAY_MANA", decision, game);
        return applyTraced(traced, () -> manaSelectionApplier.apply(
                this, game, traced.selection, decision));
    }

    // package-private bridges to the inherited player's own mana path, so the
    // mana builder/applier (same package) can reuse them:
    // - PlayerImpl.getAvailableManaProducers(Game) [protected]
    // - PlayerImpl.getUseableManaAbilities(MageObject, Zone, Game) [protected]
    // - PlayerImpl.playManaAbility(ActivatedManaAbilityImpl, Game) [protected]

    List<MageObject> cabtManaProducers(Game game) {
        return getAvailableManaProducers(game);
    }

    Map<UUID, ActivatedManaAbilityImpl> cabtManaAbilities(MageObject object, Game game) {
        Zone zone = object instanceof Permanent ? Zone.BATTLEFIELD : Zone.HAND;
        return getUseableManaAbilities(object, zone, game);
    }

    boolean cabtActivateManaAbility(ActivatedManaAbilityImpl manaAbility, Game game) {
        // activate a copy, like PlayerImpl's own mana-ability handling
        return playManaAbility((ActivatedManaAbilityImpl) manaAbility.copy(), game);
    }

    // --- combat declarations ---

    @Override
    public void selectAttackers(Game game, UUID attackingPlayerId) {
        PendingDecision decision = attackersPromptBuilder.build(this, game, attackingPlayerId);
        if (decision.options().isEmpty()) {
            // no legal attacks: nothing to declare
            return;
        }
        TracedSelection traced = prompt("SELECT_ATTACKERS", decision, game);
        applyTracedVoid(traced, () -> attackersSelectionApplier.apply(
                this, game, traced.selection, decision));
    }

    @Override
    public void selectBlockers(Ability source, Game game, UUID defendingPlayerId) {
        PendingDecision decision = blockersPromptBuilder.build(this, game, defendingPlayerId);
        if (decision.options().isEmpty()) {
            return;
        }
        TracedSelection traced = prompt("SELECT_BLOCKERS", decision, game);
        applyTracedVoid(traced, () -> blockersSelectionApplier.apply(
                this, game, traced.selection, decision));
    }

    // --- pregame mulligan ---

    @Override
    public boolean chooseMulligan(Game game) {
        PendingDecision decision = mulliganPromptBuilder.build(this, game);
        TracedSelection traced = prompt("CHOOSE_MULLIGAN", decision, game);
        return applyTraced(traced, () -> mulliganSelectionApplier.apply(traced.selection, decision));
    }

    // --- shared prompt plumbing ---

    private void assertLiveDecisionGame(String method, Game game) {
        if (game != null && (game.isSimulation() || game.inCheckPlayableState())) {
            // a simulation or playable-calc game reached a bridge decision:
            // the controller only answers decisions of the live game, so this
            // must fail closed instead of silently consuming (or inventing)
            // an agent decision. HumanPlayer answers silent defaults here;
            // doing the same is a future task and must be explicit, not
            // inherited.
            throw new CabtUnhandledDecisionException(
                    method + " was invoked from a simulation/playable-check game; "
                            + "the CABT bridge only answers live-game decisions");
        }
    }

    private TracedSelection prompt(String method, PendingDecision decision, Game game) {
        assertLiveDecisionGame(method, game);
        CabtDecisionTrace trace = traceRecorder.recordPending(method, decision);
        try {
            Selection selection = bridge.requestSelection(game, this, decision);
            SelectionValidator.validate(decision, selection);
            traceRecorder.recordSelected(trace, selection);
            return new TracedSelection(trace, selection);
        } catch (RuntimeException e) {
            traceRecorder.recordFailed(trace, e);
            throw e;
        }
    }

    /**
     * Runs the apply phase of a prompted decision under the trace lifecycle:
     * APPLIED on success, FAILED (with the error, rethrown) when the applier
     * or the engine dispatch under it throws — so an apply-phase error never
     * strands the trace at SELECTED.
     */
    private <T> T applyTraced(TracedSelection traced, Supplier<T> applyPhase) {
        T result;
        try {
            result = applyPhase.get();
        } catch (RuntimeException e) {
            traceRecorder.recordFailed(traced.trace, e);
            throw e;
        }
        traceRecorder.recordApplied(traced.trace);
        return result;
    }

    private void applyTracedVoid(TracedSelection traced, Runnable applyPhase) {
        applyTraced(traced, () -> {
            applyPhase.run();
            return null;
        });
    }

    private static final class TracedSelection {
        private final CabtDecisionTrace trace;
        private final Selection selection;

        private TracedSelection(CabtDecisionTrace trace, Selection selection) {
            this.trace = trace;
            this.selection = selection;
        }
    }
}
