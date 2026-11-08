package mage.player.cabt;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * CABT bridge: records the PENDING → SELECTED → APPLIED (or REJECTED/FAILED)
 * lifecycle of every decision the bridge surfaces, numbering traces
 * sequentially so a game can be debugged and replayed decision by decision.
 * Trace N pairs with the N-th observation captured by a recording controller;
 * the transition dataset ({@link CabtDatasetRecord}) is built from that
 * pairing.
 */
public final class CabtDecisionTraceRecorder {

    private final List<CabtDecisionTrace> traces = new ArrayList<CabtDecisionTrace>();
    private final Map<String, CabtDecisionTrace> tracesById = new HashMap<String, CabtDecisionTrace>();
    private long nextSequenceNumber;

    public CabtDecisionTrace recordPending(String method, PendingDecision decision) {
        if (decision == null) {
            throw new IllegalArgumentException("cannot trace a null decision");
        }
        CabtDecisionTrace trace = new CabtDecisionTrace(nextSequenceNumber++, method, decision);
        traces.add(trace);
        tracesById.put(trace.getTraceId(), trace);
        return trace;
    }

    public void recordSelected(CabtDecisionTrace trace, Selection selection) {
        require(trace).markSelected(selection);
    }

    public void recordApplied(CabtDecisionTrace trace) {
        require(trace).markApplied();
    }

    public void recordRejected(CabtDecisionTrace trace) {
        require(trace).markRejected();
    }

    public void recordFailed(CabtDecisionTrace trace, Throwable error) {
        require(trace).markFailed(errorText(error));
    }

    public void recordSelected(String traceId, Selection selection) {
        find(traceId).markSelected(selection);
    }

    public void recordApplied(String traceId) {
        find(traceId).markApplied();
    }

    public void recordFailed(String traceId, Throwable error) {
        find(traceId).markFailed(errorText(error));
    }

    public List<CabtDecisionTrace> getTraces() {
        return Collections.unmodifiableList(new ArrayList<CabtDecisionTrace>(traces));
    }

    public CabtDecisionTrace getLastTrace() {
        return traces.isEmpty() ? null : traces.get(traces.size() - 1);
    }

    private static String errorText(Throwable error) {
        return error == null ? "unknown error"
                : error.getClass().getSimpleName() + ": " + error.getMessage();
    }

    private CabtDecisionTrace require(CabtDecisionTrace trace) {
        if (trace == null) {
            throw new IllegalArgumentException("cannot record on a null trace");
        }
        return trace;
    }

    private CabtDecisionTrace find(String traceId) {
        CabtDecisionTrace trace = tracesById.get(traceId);
        if (trace == null) {
            throw new IllegalArgumentException("unknown trace id: " + traceId);
        }
        return trace;
    }
}
