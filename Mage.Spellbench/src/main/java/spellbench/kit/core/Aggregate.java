package spellbench.kit.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Aggregation over K worlds and the ranked fallback (design Sections 5.5.2, 5.5.4 and 6.5), on the per-world results
 * the runner returns. Keys are the canonical JSON of the full v2 semantic (N2), so two candidates of one decision
 * never share a key (protocol Section 7.1, pairwise-distinct semantics).
 */
public final class Aggregate {

    private Aggregate() {
    }

    /** Key of a semantic (Section 5.5.2): its canonical JSON. */
    public static String key(Object semantic) {
        return semantic == null ? null : Json.canonical(semantic);
    }

    /**
     * The revision-2 key (kind and source only): kept to show, in the S8 fixture, that it collides where the full
     * semantic does not.
     */
    @SuppressWarnings("unchecked")
    public static String oldKey(Map<String, Object> semantic) {
        Object src = semantic.get("source");
        String sid = src instanceof Map ? String.valueOf(((Map<String, Object>) src).get("object_id")) : null;
        return semantic.get("kind") + "|" + sid;
    }

    /** One world's vote and its statistics. */
    public static final class WorldVote {
        public final int world;
        public final String key;
        public final Map<String, Object> payload;
        /** key -> exact adjusted score of that key's best option in this world (absent when not exact). */
        public final Map<String, Long> exact = new LinkedHashMap<>();
        /** keys in this world's ranking order: exact scores descending, then bounds, then unscored in generation order */
        public final List<String> ranked = new ArrayList<>();

        public WorldVote(int world, String key, Map<String, Object> payload) {
            this.world = world;
            this.key = key;
            this.payload = payload;
        }
    }

    /** The aggregated choice: winning key, the world whose plan is used, and the full ranking of keys. */
    public static final class Result {
        public String winner;
        public int planWorld = -1;
        public Map<String, Object> planPayload;
        public final Map<String, Integer> votes = new LinkedHashMap<>();
        public final Map<String, Long> exactSum = new LinkedHashMap<>();
        public final List<String> ranking = new ArrayList<>();
    }

    /**
     * H2 vote (Section 5.5.4): most votes; ties by the larger sum of exact adjusted scores for the key across
     * worlds; then the lowest candidate id ({@code candidateOf} maps keys to offered candidate ids; keys not offered
     * rank last). The plan is the lowest-index world that voted for the winner, with its executed payload.
     */
    public static Result vote(List<WorldVote> worlds, final Map<String, Integer> candidateOf) {
        final Result r = new Result();
        List<String> keys = new ArrayList<>();
        for (WorldVote w : worlds) {
            if (w.key != null) {
                r.votes.merge(w.key, 1, Integer::sum);
                if (!keys.contains(w.key)) {
                    keys.add(w.key);
                }
            }
            for (Map.Entry<String, Long> e : w.exact.entrySet()) {
                r.exactSum.merge(e.getKey(), e.getValue(), Long::sum);
                if (!keys.contains(e.getKey())) {
                    keys.add(e.getKey());
                }
            }
            for (String k : w.ranked) {
                if (!keys.contains(k)) {
                    keys.add(k);
                }
            }
        }
        // ranking: votes, then exact sums, then the first world's own order (bounds, then unscored), then candidate id
        final Map<String, Integer> firstSeen = new LinkedHashMap<>();
        for (WorldVote w : worlds) {
            for (String k : w.ranked) {
                if (!firstSeen.containsKey(k)) {
                    firstSeen.put(k, firstSeen.size());
                }
            }
        }
        List<String> sorted = new ArrayList<>(keys);
        Collections.sort(sorted, new Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                int c = Integer.compare(r.votes.getOrDefault(b, 0), r.votes.getOrDefault(a, 0));
                if (c != 0) {
                    return c;
                }
                Long ea = r.exactSum.get(a);
                Long eb = r.exactSum.get(b);
                if (ea != null && eb != null && !ea.equals(eb)) {
                    return Long.compare(eb, ea);
                }
                if (ea != null && eb == null) {
                    return -1;
                }
                if (ea == null && eb != null) {
                    return 1;
                }
                Integer ca = candidateOf.get(a);
                Integer cb = candidateOf.get(b);
                if (r.votes.getOrDefault(a, 0) > 0 && ca != null && cb != null) {
                    return Integer.compare(ca, cb);
                }
                int fa = firstSeen.getOrDefault(a, Integer.MAX_VALUE);
                int fb = firstSeen.getOrDefault(b, Integer.MAX_VALUE);
                if (fa != fb) {
                    return Integer.compare(fa, fb);
                }
                return Integer.compare(ca == null ? Integer.MAX_VALUE : ca, cb == null ? Integer.MAX_VALUE : cb);
            }
        });
        r.ranking.addAll(sorted);
        for (String k : sorted) {
            if (r.votes.getOrDefault(k, 0) > 0) {
                r.winner = k;
                break;
            }
        }
        if (r.winner != null) {
            for (WorldVote w : worlds) {
                if (r.winner.equals(w.key)) {
                    r.planWorld = w.world;
                    r.planPayload = w.payload;
                    break;
                }
            }
        }
        return r;
    }

    /**
     * H3 (Section 5.5.4): visits summed per priority key across worlds; ties by summed wins, then the lowest
     * candidate id. The plan is the world with the most visits on the winning key (ties: lowest index), its
     * most-visited child with that key (ties: lowest child index), and that child's executed payload.
     */
    public static Result visits(List<Map<String, Object>> worlds, final Map<String, Integer> candidateOf) {
        final Result r = new Result();
        final Map<String, Long> visits = new LinkedHashMap<>();
        final Map<String, Long> wins = new LinkedHashMap<>();
        for (Map<String, Object> w : worlds) {
            for (Object o : Json.arr(w, "root_stats")) {
                Map<String, Object> s = Json.obj(o);
                if (s.get("semantic") == null) {
                    continue;
                }
                String k = key(s.get("semantic"));
                visits.merge(k, Json.num(s, "visits", 0), Long::sum);
                wins.merge(k, Json.num(s, "wins", 0), Long::sum);
            }
        }
        List<String> sorted = new ArrayList<>(visits.keySet());
        Collections.sort(sorted, new Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                int c = Long.compare(visits.get(b), visits.get(a));
                if (c != 0) {
                    return c;
                }
                c = Long.compare(wins.get(b), wins.get(a));
                if (c != 0) {
                    return c;
                }
                Integer ca = candidateOf.get(a);
                Integer cb = candidateOf.get(b);
                return Integer.compare(ca == null ? Integer.MAX_VALUE : ca, cb == null ? Integer.MAX_VALUE : cb);
            }
        });
        r.ranking.addAll(sorted);
        for (String k : sorted) {
            r.votes.put(k, visits.get(k).intValue());
        }
        r.winner = sorted.isEmpty() ? null : sorted.get(0);
        if (r.winner == null) {
            return r;
        }
        long bestVisits = -1;
        for (Map<String, Object> w : worlds) {
            long v = 0;
            Map<String, Object> bestChild = null;
            long bestChildVisits = -1;
            for (Object o : Json.arr(w, "root_stats")) {
                Map<String, Object> s = Json.obj(o);
                if (s.get("semantic") == null || !r.winner.equals(key(s.get("semantic")))) {
                    continue;
                }
                long cv = Json.num(s, "visits", 0);
                v += cv;
                if (cv > bestChildVisits) {
                    bestChildVisits = cv;
                    bestChild = s;
                }
            }
            if (v > bestVisits) {
                bestVisits = v;
                r.planWorld = (int) Json.num(w, "index", 0);
                r.planPayload = bestChild == null ? null : Json.obj(bestChild, "payload");
            }
        }
        return r;
    }

    /**
     * Builds a world's vote from the runner's priority result: its best key, its executed payload, the exact
     * adjusted score of each key's best option, and its ranking order.
     */
    @SuppressWarnings("unchecked")
    public static WorldVote fromRunner(Map<String, Object> world) {
        Object sem = world.get("semantic");
        Map<String, Object> payload = Json.obj(world, "executed_payload");
        if (payload == null) {
            payload = Json.obj(world, "option_payload");
        }
        WorldVote v = new WorldVote((int) Json.num(world, "index", 0), sem == null ? null : key(sem), payload);
        List<Object[]> exact = new ArrayList<>();
        List<String> bounds = new ArrayList<>();
        List<String> unscored = new ArrayList<>();
        for (Object o : Json.arr(world, "root_stats")) {
            Map<String, Object> s = Json.obj(o);
            Object ss = s.get("semantic");
            if (ss == null) {
                continue;
            }
            String k = key(ss);
            if ("exact".equals(s.get("bound")) && s.get("adjusted") instanceof Number) {
                long score = ((Number) s.get("adjusted")).longValue();
                Long before = v.exact.get(k);
                if (before == null || score > before) {
                    v.exact.put(k, score);
                }
                exact.add(new Object[]{k, score});
            } else if (s.get("adjusted") instanceof Number) {
                bounds.add(k);
            } else {
                unscored.add(k);
            }
        }
        Collections.sort(exact, (a, b) -> Long.compare((Long) b[1], (Long) a[1]));
        if (v.key != null) {
            v.ranked.add(v.key);
        }
        for (Object[] e : exact) {
            if (!v.ranked.contains(e[0])) {
                v.ranked.add((String) e[0]);
            }
        }
        for (String k : bounds) {
            if (!v.ranked.contains(k)) {
                v.ranked.add(k);
            }
        }
        for (String k : unscored) {
            if (!v.ranked.contains(k)) {
                v.ranked.add(k);
            }
        }
        return v;
    }
}
