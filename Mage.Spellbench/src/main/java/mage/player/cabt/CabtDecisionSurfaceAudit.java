package mage.player.cabt;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static mage.player.cabt.CabtDecisionSurfaceSource.ARENA_LOG_COMPARISON;
import static mage.player.cabt.CabtDecisionSurfaceSource.CLIENT_CALLBACK;
import static mage.player.cabt.CabtDecisionSurfaceSource.PLAYABLE_OBJECTS;
import static mage.player.cabt.CabtDecisionSurfaceSource.PLAYER_INTERFACE;
import static mage.player.cabt.CabtDecisionSurfaceStatus.DELEGATED;
import static mage.player.cabt.CabtDecisionSurfaceStatus.FAIL_CLOSED;
import static mage.player.cabt.CabtDecisionSurfaceStatus.REFERENCE_ONLY;
import static mage.player.cabt.CabtDecisionSurfaceStatus.SURFACED;

/**
 * Audit of XMage's decision surfaces, derived from the engine's actual prompt
 * APIs: the mage.players.Player callback interface, the GameSessionPlayer
 * client callback methods, and the priority playable-object query APIs.
 * <p>
 * XMage does not expose one complete legal-decision enum; the adapter's
 * decision space is the union of these surfaces. getPlayable and friends
 * cover only priority playable actions — target, mode, mana, combat,
 * replacement, and trigger decisions arrive through their own Player
 * callbacks. See docs/cabt-decision-surface.md.
 * <p>
 * Every SURFACED/DELEGATED/FAIL_CLOSED entry names its implementation class
 * and its test class; {@link CabtPromptAudit} resolves both on the classpath,
 * so adding a new prompt surface means adding an audit entry, an
 * implementation, and tests — and a surfaced prompt without test coverage
 * fails the suite.
 * <p>
 * Deliberately no UNKNOWN_PLAYABLE fallback anywhere: a decision surface the
 * bridge does not recognize must fail the audit and its tests, not be
 * silently bucketed.
 */
public final class CabtDecisionSurfaceAudit {

    private static final String PKG = "mage.player.cabt.";

    private static final List<CabtDecisionSurface> ENTRIES = buildEntries();

    private CabtDecisionSurfaceAudit() {
    }

    public static List<CabtDecisionSurface> entries() {
        return ENTRIES;
    }

    private static List<CabtDecisionSurface> buildEntries() {
        List<CabtDecisionSurface> list = new ArrayList<CabtDecisionSurface>();

        // --- Source 1: Player interface prompt callbacks ---

        list.add(new CabtDecisionSurface(
                "priority(Game)",
                PLAYER_INTERFACE, SURFACED,
                PKG + "CabtBridgePlayer",
                PKG + "CabtBridgePlayerPriorityTest",
                "Player.priority(Game); CabtBridgePlayer.priority(Game)",
                "PRIORITY prompt: PASS_PRIORITY plus one option per playable ability from "
                        + "Player.getPlayable(Game, true); dispatched through PlayerImpl.activateAbility.",
                "CabtBridgePlayerPriorityTest / CabtPriorityPromptBuilderTest / real-engine smoke test."));

        list.add(new CabtDecisionSurface(
                "choose(Outcome, Target, Ability, Game)",
                PLAYER_INTERFACE, SURFACED,
                PKG + "CabtTargetPromptBuilder",
                PKG + "CabtBridgePlayerTargetPromptTest",
                "Player.choose(Outcome, Target, Ability, Game); CabtBridgePlayer.choose(...)",
                "Non-targeted TARGET prompt via CabtTargetPromptBuilder from Target.possibleTargets.",
                "CabtBridgePlayerTargetPromptTest."));
        list.add(new CabtDecisionSurface(
                "choose(Outcome, Target, Ability, Game, Map<String, Serializable>)",
                PLAYER_INTERFACE, SURFACED,
                PKG + "CabtBridgePlayer",
                PKG + "CabtBridgePlayerTargetPromptTest",
                "Player.choose(Outcome, Target, Ability, Game, Map); HumanPlayer passes the map to the client event only",
                "Delegates to the surfaced four-argument choose: the options map carries UI hints, not decision space.",
                "CabtBridgePlayerTargetPromptTest map-overload case."));
        list.add(new CabtDecisionSurface(
                "choose(Outcome, Cards, TargetCard, Ability, Game)",
                PLAYER_INTERFACE, SURFACED,
                PKG + "CabtTargetPromptBuilder",
                PKG + "CabtTargetPromptBuilderTest",
                "Player.choose(Outcome, Cards, TargetCard, Ability, Game); CabtBridgePlayer.choose(...)",
                "TARGET prompt over the given Cards set; selections applied via TargetCard.add.",
                "CabtTargetPromptBuilderTest / CabtTargetSelectionApplierTest card cases."));

        list.add(new CabtDecisionSurface(
                "chooseTarget(Outcome, Target, Ability, Game)",
                PLAYER_INTERFACE, SURFACED,
                PKG + "CabtTargetPromptBuilder",
                PKG + "CabtBridgePlayerTargetPromptTest",
                "Player.chooseTarget(...); CabtBridgePlayer.chooseTarget(...)",
                "TARGET prompt from Target.possibleTargets, min/max, current selected targets, required flag.",
                "CabtBridgePlayerTargetPromptTest.targetedSpellPromptsForTarget."));
        list.add(new CabtDecisionSurface(
                "chooseTarget(Outcome, Cards, TargetCard, Ability, Game)",
                PLAYER_INTERFACE, SURFACED,
                PKG + "CabtTargetPromptBuilder",
                PKG + "CabtTargetPromptBuilderTest",
                "Player.chooseTarget(Outcome, Cards, TargetCard, Ability, Game); CabtBridgePlayer.chooseTarget(...)",
                "TARGET prompt over the given Cards set instead of game-wide possible targets.",
                "CabtTargetPromptBuilderTest / CabtTargetSelectionApplierTest card cases."));
        list.add(new CabtDecisionSurface(
                "chooseTargetAmount(Outcome, TargetAmount, Ability, Game)",
                PLAYER_INTERFACE, FAIL_CLOSED,
                PKG + "CabtBridgePlayer",
                PKG + "CabtPromptAuditTest",
                "Player.chooseTargetAmount(Outcome, TargetAmount, Ability, Game)",
                "Bridge override throws CabtUnhandledDecisionException instead of letting ComputerPlayer decide; "
                        + "surfacing needs a target prompt plus per-target amount distribution payload.",
                "CabtPromptAuditTest.failClosedPromptsThrowCabtUnhandledDecisionException."));

        list.add(new CabtDecisionSurface(
                "chooseMulligan(Game)",
                PLAYER_INTERFACE, SURFACED,
                PKG + "CabtMulliganPromptBuilder",
                PKG + "CabtBridgePlayerMulliganTest",
                "Player.chooseMulligan(Game); CabtBridgePlayer.chooseMulligan(...)",
                "MULLIGAN prompt: Keep/Mulligan; true takes the mulligan (HumanPlayer convention).",
                "CabtMulliganPromptTest / CabtBridgePlayerMulliganTest."));

        list.add(new CabtDecisionSurface(
                "chooseUse(Outcome, String, Ability, Game)",
                PLAYER_INTERFACE, SURFACED,
                PKG + "CabtYesNoPromptBuilder",
                PKG + "CabtYesNoPromptTest",
                "Player.chooseUse(Outcome, String, Ability, Game); CabtBridgePlayer.chooseUse(...)",
                "YES_NO prompt via CabtYesNoPromptBuilder; YES option returns true.",
                "CabtYesNoPromptTest."));
        list.add(new CabtDecisionSurface(
                "chooseUse(Outcome, String, String, String, String, Ability, Game)",
                PLAYER_INTERFACE, SURFACED,
                PKG + "CabtYesNoPromptBuilder",
                PKG + "CabtYesNoPromptTest",
                "Player.chooseUse(...); CabtBridgePlayer.chooseUse(...)",
                "YES_NO prompt; trueText/falseText become the option labels when present.",
                "CabtYesNoPromptTest custom-label case."));

        list.add(new CabtDecisionSurface(
                "choose(Outcome, Choice, Game)",
                PLAYER_INTERFACE, SURFACED,
                PKG + "CabtChoicePromptBuilder",
                PKG + "CabtChoicePromptTest",
                "Player.choose(Outcome, Choice, Game); CabtBridgePlayer.choose(...)",
                "CHOICE prompt from Choice.getChoices()/getKeyChoices(); applied via setChoice/setChoiceByKey.",
                "CabtChoicePromptTest."));
        list.add(new CabtDecisionSurface(
                "choosePile(Outcome, String, List<? extends Card>, List<? extends Card>, Game)",
                PLAYER_INTERFACE, SURFACED,
                PKG + "CabtPilePromptBuilder",
                PKG + "CabtPilePromptTest",
                "Player.choosePile(...); CabtBridgePlayer.choosePile(...)",
                "PILE prompt with two options carrying pile contents; pile 1 returns true (HumanPlayer convention).",
                "CabtPilePromptTest."));

        list.add(new CabtDecisionSurface(
                "playMana(Ability, ManaCost, String, Game)",
                PLAYER_INTERFACE, SURFACED,
                PKG + "CabtManaPromptBuilder",
                PKG + "CabtBridgePlayerManaPromptTest",
                "Player.playMana(...); CabtBridgePlayer.playMana(...); PlayerImpl.getAvailableManaProducers/getUseableManaAbilities/playManaAbility",
                "PAY_MANA prompt per payment step: usable mana abilities + pool mana + cancel; applied through the engine's own activation path.",
                "CabtManaPromptBuilderTest / CabtBridgePlayerManaPromptTest."));

        list.add(new CabtDecisionSurface(
                "announceX(int, int, String, Game, Ability, boolean)",
                PLAYER_INTERFACE, SURFACED,
                PKG + "CabtNumberPromptBuilder",
                PKG + "CabtNumberPromptTest",
                "Player.announceX(...); CabtBridgePlayer.announceX(...)",
                "NUMBER prompt with one option per legal X value in [min, max]; enumeration cap fails closed.",
                "CabtNumberPromptTest.announceXReturnsSelectedValue."));
        list.add(new CabtDecisionSurface(
                "getAmount(int, int, String, Ability, Game)",
                PLAYER_INTERFACE, SURFACED,
                PKG + "CabtNumberPromptBuilder",
                PKG + "CabtNumberPromptTest",
                "Player.getAmount(...); CabtBridgePlayer.getAmount(...)",
                "NUMBER prompt with one option per legal value in [min, max]; enumeration cap fails closed.",
                "CabtNumberPromptTest.getAmountReturnsSelectedValue."));
        list.add(new CabtDecisionSurface(
                "getMultiAmount(Outcome, List<String>, int, int, int, MultiAmountType, Game)",
                PLAYER_INTERFACE, SURFACED,
                PKG + "CabtMultiAmountPromptBuilder",
                PKG + "CabtMultiAmountPromptTest",
                "Player.getMultiAmount(...) (default delegates to getMultiAmountWithIndividualConstraints)",
                "Covered by the overridden getMultiAmountWithIndividualConstraints primitive.",
                "CabtMultiAmountPromptTest."));
        list.add(new CabtDecisionSurface(
                "getMultiAmountWithIndividualConstraints(Outcome, List<MultiAmountMessage>, int, int, MultiAmountType, Game)",
                PLAYER_INTERFACE, SURFACED,
                PKG + "CabtMultiAmountPromptBuilder",
                PKG + "CabtMultiAmountPromptTest",
                "Player.getMultiAmountWithIndividualConstraints(...); CabtBridgePlayer.getMultiAmountWithIndividualConstraints(...)",
                "MULTI_AMOUNT prompt enumerating valid assignments for small totals; larger spaces fail closed.",
                "CabtMultiAmountPromptTest."));

        list.add(new CabtDecisionSurface(
                "chooseReplacementEffect(Map<String, String>, Map<String, MageObject>, Game)",
                PLAYER_INTERFACE, SURFACED,
                PKG + "CabtReplacementEffectPromptBuilder",
                PKG + "CabtReplacementEffectPromptTest",
                "Player.chooseReplacementEffect(Map, Map, Game); CabtBridgePlayer.chooseReplacementEffect(...)",
                "REPLACEMENT_EFFECT prompt preserving effectsMap order; returns the chosen entry's original index.",
                "CabtReplacementEffectPromptTest / CabtBridgePlayerReplacementEffectTest."));
        list.add(new CabtDecisionSurface(
                "chooseTriggeredAbility(List<TriggeredAbility>, Game)",
                PLAYER_INTERFACE, SURFACED,
                PKG + "CabtTriggeredAbilityPromptBuilder",
                PKG + "CabtTriggeredAbilityPromptBuilderTest",
                "Player.chooseTriggeredAbility(...); GameImpl.checkTriggered(); CabtBridgePlayer.chooseTriggeredAbility(...)",
                "TRIGGER_ORDER prompt, one option per waiting trigger; the selected TriggeredAbility is returned.",
                "CabtTriggeredAbilityPromptBuilderTest / CabtBridgePlayerTriggeredAbilityTest."));
        list.add(new CabtDecisionSurface(
                "chooseMode(Modes, Ability, Game)",
                PLAYER_INTERFACE, SURFACED,
                PKG + "CabtModePromptBuilder",
                PKG + "CabtModePromptBuilderTest",
                "Player.chooseMode(Modes, Ability, Game); CabtBridgePlayer.chooseMode(...)",
                "MODE prompt from Modes.getAvailableModes with HumanPlayer-style filtering; selected Mode returned.",
                "CabtModePromptBuilderTest / CabtBridgePlayerModePromptTest."));

        list.add(new CabtDecisionSurface(
                "selectAttackers(Game, UUID)",
                PLAYER_INTERFACE, SURFACED,
                PKG + "CabtAttackersPromptBuilder",
                PKG + "CabtBridgePlayerAttackersTest",
                "Player.selectAttackers(Game, UUID); CabtBridgePlayer.selectAttackers(...)",
                "DECLARE_ATTACKERS prompt of attacker/defender pairs; committed via declareAttacker(..., false).",
                "CabtAttackersPromptBuilderTest / CabtBridgePlayerAttackersTest."));
        list.add(new CabtDecisionSurface(
                "selectBlockers(Ability, Game, UUID)",
                PLAYER_INTERFACE, SURFACED,
                PKG + "CabtBlockersPromptBuilder",
                PKG + "CabtBridgePlayerBlockersTest",
                "Player.selectBlockers(Ability, Game, UUID); CabtBridgePlayer.selectBlockers(...)",
                "DECLARE_BLOCKERS prompt of blocker/attacker pairs; committed via declareBlocker(...).",
                "CabtBlockersPromptBuilderTest / CabtBridgePlayerBlockersTest."));

        list.add(new CabtDecisionSurface(
                "declareAttacker(UUID, UUID, Game, boolean)",
                PLAYER_INTERFACE, DELEGATED,
                PKG + "CabtAttackersSelectionApplier",
                PKG + "CabtAttackersSelectionApplierTest",
                "Player.declareAttacker(UUID, UUID, Game, boolean)",
                "Commit API invoked from selectAttackers; the decision itself is the selectAttackers prompt.",
                "Exercised indirectly by the selectAttackers prompt test."));
        list.add(new CabtDecisionSurface(
                "declareBlocker(UUID, UUID, UUID, Game)",
                PLAYER_INTERFACE, DELEGATED,
                PKG + "CabtBlockersSelectionApplier",
                PKG + "CabtBlockersSelectionApplierTest",
                "Player.declareBlocker(UUID, UUID, UUID, Game)",
                "Commit API invoked from selectBlockers; the decision itself is the selectBlockers prompt.",
                "Exercised indirectly by the selectBlockers prompt test."));
        list.add(new CabtDecisionSurface(
                "declareBlocker(UUID, UUID, UUID, Game, boolean)",
                PLAYER_INTERFACE, DELEGATED,
                PKG + "CabtBlockersSelectionApplier",
                PKG + "CabtBlockersSelectionApplierTest",
                "Player.declareBlocker(UUID, UUID, UUID, Game, boolean)",
                "Commit API with undo flag; same as the four-argument declareBlocker.",
                "Exercised indirectly by the selectBlockers prompt test."));

        list.add(new CabtDecisionSurface(
                "getAvailableAttackers(Game)",
                PLAYER_INTERFACE, REFERENCE_ONLY,
                "", "",
                "Player.getAvailableAttackers(Game)",
                "Query used to enumerate legal attackers when building the selectAttackers prompt.",
                "Covered by the selectAttackers prompt test."));
        list.add(new CabtDecisionSurface(
                "getAvailableAttackers(UUID, Game)",
                PLAYER_INTERFACE, REFERENCE_ONLY,
                "", "",
                "Player.getAvailableAttackers(UUID, Game)",
                "Per-defender variant of getAvailableAttackers for multiplayer combat prompts.",
                "Covered by the selectAttackers prompt test."));
        list.add(new CabtDecisionSurface(
                "getAvailableBlockers(Game)",
                PLAYER_INTERFACE, REFERENCE_ONLY,
                "", "",
                "Player.getAvailableBlockers(Game)",
                "Query used to enumerate legal blockers when building the selectBlockers prompt.",
                "Covered by the selectBlockers prompt test."));

        // --- Source 3: priority playable-object query APIs ---
        // These enumerate playable actions while holding priority; they are
        // NOT the full action space (targets, modes, mana, combat, triggers,
        // and replacements arrive via the Player callbacks above).

        list.add(new CabtDecisionSurface(
                "getPlayable(Game, boolean)",
                PLAYABLE_OBJECTS, SURFACED,
                PKG + "CabtPriorityPromptBuilder",
                PKG + "CabtPriorityPromptBuilderTest",
                "Player.getPlayable(Game, boolean); CabtBridgePlayer.priority(Game)",
                "The current root-priority implementation: one PLAY_LAND/CAST_SPELL/"
                        + "ACTIVATE_ABILITY/SPECIAL_ACTION option per returned ability. Future "
                        + "refinement, not final form: compare with getPlayableOptions/"
                        + "getPlayableObjects for alternate-cost and casting-option payloads.",
                "CabtPriorityPromptBuilderTest / CabtBridgePlayerPriorityTest."));
        list.add(new CabtDecisionSurface(
                "getPlayableOptions(Ability, Game)",
                PLAYABLE_OBJECTS, REFERENCE_ONLY,
                "", "",
                "Player.getPlayableOptions(Ability, Game)",
                "Expansion of one playable ability into concrete casting options; the bridge instead "
                        + "surfaces those choices through the downstream prompts (targets, modes, X, mana).",
                "Downstream prompt tests cover the expanded choices."));
        list.add(new CabtDecisionSurface(
                "getPlayableObjects(Game, Zone)",
                PLAYABLE_OBJECTS, REFERENCE_ONLY,
                "", "",
                "Player.getPlayableObjects(Game, Zone); GameSessionPlayer.prepareGameView(...)",
                "Per-object aggregation of getPlayable used by the UI; the priority prompt uses "
                        + "getPlayable(Game, boolean) directly, one option per ability.",
                "Covered by the priority prompt tests."));
        list.add(new CabtDecisionSurface(
                "getPlayableActivatedAbilities(MageObject, Zone, Game)",
                PLAYABLE_OBJECTS, REFERENCE_ONLY,
                "", "",
                "Player.getPlayableActivatedAbilities(MageObject, Zone, Game)",
                "Per-object filter of getPlayable used by HumanPlayer's click-a-card flow; the "
                        + "priority prompt enumerates the whole action space instead.",
                "Covered by the priority prompt tests."));

        // --- Source 2: GameSessionPlayer client/UI callback surface ---
        // The existing XMage path for showing prompts (and UI-visible priority
        // playable objects) to a human client; mirrors the Player callbacks.

        list.add(new CabtDecisionSurface(
                "GameSessionPlayer.choosePile",
                CLIENT_CALLBACK, REFERENCE_ONLY,
                "", "",
                "GameSessionPlayer.choosePile(String, CardsView, CardsView)",
                "Client mirror of Player.choosePile; reference for pile prompt payload shape.",
                "Covered by the choosePile prompt test when implemented."));
        list.add(new CabtDecisionSurface(
                "GameSessionPlayer.chooseChoice",
                CLIENT_CALLBACK, REFERENCE_ONLY,
                "", "",
                "GameSessionPlayer.chooseChoice(Choice)",
                "Client mirror of Player.choose(Outcome, Choice, Game); reference for choice payload shape.",
                "Covered by the choice prompt test when implemented."));
        list.add(new CabtDecisionSurface(
                "GameSessionPlayer.playMana",
                CLIENT_CALLBACK, REFERENCE_ONLY,
                "", "",
                "GameSessionPlayer.playMana(String, Map<String, Serializable>)",
                "Client mirror of Player.playMana; reference for mana prompt payload shape.",
                "Covered by the playMana prompt test when implemented."));
        list.add(new CabtDecisionSurface(
                "GameSessionPlayer.playXMana",
                CLIENT_CALLBACK, REFERENCE_ONLY,
                "", "",
                "GameSessionPlayer.playXMana(String)",
                "Client mirror of X announcement/payment; reference for X prompt payload shape.",
                "Covered by the announceX prompt test when implemented."));
        list.add(new CabtDecisionSurface(
                "GameSessionPlayer.getAmount",
                CLIENT_CALLBACK, REFERENCE_ONLY,
                "", "",
                "GameSessionPlayer.getAmount(String, int, int)",
                "Client mirror of Player.getAmount; reference for amount prompt payload shape.",
                "Covered by the getAmount prompt test when implemented."));
        list.add(new CabtDecisionSurface(
                "GameSessionPlayer.getMultiAmount",
                CLIENT_CALLBACK, REFERENCE_ONLY,
                "", "",
                "GameSessionPlayer.getMultiAmount(List<MultiAmountMessage>, int, int, ...)",
                "Client mirror of Player.getMultiAmount; reference for distribution prompt payload shape.",
                "Covered by the getMultiAmount prompt test when implemented."));
        list.add(new CabtDecisionSurface(
                "GameSessionPlayer.prepareGameView.canPlayObjects",
                CLIENT_CALLBACK, REFERENCE_ONLY,
                "", "",
                "GameSessionPlayer.prepareGameView(...): gameView.setCanPlayObjects(priorityPlayer.getPlayableObjects(sourceGame, Zone.ALL))",
                "The existing XMage path for UI-visible priority playable objects; reference for priority option payloads.",
                "Covered by the priority prompt tests."));

        // --- Arena / replay comparison note ---

        list.add(new CabtDecisionSurface(
                "Arena/17Lands replay comparison",
                ARENA_LOG_COMPARISON, REFERENCE_ONLY,
                "", "",
                "docs/cabt-decision-surface.md",
                "Arena-style logs are state/prompt/response based, not a small fixed action enum. "
                        + "17Lands replay rows are useful telemetry, but they do not enumerate legal decisions. "
                        + "The adapter therefore models decisions on XMage prompt callbacks and option builders, "
                        + "not on a static strategic action list.",
                "Doc-content test asserts the telemetry-not-enumerator statement."));

        return Collections.unmodifiableList(list);
    }
}
