package spellbench.kit.core;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Slice exit criterion E3 at the link level (design Section 2.1 and the addendum's change 3), with a scripted runner
 * process (this class's {@code fake} mode) so the timings are the link's own: a reply whose sequence number is stale
 * is discarded; a request that does not return by its deadline plus the grace period is answered by the caller's
 * fallback within that bound, the runner is killed and its exit confirmed; the next request is served by a fresh
 * runner, whose restart time is measured.
 *
 *   java -cp kit-core.jar spellbench.kit.core.TerminationCheck WORKDIR
 */
public final class TerminationCheck {

    private TerminationCheck() {
    }

    /** The scripted runner: answers boot and game; "stale" sends an old sequence first; "hang" never answers. */
    static void fake() throws Exception {
        PrintStream out = new PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out), true, "UTF-8");
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while ((line = in.readLine()) != null) {
            Map<String, Object> req = Json.parseObject(line);
            long seq = Json.num(req, "seq", 0);
            String op = Json.str(req, "op");
            if ("stale".equals(op)) {
                out.println(Json.canonical(Json.map("seq", seq - 1, "ok", true, "late", true)));
            }
            if ("hang".equals(op)) {
                Thread.sleep(3_600_000L); // ignores everything; only a kill ends it
            }
            out.println(Json.canonical(Json.map("seq", seq, "ok", true, "op", op)));
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("fake")) {
            fake();
            return;
        }
        File work = new File(args.length > 0 ? args[0] : ".");
        String cp = TerminationCheck.class.getProtectionDomain().getCodeSource().getLocation().getPath();
        if (cp.matches("^/[A-Za-z]:/.*")) {
            cp = cp.substring(1);
        }
        List<String> cmd = new ArrayList<>(Arrays.asList("java", "-cp", cp, "spellbench.kit.core.TerminationCheck", "fake"));
        RunnerLink link = new RunnerLink(cmd, work, new File(work, "fake-runner.log"));
        List<Map<String, Object>> checks = new ArrayList<>();
        // 1. a normal request
        Map<String, Object> r1 = link.call(Json.map("op", "ping"), 5000, 1000);
        checks.add(Json.map("check", "E3.normal_reply", "pass", Json.bool(r1, "ok"), "detail", r1));
        // 2. a stale reply is discarded, the request's own reply returned
        Map<String, Object> r2 = link.call(Json.map("op", "stale"), 5000, 1000);
        checks.add(Json.map("check", "E3.late_result_discarded", "pass", link.lateDiscarded == 1 && !Json.bool(r2, "late"),
                "detail", Json.map("late_discarded", link.lateDiscarded, "reply", r2)));
        // 3. a request that never returns: timeout within deadline + grace, runner killed, exit confirmed
        long deadline = 2000;
        long grace = 1000;
        long t0 = System.nanoTime();
        boolean timedOut = false;
        boolean confirmed = false;
        try {
            link.call(Json.map("op", "hang"), deadline, grace);
        } catch (RunnerLink.Timeout e) {
            timedOut = true;
            confirmed = e.exitConfirmed;
        }
        long answeredMs = (System.nanoTime() - t0) / 1_000_000;
        checks.add(Json.map("check", "E3.hung_request_answered_in_time", "pass", timedOut && answeredMs < deadline + grace + 2000,
                "detail", Json.map("answered_ms", answeredMs, "deadline_ms", deadline, "grace_ms", grace,
                        "kill_to_exit_ms", link.killToExitMs)));
        checks.add(Json.map("check", "E3.runner_exit_confirmed", "pass", confirmed && !link.alive(),
                "detail", Json.map("exit_confirmed", confirmed, "alive", link.alive())));
        // 4. the next request: a fresh runner
        long t1 = System.nanoTime();
        Map<String, Object> r4 = link.call(Json.map("op", "ping"), 5000, 1000);
        long restartMs = (System.nanoTime() - t1) / 1_000_000;
        checks.add(Json.map("check", "E3.fresh_runner_serves_next", "pass", Json.bool(r4, "ok") && link.restarts == 1,
                "detail", Json.map("restarts", link.restarts, "restart_and_reply_ms", restartMs)));
        link.close();
        boolean all = true;
        for (Map<String, Object> c : checks) {
            System.out.println(Json.canonical(c));
            all &= Boolean.TRUE.equals(c.get("pass"));
        }
        System.out.println(Json.canonical(Json.map("verdict", all ? "PASS" : "FAIL")));
    }
}
