package mage.player.cabt;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * CABT bridge: lifecycle record of one decision — the prompt was built
 * (PENDING), the controller answered (SELECTED), and the answer was applied
 * to the engine (APPLIED). Two non-success ends exist and mean different
 * things: FAILED (an error while selecting, validating, or applying — the
 * bridge or controller misbehaved) and REJECTED (the selection reached the
 * engine but the engine declined or cancelled the action — e.g.
 * activateAbility returned false; the response was still consumed and the
 * engine re-offers the decision). Stages advance strictly
 * PENDING → SELECTED → APPLIED, with FAILED reachable from PENDING and
 * SELECTED and REJECTED reachable from SELECTED.
 * <p>
 * {@code sequenceNumber} orders traces across one recorder (one per live
 * bridge player), so trace N pairs with observation N recorded by the
 * controller and with {@link CabtDatasetRecord} transitions. {@code method}
 * names the XMage callback that raised the prompt (e.g. PRIORITY,
 * CHOOSE_TARGET, ANNOUNCE_X); {@code playerId} is the deciding player; the
 * full prompt — options, min/max, payloads — stays reachable through
 * {@code decision}, and {@link #getSelectedOptions()} resolves the answered
 * indices to their options.
 */
public final class CabtDecisionTrace {

    public enum Stage {
        PENDING,
        SELECTED,
        APPLIED,
        /**
         * The selection was dispatched but the engine declined or cancelled
         * the action; the decision response was still consumed.
         */
        REJECTED,
        FAILED
    }

    private final String traceId;
    private final long sequenceNumber;
    private final String method;
    private final MagicSelectType selectType;
    private final UUID playerId;
    private final PendingDecision decision;
    private Stage stage;
    private Selection selection;
    private String error;

    CabtDecisionTrace(long sequenceNumber, String method, PendingDecision decision) {
        this.traceId = UUID.randomUUID().toString();
        this.sequenceNumber = sequenceNumber;
        this.method = method;
        this.selectType = decision.selectType();
        this.playerId = decision.playerId();
        this.decision = decision;
        this.stage = Stage.PENDING;
    }

    void markSelected(Selection selection) {
        if (stage != Stage.PENDING) {
            throw new IllegalStateException("trace " + traceId + " already " + stage);
        }
        this.stage = Stage.SELECTED;
        this.selection = selection;
    }

    void markApplied() {
        if (stage != Stage.SELECTED) {
            throw new IllegalStateException("trace " + traceId + " is " + stage + ", not SELECTED");
        }
        this.stage = Stage.APPLIED;
    }

    void markRejected() {
        if (stage != Stage.SELECTED) {
            throw new IllegalStateException("trace " + traceId + " is " + stage + ", not SELECTED");
        }
        this.stage = Stage.REJECTED;
    }

    void markFailed(String error) {
        if (stage == Stage.APPLIED || stage == Stage.REJECTED || stage == Stage.FAILED) {
            throw new IllegalStateException("trace " + traceId + " already " + stage);
        }
        this.stage = Stage.FAILED;
        this.error = error;
    }

    public String getTraceId() {
        return traceId;
    }

    public long getSequenceNumber() {
        return sequenceNumber;
    }

    public String getMethod() {
        return method;
    }

    public MagicSelectType getSelectType() {
        return selectType;
    }

    public UUID getPlayerId() {
        return playerId;
    }

    public PendingDecision getDecision() {
        return decision;
    }

    public Stage getStage() {
        return stage;
    }

    public Selection getSelection() {
        return selection;
    }

    /**
     * The error that moved this trace to FAILED, null otherwise.
     */
    public String getError() {
        return error;
    }

    /**
     * The options the recorded selection picked, in selection order — the
     * selected indices resolved against the decision's option list. Empty
     * until the trace reaches SELECTED.
     */
    public List<MagicOption> getSelectedOptions() {
        if (selection == null) {
            return Collections.emptyList();
        }
        List<MagicOption> selected = new ArrayList<MagicOption>();
        for (int index : selection.indices()) {
            selected.add(decision.options().get(index));
        }
        return Collections.unmodifiableList(selected);
    }
}
