package mage.player.spellbench.decide;

import mage.player.spellbench.observe.Look;
import mage.player.spellbench.observe.Observation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One wire decision as a {@link SeatPlayer} callback describes it, before the observation exists: the acting seat,
 * the family, the substep within its group, exact looks, and candidates whose semantics are built from the
 * observation (so every reference equals its record, Section 5.1).
 */
final class Pose {

    /** Builds a candidate's semantic from the acting seat's observation; null drops the candidate. */
    interface Semantic {
        Map<String, Object> build(Observation o) throws Unrepresentable;
    }

    /** A candidate: its semantic, the XMage ids it names (for looks), and its sort key. */
    static final class Cand {
        final Semantic fn;
        final List<UUID> refs;
        long rank;
        UUID orderObject;
        long minor;

        Cand(Semantic fn, UUID... refs) {
            this.fn = fn;
            this.refs = new ArrayList<>(Arrays.asList(refs));
        }

        /** Sorts by rank, then the observation position of {@code object}, then {@code minor}. */
        Cand order(long rank, UUID object, long minor) {
            this.rank = rank;
            this.orderObject = object;
            this.minor = minor;
            return this;
        }
    }

    final String seat;
    final boolean priority;
    final String tag;
    boolean rewind;
    int substepIndex;
    int substepCount = 1;
    /** False keeps the candidates in insertion order (already a deterministic, visible order). */
    boolean sort = true;
    final List<Look> looks = new ArrayList<>();
    /** Cards the effect shows the seat beyond the candidates (all the looked-at cards of a card choice). */
    final List<UUID> shown = new ArrayList<>();
    final List<Cand> cands = new ArrayList<>();

    Pose(String seat, boolean priority, String tag) {
        this.seat = seat;
        this.priority = priority;
        this.tag = tag;
    }

    Pose substep(int index, int count) {
        this.substepIndex = index;
        this.substepCount = count;
        return this;
    }

    Cand add(Semantic fn, UUID... refs) {
        Cand c = new Cand(fn, refs);
        cands.add(c);
        return c;
    }
}
