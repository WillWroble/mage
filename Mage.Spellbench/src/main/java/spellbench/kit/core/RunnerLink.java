package spellbench.kit.core;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * The front's link to its runner process (design Section 2.1). One request at a time; every request carries a
 * sequence number, and a reply whose number is not the pending one is discarded (late results never answer a later
 * request). The runner's stderr goes to a log file, never to the front's stdout.
 * <p>
 * Every request of a {@code choose} shares that request's clock (A1 result review, change 2): the caller passes the
 * absolute time by which it must have the reply ({@code waitUntil}) and the time by which its answer leaves
 * ({@code answerBy}). Waiting for a replacement runner that is still booting, the runner's own safety deadline (the
 * reply time minus the grace period) and the kill all fit inside that window. A request with no reply by
 * {@code waitUntil} ends the runner: it is destroyed forcibly and its exit confirmed until {@code answerBy}; when the
 * exit cannot be confirmed in time, a reaper thread confirms it in the background, and the next start waits for the
 * reaper (a killed H2 process can leave a lock file, removed only after the confirmed exit). Until the exit is
 * confirmed the link is latched (second result review, item 1): no replacement starts and every request is refused
 * with {@code Busy("runner_exit_unconfirmed")}, so the caller answers by fallback; the latch clears only when the
 * killed process is seen gone (then its locks are removed and a replacement may start). A request that cannot get
 * a runner in time (a replacement still booting, too little time left) is refused with {@link Busy}: nothing is sent
 * and nothing is killed.
 */
public final class RunnerLink {

    /** Less than this between the runner's safety deadline and now: no request is sent. */
    public static final long MIN_WORK_MS = 200;

    /** No reply in time: the runner was killed; the caller answers by fallback (tagged cap). */
    public static final class Timeout extends Exception {
        private static final long serialVersionUID = 1L;
        public final long waitedMs;
        public final boolean exitConfirmed;

        Timeout(long waitedMs, boolean exitConfirmed) {
            super("runner timeout after " + waitedMs + " ms");
            this.waitedMs = waitedMs;
            this.exitConfirmed = exitConfirmed;
        }
    }

    /** The request was not sent: a replacement runner is booting, or the clock is too short. */
    public static final class Busy extends Exception {
        private static final long serialVersionUID = 1L;
        public final String why;
        public final long waitedMs;

        Busy(String why, long waitedMs) {
            super(why + " after " + waitedMs + " ms");
            this.why = why;
            this.waitedMs = waitedMs;
        }
    }

    private final List<String> command;
    private final File workDir;
    private final File stderrLog;
    private Process process;
    private Writer toRunner;
    private volatile BlockingQueue<String> replies;
    private long seq;
    private Map<String, Object> gameMessage;
    private boolean everStarted;
    private volatile Thread starting;
    private volatile Thread reaper;
    /** The killed runner whose exit is not confirmed yet (the latch); null when every killed runner is confirmed gone. */
    private volatile Process unconfirmed;
    private volatile boolean closed;
    /** Fixture hook: kills report the exit as unconfirmed and the reaper gives up at once (latch tests). */
    public volatile boolean simulateUnconfirmedExit;
    public long exitUnconfirmedRefusals;
    /** Measurements for the evidence (E3, E8, review change 2). */
    public long restarts;
    public long kills;
    public long lastBootMs;
    public long lastRestartMs;
    public long lateDiscarded;
    public long staleLocksRemoved;
    public long exitsConfirmedLate;
    public long busyRefusals;
    public final List<Long> killToExitMs = Collections.synchronizedList(new ArrayList<Long>());

    public RunnerLink(List<String> command, File workDir, File stderrLog) {
        this.command = new ArrayList<>(command);
        this.workDir = workDir;
        this.stderrLog = stderrLog;
    }

    public synchronized boolean alive() {
        return process != null && process.isAlive();
    }

    /** The game message every fresh runner receives after its boot. */
    public void setGame(Map<String, Object> game) {
        this.gameMessage = game;
    }

    private static long ms(long nanos) {
        return nanos / 1_000_000;
    }

    private static long nanos(long ms) {
        return TimeUnit.MILLISECONDS.toNanos(ms);
    }

    /** True while a killed runner's exit is unconfirmed; rechecks the process first (it may have exited since). */
    public synchronized boolean exitLatched() {
        Process u = unconfirmed;
        if (u != null && !u.isAlive() && !simulateUnconfirmedExit) {
            unconfirmed = null;
            exitsConfirmedLate++;
            removeStaleLocks();
        }
        return unconfirmed != null;
    }

    private void start() throws IOException, Timeout {
        if (closed) throw new IOException("runner link is closed");
        Thread r = reaper;
        if (r != null && r != Thread.currentThread()) {
            try {
                r.join(40_000); // the killed runner's exit and lock removal come first
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (exitLatched()) {
            throw new IOException("a killed runner's exit is not confirmed: no replacement starts");
        }
        long t0 = System.nanoTime();
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(workDir);
        pb.redirectError(ProcessBuilder.Redirect.appendTo(stderrLog));
        final Process p;
        final BlockingQueue<String> queue = new ArrayBlockingQueue<>(64);
        synchronized (this) {
            if (closed) throw new IOException("runner link closed while starting");
            // Creation and adoption share the close lock: a closing front must
            // either see this child or prevent it from starting.
            p = pb.start();
            process = p;
            toRunner = new OutputStreamWriter(p.getOutputStream(), StandardCharsets.UTF_8);
            replies = queue;
        }
        Thread reader = new Thread(() -> {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    queue.offer(line);
                }
            } catch (IOException ignored) {
                // the runner ended
            }
        }, "kit-runner-reader");
        reader.setDaemon(true);
        reader.start();
        Map<String, Object> boot = call(Json.map("op", "boot"), 600_000, 10_000);
        lastBootMs = Json.num(boot, "boot_ms", -1);
        if (gameMessage != null) {
            call(gameMessage, 600_000, 10_000);
        }
        lastRestartMs = ms(System.nanoTime() - t0);
    }

    /**
     * Sends one request and waits for its reply until {@code deadlineMs} + {@code graceMs} from now (boot, game and
     * the fixtures' calls; a kill may wait up to 30 s for the exit).
     */
    public Map<String, Object> call(Map<String, Object> request, long deadlineMs, long graceMs) throws IOException, Timeout {
        long until = System.nanoTime() + nanos(deadlineMs + graceMs);
        try {
            return call(request, until, until + nanos(30_000), graceMs);
        } catch (Busy b) {
            throw new Timeout(b.waitedMs, true);
        }
    }

    /**
     * Sends one request of a decision whose reply is needed by {@code waitUntil} (nanoTime) and whose answer leaves
     * by {@code answerBy}. A request carrying {@code deadline_ms} gets the runner's safety deadline written into it
     * when it is sent: the time left to {@code waitUntil} minus {@code graceMs}.
     */
    public Map<String, Object> call(Map<String, Object> request, long waitUntil, long answerBy, long graceMs)
            throws IOException, Timeout, Busy {
        long t0 = System.nanoTime();
        if (closed) throw new IOException("runner link is closed");
        boolean decisionRequest = request.containsKey("deadline_ms");
        if (Thread.currentThread() != starting && exitLatched()) {
            exitUnconfirmedRefusals++;
            busyRefusals++;
            throw new Busy("runner_exit_unconfirmed", ms(System.nanoTime() - t0));
        }
        if (Thread.currentThread() != starting) {
            if (!alive() && !restarting()) {
                startAsync(); // first use, a crashed runner, or a kill the caller did not follow with a restart
            }
            Thread booting = starting;
            if (booting != null) {
                long joinMs = ms(waitUntil - System.nanoTime()) - (decisionRequest ? graceMs + MIN_WORK_MS : 0);
                try {
                    if (joinMs > 0) {
                        booting.join(joinMs);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                if (booting.isAlive()) {
                    busyRefusals++;
                    throw new Busy("runner_restarting", ms(System.nanoTime() - t0));
                }
                synchronized (this) {
                    if (starting == booting) {
                        starting = null;
                    }
                }
                if (!alive()) {
                    throw new IOException("runner failed to start");
                }
            }
        }
        long left = ms(waitUntil - System.nanoTime());
        if (decisionRequest) {
            long runnerDeadline = left - graceMs;
            if (runnerDeadline < MIN_WORK_MS) {
                busyRefusals++;
                throw new Busy("clock_low", ms(System.nanoTime() - t0));
            }
            request.put("deadline_ms", runnerDeadline);
        }
        BlockingQueue<String> queue;
        long mySeq;
        synchronized (this) {
            mySeq = ++seq;
            request.put("seq", mySeq);
            queue = replies;
            toRunner.write(Json.canonical(request));
            toRunner.write('\n');
            toRunner.flush();
        }
        while (true) {
            long wait = waitUntil - System.nanoTime();
            String line;
            try {
                line = wait <= 0 ? null : queue.poll(wait, TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                line = null;
            }
            if (line == null) {
                boolean confirmed = kill(answerBy);
                throw new Timeout(ms(System.nanoTime() - t0), confirmed);
            }
            Map<String, Object> reply;
            try {
                reply = Json.parseObject(line);
            } catch (RuntimeException e) {
                continue; // not a reply line
            }
            if (Json.num(reply, "seq", -1) != mySeq) {
                lateDiscarded++;
                continue; // a late result of an earlier request: never answers this one
            }
            return reply;
        }
    }

    /** Starts a replacement runner in the background (after a kill); requests wait for it within their clocks. */
    public synchronized void startAsync() {
        if (alive() || restarting() || exitLatched()) {
            return;
        }
        Thread t = new Thread(() -> {
            try {
                ensureStarted();
            } catch (IOException | Timeout e) {
                System.err.println("kit-front: runner restart failed: " + e);
            }
        }, "kit-runner-restart");
        t.setDaemon(true);
        starting = t;
        t.start();
    }

    public boolean restarting() {
        Thread t = starting;
        return t != null && t.isAlive();
    }

    /** Starts a runner if none is alive, synchronously (the restart thread, the fixtures). */
    public void ensureStarted() throws IOException, Timeout {
        if (!alive()) {
            if (everStarted) {
                restarts++;
            }
            everStarted = true;
            start();
        }
    }

    /** Destroys the runner and confirms its exit, waiting up to 30 s; true when the process is gone. */
    public boolean kill() {
        return kill(System.nanoTime() + nanos(30_000));
    }

    /**
     * Destroys the runner and confirms its exit until {@code confirmBy} (nanoTime); true when confirmed in time. Past
     * that, a reaper thread waits for the exit (up to 30 s more) and removes the stale lock files after it.
     */
    public boolean kill(long confirmBy) {
        final Process p;
        synchronized (this) {
            p = process;
            if (p == null) {
                return true;
            }
            process = null;
            replies = new ArrayBlockingQueue<>(64); // nothing the dead runner wrote can answer a later request
        }
        kills++;
        final long t0 = System.nanoTime();
        p.destroyForcibly();
        boolean exited;
        try {
            exited = p.waitFor(Math.max(0, Math.min(30_000, ms(confirmBy - System.nanoTime()))), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            exited = !p.isAlive();
        }
        if (exited && !simulateUnconfirmedExit) {
            killToExitMs.add(ms(System.nanoTime() - t0));
            removeStaleLocks();
            return true;
        }
        synchronized (this) {
            unconfirmed = p; // latched until the exit is seen
        }
        Thread r = new Thread(() -> {
            boolean gone;
            try {
                gone = !simulateUnconfirmedExit && p.waitFor(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                gone = !p.isAlive();
            }
            if (gone) {
                killToExitMs.add(ms(System.nanoTime() - t0));
                exitLatched(); // confirms, clears the latch and removes the locks
            } else {
                // the reaper gives up; the latch stays until a later check sees the process gone
                System.err.println("kit-front: a killed runner's exit is unconfirmed; replacement blocked");
            }
        }, "kit-runner-reaper");
        r.setDaemon(true);
        reaper = r;
        r.start();
        return false;
    }

    /**
     * The runner's card database copy is private to this agent and its owner is confirmed gone: a lock file a killed
     * H2 process leaves behind would make the next runner wait for it (addendum change 3).
     */
    private void removeStaleLocks() {
        File[] locks = new File(workDir, "db").listFiles((dir, name) -> name.endsWith(".lock.db"));
        if (locks != null) {
            for (File f : locks) {
                if (f.delete()) {
                    staleLocksRemoved++;
                }
            }
        }
    }

    /** Fits the host's two-second exit grace; never removes work while a child is alive. */
    public boolean close() {
        Process p;
        Writer w;
        synchronized (this) {
            closed = true;
            p = process;
            w = toRunner;
            process = null;
            if (p != null && p.isAlive()) p.destroyForcibly();
        }
        if (p != null) {
            try {
                w.close();
            } catch (IOException ignored) {
                // closing stdin ends the runner
            }
            try {
                if (!p.waitFor(200, TimeUnit.MILLISECONDS)) {
                    p.destroyForcibly();
                    p.waitFor(500, TimeUnit.MILLISECONDS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        Process pending = unconfirmed;
        return (p == null || !p.isAlive()) && (pending == null || !pending.isAlive());
    }
}
