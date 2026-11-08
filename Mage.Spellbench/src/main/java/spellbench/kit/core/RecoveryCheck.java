package spellbench.kit.core;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/** Real shutdown and directory isolation checks, plus permitted rename history. */
public final class RecoveryCheck {
    private RecoveryCheck() { }

    static void require(String name, boolean condition, Object detail) {
        System.out.println(Json.canonical(Json.map("check", name, "pass", condition, "detail", detail)));
        if (!condition) throw new AssertionError(name);
    }

    static Map<String, Object> observation(String name) {
        return Json.map("players", Arrays.asList(Json.map("seat", "p0", "battlefield",
                Arrays.asList(Json.map("object_id", "seen", "card_name", name),
                        Json.map("object_id", "hidden", "card_name", null)))));
    }

    @SuppressWarnings("unchecked")
    static void names() {
        Map<String, String> origins = new LinkedHashMap<>();
        VisibleNames.observe(observation("Grizzly Bears"), origins);
        Map<String, Object> renamed = observation("Legitimate Businessperson");
        VisibleNames.observe(renamed, origins);
        Map<String, Object> history = Json.map("card_origins", VisibleNames.changed(renamed, origins));
        Map<String, Object> copy = (Map<String, Object>) Json.copy(renamed);
        Map<String, Object> repairs = VisibleNames.restore(copy, history, "Grizzly Bears"::equals);
        require("RECOVERY.rename.only_observed_origin", origins.size() == 1 && repairs.containsKey("seen")
                        && Json.canonical(copy).contains("Grizzly Bears")
                        && Json.canonical(renamed).contains("Legitimate Businessperson"), repairs);
        require("RECOVERY.rename.no_unseen_origin", VisibleNames.restore((Map<String, Object>) Json.copy(renamed),
                Json.map("card_origins", new LinkedHashMap<>()), "Grizzly Bears"::equals).isEmpty(), history);
        require("RECOVERY.rename.known_name_preserved", VisibleNames.restore(observation("Clone"), history,
                name -> "Clone".equals(name) || "Grizzly Bears".equals(name)).isEmpty(), "Clone remains Clone");
        Map<String, Object> tokenObs = observation("Legitimate Businessperson");
        Json.obj(Json.arr(Json.obj(Json.arr(tokenObs, "players").get(0)), "battlefield").get(0)).put("token", true);
        require("RECOVERY.rename.no_card_substitution_for_token", VisibleNames.restore(tokenObs, history,
                "Grizzly Bears"::equals).isEmpty(), tokenObs);
    }

    static void child() throws Exception {
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while ((line = in.readLine()) != null) {
            Map<String, Object> req = Json.parseObject(line);
            System.out.println(Json.canonical(Json.map("seq", req.get("seq"), "ok", true)));
            System.out.flush();
        }
        Thread.sleep(3_600_000L); // EOF alone cannot end this actual child JVM.
    }

    static void shutdown(Path root) throws Exception {
        String cp = new File(RecoveryCheck.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getPath();
        Path work = Files.createDirectory(root.resolve("runner"));
        RunnerLink link = new RunnerLink(Arrays.asList("java", "-cp", cp,
                RecoveryCheck.class.getName(), "child"), work.toFile(), work.resolve("stderr.log").toFile());
        require("RECOVERY.shutdown.child_started", Json.bool(link.call(Json.map("op", "ping"), 5000, 1000), "ok"), "actual JVM");
        long start = System.nanoTime();
        boolean confirmed = link.close();
        long elapsed = (System.nanoTime() - start) / 1_000_000;
        require("RECOVERY.shutdown.confirmed_before_host_grace", confirmed && elapsed < 1500,
                Json.map("confirmed", confirmed, "milliseconds", elapsed, "host_grace_ms", 2000L));
        boolean refused = false;
        try {
            link.call(Json.map("op", "ping"), 1000, 0);
        } catch (java.io.IOException expected) {
            refused = true;
        }
        require("RECOVERY.shutdown.no_late_replacement", refused, "closed link refuses restart");
    }

    static void work(Path root) throws Exception {
        Path owned = Files.createDirectory(root.resolve("kit-agent-owned"));
        Files.createDirectories(owned.resolve("db"));
        Files.write(owned.resolve("db/cards.mv.db"), new byte[]{1, 2, 3});
        Path sibling = Files.createDirectory(root.resolve("kit-agent-other"));
        Files.write(sibling.resolve("keep"), new byte[]{9});
        require("RECOVERY.work.only_assigned_directory", OwnedWork.remove(owned, root)
                && Files.exists(sibling.resolve("keep")), "sibling remains intact");
        boolean refused = false;
        try {
            OwnedWork.remove(sibling, root.resolve("wrong-parent"));
        } catch (java.io.IOException expected) {
            refused = true;
        }
        require("RECOVERY.work.boundary_refused", refused && Files.exists(sibling.resolve("keep")), sibling.toString());
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && "child".equals(args[0])) {
            child();
            return;
        }
        Path root = new File(args[0]).toPath().toAbsolutePath().normalize();
        Files.createDirectory(root);
        names();
        shutdown(root);
        work(root);
    }
}
