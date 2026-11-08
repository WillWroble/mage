package mage.player.cabt;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Writes a recorded run ({@link CabtRunRecorder}) as a reviewable artifact
 * bundle whose files must stay mutually consistent — the point is that the
 * contents are only coherent if the bridge really drove the engine:
 * <pre>
 * manifest.json       run identity (schemaVersion + caller-supplied entries)
 * decklists.json      the deterministic zone setup, caller-supplied
 * observations.jsonl  every prompt as the deciding player saw it
 * transitions.jsonl   before/selected/after per decision, with object-id
 *                     zone moves and tap changes as the delta
 * timeline.html       the transitions rendered for human review
 * final-state.json    the board after the run
 * invariants.json     machine-checked claims, each carrying the sequence
 *                     numbers that prove it
 * </pre>
 * Objects are tracked across zones by the engine's own UUIDs; a spell on the
 * stack is tracked by its source (card) id, which is also the id of the
 * permanent it resolves into — so "Grizzly Bears HAND → STACK → BATTLEFIELD"
 * is one id throughout. Zone moves in and out of hidden zones serialize as
 * from/to UNKNOWN; invisible opponent hands never produce phantom moves.
 * <p>
 * {@link Result#isPassed()} reports the invariant outcome so a test can fail
 * the build when the bundle is internally inconsistent.
 */
public final class CabtSmokeRunBundleWriter {

    public static final int SCHEMA_VERSION = 1;

    private static final Gson GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .serializeNulls()
            .create();
    private static final Gson PRETTY = new GsonBuilder()
            .disableHtmlEscaping()
            .serializeNulls()
            .setPrettyPrinting()
            .create();

    private static final Set<String> PUBLIC_ZONES = new HashSet<String>(
            Arrays.asList("BATTLEFIELD", "STACK", "GRAVEYARD", "EXILE"));
    private static final String UNKNOWN_ZONE = "UNKNOWN";

    private final File outputDir;

    public CabtSmokeRunBundleWriter(File outputDir) {
        if (outputDir == null) {
            throw new IllegalArgumentException("outputDir must not be null");
        }
        this.outputDir = outputDir;
    }

    public Result write(Map<String, ?> manifest, Object decklists,
                        List<CabtRunStep> steps, MagicCurrent finalState) {
        if (steps == null || steps.isEmpty() || finalState == null) {
            throw new IllegalArgumentException("a run bundle needs recorded steps and a final state");
        }
        if (!outputDir.isDirectory() && !outputDir.mkdirs()) {
            throw new UncheckedIOException(new IOException("cannot create " + outputDir));
        }

        Map<String, Object> manifestJson = new LinkedHashMap<String, Object>();
        manifestJson.put("schemaVersion", SCHEMA_VERSION);
        if (manifest != null) {
            manifestJson.putAll(manifest);
        }
        writeFile("manifest.json", PRETTY.toJson(manifestJson));
        writeFile("decklists.json", PRETTY.toJson(decklists));

        StringBuilder observations = new StringBuilder();
        for (CabtRunStep step : steps) {
            Map<String, Object> line = new LinkedHashMap<String, Object>();
            line.put("sequence", step.getSequence());
            line.put("player", step.getPlayerName());
            line.put("playerId", step.getPlayerId());
            line.put("observation", step.getObservation());
            observations.append(GSON.toJson(line)).append('\n');
        }
        writeFile("observations.jsonl", observations.toString());

        List<Transition> transitions = buildTransitions(steps, finalState);
        StringBuilder transitionLines = new StringBuilder();
        for (Transition transition : transitions) {
            transitionLines.append(GSON.toJson(transition.toJson())).append('\n');
        }
        writeFile("transitions.jsonl", transitionLines.toString());

        Map<String, Object> finalStateJson = new LinkedHashMap<String, Object>();
        finalStateJson.put("schemaVersion", SCHEMA_VERSION);
        finalStateJson.put("gameEnded", finalState.isGameEnded());
        finalStateJson.put("winner", finalState.getWinner());
        finalStateJson.put("state", stateSummary(finalState));
        writeFile("final-state.json", PRETTY.toJson(finalStateJson));

        List<Map<String, Object>> checks = runChecks(steps, transitions, finalState);
        boolean passed = true;
        List<String> failed = new ArrayList<String>();
        for (Map<String, Object> check : checks) {
            if (!Boolean.TRUE.equals(check.get("passed"))) {
                passed = false;
                failed.add(String.valueOf(check.get("name")));
            }
        }
        Map<String, Object> invariants = new LinkedHashMap<String, Object>();
        invariants.put("passed", passed);
        invariants.put("checks", checks);
        writeFile("invariants.json", PRETTY.toJson(invariants));

        writeFile("timeline.html", renderTimeline(manifestJson, transitions, finalState));

        return new Result(passed, failed);
    }

    // --- transitions ---

    private static List<Transition> buildTransitions(List<CabtRunStep> steps, MagicCurrent finalState) {
        List<Transition> transitions = new ArrayList<Transition>();
        for (int i = 0; i < steps.size(); i++) {
            CabtRunStep step = steps.get(i);
            MagicCurrent before = step.getObservation().getCurrent();
            MagicCurrent after = i + 1 < steps.size()
                    ? steps.get(i + 1).getObservation().getCurrent()
                    : finalState;
            transitions.add(new Transition(step, before, after));
        }
        return transitions;
    }

    /**
     * One object's position in a state snapshot, keyed by its tracking id.
     */
    private static final class Tracked {
        final String id;
        final String name;
        final String zone;
        final Boolean tapped;

        Tracked(String id, String name, String zone, Boolean tapped) {
            this.id = id;
            this.name = name;
            this.zone = zone;
            this.tapped = tapped;
        }
    }

    private static Map<String, Tracked> trackedObjects(MagicCurrent state) {
        Map<String, Tracked> map = new LinkedHashMap<String, Tracked>();
        for (MagicPermanentView permanent : state.getBattlefield()) {
            String id = permanent.getRef().getObjectId();
            putIfAbsent(map, new Tracked(id, permanent.getRef().getName(),
                    "BATTLEFIELD", permanent.isTapped()));
        }
        for (MagicStackObjectView stackObject : state.getStack()) {
            // track by source (card) id: it is the id the object had in hand
            // and the id of the permanent it resolves into
            String id = stackObject.getSourceId() != null
                    ? stackObject.getSourceId()
                    : stackObject.getRef().getObjectId();
            putIfAbsent(map, new Tracked(id, stackObject.getName(), "STACK", null));
        }
        for (MagicPlayerView player : state.getPlayers()) {
            for (MagicObjectView card : player.getGraveyard()) {
                putIfAbsent(map, new Tracked(card.getRef().getObjectId(),
                        card.getRef().getName(), "GRAVEYARD", null));
            }
            for (MagicObjectView card : player.getExile()) {
                putIfAbsent(map, new Tracked(card.getRef().getObjectId(),
                        card.getRef().getName(), "EXILE", null));
            }
            // only the perspective player's hand is populated: hidden hands
            // contribute nothing, so they can never produce phantom moves
            for (MagicObjectView card : player.getHand()) {
                putIfAbsent(map, new Tracked(card.getRef().getObjectId(),
                        card.getRef().getName(), "HAND", null));
            }
        }
        for (MagicObjectView card : state.getExile()) {
            putIfAbsent(map, new Tracked(card.getRef().getObjectId(),
                    card.getRef().getName(), "EXILE", null));
        }
        return map;
    }

    private static void putIfAbsent(Map<String, Tracked> map, Tracked tracked) {
        if (tracked.id != null && !map.containsKey(tracked.id)) {
            map.put(tracked.id, tracked);
        }
    }

    private static final class Transition {
        final CabtRunStep step;
        final MagicCurrent before;
        final MagicCurrent after;
        final List<Map<String, Object>> zoneMoves = new ArrayList<Map<String, Object>>();
        final List<Map<String, Object>> tappedChanges = new ArrayList<Map<String, Object>>();

        Transition(CabtRunStep step, MagicCurrent before, MagicCurrent after) {
            this.step = step;
            this.before = before;
            this.after = after;
            computeDelta();
        }

        private void computeDelta() {
            Map<String, Tracked> beforeMap = trackedObjects(before);
            Map<String, Tracked> afterMap = trackedObjects(after);
            for (Tracked now : afterMap.values()) {
                Tracked was = beforeMap.get(now.id);
                if (was != null && !was.zone.equals(now.zone)) {
                    zoneMoves.add(zoneMove(now.id, now.name, was.zone, now.zone));
                } else if (was == null && PUBLIC_ZONES.contains(now.zone)) {
                    // appeared in a public zone from somewhere invisible
                    zoneMoves.add(zoneMove(now.id, now.name, UNKNOWN_ZONE, now.zone));
                }
                if (was != null && was.tapped != null && now.tapped != null
                        && !was.tapped.equals(now.tapped)) {
                    Map<String, Object> change = new LinkedHashMap<String, Object>();
                    change.put("objectId", now.id);
                    change.put("name", now.name);
                    change.put("from", was.tapped);
                    change.put("to", now.tapped);
                    tappedChanges.add(change);
                }
            }
            for (Tracked was : beforeMap.values()) {
                if (!afterMap.containsKey(was.id) && PUBLIC_ZONES.contains(was.zone)) {
                    // left a public zone for somewhere invisible
                    zoneMoves.add(zoneMove(was.id, was.name, was.zone, UNKNOWN_ZONE));
                }
            }
        }

        private static Map<String, Object> zoneMove(String id, String name, String from, String to) {
            Map<String, Object> move = new LinkedHashMap<String, Object>();
            move.put("objectId", id);
            move.put("name", name);
            move.put("from", from);
            move.put("to", to);
            return move;
        }

        Map<String, Object> toJson() {
            Map<String, Object> line = new LinkedHashMap<String, Object>();
            line.put("sequence", step.getSequence());
            line.put("player", step.getPlayerName());
            line.put("playerId", step.getPlayerId());
            line.put("method", step.getDecision().selectType().name());
            line.put("selectedIndices", step.getSelection().indices());
            List<Map<String, Object>> selected = new ArrayList<Map<String, Object>>();
            for (MagicOption option : step.getSelectedOptions()) {
                Map<String, Object> view = new LinkedHashMap<String, Object>();
                view.put("type", option.type().name());
                view.put("label", option.label());
                view.put("payload", option.payload());
                selected.add(view);
            }
            line.put("selectedOptions", selected);
            line.put("before", stateSummary(before));
            line.put("after", stateSummary(after));
            Map<String, Object> delta = new LinkedHashMap<String, Object>();
            delta.put("zoneMoves", zoneMoves);
            delta.put("tappedChanges", tappedChanges);
            line.put("delta", delta);
            return line;
        }
    }

    private static Map<String, Object> stateSummary(MagicCurrent state) {
        Map<String, Object> summary = new LinkedHashMap<String, Object>();
        summary.put("turn", state.getTurnNumber());
        summary.put("phase", state.getPhase());
        summary.put("step", state.getStep());
        List<Map<String, Object>> battlefield = new ArrayList<Map<String, Object>>();
        for (MagicPermanentView permanent : state.getBattlefield()) {
            Map<String, Object> entry = new LinkedHashMap<String, Object>();
            entry.put("objectId", permanent.getRef().getObjectId());
            entry.put("name", permanent.getRef().getName());
            entry.put("controllerId", permanent.getControllerId());
            entry.put("tapped", permanent.isTapped());
            battlefield.add(entry);
        }
        summary.put("battlefield", battlefield);
        List<Map<String, Object>> stack = new ArrayList<Map<String, Object>>();
        for (MagicStackObjectView stackObject : state.getStack()) {
            Map<String, Object> entry = new LinkedHashMap<String, Object>();
            entry.put("objectId", stackObject.getSourceId() != null
                    ? stackObject.getSourceId() : stackObject.getRef().getObjectId());
            entry.put("name", stackObject.getName());
            entry.put("controllerId", stackObject.getControllerId());
            stack.add(entry);
        }
        summary.put("stack", stack);
        List<Map<String, Object>> players = new ArrayList<Map<String, Object>>();
        for (MagicPlayerView player : state.getPlayers()) {
            Map<String, Object> entry = new LinkedHashMap<String, Object>();
            entry.put("name", player.getName());
            entry.put("playerId", player.getPlayerId());
            entry.put("life", player.getLife());
            entry.put("handCount", player.getHandCount());
            entry.put("libraryCount", player.getLibraryCount());
            entry.put("graveyardCount", player.getGraveyardCount());
            List<Map<String, Object>> hand = new ArrayList<Map<String, Object>>();
            for (MagicObjectView card : player.getHand()) {
                Map<String, Object> cardEntry = new LinkedHashMap<String, Object>();
                cardEntry.put("objectId", card.getRef().getObjectId());
                cardEntry.put("name", card.getRef().getName());
                hand.add(cardEntry);
            }
            entry.put("hand", hand);
            players.add(entry);
        }
        summary.put("players", players);
        return summary;
    }

    // --- invariants ---

    private static List<Map<String, Object>> runChecks(List<CabtRunStep> steps,
                                                       List<Transition> transitions,
                                                       MagicCurrent finalState) {
        List<Map<String, Object>> checks = new ArrayList<Map<String, Object>>();
        checks.add(selectionMovedItsObject(transitions, "a PLAY_LAND selection moved its card HAND -> BATTLEFIELD",
                MagicOptionType.PLAY_LAND, CabtPriorityOptionFactory.PAYLOAD_SOURCE_ID,
                "HAND", "BATTLEFIELD"));
        checks.add(selectionMovedItsObject(transitions, "a CAST_SPELL selection moved its card HAND -> STACK",
                MagicOptionType.CAST_SPELL, CabtPriorityOptionFactory.PAYLOAD_SOURCE_ID,
                "HAND", "STACK"));
        checks.add(manaSourceTapped(transitions));
        checks.add(spellResolved(transitions));
        checks.add(movesChainPerObject(transitions));
        checks.add(finalBattlefieldAgrees(transitions, finalState));
        checks.add(opponentHandsHidden(steps));
        return checks;
    }

    /**
     * A transition whose selected option has the given type must show the
     * option's own object (by payload id) making the given zone move.
     */
    private static Map<String, Object> selectionMovedItsObject(List<Transition> transitions,
                                                               String checkName,
                                                               MagicOptionType optionType,
                                                               String payloadIdKey,
                                                               String fromZone, String toZone) {
        for (Transition transition : transitions) {
            for (MagicOption option : transition.step.getSelectedOptions()) {
                if (option.type() != optionType) {
                    continue;
                }
                Object objectId = option.payload().get(payloadIdKey);
                for (Map<String, Object> move : transition.zoneMoves) {
                    if (move.get("objectId").equals(objectId)
                            && fromZone.equals(move.get("from"))
                            && toZone.equals(move.get("to"))) {
                        Map<String, Object> evidence = new LinkedHashMap<String, Object>();
                        evidence.put("transitionSequence", transition.step.getSequence());
                        evidence.put("objectId", objectId);
                        evidence.put("objectName", move.get("name"));
                        evidence.put("from", fromZone);
                        evidence.put("to", toZone);
                        return check(checkName, true, evidence);
                    }
                }
            }
        }
        return check(checkName, false, Collections.<String, Object>singletonMap(
                "reason", "no transition shows a selected " + optionType + " option's object moving "
                        + fromZone + " -> " + toZone));
    }

    private static Map<String, Object> manaSourceTapped(List<Transition> transitions) {
        String name = "a PAY_MANA mana-source selection tapped its producer";
        for (Transition transition : transitions) {
            if (transition.step.getDecision().selectType() != MagicSelectType.PAY_MANA) {
                continue;
            }
            for (MagicOption option : transition.step.getSelectedOptions()) {
                if (option.type() != MagicOptionType.PROMPT_MANA_SOURCE) {
                    continue;
                }
                Object producerId = option.payload().get("objectId");
                for (Map<String, Object> change : transition.tappedChanges) {
                    if (change.get("objectId").equals(producerId)
                            && Boolean.FALSE.equals(change.get("from"))
                            && Boolean.TRUE.equals(change.get("to"))) {
                        Map<String, Object> evidence = new LinkedHashMap<String, Object>();
                        evidence.put("transitionSequence", transition.step.getSequence());
                        evidence.put("objectId", producerId);
                        evidence.put("objectName", change.get("name"));
                        return check(name, true, evidence);
                    }
                }
            }
        }
        return check(name, false, Collections.<String, Object>singletonMap(
                "reason", "no PAY_MANA transition shows the selected producer becoming tapped"));
    }

    private static Map<String, Object> spellResolved(List<Transition> transitions) {
        String name = "a spell resolved STACK -> BATTLEFIELD";
        for (Transition transition : transitions) {
            for (Map<String, Object> move : transition.zoneMoves) {
                if ("STACK".equals(move.get("from")) && "BATTLEFIELD".equals(move.get("to"))) {
                    Map<String, Object> evidence = new LinkedHashMap<String, Object>();
                    evidence.put("transitionSequence", transition.step.getSequence());
                    evidence.put("objectId", move.get("objectId"));
                    evidence.put("objectName", move.get("name"));
                    return check(name, true, evidence);
                }
            }
        }
        return check(name, false, Collections.<String, Object>singletonMap(
                "reason", "no STACK -> BATTLEFIELD zone move in any transition"));
    }

    /**
     * Every tracked object's moves must chain: each move starts where the
     * previous one ended (UNKNOWN endpoints are wildcards — hidden zones).
     */
    private static Map<String, Object> movesChainPerObject(List<Transition> transitions) {
        String name = "zone moves chain consistently per object";
        Map<String, String> lastZone = new LinkedHashMap<String, String>();
        int movesChecked = 0;
        for (Transition transition : transitions) {
            for (Map<String, Object> move : transition.zoneMoves) {
                String id = (String) move.get("objectId");
                String from = (String) move.get("from");
                String to = (String) move.get("to");
                String expected = lastZone.get(id);
                movesChecked++;
                if (expected != null && !UNKNOWN_ZONE.equals(expected)
                        && !UNKNOWN_ZONE.equals(from) && !expected.equals(from)) {
                    Map<String, Object> evidence = new LinkedHashMap<String, Object>();
                    evidence.put("transitionSequence", transition.step.getSequence());
                    evidence.put("objectId", id);
                    evidence.put("objectName", move.get("name"));
                    evidence.put("expectedFrom", expected);
                    evidence.put("actualFrom", from);
                    return check(name, false, evidence);
                }
                lastZone.put(id, to);
            }
        }
        Map<String, Object> evidence = new LinkedHashMap<String, Object>();
        evidence.put("objectsTracked", lastZone.size());
        evidence.put("movesChecked", movesChecked);
        return check(name, true, evidence);
    }

    /**
     * final-state.json must agree with transitions.jsonl: every object whose
     * recorded moves end somewhere visible is where the moves say it is, and
     * everything the moves put on the battlefield is in the final battlefield.
     */
    private static Map<String, Object> finalBattlefieldAgrees(List<Transition> transitions,
                                                              MagicCurrent finalState) {
        String name = "final battlefield agrees with the recorded zone moves";
        Map<String, String> lastZone = new LinkedHashMap<String, String>();
        Map<String, Object> lastMoveSequence = new LinkedHashMap<String, Object>();
        for (Transition transition : transitions) {
            for (Map<String, Object> move : transition.zoneMoves) {
                lastZone.put((String) move.get("objectId"), (String) move.get("to"));
                lastMoveSequence.put((String) move.get("objectId"), transition.step.getSequence());
            }
        }
        Set<String> finalBattlefieldIds = new HashSet<String>();
        for (MagicPermanentView permanent : finalState.getBattlefield()) {
            finalBattlefieldIds.add(permanent.getRef().getObjectId());
        }
        int movedOntoBattlefield = 0;
        for (Map.Entry<String, String> entry : lastZone.entrySet()) {
            boolean movesSayBattlefield = "BATTLEFIELD".equals(entry.getValue());
            boolean finallyThere = finalBattlefieldIds.contains(entry.getKey());
            if (movesSayBattlefield) {
                movedOntoBattlefield++;
            }
            if (movesSayBattlefield != finallyThere) {
                Map<String, Object> evidence = new LinkedHashMap<String, Object>();
                evidence.put("objectId", entry.getKey());
                evidence.put("lastMoveTo", entry.getValue());
                evidence.put("inFinalBattlefield", finallyThere);
                evidence.put("lastMoveTransitionSequence", lastMoveSequence.get(entry.getKey()));
                return check(name, false, evidence);
            }
        }
        Map<String, Object> evidence = new LinkedHashMap<String, Object>();
        evidence.put("finalBattlefieldObjects", finalBattlefieldIds.size());
        evidence.put("movedOntoBattlefield", movedOntoBattlefield);
        return check(name, true, evidence);
    }

    private static Map<String, Object> opponentHandsHidden(List<CabtRunStep> steps) {
        String name = "opponent hidden hands are never leaked";
        int observations = 0;
        int opponentHandObjectsSeen = 0;
        int opponentHandCountsSeen = 0;
        for (CabtRunStep step : steps) {
            observations++;
            String decidingPlayerId = step.getDecision().playerId() == null
                    ? null : step.getDecision().playerId().toString();
            for (MagicPlayerView player : step.getObservation().getCurrent().getPlayers()) {
                if (player.getPlayerId() != null && player.getPlayerId().equals(decidingPlayerId)) {
                    continue;
                }
                opponentHandObjectsSeen += player.getHand().size() + player.getRevealedHand().size();
                if (player.getHandCount() > 0) {
                    opponentHandCountsSeen++;
                }
            }
        }
        Map<String, Object> evidence = new LinkedHashMap<String, Object>();
        evidence.put("observationsChecked", observations);
        evidence.put("opponentHandObjectsSeen", opponentHandObjectsSeen);
        evidence.put("opponentNonEmptyHandCountsSeen", opponentHandCountsSeen);
        return check(name, opponentHandObjectsSeen == 0, evidence);
    }

    private static Map<String, Object> check(String name, boolean passed, Map<String, Object> evidence) {
        Map<String, Object> check = new LinkedHashMap<String, Object>();
        check.put("name", name);
        check.put("passed", passed);
        check.put("evidence", evidence);
        return check;
    }

    // --- timeline ---

    private static String renderTimeline(Map<String, Object> manifest,
                                         List<Transition> transitions, MagicCurrent finalState) {
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html>\n<html>\n<head>\n<meta charset=\"utf-8\">\n")
                .append("<title>CABT smoke run timeline</title>\n<style>\n")
                .append("body { font-family: monospace; margin: 2em; }\n")
                .append(".decision { border-left: 3px solid #888; padding: 0 1em; margin: 1em 0; }\n")
                .append(".delta li { color: #a40; }\n")
                .append(".pass { color: #070; }\nh3 { margin-bottom: 0.2em; }\n")
                .append("details { margin: 0.4em 0; }\n</style>\n</head>\n<body>\n");
        html.append("<h1>CABT smoke run</h1>\n<p>").append(escape(GSON.toJson(manifest))).append("</p>\n");
        for (Transition transition : transitions) {
            MagicCurrent before = transition.before;
            html.append("<div class=\"decision\">\n<h3>#").append(transition.step.getSequence())
                    .append(" &mdash; Turn ").append(before.getTurnNumber())
                    .append(" &mdash; ").append(escape(String.valueOf(before.getPhase())))
                    .append('/').append(escape(String.valueOf(before.getStep())))
                    .append(" &mdash; ").append(escape(transition.step.getPlayerName()))
                    .append(" &mdash; ").append(escape(transition.step.getDecision().selectType().name()))
                    .append("</h3>\n<p>Selected: ");
            if (transition.step.getSelectedOptions().isEmpty()) {
                html.append("(nothing)");
            } else {
                List<String> labels = new ArrayList<String>();
                for (MagicOption option : transition.step.getSelectedOptions()) {
                    labels.add(option.label());
                }
                html.append(escape(join(labels)));
            }
            html.append("</p>\n");
            if (!transition.zoneMoves.isEmpty() || !transition.tappedChanges.isEmpty()) {
                html.append("<ul class=\"delta\">\n");
                for (Map<String, Object> move : transition.zoneMoves) {
                    html.append("<li>").append(escape(String.valueOf(move.get("name"))))
                            .append(" moved ").append(escape(String.valueOf(move.get("from"))))
                            .append(" &rarr; ").append(escape(String.valueOf(move.get("to"))))
                            .append("</li>\n");
                }
                for (Map<String, Object> change : transition.tappedChanges) {
                    html.append("<li>").append(escape(String.valueOf(change.get("name"))))
                            .append(" tapped ").append(change.get("from"))
                            .append(" &rarr; ").append(change.get("to")).append("</li>\n");
                }
                html.append("</ul>\n");
            }
            html.append("<details><summary>before/after</summary>\n<pre>before: ")
                    .append(escape(PRETTY.toJson(stateSummary(transition.before))))
                    .append("\n\nafter: ")
                    .append(escape(PRETTY.toJson(stateSummary(transition.after))))
                    .append("</pre>\n</details>\n</div>\n");
        }
        html.append("<h2>Final state</h2>\n<pre>")
                .append(escape(PRETTY.toJson(stateSummary(finalState))))
                .append("</pre>\n</body>\n</html>\n");
        return html.toString();
    }

    private static String join(List<String> values) {
        StringBuilder joined = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                joined.append(", ");
            }
            joined.append(values.get(i));
        }
        return joined.toString();
    }

    private static String escape(String text) {
        return text == null ? "" : text
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private void writeFile(String fileName, String content) {
        try {
            Files.write(new File(outputDir, fileName).toPath(),
                    content.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("failed to write " + fileName, e);
        }
    }

    /**
     * Outcome of the bundle's internal consistency checks, so a test can fail
     * when invariants.json says the artifacts disagree with each other.
     */
    public static final class Result {
        private final boolean passed;
        private final List<String> failedChecks;

        Result(boolean passed, List<String> failedChecks) {
            this.passed = passed;
            this.failedChecks = Collections.unmodifiableList(new ArrayList<String>(failedChecks));
        }

        public boolean isPassed() {
            return passed;
        }

        public List<String> getFailedChecks() {
            return failedChecks;
        }
    }
}
