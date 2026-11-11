package org.mage.spellbench;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.util.List;

/**
 * The kernel's side of a lockstep game: Jack's protocol engine for mtg-kernel (patched to take XMage's hidden zones)
 * and the Archon, which plays its seat natively. This is the host of that game: it resets it, steps it with each
 * seat's answer, and asks the Archon when its seat acts. The other seat's answers come from the XMage game.
 */
final class KernelMatch implements AutoCloseable {

    private static final String PROTOCOL = "spellbench/v2";

    /** Where the engine and the Archon run, the Archon's seat, and the matchup's fixed setup (bench/lockstep_setup.py). */
    static final class Settings {
        List<String> engine;
        List<String> archon;
        String seat = "p1";
        String setup;
        String logs;
        /** Keep each process's protocol transcript in the logs (large: every observation). */
        boolean transcript;

        static Settings load(String path) {
            try {
                return new Gson().fromJson(new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8),
                        Settings.class);
            } catch (IOException e) {
                throw new UncheckedIOException("lockstep settings " + path, e);
            }
        }

        JsonObject setup() {
            try {
                return JsonParser.parseString(new String(Files.readAllBytes(Paths.get(setup)), StandardCharsets.UTF_8))
                        .getAsJsonObject();
            } catch (IOException e) {
                throw new UncheckedIOException("lockstep setup " + setup, e);
            }
        }
    }

    final String archonSeat;
    private final Settings settings;
    private final JsonObject setup;
    private NdjsonProcess engine;
    private NdjsonProcess archon;
    private String gameId;
    /** The engine's declared observation flags (its hello_ok profile). */
    private JsonObject observationFlags;
    /** The engine's last answer: a decision or the terminal. */
    private JsonObject current;

    KernelMatch(Settings settings, String archonSeat) {
        this.settings = settings;
        this.setup = settings.setup();
        this.archonSeat = archonSeat;
    }

    /** Starts the game with each seat's library order (card names, top first, opening hand included). */
    void start(String gameId, String startingSeat, List<String> p0Library, List<String> p1Library) {
        this.gameId = gameId;
        File logs = new File(settings.logs);
        logs.mkdirs();
        engine = new NdjsonProcess(settings.engine, new File(logs, gameId + "-engine.log"),
                settings.transcript ? new File(logs, gameId + "-engine.jsonl") : null);
        JsonObject hello = expect(engine.request(PROTOCOL, "hello", minor()), "hello_ok");
        observationFlags = hello.getAsJsonObject("observation");
        JsonObject reset = new JsonObject();
        reset.addProperty("game_id", gameId);
        reset.add("format", setup.get("format"));
        reset.add("seats", setup.get("seats"));
        JsonObject rules = setup.getAsJsonObject("rules").deepCopy();
        rules.addProperty("starting_seat", startingSeat);
        reset.add("rules", rules);
        reset.addProperty("game_secret", secret());
        reset.add("max_decisions", setup.getAsJsonObject("limits").get("max_decisions"));
        reset.add("max_steps", setup.getAsJsonObject("limits").get("max_steps"));
        JsonArray orders = new JsonArray();
        orders.add(new Gson().toJsonTree(p0Library));
        orders.add(new Gson().toJsonTree(p1Library));
        reset.add("library_orders", orders);
        current = engine.request(PROTOCOL, "x_lockstep_reset", reset);

        archon = new NdjsonProcess(settings.archon, new File(logs, gameId + "-archon.log"), null);
        expect(archon.request(PROTOCOL, "hello", minor()), "hello_ok");
        int seat = archonSeat.equals("p0") ? 0 : 1;
        JsonObject start = new JsonObject();
        start.addProperty("game_id", gameId);
        start.addProperty("seat", archonSeat);
        start.add("format", setup.get("format"));
        start.add("own_deck", setup.getAsJsonArray("own_decks").get(seat));
        start.add("opponent_deck", null);
        start.add("rules", rules);
        start.add("engine", hello.get("engine"));
        start.add("engine_profile", profile(hello));
        start.add("time_control", setup.get("time_control"));
        start.add("limits", setup.get("limits"));
        start.add("resources", setup.get("resources"));
        start.addProperty("agent_seed", new SecureRandom().nextLong() & ((1L << 53) - 1)); // a JSON-safe integer
        expect(archon.request(PROTOCOL, "game_start", start), "ack");
    }

    /** The observation fields the engine fills (spec 6.9), as flags. */
    JsonObject observationFlags() {
        return observationFlags;
    }

    /** The pending seat decision, or null once the game is over. */
    JsonObject decision() {
        return current.has("seat_decision") ? current.getAsJsonObject("seat_decision") : null;
    }

    String actingSeat() {
        return decision().get("acting_seat").getAsString();
    }

    /** The terminal result, or null while the game goes on. */
    JsonObject terminal() {
        return "terminal".equals(current.get("response_type").getAsString()) ? current : null;
    }

    /** Answers the pending decision with one of its candidates. */
    void step(int candidateId) {
        JsonObject decision = decision();
        trace(decision, candidateId);
        JsonObject selection = new JsonObject();
        selection.addProperty("candidate_id", candidateId);
        selection.add("semantic_echo",
                decision.getAsJsonArray("candidates").get(candidateId).getAsJsonObject().get("semantic"));
        JsonObject step = new JsonObject();
        step.addProperty("game_id", gameId);
        step.add("expected_step", current.get("step"));
        step.add("selection", selection);
        current = engine.request(PROTOCOL, "step", step);
        if (!current.get("response_type").getAsString().matches("decision|terminal")) {
            throw new IllegalStateException("the engine refused the step: " + current);
        }
    }

    /** The Archon's answer to the pending decision, which is its seat's. */
    int archonChoice() {
        JsonObject clock = new JsonObject();
        clock.add("remaining_ms", setup.getAsJsonObject("time_control").get("bank_ms"));
        clock.add("max_decision_ms", setup.getAsJsonObject("time_control").get("max_decision_ms"));
        JsonObject choose = new JsonObject();
        choose.addProperty("game_id", gameId);
        choose.add("decision", decision());
        choose.add("clock", clock);
        return expect(archon.request(PROTOCOL, "choose", choose), "choice")
                .getAsJsonObject("selection").get("candidate_id").getAsInt();
    }

    /** One line per step in {@code <logs>/<game>-trace.jsonl}: who decided what, where. */
    private void trace(JsonObject decision, int candidateId) {
        JsonObject observation = decision.getAsJsonObject("observation");
        JsonObject line = new JsonObject();
        line.add("seat", decision.get("acting_seat"));
        line.add("turn", observation.get("turn"));
        line.add("step", observation.get("phase_step"));
        line.addProperty("stack", observation.getAsJsonArray("stack").size());
        line.add("chosen", decision.getAsJsonArray("candidates").get(candidateId).getAsJsonObject().get("semantic"));
        line.addProperty("of", decision.getAsJsonArray("candidates").size());
        JsonArray offered = new JsonArray();
        decision.getAsJsonArray("candidates").forEach(c -> {
            JsonElement kind = c.getAsJsonObject().getAsJsonObject("semantic").get("kind");
            if (!offered.contains(kind)) offered.add(kind);
        });
        line.add("offered", offered);
        JsonArray hand = new JsonArray();
        JsonElement cards = observation.getAsJsonArray("players")
                .get(decision.get("acting_seat").getAsString().equals("p0") ? 0 : 1).getAsJsonObject().get("hand");
        if (cards.isJsonArray()) cards.getAsJsonArray().forEach(c -> hand.add(c.getAsJsonObject().get("card_name")));
        line.add("hand", hand);
        try (java.io.FileWriter out = new java.io.FileWriter(new File(settings.logs, gameId + "-trace.jsonl"), true)) {
            out.write(line + "\n");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Puts a seat's library in XMage's order (card names, top first) after XMage shuffled it; one the kernel's library
     * cannot take (XMage has resolved an effect the kernel has not: Sagu Wildling's omen shuffled in, a Forest out) is
     * left for later, as the next comparison checks the libraries' cards.
     */
    /**
     * A seat's shuffle {@code count} in both engines (each counts a seat's shuffles): the kernel's order for it, card
     * names top first, when it made that shuffle already; else null, and the kernel takes XMage's {@code library}.
     */
    List<String> shuffle(String seat, int count, List<String> library) {
        JsonObject shuffle = new JsonObject();
        shuffle.addProperty("game_id", gameId);
        shuffle.addProperty("seat", seat);
        shuffle.addProperty("count", count);
        shuffle.add("library", new Gson().toJsonTree(library));
        JsonElement made = engine.request(PROTOCOL, "x_lockstep_shuffle", shuffle).get("library");
        return made == null || made.isJsonNull() ? null
                : new Gson().fromJson(made, new TypeToken<List<String>>() {}.getType());
    }

    void arrangeLibrary(String seat, List<String> library) {
        JsonObject arrange = new JsonObject();
        arrange.addProperty("game_id", gameId);
        arrange.addProperty("seat", seat);
        arrange.add("library", new Gson().toJsonTree(library));
        engine.request(PROTOCOL, "x_lockstep_arrange_library", arrange);
    }

    @Override
    public void close() {
        if (archon != null) {
            archon.close();
        }
        if (engine != null) {
            engine.close();
        }
    }

    /** The engine profile within hello_ok (spec 9.1), as game_start carries it. */
    private static JsonObject profile(JsonObject hello) {
        JsonObject profile = new JsonObject();
        for (String field : new String[]{"rules_supported", "observation", "decision_kinds", "engine_defaults", "rewind",
                "fairness", "extensions"}) {
            profile.add(field, hello.get(field));
        }
        return profile;
    }

    private static JsonObject minor() {
        JsonObject hello = new JsonObject();
        hello.addProperty("protocol_minor", 0);
        return hello;
    }

    private static String secret() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        StringBuilder hex = new StringBuilder();
        for (byte b : bytes) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    private static JsonObject expect(JsonObject response, String type) {
        JsonElement got = response.get("response_type");
        if (got == null || !type.equals(got.getAsString())) {
            throw new IllegalStateException("expected " + type + ", got " + response);
        }
        return response;
    }
}
