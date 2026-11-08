package mage.player.spellbench.decide;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * A seat's decision state, shared by every copy of its {@link SeatPlayer}. XMage copies players into simulations
 * and restores player copies into the live game when it rolls back a failed action (GameState copy and restore),
 * so state kept in player fields would be lost or forked; it lives here instead.
 */
final class SeatState {

    final String seat;
    Exchange exchange;

    /** Priority actions that failed since the seat's last pass or completed action: not offered again (Section 8). */
    final Set<String> excluded = new LinkedHashSet<>();
    /** The next priority decision re-poses a rejected action's priority decision (Section 8 rewind). */
    boolean rewindNext;

    /** Answers already decided by a posed group, consumed by XMage's follow-up card prompts (order loops). */
    final Deque<UUID> scriptedCards = new ArrayDeque<>();
    /** Trigger order already decided by a posed order_pick group, by ability id. */
    final Deque<UUID> scriptedTriggers = new ArrayDeque<>();
    /** Modes already decided by a posed fixed group. */
    final Deque<UUID> scriptedModes = new ArrayDeque<>();

    /** The target whose selection the seat ended with a finish candidate, and its size then. */
    Object finishedTarget;
    int finishedSize;

    /** Set when autopay could not pay during the current priority action (for the X4 evidence counters). */
    boolean autopayFailed;

    /** Combat declarations XMage rejected in a row, and the turn of the last declaration (CombatOracle). */
    int attackRejections;
    int blockRejections;
    int attackTurn = -1;
    int blockTurn = -1;

    SeatState(String seat) {
        this.seat = seat;
    }
}
